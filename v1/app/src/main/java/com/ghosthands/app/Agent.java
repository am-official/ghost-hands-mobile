package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The agent loop — a port of ghost_hands/runner.py's Runner:
 * perceive → decide → classify → govern → record → act.
 *
 * Governance invariants (unchanged from the Python):
 * - Every action is classified and judged by the Governor BEFORE it runs.
 * - The govern + execute events hit the trail BEFORE the hands move
 *   (record-before-execute). A denied or unapproved action never runs.
 * - An "ask" verdict the user declines (or never answers — Stop, or
 *   the pack's approval timeout) is a denial: silence is never consent.
 * - Unparseable planner output is denied and recorded (fail closed);
 *   it executes nothing. Three in a row stop the run as an error.
 *
 * Honest stop reasons: done / stuck / denied / budget / error / stopped.
 *
 * Performance shape for this hardware (~20 tok/s prompt processing):
 * the prompt is a short system contract + the goal + at most the last
 * 5 step results + the capped element map. No full transcripts.
 *
 * v1.8: the prompt the planner sees is assembled by
 * buildPromptWithinBudget, which guarantees an estimated size of at
 * most PROMPT_TOKEN_BUDGET tokens (the native context is 2048; 160
 * are reserved for the reply). The v1.7 field run died when an
 * auto-read chunk carried TWICE (auto-read section + read history)
 * pushed a step's prompt past the context: the engine's resulting
 * "ERROR: prompt too long for context" was then misread as planner
 * gibberish and the run burned three steps failing closed on it.
 * Engine errors now stop the run immediately (isEngineError), and
 * the budget trims deterministically before that can happen.
 *
 * v2.9: the guarantee is now a FIT, owned by fitPrompt: the v1.8
 * trimmer measured the v2.8 field run's step-5 prompt as fitting
 * and the engine refused it anyway — the estimator under-measured
 * an element-dense page (digit-dense read text; see estTokens) and
 * the trimmer's stages had nothing to say to a heavy CURRENT
 * perception. The loop now assembles each step prompt from its
 * parts, measures it before the engine call, degrades it down a
 * deterministic ladder (fitPrompt) until it fits, and — when even
 * the ladder's end does not fit — stops the run honestly ("page
 * too dense for the model's context") instead of spending a
 * decide on a refusal it could have predicted. Reads on a dense
 * page serve smaller chunks (perceptionWeight / readChunkChars),
 * so one read cannot single-handedly blow the following decide.
 * buildPromptWithinBudget remains, pinned by its own tests; the
 * loop's fitter is fitPrompt.
 *
 * v3.0 (item 1 — the fact scratchpad): the planner can record a
 * fact mid-run with {"action":"note","fact":"..."} — a readonly
 * action the Governor allows under every pack, judged and
 * recorded like any other, whose only effect is an entry in this
 * run's Scratchpad, stamped with the current page. The facts ride
 * in the prompt as a FACTS block (buildPrompt's factsText) and
 * join the fit ladder as its last rung: stubbed to a count only
 * when nothing else fits. The done-grounding guard is now
 * evidence-aware: a number in a done summary grounds it only
 * when that number appears in the run's evidence — the read text
 * in history union the noted facts — so a value from a page the
 * run left behind (and noted) still grounds the answer, and a
 * number that was never read or noted no longer does. Each run
 * also opens its trail with a "run" event (goal, pack, model),
 * so a trail file is self-describing for the answer card.
 *
 * v3.1 — break the loop (field evidence trail-20261025-025203,
 * the v3.0.0 Hawaii fares run): the planner tapped "Google
 * Search", read the fares, then proposed the same tap again,
 * and the run's real findings never reached its answer card.
 * Three loop-level answers: the repeat-action guard (a
 * run-scoped ledger of successfully executed actions, keyed
 * semantically; a re-proposal of an already-executed tap,
 * type or goto is corrected, capped per key — the older
 * guards all keyed on consecutive state and the intervening
 * read disarmed them); the scratchpad nudge (the first
 * successful read's history entry carries a one-time hint to
 * note facts as they are found — the field planner never used
 * note once); and the answer card's findings fallback lives
 * in AnswerCard (a non-done, note-less run's card carries the
 * last read's opening text as an excerpt instead of letting
 * the bare error line pose as the answer).
 */
public class Agent {

    public static final int MAX_STEPS = 25;
    public static final int MAX_TOKENS_PER_STEP = 160;
    private static final int HISTORY_KEPT = 5;
    private static final int RAW_RECORDED = 400;
    /** v1.2: read results are carried into the next prompt nearly in
     *  full (a whole 2400-char chunk plus its framing) — that text is
     *  the planner's only way to answer a question goal. Other action
     *  results keep the lean 120-char history truncation. */
    private static final int READ_HISTORY_CHARS = 2600;
    /** v1.8: the prompt budget, in estimated tokens (chars/4, the
     *  estimate Trail.accounting already uses). The native context
     *  is 2048 tokens and MAX_TOKENS_PER_STEP reserves 160 for the
     *  reply; 1700 leaves margin under both. buildPromptWithinBudget
     *  trims to this before the prompt ever reaches the engine. */
    public static final int PROMPT_TOKEN_BUDGET = 1700;
    /** v2.2: how many times one step may re-perceive and re-decide
     *  after a navigation race (an executed action failing against
     *  a page that navigated away while the planner was deciding).
     *  One retry heals the observed shape — the fresh perception
     *  shows the page the agent is actually on — and a second race
     *  inside the same step is a genuinely unstable page, whose
     *  error should stand as the step's result. */
    public static final int RACE_RETRY_CAP = 1;
    /** v2.5: how many times one run may detour back to a
     *  search-results page after a result tapped from it failed to
     *  load (its one v1.6 reload already spent). Two dead results
     *  are bad luck the planner can route around; a third means
     *  the destination class itself is broken (or the network
     *  is), and the honest error stop returns. */
    public static final int DETOUR_CAP = 2;
    /** Marker appended where the budget cut the auto-read section. */
    public static final String AUTO_READ_TRUNC_MARKER =
            "\n... (auto-read text truncated to fit the prompt budget)";
    /** Marker appended where the budget cut the element map. */
    public static final String MAP_TRUNC_MARKER =
            "... (element map truncated to fit the prompt budget)\n";

    // ---------------- v2.9: the fit guarantee ----------------
    // The v2.8 field run (trail-20261025-015133) died at step 5
    // with the engine's "ERROR: prompt too long for context" even
    // though the v1.8 trimmer had measured the prompt as fitting:
    // on an element-dense page the perception itself is the heavy
    // part, and the estimator under-measured its shape (item 2's
    // digit-density surcharge is the measurement half). The fit
    // half is fitPrompt below: the step prompt is assembled from
    // its parts and measured BEFORE the engine call, a
    // deterministic degradation ladder degrades the degradable
    // parts (re-measuring after each rung) until it fits, and
    // when even the ladder's end does not fit, the run stops
    // honestly — "page too dense for the model's context" — with
    // the run summary, instead of learning it from the engine.
    /** Rung 2: the text preview's truncated length (the standing
     *  preview is Eyes.TEXT_PREVIEW_CHARS, 240). */
    public static final int PREVIEW_FIT_CHARS = 120;
    /** Rung 3: the map's harder name / href caps (the standing
     *  render leaves names at their 48-char extraction cap and
     *  hrefs at 80). */
    public static final int MAP_FIT_NAME_CHARS = 24;
    public static final int MAP_FIT_HREF_CHARS = 40;
    /** Rung 3: how many elements the reduced map keeps, and the
     *  char budget it renders under (the standing render's budget
     *  is Eyes.MAP_BUDGET_CHARS, 1500). */
    public static final int MAP_FIT_KEEP = 24;
    public static final int MAP_FIT_CHARS = 1000;
    /** Rung 1: the fixed tail a stubbed history read entry ends
     *  with — its step line and its "read chars S-E of T"
     *  accounting survive; the chunk text does not. */
    public static final String READ_STUB_TAIL =
            "(read text omitted to fit the prompt budget)";
    /** Item 3: a perception whose measured weight (page line +
     *  map + preview, in estTokens) reaches this is a dense page:
     *  its reads serve DENSE_READ_CHUNK_CHARS instead of the
     *  standing chunk, so one read cannot single-handedly blow
     *  the following decide. Calibrated on the harness fixtures:
     *  a saturated 40-element Wikipedia article perception
     *  measures 546, the v2.8 field run's AccuWeather perception
     *  614 — the threshold sits between them, near the ceiling a
     *  perception can reach (the map's own char cap bounds it),
     *  so "dense" means a perception within reach of the heaviest
     *  a perception can be. The margin is honest but thin: a
     *  maximal Wikipedia map can cross it, and the cost of
     *  crossing is only smaller read chunks there. */
    public static final int DENSE_PERCEPTION_TOKENS = 600;
    /** Item 3: the dense page's read chunk (the standing chunk is
     *  Eyes.READ_CHUNK_CHARS, 2400). */
    public static final int DENSE_READ_CHUNK_CHARS = 1200;
    /** Item 3: the line a dense page's read result carries, so
     *  the trail and the planner both know the chunks shrank. */
    public static final String DENSE_READ_NOTE =
            "(read smaller chunks on this dense page)";
    /** v3.1 (item 3 — the scratchpad nudge): the hint appended
     *  to the FIRST successful read's history entry, once per
     *  run. In the v3.0.0 field run the planner read the Hawaii
     *  fares at step 3 and never once reached for the note
     *  action the scratchpad shipped with — the contract names
     *  the action, but nothing reminded the planner at the
     *  moment a fact was in front of it. The hint rides the
     *  read's own history entry (the fit ladder's rung 1 stubs
     *  read chunks by cutting at the read header's colon, so
     *  the hint leaves with the chunk text it annotates). */
    public static final String NOTE_HINT =
            " (tip: when you find a fact the goal needs, record "
            + "it with the note action {\"action\":\"note\","
            + "\"fact\":\"...\"} before moving on; your final "
            + "answer is checked against your notes and reads)";

    /** The body: WebView hands, implemented by the Activity. All calls
     *  block until the page side has answered (or timed out). */
    public interface Hands {
        Eyes.Perception perceive() throws Exception;
        String tap(int el) throws Exception;
        String type(int el, String text) throws Exception;
        String gotoUrl(String url) throws Exception;
        String scroll(String dir) throws Exception;
        String back() throws Exception;
        /** v1.1: the dedicated search action's body (site search box,
         *  Wikipedia search page, or a search-engine fallback). */
        String search(String query) throws Exception;
        /** v1.2: the read action's body — extract the page's readable
         *  text, one chunk per call, walking a per-page cursor.
         *  v2.9: the loop names the chunk size — the standing
         *  Eyes.READ_CHUNK_CHARS, or DENSE_READ_CHUNK_CHARS when
         *  the current perception is dense (item 3). */
        String read(int chunkChars) throws Exception;
        /** v1.6: error-page recovery's body — navigate back to the
         *  failed URL when the error page names one, else reload
         *  whatever is current. Returns a short ok/error string. */
        String reload(String failedUrl) throws Exception;
    }

    /** The screen: step feed, approvals, stop. approve() blocks the
     *  agent thread until the user answers, the pack timeout passes,
     *  or stop is requested (which counts as a decline). */
    public interface Ui {
        void feed(String line);
        void status(String s);
        boolean approve(GhAction action, Governor.Decision decision, String describe);
        boolean stopRequested();

        /** v3.3 (item 4 — co-pilot tap): whether the app is in the
         *  foreground right now. A co-pilot pause nobody can see
         *  would be a hang, so a stuck stop is offered the pause
         *  only on screen (Copilot.eligible). */
        boolean foreground();

        /** v3.3 (item 4 — co-pilot tap): pause the loop for the
         *  user's own tap. Blocks until the user has tapped an
         *  identified element in the page and pressed Resume —
         *  returns that element, the user's tap, identified
         *  through the current perception — or returns null when
         *  the user stops, or the wait expires: the run then stops
         *  stuck, exactly as it would have without the offer.
         *  The wait is bounded by timeoutMs, which the loop sets
         *  to the pack's approval timeout: the pause counts
         *  against the pack clock exactly like an approval wait. */
        ElementInfo awaitUserTap(int step, long timeoutMs);

        /** v3.3 (item 1 — route replay): pause the replay for the
         *  user to fill a field the route deliberately never
         *  recorded (a password step). Blocks until the user
         *  presses Resume (true) or stops / the wait expires
         *  (false). Same timeout discipline as approve(). */
        boolean awaitUserResume(int step, String message, long timeoutMs);
    }

    private final Hands hands;
    private final ModelEngine engine;
    private final Governor governor;
    private final PolicyPacks.Pack pack;
    private final Trail trail;
    private final Ui ui;
    /** v2.6: the active model's registry id (ModelStore), recorded
     *  on the run's decide / stop / run_summary trail records next
     *  to the v2.4 "grammar" flag, so a trail always names the
     *  brain that produced it. */
    private final String modelId;
    /** v2.6.1: the grammar kill-switch SETTING for this run,
     *  recorded on the same records as "grammar_setting", beside
     *  "model". Distinct from the per-call "grammar" flag, which
     *  reports what a call actually did: the setting is what the
     *  user asked for, the per-call flag is what the shim applied
     *  (with the switch off, the setting is false and every call's
     *  flag is false; with it on, a call whose grammar failed to
     *  initialise still reports its own flag false). */
    private final boolean grammarSettingOn;

    public volatile int currentStep = 0;

    // v2.5 (item 4 — run summary): the run's own tallies, reset at
    // the top of run() and read by stop(): when the run started
    // (wall clock), and the prompt / reused token totals summed
    // over every decide (the same per-decide stats the trail
    // records). MainActivity builds a fresh Agent per run, but
    // run() resets them regardless.
    private long runStartMs = 0;
    private long totalPromptTokens = 0;
    private long totalReusedTokens = 0;

    // v2.9 (item 3 — adaptive read chunks): the chunk size the
    // hands serve for the CURRENT perception — the standing
    // Eyes.READ_CHUNK_CHARS, or DENSE_READ_CHUNK_CHARS when the
    // perception measures dense (perceptionWeight). Refreshed
    // before the auto-read and at the top of every decide
    // iteration, so a race/stale re-perceive re-decides it for
    // the page the agent is actually on.
    private int currentReadChunkChars = Eyes.READ_CHUNK_CHARS;

    // v3.3 (item 4 — co-pilot tap): the assists this run has
    // spent, judged against Copilot.MAX_ASSISTS at every stuck
    // stop. Reset at the top of run(), like the tallies above.
    private int assistsUsed = 0;

    // v3.0 (item 1 — the fact scratchpad): this run's notebook.
    // Owned by the Agent instance (a fresh Agent is built per
    // run), filled only through the governed `note` action, read
    // by the prompt assembly (the FACTS block), the fit ladder
    // (its last rung) and the done-grounding guard (as evidence).
    private final Scratchpad scratchpad = new Scratchpad();

    /** v3.0: this run's scratchpad (the Activity's answer card
     *  reads the same facts back from the trail's "note" events;
     *  this accessor is for the loop's own tests and callers). */
    public Scratchpad scratchpad() {
        return scratchpad;
    }

    public Agent(Hands hands, ModelEngine engine, PolicyPacks.Pack pack,
                 Trail trail, Ui ui, String modelId) {
        this(hands, engine, pack, trail, ui, modelId, false);
    }

    public Agent(Hands hands, ModelEngine engine, PolicyPacks.Pack pack,
                 Trail trail, Ui ui, String modelId,
                 boolean grammarSettingOn) {
        this.hands = hands;
        this.engine = engine;
        this.pack = pack;
        this.governor = new Governor(pack.policy);
        this.trail = trail;
        this.ui = ui;
        this.modelId = (modelId == null || modelId.length() == 0)
                ? "unknown" : modelId;
        this.grammarSettingOn = grammarSettingOn;
    }

    public Trail trail() {
        return trail;
    }

    /** Runs the loop on the caller's (worker) thread. */
    public void run(String goal) {
        // v2.5: the run-summary tallies (see stop()).
        runStartMs = System.currentTimeMillis();
        totalPromptTokens = 0;
        totalReusedTokens = 0;
        assistsUsed = 0;
        // v3.0: the run's opening record — the trail is self-
        // describing from its first line (the answer card reads
        // the goal from here; pre-v3.0 trails have no such event
        // and the card falls back to a caller-supplied goal).
        trail.record("run", 0, Trail.fields(
                "goal", goal,
                "pack", pack.name,
                "model", modelId,
                "grammar_setting", Boolean.valueOf(grammarSettingOn)));
        List<String> history = new ArrayList<String>();
        // v1.8: parallel to history — for a history entry that is a
        // read result, the URL of the page that was read (null for
        // every other entry). The prompt budget uses it to stub a
        // read chunk the auto-read section already shows, instead
        // of carrying the same page text twice. The two lists are
        // only ever mutated through addHistory / trimHistory below,
        // which keep them the same length.
        List<String> historyReadUrls = new ArrayList<String>();
        Map<String, Integer> seenActions = new LinkedHashMap<String, Integer>();
        Map<String, Integer> guardCounts = new LinkedHashMap<String, Integer>();
        String lastSignature = null;
        int unchanged = 0;
        int unparseable = 0;
        boolean searched = false;
        // v1.2 repeat-search correction state: the last executed
        // search query, and whether the agent is still sitting on
        // that query's results page (no navigating action since).
        String lastSearchQuery = null;
        boolean onSearchResults = false;
        // v1.3 hardened repeat-search state: keyed purely on the
        // execution history — the last successfully EXECUTED search
        // query, and whether a tap/goto/read/type has executed
        // since. No perception is consulted, so a perception that
        // races a navigation cannot disarm it (v1.2 field miss).
        String lastExecutedSearchQuery = null;
        boolean navigatedSinceSearch = false;
        // v1.3 auto-read state: URLs already auto-read this run,
        // and the labelled section for the page currently open (the
        // extraction happens once per URL; the section is re-shown
        // in the prompt while the agent stays on that URL).
        Set<String> autoReadUrls = new LinkedHashSet<String>();
        String autoReadUrl = null;
        String autoReadSection = null;
        // v1.3 chrome-tab correction state: tab labels already
        // corrected this run (each label is corrected at most once).
        Set<String> chromeCorrected = new LinkedHashSet<String>();
        // v1.6 error-page recovery state: failed URLs that have
        // already had their one auto-reload this run.
        Set<String> recoveredUrls = new LinkedHashSet<String>();
        // v2.5 dead-result detour state: the URL of the most recent
        // search-results page the agent departed by tapping one of
        // its links, whether the current page was arrived at by such
        // a tap (set/cleared in the post-execute bookkeeping below),
        // and how many detours this run has spent (DETOUR_CAP).
        String lastResultsUrl = null;
        boolean arrivedViaResultsLink = false;
        int detoursUsed = 0;
        // v1.6 empty-map correction count (the cap itself lives in
        // RelevanceGuard.EMPTY_MAP_CORRECTION_CAP).
        int emptyMapCorrections = 0;
        // v2.0 pagination-guard correction count (the cap itself
        // lives in RelevanceGuard.PAGINATION_CORRECTION_CAP).
        int paginationCorrections = 0;
        // v2.1 done-grounding correction count (the cap itself
        // lives in RelevanceGuard.DONE_CORRECTION_CAP).
        int doneCorrections = 0;
        // v3.1 repeat-action guard state: the semantic keys of
        // the actions this run has SUCCESSFULLY executed (the
        // ledger RelevanceGuard.repeatActionReason judges
        // against — failed attempts never enter it), and the
        // corrections spent per key (the cap itself lives in
        // RelevanceGuard.REPEAT_ACTION_CORRECTION_CAP).
        Set<String> executedActionKeys = new LinkedHashSet<String>();
        Map<String, Integer> repeatActionCorrections =
                new LinkedHashMap<String, Integer>();
        // v3.1 scratchpad-nudge state: has the one-time note
        // hint been appended to a read's history entry yet?
        boolean noteHintGiven = false;
        // v1.7 arrival-guard correction count (the cap itself lives
        // in RelevanceGuard.ARRIVAL_CORRECTION_CAP).
        int arrivalCorrections = 0;
        // v1.9 end-of-text state (generalises v1.8's
        // read-after-complete slot): for each URL, the total chars
        // of its extracted text once ANY read — the deterministic
        // auto-read OR a planner `read` — has reached the end of
        // that text. v1.8 keyed completion on the auto-read alone,
        // and the v1.8 field run paged to the end with PLANNER
        // reads (auto-read stopped at 2,400 of 11,604), so the
        // step-7 re-read wrapped the cursor uncorrected. One map,
        // fed by both paths; a shared correction counter caps the
        // rule per run (RelevanceGuard.READ_COMPLETE_CORRECTION_CAP).
        Map<String, Integer> readCompleteTotals =
                new LinkedHashMap<String, Integer>();
        int readCompleteCorrections = 0;
        // v1.2: reads legitimately leave the element map unchanged;
        // the stuck checks must not count a read as stagnation.
        String lastExecutedKind = null;

        ui.status("Agent running (policy: " + pack.name + ", max " + MAX_STEPS + " steps)");
        steps:
        for (int step = 1; step <= MAX_STEPS; step++) {
            currentStep = step;
            if (ui.stopRequested()) {
                stop(step, "stopped", "user pressed Stop");
                return;
            }

            // -- perceive ------------------------------------------------
            Eyes.Perception perception;
            try {
                perception = hands.perceive();
            } catch (Exception e) {
                stop(step, "error", "perceive failed: " + e.getMessage());
                return;
            }
            String rendered = perception.render(goal);
            trail.record("perceive", step, Trail.fields(
                    "url", perception.url,
                    "title", perception.title,
                    "text_preview", truncate(perception.textPreview, 240),
                    "elements", Long.valueOf(perception.elements.size()),
                    "map_chars", Long.valueOf(rendered.length()),
                    "signature", perception.signature()));

            // -- error-page recovery (v1.6) --------------------------
            // Field evidence (v1.5 field run 2): a `search`
            // re-navigation aborted (net::ERR_CONNECTION_ABORTED)
            // and the WebView sat on Chrome's error page for the
            // rest of the run — 0 elements, the planner hallucinating
            // taps on elements 1/2/3 — until the stuck detector
            // stopped it at step 8. Nobody recovered the page. Now
            // the loop does, deterministically: the FIRST sight of
            // a given failed URL in a run gets one reload and a
            // fresh perception inside the same step; if the page is
            // still an error page after that (or the reload itself
            // fails), the run stops honestly with the failed URL and
            // the net error, instead of burning steps on a dead
            // page. One auto-reload per distinct failed URL per run.
            // v2.5: "stops honestly" now has one exception — the
            // dead-result detour in the exhausted branch below:
            // a result tapped from a search-results page whose
            // load fails gets the planner back to those results
            // instead of ending the run (DETOUR_CAP per run).
            if (ErrorPages.isErrorPage(perception.url, perception.title,
                    perception.textPreview)) {
                String failedUrl = ErrorPages.failedUrl(perception);
                String failedKey = (failedUrl != null)
                        ? failedUrl : perception.url;
                String netErr = ErrorPages.netError(perception.title,
                        perception.textPreview);
                // v2.5: every way recovery can be exhausted — the
                // URL already spent its reload on an earlier
                // sighting, the reload itself fails, or the page is
                // still an error page after it — converges on
                // `exhausted` below, where the dead-result detour
                // gets its say before the honest stop. (Through
                // v2.4 each branch stopped directly.)
                boolean exhausted = recoveredUrls.contains(failedKey);
                if (!exhausted) {
                    recoveredUrls.add(failedKey);
                    trail.record("recover", step, Trail.fields(
                            "url", perception.url,
                            "failed_url", failedUrl,
                            "net_error", netErr));
                    ui.feed("Step " + step + ": Page failed to load, "
                            + "reloading: " + failedKey);
                    boolean reloadFailed = false;
                    try {
                        hands.reload(failedUrl);
                    } catch (Exception e) {
                        reloadFailed = true;
                    }
                    if (!reloadFailed) {
                        try {
                            perception = hands.perceive();
                        } catch (Exception e) {
                            reloadFailed = true;
                        }
                    }
                    if (reloadFailed) {
                        exhausted = true;
                    } else {
                        rendered = perception.render(goal);
                        trail.record("perceive", step, Trail.fields(
                                "url", perception.url,
                                "title", perception.title,
                                "text_preview",
                                truncate(perception.textPreview, 240),
                                "elements",
                                Long.valueOf(perception.elements.size()),
                                "map_chars",
                                Long.valueOf(rendered.length()),
                                "signature", perception.signature()));
                        if (ErrorPages.isErrorPage(perception.url,
                                perception.title, perception.textPreview)) {
                            exhausted = true;
                        }
                    }
                }
                if (!exhausted) {
                    // Recovered: fall through with the fresh
                    // perception.
                    if (ui.stopRequested()) {
                        stop(step, "stopped", "user pressed Stop");
                        return;
                    }
                } else if (shouldDetour(failedKey, lastResultsUrl,
                        detoursUsed, arrivedViaResultsLink)) {
                    // -- dead-result detour (v2.5) ------------------
                    // Field evidence (trail-20261024-234232): the
                    // planner tapped a forecast result out of the
                    // DuckDuckGo results, the destination failed to
                    // load (chrome-error), the one reload failed,
                    // and the run STOPPED — with a results page full
                    // of alternatives one Back away. Now, when the
                    // dead page was arrived at by tapping a link on
                    // a recognised results page, the loop goes back
                    // there instead and lets the planner pick
                    // another, naming the dead destination in the
                    // history so it is not re-tapped. Capped per
                    // run (DETOUR_CAP); the arrival/completion state
                    // cannot arm on the dead URL — this block runs
                    // before the auto-read, shouldAutoRead stands
                    // down on results pages, and the perception the
                    // step continues with is the page Back landed
                    // on, never the error page.
                    detoursUsed++;
                    trail.record("detour", step, Trail.fields(
                            "failed_url", failedKey,
                            "results_url", lastResultsUrl,
                            "detours_used", Long.valueOf(detoursUsed)));
                    ui.feed("Step " + step + ": result failed to load; "
                            + "back to results to pick another: "
                            + failedKey);
                    addHistory(history, historyReadUrls,
                            "step " + step + ": the result you opened ("
                            + failedKey + ") failed to load, so you "
                            + "went back. Do not tap that result again; "
                            + "pick a different one.", null);
                    trimHistory(history, historyReadUrls);
                    boolean backOk = true;
                    try {
                        hands.back();
                    } catch (Exception e) {
                        backOk = false;
                    }
                    Eyes.Perception backPage = null;
                    if (backOk) {
                        try {
                            backPage = hands.perceive();
                        } catch (Exception e) {
                            backOk = false;
                        }
                    }
                    if (backOk && backPage != null
                            && !ErrorPages.isErrorPage(backPage.url,
                                    backPage.title, backPage.textPreview)) {
                        perception = backPage;
                        rendered = perception.render(goal);
                        trail.record("perceive", step, Trail.fields(
                                "url", perception.url,
                                "title", perception.title,
                                "text_preview",
                                truncate(perception.textPreview, 240),
                                "elements",
                                Long.valueOf(perception.elements.size()),
                                "map_chars",
                                Long.valueOf(rendered.length()),
                                "signature", perception.signature()));
                        // Wherever Back landed, the agent is no
                        // longer "arrived via a results link" — the
                        // next departure re-arms the detour state.
                        arrivedViaResultsLink = false;
                        if (ui.stopRequested()) {
                            stop(step, "stopped", "user pressed Stop");
                            return;
                        }
                        // Fall through with the new perception.
                    } else {
                        String netErrNow = ErrorPages.netError(
                                perception.title, perception.textPreview);
                        stop(step, "error", "page failed to load: "
                                + failedKey + (netErrNow != null
                                        ? " (" + netErrNow + ")" : ""));
                        return;
                    }
                } else {
                    String netErrNow = ErrorPages.netError(
                            perception.title, perception.textPreview);
                    stop(step, "error", "page failed to load: " + failedKey
                            + (netErrNow != null
                                    ? " (" + netErrNow + ")" : ""));
                    return;
                }
            }

            // v2.9 (item 3): the read chunk size for THIS
            // perception, settled before the auto-read below and
            // refreshed again inside the decide loop (a re-perceive
            // there replaces the page this was measured on).
            currentReadChunkChars = readChunkChars(
                    perceptionWeight(perception, rendered));

            // -- auto-read on arrival (v1.3) --------------------------
            // A question goal + a page whose title/URL matches a
            // significant goal token = the agent has arrived. The
            // v1.2 field run showed the planner does not recognise
            // arrival (it tapped the "Article" tab twice instead of
            // reading), so arrival reading is deterministic, like
            // the v1.1 search action: the loop performs one read
            // extraction itself, once per URL, and injects the chunk
            // into this and subsequent prompts while the agent stays
            // on that URL. The extraction goes through hands.read(),
            // so it shares the read cursor: a planner `read` later
            // continues where the auto-read stopped. Best-effort: a
            // failed extraction never kills the run — the planner
            // can still call read itself.
            if (RelevanceGuard.shouldAutoRead(goal, perception.title,
                    perception.url, autoReadUrls)) {
                // v2.2: redundant auto-read suppression. This page
                // was ALREADY read to the end this run (the planner
                // paged through it; the completion state is fed by
                // either read path), so extracting its opening chunk
                // again and injecting it as a section would carry
                // text the planner already holds a second time. In
                // the v2.1 field run the auto-read fired LATE on
                // weather.com (its title settled only after the
                // planner's step-4 read had completed the page) and
                // the duplicated section pushed the step-5 prompt
                // over the 2048 context. Skip the extraction and the
                // section entirely; arrival still arms (the arrival
                // guard keys on autoReadUrl) and the completion
                // banner still applies (decide computes it from
                // readCompleteTotals).
                if (readCompleteTotals.containsKey(perception.url)) {
                    autoReadUrls.add(perception.url);
                    autoReadUrl = perception.url;
                    autoReadSection = null;
                } else {
                    try {
                        String autoResult = hands.read(currentReadChunkChars);
                        if (autoResult != null
                                && !autoResult.startsWith("error")) {
                            autoReadUrls.add(perception.url);
                            autoReadUrl = perception.url;
                            autoReadSection = autoReadSection(autoResult);
                            // v1.9: did this extraction reach the end
                            // of the page text? Feed the shared
                            // completion map (a planner read on a
                            // fully-read page can only re-wrap and
                            // repeat the first chunk).
                            if (readReachedEnd(autoResult)) {
                                int total = readTotalChars(autoResult);
                                if (total > 0) {
                                    readCompleteTotals.put(perception.url,
                                            Integer.valueOf(total));
                                }
                            }
                            trail.record("read", step, Trail.fields(
                                    "phase", "auto",
                                    "url", perception.url,
                                    "title", perception.title,
                                    "result", truncate(autoResult, 300)));
                            ui.feed("Step " + step + ": Auto-read: "
                                    + perception.title);
                        }
                    } catch (Exception e) {
                        // Best-effort (see above): no trail event, no
                        // stop; the planner keeps its own read action.
                    }
                }
            }

            // stuck: map unchanged across consecutive steps. A step
            // whose last executed action was `read` is exempt: reading
            // walks the page text, the interactive map does not move.
            // v3.0: `note` is exempt for the same reason — noting a
            // fact is bookkeeping progress on a page that does not
            // move, not stagnation.
            String sig = perception.signature();
            if (sig.equals(lastSignature) && !"read".equals(lastExecutedKind)
                    && !"note".equals(lastExecutedKind)) {
                unchanged++;
            } else {
                unchanged = 0;
                lastSignature = sig;
            }
            if (unchanged >= 3) {
                // v3.3 (item 4 — co-pilot tap): before the stuck
                // stop lands, the user gets the wheel — foreground
                // only, at most Copilot.MAX_ASSISTS times a run.
                // After an assist the page is whatever the user's
                // tap made it: re-perceive and decide this step
                // fresh, with the stagnation counters reset.
                if (offerAssist(step,
                        "element map unchanged for 3 consecutive steps",
                        history, historyReadUrls)) {
                    try {
                        perception = hands.perceive();
                    } catch (Exception e) {
                        stop(step, "error",
                                "perceive failed: " + e.getMessage());
                        return;
                    }
                    rendered = perception.render(goal);
                    trail.record("perceive", step, Trail.fields(
                            "url", perception.url,
                            "title", perception.title,
                            "text_preview",
                            truncate(perception.textPreview, 240),
                            "elements",
                            Long.valueOf(perception.elements.size()),
                            "map_chars",
                            Long.valueOf(rendered.length()),
                            "signature", perception.signature()));
                    unchanged = 0;
                    lastSignature = null;
                    lastExecutedKind = null;
                } else {
                    stop(step, "stuck",
                            "element map unchanged for 3 consecutive steps");
                    return;
                }
            }

            // v2.2: the decide -> guard -> govern -> execute pipeline
            // below runs in a retry loop so a navigation race (see
            // the race branch at its end) can re-perceive and
            // re-decide the SAME step instead of burning it. Guard
            // corrections and the unparseable path continue the
            // OUTER (steps) loop; only the race path continues this
            // inner one, and falling off its end breaks it.
            // v2.7: the stale-stash refresh retry (same branch, the
            // same-URL sibling) shares the loop under its own cap.
            int raceRetries = 0;
            int staleRetries = 0;
            while (true) {
                // v2.9 (item 3): a race/stale re-decide replaced the
                // perception at the bottom of this loop; the read
                // chunk size follows the page now being decided
                // against.
                currentReadChunkChars = readChunkChars(
                        perceptionWeight(perception, rendered));
                // -- decide --------------------------------------------------
                // The auto-read section is shown only while the agent is
                // on the URL it was extracted from, and only when that
                // page has not already been read to the end this run
                // (autoReadSectionFor, v2.2).
                String currentAutoRead = autoReadSectionFor(autoReadSection,
                        autoReadUrl, perception.url, readCompleteTotals);
                // v1.9: the completion banner joins it when THIS page's
                // text has been read to the end (by either read path).
                Integer completeBoxed = readCompleteTotals.get(perception.url);
                int completeTotal = (completeBoxed == null)
                        ? 0 : completeBoxed.intValue();
                // v2.9 (item 1 — the fit guarantee): the prompt is
                // assembled from its parts and measured BEFORE the
                // engine call; the degradation ladder degrades the
                // degradable parts (re-measuring after each rung)
                // until it fits PROMPT_TOKEN_BUDGET. When even the
                // ladder's end does not fit, the run stops HERE,
                // honestly — the engine's "ERROR: prompt too long"
                // is never again a run's epitaph when we could have
                // known beforehand (the v2.8 field death: the v1.8
                // trimmer measured that step-5 prompt as fitting,
                // in an estimator that under-measured its shape).
                // v3.0 (item 1): the scratchpad's FACTS block joins
                // the assembly — null while the notebook is empty,
                // so a fact-free run's prompt is byte-identical to
                // v2.9's. The fit ladder may stub it only at its
                // last rung; FitResult says when it did.
                String factsBlock = scratchpad.render();
                FitResult fit = fitPrompt(goal, perception, rendered,
                        history, historyReadUrls, currentAutoRead,
                        completeTotal,
                        factsBlock.length() > 0 ? factsBlock : null,
                        factsBlock.length() > 0
                                ? scratchpad.renderStubbed() : null);
                if (!fit.fits) {
                    stop(step, "budget", densePageSummary(fit.estTokens));
                    return;
                }
                if (fit.rungs > 0) {
                    trail.record("fit", step, Trail.fields(
                            "rungs", Long.valueOf(fit.rungs),
                            "est_tokens", Long.valueOf(fit.estTokens),
                            "facts_stubbed",
                            Boolean.valueOf(fit.factsStubbed)));
                    ui.feed("Step " + step + ": dense page; prompt "
                            + "trimmed to fit the model (est "
                            + fit.estTokens + " tokens)");
                    if (fit.factsStubbed) {
                        ui.feed("Step " + step + ": noted facts "
                                + "stubbed to a count to fit the "
                                + "prompt (their values still count "
                                + "as evidence)");
                    }
                }
                String prompt = fit.prompt;
                String raw = engine.generate(prompt, MAX_TOKENS_PER_STEP);
                ModelEngine.Stats stats = engine.lastStats();
                // v2.5: the run summary's token tallies — every
                // decide counts, including a race re-decide (it
                // really did spend the tokens again).
                totalPromptTokens += stats.lastPromptTokens;
                totalReusedTokens += stats.lastReusedTokens;
                // v1.8: an engine failure is NOT planner output. Through
                // v1.7 an "ERROR: ..." string fell into the unparseable
                // path below and the run burned three steps failing
                // closed on it (the v1.7 field run: context overflow at
                // step 4, two more identical overflows, stop). Record
                // the decide and stop immediately, honestly.
                if (isEngineError(raw)) {
                    trail.record("decide", step, Trail.fields(
                            "raw", truncate(raw, RAW_RECORDED),
                            "prompt_tokens", Long.valueOf(stats.lastPromptTokens),
                            "reused_tokens", Long.valueOf(stats.lastReusedTokens),
                            "evaluated_tokens", Long.valueOf(stats.lastEvaluatedTokens),
                            "prompt_ms", Long.valueOf(stats.lastPromptMs),
                            "grammar", Boolean.valueOf(stats.lastGrammarConstrained),
                            "model", modelId,
                            "grammar_setting", Boolean.valueOf(grammarSettingOn)));
                    stop(step, "error", engineErrorSummary(raw));
                    return;
                }
                GhAction action = PlannerParse.extract(raw);
                if (action == null) {
                    // Fail closed: nothing runs; the denial is on the record.
                    trail.record("decide", step, Trail.fields(
                            "raw", truncate(raw, RAW_RECORDED),
                            "prompt_tokens", Long.valueOf(stats.lastPromptTokens),
                            "reused_tokens", Long.valueOf(stats.lastReusedTokens),
                            "evaluated_tokens", Long.valueOf(stats.lastEvaluatedTokens),
                            "prompt_ms", Long.valueOf(stats.lastPromptMs),
                            "grammar", Boolean.valueOf(stats.lastGrammarConstrained),
                            "model", modelId,
                            "grammar_setting", Boolean.valueOf(grammarSettingOn)));
                    trail.record("govern", step, Trail.fields(
                            "classification", "unknown",
                            "outcome", Governor.DENY,
                            "reason", "unparseable planner output (fail closed)"));
                    ui.feed("Step " + step + ": planner output was not one JSON "
                            + "action — denied, nothing ran."
                            + modelStatsLine(stats));
                    unparseable++;
                    addHistory(history, historyReadUrls,
                            "step " + step + ": (invalid output) -> denied; "
                            + "reply with exactly one JSON action object", null);
                    trimHistory(history, historyReadUrls);
                    if (unparseable >= 3) {
                        stop(step, "error", "planner produced unparseable output 3 times");
                        return;
                    }
                    continue steps;
                }
                unparseable = 0;
                trail.record("decide", step, Trail.fields(
                        "action", action.toMap(),
                        "prompt_tokens", Long.valueOf(stats.lastPromptTokens),
                        "reused_tokens", Long.valueOf(stats.lastReusedTokens),
                        "evaluated_tokens", Long.valueOf(stats.lastEvaluatedTokens),
                        "prompt_ms", Long.valueOf(stats.lastPromptMs),
                        "grammar", Boolean.valueOf(stats.lastGrammarConstrained),
                        "model", modelId,
                        "grammar_setting", Boolean.valueOf(grammarSettingOn)));

                // -- done-grounding guard (v2.1: agent-loop correction,
                //    capped per run) — field evidence (trail-20261024-
                //    230228, the first non-Wikipedia test): the run
                //    reached DuckDuckGo, read the results page to
                //    end-of-text, and declared done with a summary that
                //    merely restated the goal ("Oakdale, Tennessee
                //    forecast provides tonight's low temperature and
                //    chance of rain.") — neither requested value in it.
                //    Every mechanical guard held; the answer was empty.
                //    When the goal requests number-shaped facts, a done
                //    summary with no digit and no plain statement of
                //    inability is corrected: go read the actual values,
                //    or say plainly they could not be found. After
                //    DONE_CORRECTION_CAP corrections the guard falls
                //    silent and dones execute exactly as before.
                // v3.0 (item 1): the guard now judges the summary
                // against the run's evidence — the text carried in
                // history (read chunks included), the current
                // page's auto-read section, and the scratchpad's
                // facts — instead of the summary's shape alone.
                // A number the planner read three pages ago and
                // noted still grounds its done; a number that
                // appears nowhere in the evidence is corrected
                // like a numberless restatement was in v2.1.
                String doneReason = RelevanceGuard.doneGroundingReason(
                        action, goal, doneCorrections,
                        groundingEvidence(history, currentAutoRead,
                                scratchpad));
                if (doneReason != null) {
                    doneCorrections++;
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "outcome", "correct",
                            "reason", doneReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(null)
                            + "  [summary states no values -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: your summary gives "
                            + "none of the values the goal asks for. Open "
                            + "the most relevant result and read its actual "
                            + "values, then answer done with the numbers, "
                            + "or say plainly that you could not find them.",
                            null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                if ("done".equals(action.action)) {
                    stop(step, "done", action.summary == null ? "goal complete" : action.summary);
                    return;
                }
                if ("stuck".equals(action.action)) {
                    // v3.3 (co-pilot tap): the planner's own stuck
                    // is a stuck stop like the detectors' — the
                    // user may rescue it the same way.
                    String stuckWhy = action.reason == null
                            ? "planner reported stuck" : action.reason;
                    if (offerAssist(step, stuckWhy, history,
                            historyReadUrls)) {
                        unchanged = 0;
                        lastSignature = null;
                        continue steps;
                    }
                    stop(step, "stuck", stuckWhy);
                    return;
                }

                // -- repeat-search correction (v1.2: same guard machinery
                //    as the relevance guard, distinct from the stuck
                //    counter below — a correction costs no stuck credit)
                String signature = action.signature();
                String repeatReason = RelevanceGuard.searchRepeatReason(
                        action, lastSearchQuery, onSearchResults);
                String repeatQuery = lastSearchQuery;
                if (repeatReason == null) {
                    // v1.3: the history-based condition — the previous
                    // EXECUTED action was this same search and nothing
                    // that moves or reads has executed since. Either
                    // condition firing corrects the repeat.
                    repeatReason = RelevanceGuard.searchRepeatByHistoryReason(
                            action, lastExecutedSearchQuery,
                            navigatedSinceSearch);
                    if (repeatReason != null) {
                        repeatQuery = lastExecutedSearchQuery;
                    }
                }
                if (repeatReason == null) {
                    // v1.6: the perception-based condition — the CURRENT
                    // page already IS this query's results page (its URL
                    // carries search=<query>, or its title reads "<query>
                    // - Search results ..."). Needs no in-run history,
                    // so it fires on a fresh run that starts on a
                    // leftover results page — the v1.5 run-2 shape, where
                    // executing the repeat re-navigated and the load
                    // aborted, killing the run.
                    repeatReason = RelevanceGuard.searchSamePageReason(
                            action, perception.title, perception.url);
                    if (repeatReason != null) {
                        repeatQuery = action.query;
                    }
                }
                if (repeatReason != null) {
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "outcome", "correct",
                            "reason", repeatReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(null)
                            + "  [repeat search -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: you already searched "
                            + "for \"" + repeatQuery + "\" and have not "
                            + "moved since. Next: tap the result link that "
                            + "matches the goal, or use {\"action\":\"read\"} "
                            + "to read this page. Do not search the same "
                            + "query again.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation: the next perception
                    // of the same page must not count toward stuck.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // Resolve the proposal's target element once, here: the
                // stuck check keys on it (v1.7) and every guard below
                // uses it.
                ElementInfo element = null;
                if (action.el >= 1) element = perception.get(action.el);

                // -- repeat-action guard (v3.1: agent-loop correction,
                //    capped per key per run) — field evidence
                //    (trail-20261025-025203, the v3.0.0 Hawaii fares
                //    run): the planner tapped #1 "Google Search" at
                //    step 2 (after the race retry re-decided it), read
                //    the fares at step 3, then proposed the SAME tap
                //    at step 4 — and no guard objected: the stuck
                //    counter keys per page+action and the intervening
                //    read broke the consecutiveness the repeat-search
                //    rule needs. So the repeat question is asked
                //    against a RUN-scoped ledger of successfully
                //    executed actions, keyed semantically (the heal's
                //    element identity for tap/type, the URL for
                //    goto — see RelevanceGuard.repeatActionKey), and
                //    answered before the stuck counter below, so a
                //    corrected repeat costs no stuck credit either.
                //    Search keeps its dedicated corrections above;
                //    read/scroll/back/wait and note/done/stuck are
                //    exempt (their keys are null). After
                //    REPEAT_ACTION_CORRECTION_CAP corrections for a
                //    key the proposal falls through and executes —
                //    the guard buys pointed re-asks, never a trap.
                String executedKey = RelevanceGuard.repeatActionKey(
                        action, element);
                Integer raBoxed = (executedKey == null) ? null
                        : repeatActionCorrections.get(executedKey);
                int raCorrections = (raBoxed == null)
                        ? 0 : raBoxed.intValue();
                String repeatActionReason =
                        RelevanceGuard.repeatActionReason(action,
                                element, executedActionKeys,
                                raCorrections);
                if (repeatActionReason != null) {
                    repeatActionCorrections.put(executedKey,
                            Integer.valueOf(raCorrections + 1));
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "element", element == null ? null
                                    : element.descriptor(),
                            "outcome", "correct",
                            "reason", repeatActionReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [already done this run -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: "
                            + repeatActionReason, null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // stuck: same action, same page, same target, three
                // times. v1.7: the counter keys on WHERE the action was
                // taken (repeatKey below), not the bare action JSON.
                // The v1.6 field run died here wrongly: taps on element
                // #20 on THREE DIFFERENT pages (the results page, the
                // results page again after the step-5 re-search, and
                // the Clinch disambiguation page) accumulated into one
                // "action repeated 3 times" stop while the agent was in
                // fact moving between pages. `read` is exempt:
                // consecutive reads return successive text chunks,
                // which is progress, not repetition. v3.0: `note` is
                // exempt for the same bookkeeping reason — the
                // scratchpad dedupes a repeated fact itself, and
                // writing several facts down in a row is progress,
                // not a loop.
                String countedKey = null;
                if (!"read".equals(action.action)
                        && !"note".equals(action.action)) {
                    String repeatKey = repeatKey(perception.url, action, element);
                    countedKey = repeatKey;
                    Integer seen = seenActions.get(repeatKey);
                    int count = (seen == null ? 0 : seen.intValue()) + 1;
                    seenActions.put(repeatKey, Integer.valueOf(count));
                    if (count > 3) {
                        // v3.3 (co-pilot tap): a repeated-action
                        // stuck stop is offered the same rescue.
                        // After an assist the repeated key is
                        // forgiven — the human changed the state
                        // the counter was judging.
                        String stuckWhy = "action repeated 3 times: "
                                + action.toJson();
                        if (offerAssist(step, stuckWhy, history,
                                historyReadUrls)) {
                            seenActions.remove(repeatKey);
                            unchanged = 0;
                            lastSignature = null;
                            continue steps;
                        }
                        stop(step, "stuck", stuckWhy);
                        return;
                    }
                }

                // -- relevance guard (v1.1: agent-loop correction, ---------
                //    distinct from the safety Governor below)
                String guardReason = RelevanceGuard.blockReason(
                        goal, searched, perception.title, perception.url,
                        action, element);
                if (guardReason != null) {
                    Integer gSeen = guardCounts.get(signature);
                    int gCount = (gSeen == null ? 0 : gSeen.intValue());
                    if (gCount < 3) {
                        guardCounts.put(signature, Integer.valueOf(gCount + 1));
                        trail.record("guard", step, Trail.fields(
                                "action", action.toMap(),
                                "element", element == null ? null
                                        : element.descriptor(),
                                "outcome", "correct",
                                "reason", guardReason));
                        ui.feed("Step " + step + ": guard corrected "
                                + action.describe(element)
                                + "  [off-goal -> not executed]");
                        addHistory(history, historyReadUrls,
                                "step " + step + ": " + action.toJson()
                                + " -> blocked by relevance guard (off-goal). "
                                + "The goal asks to search: reply with "
                                + "{\"action\":\"search\",\"query\":\"...\"} "
                                + "using the goal's target terms.", null);
                        trimHistory(history, historyReadUrls);
                        // A correction is not stagnation (see v1.2 repeat
                        // search): reset the unchanged-map counter.
                        unchanged = 0;
                        lastSignature = null;
                        continue steps;
                    }
                    // Cap reached for this action: fall through and let the
                    // governor / stuck logic above deal with the repeat.
                }

                // -- chrome-tab correction (v1.3: agent-loop, once per
                //    label per run; the v1.1 relevance guard stands down
                //    on arrived pages, which is exactly where this trap
                //    springs — the planner taps the "Article" tab of the
                //    article it is already reading)
                String chromeReason = RelevanceGuard.chromeTabReason(
                        goal, perception.title, perception.url, action,
                        element, chromeCorrected);
                if (chromeReason != null) {
                    chromeCorrected.add(RelevanceGuard.chromeTabLabel(element));
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "element", element == null ? null
                                    : element.descriptor(),
                            "outcome", "correct",
                            "reason", chromeReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [page tab -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: \"" + element.name
                            + "\" is a page tab - it does not change the "
                            + "page. You are already on the article; use "
                            + "{\"action\":\"read\"} to read it.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // -- pagination guard (v2.0: agent-loop, capped per ---
                //    run) While the agent is on a search-results page
                //    whose map provably contains a goal-matching result
                //    link, a tap on a pagination control pages AWAY
                //    from the answer. Field evidence (v1.9's first
                //    passing run): the planner tapped "Next page" twice
                //    on the Clinch River results page while the
                //    exact-match "Clinch River" result link sat in the
                //    map; it recovered by itself at step 5, but nothing
                //    discouraged leaving. The correction names the
                //    match and points the planner at it. When no
                //    goal-matching link is in the map the rule is
                //    silent — paging is legitimate when results are
                //    poor. Placed after the chrome-tab correction (a
                //    page tab keeps its specific message) and before
                //    the arrival guard (an arrived article page is not
                //    a results page, so the two never compete).
                String paginationReason = RelevanceGuard.paginationReason(
                        goal, perception.title, perception.url, action,
                        element, perception.elements, paginationCorrections);
                if (paginationReason != null) {
                    paginationCorrections++;
                    // The match the correction points at (the reason
                    // already names it; the history note repeats the
                    // direction in the planner's own terms).
                    ElementInfo match = RelevanceGuard.goalMatchingLink(
                            goal, perception.elements);
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "element", element == null ? null
                                    : element.descriptor(),
                            "outcome", "correct",
                            "reason", paginationReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [paging away from a matching result"
                            + " -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: " + paginationReason
                            + " Tap {\"action\":\"tap\",\"element\":"
                            + (match == null ? "?" : String.valueOf(match.number))
                            + "} (\"" + (match == null ? "" : match.name)
                            + "\") instead.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // -- arrival guard (v1.7: agent-loop, capped per run) ---
                // Once auto-read has fired for the page the agent is
                // STILL on, the answer's evidence is already in the
                // prompt — yet the v1.6 field run's step 5, with the
                // Clinch River article's opening text in front of it,
                // proposed `search "Clinch River"` again and walked
                // away from the page it had just read. No older guard
                // applied (the page was the article, not a results
                // page, and the intervening taps had disarmed the
                // repeat-search conditions). A proposal that would
                // LEAVE the arrived page — search, goto, back, or a
                // tap on a link — is corrected through the same guard
                // machinery; read, scroll, wait, done, stuck, type and
                // non-link taps proceed untouched. Placed after the
                // chrome-tab correction so a page tab keeps its
                // specific message on its first offence; a repeat tab
                // tap (label already corrected there) is caught here as
                // the link tap it is. After ARRIVAL_CORRECTION_CAP
                // corrections the guard falls silent and the planner's
                // proposals execute — no permanent trap.
                String arrivalReason = RelevanceGuard.arrivalLeaveReason(
                        action, element, perception.url, autoReadUrl,
                        arrivalCorrections);
                if (arrivalReason != null) {
                    arrivalCorrections++;
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "element", element == null ? null
                                    : element.descriptor(),
                            "outcome", "correct",
                            "reason", arrivalReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [leaving arrived page -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by arrival guard: you are on "
                            + "the target page and its opening text was "
                            + "auto-read above. Do not leave it: answer "
                            + "{\"action\":\"done\"} from that text, or "
                            + "use {\"action\":\"read\"} for more.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // -- end-of-text read correction (v1.8 as the
                //    auto-read-only case, generalised in v1.9) — when
                //    the text of the page the agent is still on has
                //    been read to the END (by the auto-read, by planner
                //    reads, or by the two together), the planner
                //    already holds the page's full text in its history.
                //    Another `read` can only wrap the shared cursor to
                //    the top and repeat the first chunk: the v1.7 field
                //    run did it after an auto-read (the duplicate then
                //    helped overflow the next prompt), and the v1.8
                //    field run did it at step 7 after its own reads
                //    paged 2,400 -> 11,604 — v1.8's rule, keyed on the
                //    auto-read alone, never saw that completion. The
                //    unified state (readCompleteTotals, fed at both
                //    read executions) sees both. Correct the read:
                //    answer done from the text already read. Capped
                //    per run; reads on other pages and reads before
                //    completion are never touched.
                Integer doneTotalBoxed =
                        readCompleteTotals.get(perception.url);
                String readCompleteReason = (doneTotalBoxed == null)
                        ? null
                        : RelevanceGuard.readAfterCompleteReason(action,
                                perception.url, perception.url,
                                doneTotalBoxed.intValue(),
                                readCompleteCorrections);
                if (readCompleteReason != null) {
                    readCompleteCorrections++;
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "element", element == null ? null
                                    : element.descriptor(),
                            "outcome", "correct",
                            "reason", readCompleteReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [page already fully read -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: you have read this "
                            + "entire page (chars 0-" + doneTotalBoxed
                            + " of " + doneTotalBoxed + "). Reading again "
                            + "only repeats it from the top. Answer "
                            + "{\"action\":\"done\"} from the text you "
                            + "have read.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // -- empty-map correction (v1.6: agent-loop, capped per
                //    run) — a tap/type against a map with ZERO elements
                //    can only fail ("element N not on page"): the page
                //    is blank, still loading, canvas-only, or a load
                //    failure the recovery above did not recognise.
                //    Correct it through the same guard machinery instead
                //    of executing a guaranteed failure; after the cap
                //    (RelevanceGuard.EMPTY_MAP_CORRECTION_CAP per run)
                //    the proposal falls through and the governor / stuck
                //    logic owns the repeat, as with the other guards.
                String emptyMapReason = RelevanceGuard.emptyMapReason(
                        action, perception.elements.size(), emptyMapCorrections);
                if (emptyMapReason != null) {
                    emptyMapCorrections++;
                    trail.record("guard", step, Trail.fields(
                            "action", action.toMap(),
                            "outcome", "correct",
                            "reason", emptyMapReason));
                    ui.feed("Step " + step + ": guard corrected "
                            + action.describe(element)
                            + "  [empty map -> not executed]");
                    addHistory(history, historyReadUrls,
                            "step " + step + ": " + action.toJson()
                            + " -> corrected by guard: this page shows no "
                            + "interactive elements (it may have failed to "
                            + "load or still be loading). Use goto, back, "
                            + "wait, or read instead of tap/type.", null);
                    trimHistory(history, historyReadUrls);
                    // A correction is not stagnation.
                    unchanged = 0;
                    lastSignature = null;
                    continue steps;
                }

                // -- govern (record-before-execute) ---------------------------
                Governor.Decision decision = governor.evaluate(action, element, perception.url);
                trail.record("govern", step, Trail.fields(
                        "action", action.toMap(),
                        "classification", decision.classification,
                        "outcome", decision.outcome,
                        "reason", decision.reason));
                ui.feed("Step " + step + ": " + action.describe(element)
                        + "  [" + decision.classification + " -> " + decision.outcome + "]"
                        + modelStatsLine(stats));

                if (Governor.DENY.equals(decision.outcome)) {
                    stop(step, "denied", decision.reason);
                    return;
                }
                if (Governor.ASK.equals(decision.outcome)) {
                    boolean approved = ui.approve(action, decision, action.describe(element));
                    trail.record("approval", step, Trail.fields(
                            "phase", "decision",
                            "classification", decision.classification,
                            "action", action.toMap(),
                            "approved", Boolean.valueOf(approved)));
                    if (!approved) {
                        stop(step, "denied", "approver declined the action");
                        return;
                    }
                }

                // -- execute (recorded before the hands move) -----------------
                trail.record("execute", step, Trail.fields(
                        "action", action.toMap(),
                        "classification", decision.classification,
                        "element", element == null ? null : element.descriptor()));
                String result;
                try {
                    // v3.0 (item 1): a note's body is the notebook,
                    // not the hands — noteHere records the "note"
                    // event (with the notebook's verdict) before
                    // the scratchpad is touched, so the trail's
                    // govern -> execute -> note order keeps
                    // record-before-execute true for the new
                    // action too.
                    result = "note".equals(action.action)
                            ? noteHere(action, perception, step)
                            : execute(action);
                } catch (Exception e) {
                    result = "error: " + e.getMessage();
                    trail.record("result", step, Trail.fields(
                            "ok", Boolean.FALSE, "result", truncate(result, 300)));
                    stop(step, "error", "action failed: " + e.getMessage());
                    return;
                }
                boolean ok = result != null && !result.startsWith("error");
                // -- navigation-race re-decide (v2.2) ------------------
                // The planner decided against `perception`, but a slow
                // navigation can land DURING the 40-100 s generation:
                // the hands then execute against a page that no longer
                // exists and the tap dies "element N not on page". Both
                // weather.gov field runs paid exactly one step to this
                // (step 2's tap on the departed homepage's SEARCH, after
                // step 1's search had already navigated to DuckDuckGo).
                // When the failure is that stale shape AND the page has
                // in fact moved on, the attempt is not a planner error:
                // its result is recorded (the hands did move), but it
                // earns no history entry, no action-state updates and
                // no stuck credit; the loop re-perceives and re-decides
                // the SAME step, at most RACE_RETRY_CAP times. A stale
                // error on the same URL is not a race — it is the
                // v2.7 stale-stash case handled just below when the
                // signature is also unchanged, and otherwise falls
                // through to the ordinary result handling.
                if (!ok && staleElementError(result)) {
                    Eyes.Perception fresh = null;
                    try {
                        fresh = hands.perceive();
                    } catch (Exception e) {
                        fresh = null;
                    }
                    if (fresh != null && shouldRaceRetry(perception.url,
                            fresh.url, result, raceRetries)) {
                        raceRetries++;
                        trail.record("result", step, Trail.fields(
                                "ok", Boolean.FALSE,
                                "result", truncate(result, 300)));
                        ui.feed("   -> " + truncate(result, 160));
                        trail.record("race", step, Trail.fields(
                                "action", action.toMap(),
                                "perceived_url", perception.url,
                                "current_url", fresh.url,
                                "result", truncate(result, 300)));
                        ui.feed("Step " + step + ": page changed while "
                                + "deciding; re-perceiving and deciding "
                                + "again");
                        if (countedKey != null) {
                            Integer c = seenActions.get(countedKey);
                            if (c != null) {
                                if (c.intValue() <= 1) {
                                    seenActions.remove(countedKey);
                                } else {
                                    seenActions.put(countedKey,
                                            Integer.valueOf(
                                                    c.intValue() - 1));
                                }
                            }
                        }
                        perception = fresh;
                        rendered = perception.render(goal);
                        trail.record("perceive", step, Trail.fields(
                                "url", perception.url,
                                "title", perception.title,
                                "text_preview",
                                truncate(perception.textPreview, 240),
                                "elements",
                                Long.valueOf(perception.elements.size()),
                                "map_chars",
                                Long.valueOf(rendered.length()),
                                "signature", perception.signature()));
                        continue;
                    }
                    // -- stale-stash refresh retry (v2.7) ------------
                    // The race's same-URL sibling: the failure is the
                    // stale shape, but the page has NOT moved — same
                    // URL, and the fresh perception's signature is
                    // identical to the deciding one. What failed was
                    // the execution-time view of the page (the stash
                    // / identity layer), not the decision, so the
                    // attempt earns no history entry, no action-state
                    // updates and no stuck credit; the fresh
                    // perception (which re-stashes) replaces the
                    // deciding one and the SAME step is re-decided,
                    // at most STALE_RETRY_CAP times. A second stale
                    // failure in the step falls through to the
                    // ordinary result handling below.
                    if (fresh != null && shouldStaleRetry(
                            perception.signature(), fresh.signature(),
                            perception.url, fresh.url, result,
                            staleRetries)) {
                        staleRetries++;
                        trail.record("result", step, Trail.fields(
                                "ok", Boolean.FALSE,
                                "result", truncate(result, 300)));
                        ui.feed("   -> " + truncate(result, 160));
                        trail.record("stale_stash", step, Trail.fields(
                                "action", action.toMap(),
                                "perceived_url", perception.url,
                                "current_url", fresh.url,
                                "perceived_signature",
                                perception.signature(),
                                "current_signature", fresh.signature(),
                                "result", truncate(result, 300)));
                        ui.feed("Step " + step + ": element map may "
                                + "be stale; refreshing and deciding "
                                + "again");
                        if (countedKey != null) {
                            Integer c = seenActions.get(countedKey);
                            if (c != null) {
                                if (c.intValue() <= 1) {
                                    seenActions.remove(countedKey);
                                } else {
                                    seenActions.put(countedKey,
                                            Integer.valueOf(
                                                    c.intValue() - 1));
                                }
                            }
                        }
                        perception = fresh;
                        rendered = perception.render(goal);
                        trail.record("perceive", step, Trail.fields(
                                "url", perception.url,
                                "title", perception.title,
                                "text_preview",
                                truncate(perception.textPreview, 240),
                                "elements",
                                Long.valueOf(perception.elements.size()),
                                "map_chars",
                                Long.valueOf(rendered.length()),
                                "signature", perception.signature()));
                        continue;
                    }
                }
                lastExecutedKind = action.action;
                // v3.1: the repeat-action guard's ledger — a
                // SUCCESSFULLY executed action's semantic key is
                // remembered, so a later identical proposal can be
                // corrected by the guard above. Only ok results
                // record: a failed attempt did nothing and proves
                // nothing (the race / stale-stash retries above
                // also never reach this line for their failed
                // attempts — they re-decide the step first).
                if (ok) {
                    String doneKey = RelevanceGuard.repeatActionKey(
                            action, element);
                    if (doneKey != null) {
                        executedActionKeys.add(doneKey);
                    }
                }
                // v2.5: dead-result detour bookkeeping — remember a
                // departure from a recognised search-results page
                // by one of its links (the page the loop can detour
                // back to if that destination fails to load). Any
                // other executed navigation — a goto, a back, a
                // search, a tap from any other page — clears the
                // "arrived via a results link" half: the agent is
                // then somewhere a detour has no claim on. Reads,
                // types, scrolls and waits do not move the agent
                // between pages and leave the state alone.
                if (ok && "tap".equals(action.action) && element != null
                        && Eyes.isLink(element)
                        && RelevanceGuard.isSearchResultsPage(
                                perception.title, perception.url)) {
                    lastResultsUrl = perception.url;
                    arrivedViaResultsLink = true;
                } else if (ok && ("tap".equals(action.action)
                        || "goto".equals(action.action)
                        || "back".equals(action.action)
                        || "search".equals(action.action))) {
                    arrivedViaResultsLink = false;
                }
                if (ok && "search".equals(action.action)) {
                    searched = true;
                    // v1.2: remember where we are — the results page for
                    // this query — so a repeat of the same search can be
                    // corrected instead of executed (and stuck-counted).
                    lastSearchQuery = action.query;
                    onSearchResults = true;
                    // v1.3: the execution-history record for the
                    // hardened repeat-search condition.
                    lastExecutedSearchQuery = action.query;
                    navigatedSinceSearch = false;
                }
                if (ok && ("tap".equals(action.action) || "type".equals(action.action)
                        || "goto".equals(action.action) || "back".equals(action.action))) {
                    // A navigating action leaves the results page behind.
                    onSearchResults = false;
                }
                if (ok && ("tap".equals(action.action) || "goto".equals(action.action)
                        || "read".equals(action.action) || "type".equals(action.action))) {
                    // v1.3: something that moves or reads has executed
                    // since the last search — the history-based repeat
                    // condition disarms (a later deliberate re-search is
                    // the stuck counter's problem again).
                    navigatedSinceSearch = true;
                }
                if (ok && "read".equals(action.action)
                        && readReachedEnd(result)
                        && !RelevanceGuard.isSearchResultsPage(
                                perception.title, perception.url)) {
                    // v1.9: a PLANNER read just paged to the end of
                    // this page's text — record the completion in the
                    // same map the auto-read feeds, so the end-of-text
                    // correction and the completion banner apply no
                    // matter which path read the page. (The v1.8 field
                    // run's reads reached 11,604 this way while the
                    // auto-read had stopped at 2,400.)
                    // v2.1: never on a recognised search-results page —
                    // a results page is a waypoint, and arming the
                    // completion state (banner, read-after-complete
                    // correction) on one was half of the weather.gov
                    // field failure: the DuckDuckGo results were read
                    // to end-of-text and treated as the arrived
                    // article. (The auto-read path needs no such gate:
                    // shouldAutoRead already stands down on results
                    // pages, so arrival cannot arm there either.)
                    int total = readTotalChars(result);
                    if (total > 0) {
                        readCompleteTotals.put(perception.url,
                                Integer.valueOf(total));
                    }
                }
                trail.record("result", step, Trail.fields(
                        "ok", Boolean.valueOf(ok), "result", truncate(result, 300)));
                ui.feed("   -> " + truncate(result, 160));
                // v1.2: read results are carried nearly whole (the text
                // is the answer's evidence); everything else stays lean.
                // A successful search also leaves a deterministic note so
                // the next prompt steers the planner at the results.
                int historyCap = "read".equals(action.action)
                        ? READ_HISTORY_CHARS : 120;
                String entry = "step " + step + ": " + action.toJson()
                        + " -> " + truncate(result, historyCap);
                // v3.1: the scratchpad nudge — the FIRST successful
                // read's history entry carries the one-time hint to
                // note facts as they are found (see NOTE_HINT). Once
                // per run, never repeated: later reads' entries are
                // untouched, and a run that never reads successfully
                // never sees it.
                if (ok && "read".equals(action.action)
                        && !noteHintGiven) {
                    noteHintGiven = true;
                    entry += NOTE_HINT;
                }
                if (ok && "search".equals(action.action)) {
                    entry += " [You searched for \"" + action.query + "\" and "
                            + "are on the results page. Next: tap the result "
                            + "link that matches the goal, or use "
                            + "{\"action\":\"read\"} to read this page. Do not "
                            + "search the same query again.]";
                }
                // v1.8: a successful read entry remembers which page its
                // chunk came from, so the prompt budget can stub the
                // chunk when the auto-read section shows the same page.
                String entryReadUrl = (ok && "read".equals(action.action))
                        ? perception.url : null;
                addHistory(history, historyReadUrls, entry, entryReadUrl);
                trimHistory(history, historyReadUrls);

                if ("wait".equals(action.action)) {
                    try {
                        Thread.sleep(action.ms);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                break;
            }
        }
        stop(MAX_STEPS, "budget", "step budget exhausted");
    }

    /**
     * v3.0 (item 1): execute a `note` action — record the fact in
     * this run's scratchpad, stamped with the page the planner is
     * looking at. The "note" trail event is written (with the
     * notebook's verdict, computed from a pure read of the
     * notebook) BEFORE the notebook changes: record-before-
     * execute, like every other action. The result strings are
     * the planner's feedback: a fresh note confirms its slot, a
     * duplicate confirms the fact is already held (a success —
     * the planner's aim was to hold it), and a full notebook is
     * an honest error steering the planner to answer from the
     * facts it has.
     */
    private String noteHere(GhAction a, Eyes.Perception perception,
                            int step) {
        String status = scratchpad.statusFor(a.fact);
        trail.record("note", step, Trail.fields(
                "fact", Scratchpad.normalise(a.fact),
                "url", perception.url,
                "title", perception.title,
                "status", status));
        if (Scratchpad.ADDED.equals(status)) {
            scratchpad.add(a.fact, perception.url, perception.title);
            return "ok: noted fact #" + scratchpad.size() + ": "
                    + Scratchpad.normalise(a.fact);
        }
        if (Scratchpad.DUPLICATE.equals(status)) {
            return "ok: that fact is already noted";
        }
        if (Scratchpad.FULL.equals(status)) {
            return "error: the fact notebook is full ("
                    + Scratchpad.MAX_FACTS + " facts) - answer done "
                    + "from the facts already noted";
        }
        return "error: nothing to note (empty fact)";
    }

    /**
     * v3.0 (item 1): the done-grounding guard's evidence — every
     * word the run can honestly claim to have taken in: the
     * history (whose read entries carry the read chunks nearly
     * whole), the current page's auto-read section, and the
     * scratchpad's facts. Pure assembly; the guard does the
     * judging.
     */
    public static String groundingEvidence(List<String> history,
                                           String autoReadText,
                                           Scratchpad pad) {
        StringBuilder sb = new StringBuilder();
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                sb.append(history.get(i)).append('\n');
            }
        }
        if (autoReadText != null) sb.append(autoReadText).append('\n');
        if (pad != null) sb.append(pad.factsText());
        return sb.toString();
    }

    // ---------------- v3.3: co-pilot + route replay --------------

    /** How long a co-pilot / route-fill pause may wait: the
     *  pack's approval timeout, in milliseconds — the pause
     *  counts against the pack clock exactly like the approval
     *  waits the timeout already governs. */
    private long pauseTimeoutMs() {
        return (long) (pack.approvalTimeout * 1000.0);
    }

    /** The name a history note calls an element: its name, else
     *  its href, else its tag. */
    private static String assistName(ElementInfo el) {
        if (el == null) return "an element";
        if (el.name != null && el.name.length() > 0) return el.name;
        if (el.href != null && el.href.length() > 0) return el.href;
        return (el.tag != null && el.tag.length() > 0) ? el.tag : "an element";
    }

    /**
     * v3.3 (item 4 — co-pilot tap): the run is about to stop
     * stuck — offer the user the wheel first. Returns true when
     * the user tapped an element and resumed: the assist is on
     * the trail (an "assist" event whose tapped phase carries
     * provenance USER — the tap was the user's hand, and is
     * never recorded as an agent action), the history carries
     * the note the brief prescribes, and the caller continues
     * the run with its stuck state reset. Returns false when no
     * assist is available (not a stuck stop, backgrounded, the
     * cap spent) or the pause was declined / expired — the
     * caller then stops stuck exactly as it always has.
     */
    private boolean offerAssist(int step, String stuckSummary,
                                List<String> history,
                                List<String> historyReadUrls) {
        if (!Copilot.eligible("stuck", assistsUsed, ui.foreground())) {
            return false;
        }
        assistsUsed++;
        trail.record("assist", step, Trail.fields(
                "phase", "offered",
                "reason", stuckSummary,
                "assists_used", Long.valueOf(assistsUsed)));
        ui.feed("Step " + step + ": Stuck here. Tap the element "
                + "yourself in the page, then press Resume — or "
                + "press Stop.");
        ElementInfo tapped = ui.awaitUserTap(step, pauseTimeoutMs());
        if (tapped == null) {
            trail.record("assist", step, Trail.fields(
                    "phase", "declined",
                    "reason", stuckSummary));
            return false;
        }
        trail.record("assist", step, Trail.fields(
                "phase", "tapped",
                "provenance", "user",
                "element", tapped.descriptor(),
                "name", tapped.name));
        addHistory(history, historyReadUrls,
                "step " + step + ": you were stuck (" + stuckSummary
                + "); the user tapped \"" + assistName(tapped)
                + "\" to get past a stuck point. Continue from "
                + "the page as it is now.", null);
        trimHistory(history, historyReadUrls);
        ui.feed("Step " + step + ": the user tapped \""
                + assistName(tapped) + "\" — continuing.");
        return true;
    }

    /**
     * v3.3 (item 1 — teach a route): replay a taught route on the
     * caller's (worker) thread. A replay is governed like a run:
     * the start navigation and every step are classified by the
     * pack in force, consequential steps ASK at replay time (a
     * recording is not a blank check), and every step is
     * trail-recorded before it executes, its records carrying
     * the "route_replay" provenance. Each step's element is
     * resolved live, by identity, against a fresh perception —
     * and the hands' own heal stands behind the execution, as
     * for any agent action. A step that cannot resolve stops
     * the replay honestly, naming the step. A redacted (password)
     * step is never executed at all: the replay pauses and the
     * user fills the field themselves.
     */
    public void runRoute(Routes.Route route) {
        runStartMs = System.currentTimeMillis();
        totalPromptTokens = 0;
        totalReusedTokens = 0;
        assistsUsed = 0;
        trail.record("run", 0, Trail.fields(
                "goal", "Route: " + route.name,
                "pack", pack.name,
                "model", modelId,
                "grammar_setting", Boolean.valueOf(grammarSettingOn)));
        trail.record("route", 0, Trail.fields(
                "phase", "start",
                "name", route.name,
                "start_url", route.startUrl,
                "steps", Long.valueOf(route.steps.size()),
                "provenance", "route_replay"));
        ui.status("Replaying route \"" + route.name
                + "\" (policy: " + pack.name + ")");
        ui.feed("Route \"" + route.name + "\": " + route.steps.size()
                + " step(s) from " + route.startUrl);

        GhAction go = new GhAction("goto");
        go.url = route.startUrl;
        if (!routeAction(0, go, null, "")) return;

        for (int i = 0; i < route.steps.size(); i++) {
            int step = i + 1;
            currentStep = step;
            Routes.Step rs = route.steps.get(i);
            if (ui.stopRequested()) {
                stop(step, "stopped", "user pressed Stop");
                return;
            }
            Eyes.Perception perception;
            try {
                perception = hands.perceive();
            } catch (Exception e) {
                stop(step, "error", "perceive failed: " + e.getMessage());
                return;
            }
            trail.record("perceive", step, Trail.fields(
                    "url", perception.url,
                    "title", perception.title,
                    "text_preview", truncate(perception.textPreview, 240),
                    "elements", Long.valueOf(perception.elements.size()),
                    "signature", perception.signature()));
            ui.feed("Step " + step + ": " + rs.describe());

            if (rs.redacted) {
                // The password step: the recording holds no text,
                // so there is nothing to govern or execute — the
                // fill is the user's own action, paused for here.
                trail.record("route", step, Trail.fields(
                        "phase", "user_fill",
                        "route_step", Long.valueOf(step),
                        "step_kind", rs.kind,
                        "element", rs.targetElement().descriptor(),
                        "provenance", "route_replay"));
                boolean filled = ui.awaitUserResume(step,
                        "Route step " + step + " fills a password "
                        + "field (" + rs.displayName() + "). It was "
                        + "never recorded — type it into the page "
                        + "yourself, then press Resume.",
                        pauseTimeoutMs());
                if (!filled) {
                    stop(step, "stopped", "route paused at step "
                            + step + " (" + rs.describe() + ") and "
                            + "the user stopped");
                    return;
                }
                trail.record("route", step, Trail.fields(
                        "phase", "user_filled",
                        "route_step", Long.valueOf(step),
                        "provenance", "user"));
                continue;
            }

            ElementInfo target = Routes.resolve(rs, perception.elements);
            if (target == null) {
                stop(step, "error", "route step " + step
                        + " could not be found on this page: "
                        + rs.describe());
                return;
            }
            GhAction a = new GhAction(rs.kind);
            a.el = target.number;
            if ("type".equals(rs.kind)) {
                a.text = (rs.text == null) ? "" : rs.text;
            }
            if (!routeAction(step, a, target, perception.url)) return;
        }
        stop(route.steps.size(), "done", "route \"" + route.name
                + "\" completed (" + route.steps.size() + " steps)");
    }

    /**
     * One governed route-replay action: classify, record, ask
     * when the pack asks, execute, record the result — run()'s
     * govern/execute core without the planner, every record
     * carrying the route provenance. Returns false when the run
     * has stopped (denied, declined, or the action failed) and
     * the replay must end.
     */
    private boolean routeAction(int step, GhAction a, ElementInfo element,
                                String pageUrl) {
        Governor.Decision decision = governor.evaluate(a, element, pageUrl);
        trail.record("govern", step, Trail.fields(
                "action", a.toMap(),
                "classification", decision.classification,
                "outcome", decision.outcome,
                "reason", decision.reason,
                "provenance", "route_replay",
                "route_step", Long.valueOf(step)));
        ui.feed("Step " + step + ": " + a.describe(element)
                + "  [" + decision.classification + " -> "
                + decision.outcome + "]");
        if (Governor.DENY.equals(decision.outcome)) {
            stop(step, "denied", decision.reason);
            return false;
        }
        if (Governor.ASK.equals(decision.outcome)) {
            boolean approved = ui.approve(a, decision, a.describe(element));
            trail.record("approval", step, Trail.fields(
                    "phase", "decision",
                    "classification", decision.classification,
                    "action", a.toMap(),
                    "approved", Boolean.valueOf(approved),
                    "provenance", "route_replay",
                    "route_step", Long.valueOf(step)));
            if (!approved) {
                stop(step, "denied", "approver declined the action");
                return false;
            }
        }
        trail.record("execute", step, Trail.fields(
                "action", a.toMap(),
                "classification", decision.classification,
                "element", element == null ? null : element.descriptor(),
                "provenance", "route_replay",
                "route_step", Long.valueOf(step)));
        String result;
        try {
            result = execute(a);
        } catch (Exception e) {
            result = "error: " + e.getMessage();
        }
        boolean ok = result != null && !result.startsWith("error");
        trail.record("result", step, Trail.fields(
                "ok", Boolean.valueOf(ok),
                "result", truncate(result, 300),
                "provenance", "route_replay",
                "route_step", Long.valueOf(step)));
        ui.feed("   -> " + truncate(result, 160));
        if (!ok) {
            stop(step, "error", "route step " + step + " failed: "
                    + truncate(result, 160));
            return false;
        }
        return true;
    }

    private String execute(GhAction a) throws Exception {
        if ("tap".equals(a.action)) return hands.tap(a.el);
        if ("type".equals(a.action)) return hands.type(a.el, a.text == null ? "" : a.text);
        if ("goto".equals(a.action)) return hands.gotoUrl(a.url);
        if ("scroll".equals(a.action)) return hands.scroll(a.dir == null ? "down" : a.dir);
        if ("back".equals(a.action)) return hands.back();
        if ("search".equals(a.action)) return hands.search(a.query == null ? "" : a.query);
        if ("read".equals(a.action)) return hands.read(currentReadChunkChars);
        if ("wait".equals(a.action)) return "ok: waited " + a.ms + "ms";
        return "error: unhandled action " + a.action;
    }

    private void stop(int step, String reason, String summary) {
        trail.record("stop", step, Trail.fields(
                "reason", reason, "summary", summary,
                "model", modelId,
                "grammar_setting", Boolean.valueOf(grammarSettingOn),
                "accounting", trail.accounting()));
        ui.feed("Stopped (" + reason + "): " + summary);
        // v2.5 (item 4 — run summary): every stop, whatever its
        // reason, closes with the run's account of itself: one
        // trail "run_summary" event and one feed line AFTER the
        // stop line (the planner's done summary above stays exactly
        // as written; this is a separate line).
        long seconds = (runStartMs > 0)
                ? (System.currentTimeMillis() - runStartMs) / 1000 : 0;
        trail.record("run_summary", step, Trail.fields(
                "outcome", reason,
                "model", modelId,
                "grammar_setting", Boolean.valueOf(grammarSettingOn),
                "steps", Long.valueOf(step),
                "seconds", Long.valueOf(seconds),
                "prompt_tokens", Long.valueOf(totalPromptTokens),
                "reused_tokens", Long.valueOf(totalReusedTokens),
                "reuse_percent", Long.valueOf(
                        reusePercent(totalPromptTokens, totalReusedTokens))));
        ui.feed(runSummaryLine(reason, step, seconds,
                totalPromptTokens, totalReusedTokens));
        ui.status("Agent stopped: " + reason);
        currentStep = 0;
    }

    /** v2.5: the reuse share of a run's prompt tokens, as a whole
     *  percent (reused * 100 / prompt, integer division). Zero
     *  prompt tokens — a run stopped before any decide — reads as
     *  0, never a division by zero. */
    public static int reusePercent(long promptTokens, long reusedTokens) {
        if (promptTokens <= 0) return 0;
        return (int) ((reusedTokens * 100) / promptTokens);
    }

    /** v2.5: the feed's closing line for a run, in substance
     *  "— run: done in 10 steps, 512 s, 61% of prompt reused".
     *  Pure: the host harness pins the format, including the
     *  zero-token shape. */
    public static String runSummaryLine(String outcome, int steps,
                                        long seconds, long promptTokens,
                                        long reusedTokens) {
        return "— run: " + outcome + " in " + steps + " steps, "
                + seconds + " s, "
                + reusePercent(promptTokens, reusedTokens)
                + "% of prompt reused";
    }

    /** v1.8: the ONLY way history entries are added: the entry and
     *  its read-source URL (null unless the entry is a read result)
     *  go in together, keeping the two lists the same length. */
    private static void addHistory(List<String> history,
                                   List<String> historyReadUrls,
                                   String entry, String readUrl) {
        history.add(entry);
        historyReadUrls.add(readUrl);
    }

    private static void trimHistory(List<String> history,
                                    List<String> historyReadUrls) {
        while (history.size() > HISTORY_KEPT) {
            history.remove(0);
            if (!historyReadUrls.isEmpty()) historyReadUrls.remove(0);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    /**
     * v1.7: the repeat (stuck) counter's key: WHERE an action was
     * proposed, not just what it was. Page URL + the action
     * signature + — for tap/type — the resolved element's identity
     * (name + href) from the CURRENT perception, falling back to
     * the bare element number when the element cannot be resolved
     * or carries neither a name nor an href. The same action JSON
     * on a different page therefore keys differently (the v1.6
     * field stop: element #20 tapped on three different pages),
     * while the same action on the same page at the same element
     * keys identically — exactly the pre-v1.7 counter's behaviour
     * for the same-page repeats it exists to catch.
     */
    public static String repeatKey(String pageUrl, GhAction action,
                                   ElementInfo element) {
        StringBuilder sb = new StringBuilder();
        sb.append(pageUrl == null ? "" : pageUrl).append('|');
        if (action == null) {
            sb.append("null");
            return sb.toString();
        }
        sb.append(action.signature());
        if ("tap".equals(action.action) || "type".equals(action.action)) {
            sb.append('|');
            String name = (element == null) ? null : element.name;
            String href = (element == null) ? null : element.href;
            boolean identified = (name != null && name.length() > 0)
                    || (href != null && href.length() > 0);
            if (identified) {
                sb.append(name == null ? "" : name).append('@')
                        .append(href == null ? "" : href);
            } else {
                sb.append('#').append(action.el);
            }
        }
        return sb.toString();
    }

    /**
     * v1.5: the model-stats suffix appended to the feed lines that
     * already report a step's decision, so the prefix cache's effect
     * is visible in the run feed without a new line per step:
     * " (model: 812 prompt tokens, 640 reused, 1204 ms)". A null
     * snapshot renders as all zeros.
     */
    public static String modelStatsLine(ModelEngine.Stats s) {
        long p = s == null ? 0 : s.lastPromptTokens;
        long r = s == null ? 0 : s.lastReusedTokens;
        long ms = s == null ? 0 : s.lastPromptMs;
        return " (model: " + p + " prompt tokens, " + r + " reused, "
                + ms + " ms)";
    }

    /**
     * v1.9: did this read result reach the end of the page's
     * extracted text? Eyes.formatReadChunk marks the final chunk
     * "; end of page text" (a mid-text chunk says "; read again
     * for more"). Both read paths (the loop's auto-read and a
     * planner `read` execution) report through that format, so
     * this one check feeds the completion state from either.
     */
    public static boolean readReachedEnd(String readResult) {
        return readResult != null
                && readResult.contains("; end of page text");
    }

    /**
     * v1.9: the total extracted-text length a read result reports
     * (the T in its "ok: read chars S-E of T; ..." header), or -1
     * when the result does not carry that header. The header
     * precedes the chunk text, so the first "read chars" / " of "
     * pair is always the framing, never page content.
     */
    public static int readTotalChars(String readResult) {
        if (readResult == null) return -1;
        int cs = readResult.indexOf("read chars ");
        if (cs < 0) return -1;
        int of = readResult.indexOf(" of ", cs);
        if (of < 0) return -1;
        int i = of + 4;
        int value = 0;
        boolean any = false;
        while (i < readResult.length()) {
            char c = readResult.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            any = true;
            i++;
        }
        return any ? value : -1;
    }

    /**
     * v1.3: relabel a hands.read() result ("ok: read chars S-E of
     * T; ...:\nTEXT") as the prompt's auto-read section
     * ("Page text (auto-read, chars S-E of T):\nTEXT"). Returns
     * null when the result does not have the read framing (an error
     * string, or a shape this build does not recognise) — the chunk
     * is then simply not injected.
     */
    public static String autoReadSection(String readResult) {
        if (readResult == null) return null;
        int headEnd = readResult.indexOf(":\n");
        if (headEnd < 0) return null;
        String header = readResult.substring(0, headEnd);
        int cs = header.indexOf("chars ");
        if (cs < 0) return null;
        String range = header.substring(cs);
        int semi = range.indexOf(';');
        if (semi >= 0) range = range.substring(0, semi);
        String text = readResult.substring(headEnd + 2);
        if (text.length() == 0) return null;
        return "Page text (auto-read, " + range + "):\n" + text;
    }

    /**
     * v2.4 (1.5B Max, item 5 — contract trim): the standing
     * instruction text, tightened in the same pass that added the
     * worked examples below. Every action's syntax line, the
     * read-for-more instruction, the done-with-values instruction
     * and every sentence a guard's history note refers back to are
     * preserved; what went was pure redundancy (each removal is
     * listed with its reason in BUILD_NOTES v2.4.0). The block is a
     * constant so the host harness can measure it exactly.
     *
     * v3.0 (item 1): the note action's syntax line and its rule
     * ("Note facts the goal needs." + the done-summary rule
     * extended to noted facts) joined, paid for by trims in the
     * sentences no guard note quotes: "Use only element numbers
     * from the map. One step at a time. When the goal is met,
     * answer done." compressed to "Use only map numbers; answer
     * done when the goal is met." (one-action-per-reply already
     * stands in line 1 and in the parser), "content or article
     * link unless its text matches" inverted to "a link whose
     * text matches no goal term", and the fillers "already" /
     * "below" dropped. Every sentence a test or a guard's
     * history note quotes survives verbatim, and the block stays
     * within the v2.4 budget pin (332 est) with the standing text
     * under its own (292).
     */
    public static final String CONTRACT_TEXT =
            "You are Ghost Hands, a careful web agent. Reply with EXACTLY ONE JSON object, no other text.\n"
            + "Actions: {\"action\":\"tap\",\"el\":N} {\"action\":\"type\",\"el\":N,\"text\":\"...\"} "
            + "{\"action\":\"search\",\"query\":\"...\"} {\"action\":\"read\"} "
            + "{\"action\":\"note\",\"fact\":\"...\"} "
            + "{\"action\":\"goto\",\"url\":\"...\"} {\"action\":\"scroll\",\"dir\":\"down\"} "
            + "{\"action\":\"back\"} {\"action\":\"wait\",\"ms\":1000} "
            + "{\"action\":\"done\",\"summary\":\"...\"} {\"action\":\"stuck\",\"reason\":\"...\"}\n"
            + "Use only map numbers; answer done when the goal is met.\n"
            + "Rules: if the goal asks to search or find something not "
            + "on this page, use search first. "
            + "Never tap a link whose text matches no goal term. "
            + "Do not tap featured or random homepage content. "
            + "For a question goal, open the target page, then use read "
            + "(again for more text if needed) before answering done. "
            + "Note facts the goal needs. "
            + "Base your done summary only on text you read or facts "
            + "you noted. "
            + "If a Page text (auto-read) section appears, answer "
            + "from it; use read again only if the fact you need is "
            + "not in it. "
            + "If the page title matches your target, you are ON the "
            + "article. Do not tap page tabs such as Article, Talk, "
            + "Read, or Edit; use read.\n";

    /**
     * v2.4 (1.5B Max, item 1 — few-shot examples): two miniature
     * worked examples in the prompt's own "Next action JSON:"
     * idiom, teaching the two behaviours the guards correct most:
     * (a) results discipline — on a results page, tap the link
     * that matches the goal, NOT the pagination control sitting
     * next to it (the v1.9 Clinch River run paged away twice);
     * (b) answer from text — when the page text carries the value
     * the goal asks for, the done summary carries that value (the
     * v2.0 weather run answered done with a restatement of the
     * goal and neither requested number). They live at the END of
     * the contract block, so they sit entirely inside the static
     * prefix the native prefix cache reuses across steps; the
     * per-step sections (goal, page, history, map) all follow.
     */
    public static final String EXAMPLES_TEXT =
            "Examples:\n"
            + "Results: [4] \"Clinch River\" [9] \"Next page\" -> Next "
            + "action JSON: {\"action\":\"tap\",\"el\":4}\n"
            + "Goal: its length? Text: \"Length 337 mi\" -> Next "
            + "action JSON: {\"action\":\"done\",\"summary\":\"337 "
            + "miles long.\"}\n";

    /** The full never-trimmed contract block: standing text plus
     *  the worked examples. buildPromptWithinBudget's trim stages
     *  never touch it, and the host harness pins its size against
     *  v2.3's (item 5's budget: the trim pays for the examples). */
    public static final String CONTRACT_BLOCK = CONTRACT_TEXT + EXAMPLES_TEXT;

    /** Lean prompt: contract + goal + last steps + capped map. */
    public static String buildPrompt(String goal, Eyes.Perception p, String rendered,
                                     List<String> history) {
        return buildPrompt(goal, p, rendered, history, null);
    }

    /**
     * v1.3: as the 4-arg prompt, plus the auto-read section for the
     * page the agent is currently on (null when no auto-read has
     * happened for this URL). The section is the labelled chunk the
     * loop extracted on arrival:
     * "Page text (auto-read, chars X-Y of Z):" + the chunk text.
     */
    public static String buildPrompt(String goal, Eyes.Perception p, String rendered,
                                     List<String> history, String autoReadText) {
        return buildPrompt(goal, p, rendered, history, autoReadText, 0);
    }

    /**
     * v1.9: as the 5-arg prompt, plus — when completeTotalChars
     * is positive — the completion banner: the current page's text
     * has been read to the end (by the auto-read, by planner reads,
     * or by both), so the planner already holds the whole page and
     * should answer from it, not read again. The end-of-text
     * correction enforces the same point when the banner is
     * ignored.
     *
     * v2.5 LAYOUT (cache geometry): the section order is now
     * contract → goal → page line → element map → text preview →
     * auto-read section → repeated goal → arrival banner →
     * completion banner → history → cue. Through v2.4 the map sat
     * AFTER the history, so on same-page steps the native prefix
     * cache (which reuses the longest common prefix with the
     * previous prompt) diverged inside the history and the whole
     * perception — map included, ~600-900 est tokens — was
     * re-evaluated every step. Everything before "Recent steps:"
     * is byte-identical across same-page steps now, so the cache
     * reuses all of it and only the history tail is fresh. The
     * banners keep their post-goal position in their group,
     * exactly once each, ahead of the history instead of ahead of
     * the cue; the arrival banner's "its text is above" stays
     * literally true (the auto-read section precedes it). The
     * information content is unchanged — the same lines, only
     * reordered — and the host harness measures the prefix gain
     * against a frozen copy of the v2.4 layout.
     */
    public static String buildPrompt(String goal, Eyes.Perception p, String rendered,
                                     List<String> history, String autoReadText,
                                     int completeTotalChars) {
        return buildPrompt(goal, p, rendered, history, autoReadText,
                completeTotalChars, null);
    }

    /**
     * v3.0 (item 1): as the 6-arg prompt, plus the scratchpad's
     * FACTS block (factsText — Scratchpad.render()'s output, or
     * its stubbed last-rung form). The block sits inside the
     * prompt's stable prefix, with the rest of the perception:
     * after the auto-read section, before the repeated goal. A
     * null or empty factsText leaves the prompt byte-identical
     * to the 6-arg's — a run that has noted nothing prompts
     * exactly as v2.9 did.
     */
    public static String buildPrompt(String goal, Eyes.Perception p, String rendered,
                                     List<String> history, String autoReadText,
                                     int completeTotalChars,
                                     String factsText) {
        StringBuilder sb = new StringBuilder();
        // v2.4: the contract block (standing text + worked examples)
        // is a single constant — see CONTRACT_TEXT / EXAMPLES_TEXT.
        sb.append(CONTRACT_BLOCK);
        sb.append("Goal: ").append(goal).append('\n');
        sb.append("Page: ").append(p.url);
        if (p.title != null && p.title.length() > 0) sb.append(" - ").append(p.title);
        sb.append('\n');
        // v2.5: the element map rides with the rest of the
        // perception, before the history (see the layout note in
        // this method's javadoc).
        sb.append("Elements:\n");
        sb.append(rendered);
        if (p.textPreview != null && p.textPreview.length() > 0) {
            sb.append("Page text: ").append(p.textPreview).append('\n');
        }
        if (autoReadText != null && autoReadText.length() > 0) {
            sb.append(autoReadText).append('\n');
        }
        // v3.0: the FACTS block — the run's noted evidence, riding
        // with the perception in the stable prefix (see the 7-arg
        // javadoc). Absent entirely while the notebook is empty.
        if (factsText != null && factsText.length() > 0) {
            sb.append(factsText).append('\n');
        }
        // Small-model salience fix (v1.1): repeat the goal after the
        // perception — a 1.5B planner attends to what is nearest its
        // reply. v2.5: the repeat now closes the prompt's stable
        // prefix (with the banners below) instead of sitting after
        // the history; it is still the last goal-shaped thing the
        // planner reads before the steps and the cue.
        sb.append("Goal: ").append(goal).append('\n');
        // v1.7: arrival banner — when the auto-read section is in
        // this prompt, one blunt line at the point of decision. The
        // arrival guard enforces the same commitment when the
        // planner ignores it; this is the cheap salience half.
        if (autoReadText != null && autoReadText.length() > 0) {
            sb.append("You are ON the target page and its text is above. "
                    + "Answer done from it, or read for more.\n");
        }
        // v1.9: completion banner — after the arrival banner when
        // both apply: this page's text has been read to the end.
        if (completeTotalChars > 0) {
            sb.append("You have read this entire page. Answer done "
                    + "from it now.\n");
        }
        sb.append("Recent steps:\n");
        if (history.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String h : history) sb.append(h).append('\n');
        }
        sb.append("Next action JSON:");
        return sb.toString();
    }

    // ---------------- v1.8: prompt budget + engine errors -----------

    /**
     * The token estimate the prompt budget enforces. Base rate is
     * the project's chars/4 (the estimate Trail.accounting uses),
     * rounded up: the budget is a ceiling, so it errs high.
     *
     * v2.2: DENSITY-AWARE. The v2.1 field run overflowed the real
     * 2048 context on weather.com even though this estimate said
     * the prompt fit: a URL-dense prompt (long hrefs in every map
     * line, URLs through the history) tokenizes far worse than
     * prose — BPE spends roughly a token per 3 chars on URL text,
     * not per 4. So each line containing "http" (the Page line,
     * map lines with hrefs, read text quoting URLs...) is charged
     * at chars/3 instead, applied as a surcharge over the base
     * rate for that line. Lines without "http" cost EXACTLY what
     * they cost before v2.2 — a prose prompt (the Clinch River
     * shape) estimates identically; only URL-carrying lines moved.
     *
     * v2.9: DIGIT-DENSE LINES. The v2.2 surcharge still missed the
     * v2.8 field death (trail-20261025-015133): the step-5 prompt
     * carried an AccuWeather hourly read — fragment lines of
     * temperatures, percents and times ("54°", "10%", "2 AM 54°
     * Clear 10%") — and digits tokenize as badly as URLs: a digit
     * pair or triple is typically its own token, and the degree
     * signs, percents and colons around them are theirs, so a
     * digit-dense line runs at roughly a token per 2 chars. A line
     * whose digits are at least a third of its non-space chars
     * (and which carries at least 2 digits) is therefore charged
     * at chars/2, as a surcharge over the base rate, taking the
     * LARGER of the two surcharges when a line is both URL- and
     * digit-dense (the rates do not stack — a line tokenizes at
     * its worst rate, not the sum of two). The separator is
     * measured against the prompt's own standing text: prose
     * lines run ~6% digits, the read/auto-read header lines
     * ("ok: read chars 0-2400 of 9074 ...") run 12-27%, hourly
     * data rows and fragments run 33-100%. Lines under the
     * threshold cost EXACTLY what they cost before v2.9 — the
     * Clinch-shaped prompt still estimates identically, and every
     * pre-v2.9 pin on this function still holds on its fixture.
     */
    public static int estTokens(String s) {
        if (s == null) return 0;
        int tokens = (s.length() + 3) / 4;
        int start = 0;
        while (start < s.length()) {
            int nl = s.indexOf('\n', start);
            int end = (nl < 0) ? s.length() : nl;
            int len = end - start;
            int surcharge = 0;
            if (lineHasHttp(s, start, end)) {
                surcharge = (len + 2) / 3 - (len + 3) / 4;
            }
            if (isDigitDenseLine(s, start, end)) {
                int d = (len + 1) / 2 - (len + 3) / 4;
                if (d > surcharge) surcharge = d;
            }
            tokens += surcharge;
            if (nl < 0) break;
            start = nl + 1;
        }
        return tokens;
    }

    /** v2.9: is this line digit-dense — at least 2 digits, and
     *  digits making up at least a third of its non-space chars?
     *  (See estTokens for the measured separators.) */
    private static boolean isDigitDenseLine(String s, int start, int end) {
        int digits = 0;
        int nonSpace = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') continue;
            nonSpace++;
            if (c >= '0' && c <= '9') digits++;
        }
        return digits >= 2 && digits * 3 >= nonSpace;
    }

    /** Case-insensitive "http" substring test over s[start, end)
     *  without allocating the line. */
    private static boolean lineHasHttp(String s, int start, int end) {
        for (int i = start; i + 4 <= end; i++) {
            char a = s.charAt(i);
            if (a != 'h' && a != 'H') continue;
            if (lowerEq(s.charAt(i + 1), 't')
                    && lowerEq(s.charAt(i + 2), 't')
                    && lowerEq(s.charAt(i + 3), 'p')) {
                return true;
            }
        }
        return false;
    }

    private static boolean lowerEq(char c, char lower) {
        return c == lower || c == (char) (lower - 'a' + 'A');
    }

    /** True when a generate() result is an ENGINE failure ("ERROR:
     *  ..." — no model loaded, a native failure, an over-long
     *  prompt), as opposed to planner output. ModelEngine.generate
     *  and the native shim both report failures in exactly this
     *  shape; the planner's own text is a JSON object (or gibberish,
     *  which is the fail-closed path's business, not this one's). */
    public static boolean isEngineError(String raw) {
        return raw != null && raw.startsWith("ERROR:");
    }

    /** The stop summary for an engine failure. */
    public static String engineErrorSummary(String raw) {
        return "model engine error: " + raw;
    }

    // ---------------- v2.2: navigation-race re-decide ---------------

    /** The stale-element failure shape: an element-addressed action
     *  executed against a page where that element no longer exists
     *  (Eyes.tapJs / typeJs and the heal path all report it in
     *  exactly this form). */
    private static boolean staleElementError(String result) {
        return result != null
                && result.startsWith("error: element")
                && result.contains("not on page");
    }

    /**
     * v2.2: was this failure a NAVIGATION RACE? The planner decided
     * against the perception taken at perceivedUrl; if the browser
     * has since moved to a different URL (currentUrl), the action
     * was executed against a page that no longer exists — the
     * failure indicts the timing, not the decision. Both weather
     * field runs paid exactly one step to this: a step-1 search
     * navigated weather.gov -> DuckDuckGo during the ~50 s decide,
     * and step 2's tap on the departed page's SEARCH died "element
     * 1 not on page". A stale error on the SAME URL is a genuine
     * stale element (the v2.0 heal's territory), not a race.
     * Pure: the host harness pins this decision.
     */
    public static boolean staleNavigationRace(String perceivedUrl,
                                              String currentUrl,
                                              String result) {
        if (!staleElementError(result)) return false;
        String p = (perceivedUrl == null) ? "" : perceivedUrl;
        String c = (currentUrl == null) ? "" : currentUrl;
        return !p.equals(c);
    }

    /**
     * v2.2: should the loop re-perceive and re-decide this step?
     * Yes when the failure was a navigation race and the step has
     * not already spent its RACE_RETRY_CAP retries. After the cap
     * the stale error stands as the step's ordinary result — a
     * page that races twice in one step is unstable, and the retry
     * heals the symptom, not the cause.
     */
    public static boolean shouldRaceRetry(String perceivedUrl,
                                          String currentUrl,
                                          String result,
                                          int retriesSoFar) {
        return retriesSoFar < RACE_RETRY_CAP
                && staleNavigationRace(perceivedUrl, currentUrl, result);
    }

    // ---------------- v2.7: stale-stash refresh retry ---------------

    /** Per-step cap on stale-stash refresh retries (sibling of
     *  RACE_RETRY_CAP): one refresh per step; a second stale
     *  failure in the same step stands as the ordinary result. */
    public static final int STALE_RETRY_CAP = 1;

    /**
     * v2.7: was this failure a STALE STASH rather than a navigation
     * race? The planner decided against a perception; the tap/type
     * died "element N not on page"; yet the page has NOT moved —
     * the URL is the same and a fresh perception's signature is
     * identical to the deciding one (the v2.6.1 field run,
     * trail-20261025-003150: signature d2f2d2f4:1508 at every step,
     * two of two taps dead). Nothing about the page changed, so
     * what failed was the execution-time view of it (the stashed
     * node list / identity layer), not the decision: refreshing
     * the perception (which re-stashes) and re-deciding the same
     * step is the honest retry. A changed URL belongs to the race
     * path above and never fires here; a changed signature means
     * the page really did change under the decision and the
     * failure stands. Pure: the host harness pins this decision.
     */
    public static boolean staleStashSuspect(String perceivedSignature,
                                            String currentSignature,
                                            String perceivedUrl,
                                            String currentUrl,
                                            String result) {
        if (!staleElementError(result)) return false;
        if (perceivedSignature == null || currentSignature == null) {
            return false;
        }
        if (!perceivedSignature.equals(currentSignature)) return false;
        String p = (perceivedUrl == null) ? "" : perceivedUrl;
        String c = (currentUrl == null) ? "" : currentUrl;
        return p.equals(c);
    }

    /**
     * v2.7: should the loop refresh the perception and re-decide
     * this step? Yes when the failure was a stale-stash suspect
     * and the step has not already spent its STALE_RETRY_CAP
     * retries. After the cap the stale error stands as the step's
     * ordinary result.
     */
    public static boolean shouldStaleRetry(String perceivedSignature,
                                           String currentSignature,
                                           String perceivedUrl,
                                           String currentUrl,
                                           String result,
                                           int retriesSoFar) {
        return retriesSoFar < STALE_RETRY_CAP
                && staleStashSuspect(perceivedSignature,
                        currentSignature, perceivedUrl, currentUrl,
                        result);
    }

    // ---------------- v2.5: dead-result detour --------------------

    /**
     * v2.5: should a page that failed to load — its one recovery
     * reload already spent — detour back to a search-results page
     * instead of stopping the run? Yes only when there is somewhere
     * to detour TO and a reason to believe it holds alternatives:
     * the agent arrived at the dead page by tapping a link on a
     * recognised results page (arrivedViaResultsLink, tracked in
     * the loop's post-execute bookkeeping), that results page's
     * URL is on record (lastResultsUrl), it is not itself the page
     * that failed, and the run has detours left (DETOUR_CAP).
     * Every other failure — a typed URL dying, a start page dying,
     * a third dead result — keeps the v1.6 honest stop. Pure: the
     * host harness pins this decision.
     */
    public static boolean shouldDetour(String failedUrl,
                                       String lastResultsUrl,
                                       int detoursUsed,
                                       boolean arrivedViaResultsLink) {
        if (!arrivedViaResultsLink) return false;
        if (lastResultsUrl == null || lastResultsUrl.length() == 0) {
            return false;
        }
        if (failedUrl != null && failedUrl.equals(lastResultsUrl)) {
            return false;
        }
        return detoursUsed < DETOUR_CAP;
    }

    /**
     * v2.2: which auto-read section (if any) the prompt for the
     * current page carries, factored purely. The section shows
     * only while the agent is on the URL it was extracted from
     * (the pre-v2.2 rule) — and, new in v2.2, NOT when that URL is
     * already in readCompleteTotals: the planner has read this
     * page to the end this run, so its text already rides in the
     * history, and injecting the section too would carry the same
     * text twice (the v2.1 field run's late-firing auto-read on
     * weather.com did exactly that and pushed the prompt over the
     * context). The completion banner is computed separately from
     * readCompleteTotals and is unaffected; the arrival GUARD keys
     * on autoReadUrl in the loop, also unaffected.
     */
    public static String autoReadSectionFor(String autoReadSection,
                                            String autoReadUrl,
                                            String currentUrl,
                                            Map<String, Integer> readCompleteTotals) {
        if (autoReadSection == null) return null;
        if (currentUrl == null || !currentUrl.equals(autoReadUrl)) {
            return null;
        }
        if (readCompleteTotals != null
                && readCompleteTotals.containsKey(currentUrl)) {
            return null;
        }
        return autoReadSection;
    }

    /**
     * v1.8 fix 2 (read-history dedupe), factored purely: when the
     * prompt carries an auto-read section for autoReadUrl, a history
     * entry that is a read result FROM THAT SAME PAGE would carry
     * the page's text a second time — in the v1.7 field run the
     * planner's wrapped re-read chunk sat in history at full
     * READ_HISTORY_CHARS weight next to the identical auto-read
     * section, and together they overflowed the next prompt. Such
     * an entry's chunk is replaced by a stub; the step line and the
     * action JSON survive (the planner still sees that it read).
     * Entries for other pages, non-read entries, and every entry
     * when no auto-read section is being shown are untouched.
     * Always returns a fresh list; the inputs are not mutated.
     */
    public static List<String> dedupeReadHistory(List<String> history,
                                                 List<String> historyReadUrls,
                                                 String autoReadUrl) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < history.size(); i++) {
            String entry = history.get(i);
            String readUrl = (historyReadUrls != null
                    && i < historyReadUrls.size())
                    ? historyReadUrls.get(i) : null;
            if (autoReadUrl != null && autoReadUrl.length() > 0
                    && readUrl != null && readUrl.equals(autoReadUrl)
                    && entry != null) {
                int arrow = entry.indexOf(" -> ");
                if (arrow >= 0) {
                    entry = entry.substring(0, arrow + 4)
                            + "(page text shown above in the "
                            + "auto-read section; not repeated here)";
                }
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * v1.8 fix 1 (prompt budget): assemble the step prompt through
     * buildPrompt, then — ONLY when its estimate exceeds
     * PROMPT_TOKEN_BUDGET — trim deterministically, in this order:
     *   (i)   stub duplicated read history (dedupeReadHistory);
     *   (ii)  drop the Page text preview;
     *   (iii) shorten history to the most recent 3 entries;
     *   (iv)  truncate the auto-read section's tail (marker appended);
     *   (v)   truncate the element map's tail (marker appended, at
     *         a line boundary).
     * After each step the prompt is rebuilt and re-measured, and
     * trimming stops as soon as the estimate fits. The contract,
     * the goal (either copy), the banners and the cue are never
     * trimmed. A prompt that fits untouched is returned
     * byte-identical to buildPrompt's output.
     * v1.9: completeTotalChars is threaded through to buildPrompt
     * (the completion banner); it takes part in the measurement
     * like everything else and, living in buildPrompt's fixed
     * tail, is never trimmed either.
     */
    public static String buildPromptWithinBudget(
            String goal, Eyes.Perception p, String rendered,
            List<String> history, List<String> historyReadUrls,
            String autoReadText, String autoReadUrl,
            int completeTotalChars) {
        String prompt = buildPrompt(goal, p, rendered, history,
                autoReadText, completeTotalChars);
        if (estTokens(prompt) <= PROMPT_TOKEN_BUDGET) return prompt;

        // (i) stub duplicated read history.
        if (autoReadText != null && autoReadText.length() > 0) {
            List<String> deduped = dedupeReadHistory(history,
                    historyReadUrls, autoReadUrl);
            prompt = buildPrompt(goal, p, rendered, deduped,
                    autoReadText, completeTotalChars);
            history = deduped;
            if (estTokens(prompt) <= PROMPT_TOKEN_BUDGET) return prompt;
        }

        // (ii) drop the Page text preview (a 240-char glimpse is the
        // cheapest thing in the prompt to lose).
        Eyes.Perception bare = new Eyes.Perception();
        bare.url = p.url;
        bare.title = p.title;
        bare.textPreview = "";
        bare.elements = p.elements;
        prompt = buildPrompt(goal, bare, rendered, history, autoReadText,
                completeTotalChars);
        if (estTokens(prompt) <= PROMPT_TOKEN_BUDGET) return prompt;

        // (iii) history to the most recent 3 entries.
        if (history.size() > 3) {
            history = new ArrayList<String>(
                    history.subList(history.size() - 3, history.size()));
            prompt = buildPrompt(goal, bare, rendered, history,
                    autoReadText, completeTotalChars);
            if (estTokens(prompt) <= PROMPT_TOKEN_BUDGET) return prompt;
        }

        // (iv) truncate the auto-read section's tail.
        if (autoReadText != null && autoReadText.length() > 0) {
            int excess = (estTokens(prompt) - PROMPT_TOKEN_BUDGET) * 4;
            int keep = autoReadText.length() - Math.max(excess, 0)
                    - AUTO_READ_TRUNC_MARKER.length();
            if (keep < 0) keep = 0;
            autoReadText = autoReadText.substring(0, keep)
                    + AUTO_READ_TRUNC_MARKER;
            prompt = buildPrompt(goal, bare, rendered, history,
                    autoReadText, completeTotalChars);
            if (estTokens(prompt) <= PROMPT_TOKEN_BUDGET) return prompt;
        }

        // (v) truncate the element map's tail, at a line boundary.
        int excess = (estTokens(prompt) - PROMPT_TOKEN_BUDGET) * 4;
        int keep = rendered.length() - Math.max(excess, 0)
                - MAP_TRUNC_MARKER.length();
        if (keep < 0) keep = 0;
        if (keep < rendered.length()) {
            int cut = rendered.lastIndexOf('\n', keep);
            if (cut < 0) cut = keep;
            rendered = rendered.substring(0, cut + 1) + MAP_TRUNC_MARKER;
            prompt = buildPrompt(goal, bare, rendered, history,
                    autoReadText, completeTotalChars);
        }

        // Last resort (the trims above suffice whenever the map or
        // the auto-read section carried the bulk; this covers a
        // pathological remainder): cut both to stubs and return
        // whatever remains — still bounded by the contract + goal.
        if (estTokens(prompt) > PROMPT_TOKEN_BUDGET) {
            if (autoReadText != null && autoReadText.length() > 0) {
                int nl = autoReadText.indexOf('\n');
                String head = (nl >= 0)
                        ? autoReadText.substring(0, nl) : autoReadText;
                autoReadText = head + AUTO_READ_TRUNC_MARKER;
            }
            int cut = rendered.lastIndexOf('\n', Math.min(200,
                    rendered.length()));
            rendered = (cut > 0)
                    ? rendered.substring(0, cut + 1) + MAP_TRUNC_MARKER
                    : MAP_TRUNC_MARKER;
            prompt = buildPrompt(goal, bare, rendered, history,
                    autoReadText, completeTotalChars);
        }
        return prompt;
    }

    // ---------------- v2.9: the fit guarantee ----------------

    /** The outcome of a pre-flight fit (fitPrompt): the prompt
     *  to hand the engine (null when nothing fits), its measured
     *  size, the highest ladder rung that was applied (0 = the
     *  untouched assembly already fit), and whether it fits. On
     *  a non-fit, estTokens is the least-bad candidate's measure
     *  — what the honest stop reports. */
    public static final class FitResult {
        public final String prompt;
        public final int estTokens;
        public final int rungs;
        public final boolean fits;
        /** v3.0: the ladder's last rung stubbed the FACTS block
         *  to its count (always false for fact-free assemblies). */
        public final boolean factsStubbed;

        public FitResult(String prompt, int estTokens, int rungs,
                         boolean fits) {
            this(prompt, estTokens, rungs, fits, false);
        }

        public FitResult(String prompt, int estTokens, int rungs,
                         boolean fits, boolean factsStubbed) {
            this.prompt = prompt;
            this.estTokens = estTokens;
            this.rungs = rungs;
            this.fits = fits;
            this.factsStubbed = factsStubbed;
        }
    }

    /**
     * v2.9 (item 1): assemble the step prompt from its parts via
     * buildPrompt, measure it with estTokens (the budgeter's own
     * estimator), and — only when it exceeds PROMPT_TOKEN_BUDGET
     * — degrade deterministically, re-measuring after each rung,
     * least damaging first:
     *   rung 1: stub the read chunks carried in history — every
     *     read entry shrinks to its step line + its "read chars
     *     S-E of T" accounting + READ_STUB_TAIL (stubReadChunk).
     *     History is by definition text the planner was already
     *     shown when it was fresh; the current page's own text
     *     still rides in the preview / auto-read section, and
     *     what a stub dropped can be re-read — on a dense page
     *     at item 3's smaller chunk.
     *   rung 2: truncate the text preview to PREVIEW_FIT_CHARS.
     *   rung 3: the map — first harder name/href caps
     *     (MAP_FIT_NAME_CHARS / MAP_FIT_HREF_CHARS, same char
     *     budget: more of the map visible per token), then the
     *     reduced set: the top MAP_FIT_KEEP elements
     *     (Eyes.topElements — goal-matching and interactive
     *     first, never renumbered) rendered under MAP_FIT_CHARS
     *     and headed by mapFitHeader, which states the reduction
     *     in the prompt itself.
     *   rung 4: the read-state accounting to counts only — the
     *     auto-read section's text leaves the prompt; a history
     *     note keeps its accounting (countsOnlyNote), so the
     *     planner knows the page was read, which range, and that
     *     seeing the text again is one `read` away. (Dropping
     *     the section also drops the arrival banner, whose "its
     *     text is above" would otherwise be a lie.)
     *   rung 5 (v3.0): the FACTS block is stubbed to its count
     *     (Scratchpad.renderStubbed) — the last thing the ladder
     *     may take, because the facts are the run's evidence;
     *     their count line still tells the planner they exist,
     *     and FitResult.factsStubbed + the loop's "fit" event
     *     say that this rung fired. Applies only when the
     *     assembly carries a facts block at all.
     * The contract, the goal (either copy), the current action
     * context, the banners' inputs and the cue are NOT degradable
     * and are never touched. When the ladder ends and the prompt
     * still does not fit, the result is a non-fit: the caller
     * stops the run honestly (densePageSummary) instead of
     * handing the engine a prompt it can only refuse. A prompt
     * that fits untouched is buildPrompt's output byte-for-byte.
     * Pure: the inputs are never mutated (history is copied at
     * the first rung that changes it).
     */
    public static FitResult fitPrompt(String goal, Eyes.Perception p,
                                      String rendered,
                                      List<String> history,
                                      List<String> historyReadUrls,
                                      String autoReadText,
                                      int completeTotalChars) {
        return fitPrompt(goal, p, rendered, history, historyReadUrls,
                autoReadText, completeTotalChars, null, null);
    }

    /** v3.0 (item 1): fitPrompt with the scratchpad's FACTS block
     *  (factsText, null when the notebook is empty) and its
     *  stubbed form (factsStubText) for rung 5. Every rung before
     *  the last carries the block whole; the facts are protected
     *  until nothing else is left to give. */
    public static FitResult fitPrompt(String goal, Eyes.Perception p,
                                      String rendered,
                                      List<String> history,
                                      List<String> historyReadUrls,
                                      String autoReadText,
                                      int completeTotalChars,
                                      String factsText,
                                      String factsStubText) {
        String prompt = buildPrompt(goal, p, rendered, history,
                autoReadText, completeTotalChars, factsText);
        int est = estTokens(prompt);
        if (est <= PROMPT_TOKEN_BUDGET) {
            return new FitResult(prompt, est, 0, true);
        }

        // rung 1: stub the read chunks carried in history.
        List<String> stubbed = stubReadChunksInHistory(history,
                historyReadUrls);
        prompt = buildPrompt(goal, p, rendered, stubbed, autoReadText,
                completeTotalChars, factsText);
        est = estTokens(prompt);
        if (est <= PROMPT_TOKEN_BUDGET) {
            return new FitResult(prompt, est, 1, true);
        }

        // rung 2: truncate the text preview to its shorter cap.
        Eyes.Perception slim = new Eyes.Perception();
        slim.url = p.url;
        slim.title = p.title;
        slim.textPreview = (p.textPreview == null) ? ""
                : (p.textPreview.length() > PREVIEW_FIT_CHARS
                        ? p.textPreview.substring(0, PREVIEW_FIT_CHARS)
                        : p.textPreview);
        slim.elements = p.elements;
        prompt = buildPrompt(goal, slim, rendered, stubbed, autoReadText,
                completeTotalChars, factsText);
        est = estTokens(prompt);
        if (est <= PROMPT_TOKEN_BUDGET) {
            return new FitResult(prompt, est, 2, true);
        }

        // rung 3a: the map under harder name/href caps.
        String mapNow = Eyes.renderElements(p.elements, goal,
                MAP_FIT_NAME_CHARS, MAP_FIT_HREF_CHARS,
                Eyes.MAP_BUDGET_CHARS);
        prompt = buildPrompt(goal, slim, mapNow, stubbed, autoReadText,
                completeTotalChars, factsText);
        est = estTokens(prompt);
        if (est <= PROMPT_TOKEN_BUDGET) {
            return new FitResult(prompt, est, 3, true);
        }

        // rung 3b: the reduced map set, headed by its own account.
        List<ElementInfo> kept = Eyes.topElements(p.elements, goal,
                MAP_FIT_KEEP);
        if (kept.size() < p.elements.size()) {
            mapNow = mapFitHeader(kept.size(), p.elements.size())
                    + Eyes.renderElements(kept, goal,
                            MAP_FIT_NAME_CHARS, MAP_FIT_HREF_CHARS,
                            MAP_FIT_CHARS);
            prompt = buildPrompt(goal, slim, mapNow, stubbed,
                    autoReadText, completeTotalChars, factsText);
            est = estTokens(prompt);
            if (est <= PROMPT_TOKEN_BUDGET) {
                return new FitResult(prompt, est, 3, true);
            }
        }

        // rung 4: the read-state accounting to counts only.
        List<String> noted = null;
        if (autoReadText != null && autoReadText.length() > 0) {
            noted = new ArrayList<String>(stubbed);
            noted.add(countsOnlyNote(autoReadText));
            prompt = buildPrompt(goal, slim, mapNow, noted, null,
                    completeTotalChars, factsText);
            est = estTokens(prompt);
            if (est <= PROMPT_TOKEN_BUDGET) {
                return new FitResult(prompt, est, 4, true);
            }
        }

        // rung 5 (v3.0): the FACTS block is stubbed to its count,
        // on top of the deepest assembly the ladder reached (the
        // rung-4 one when it ran, else rung 3's). The block's own
        // stub line says the texts went and the values still
        // count; FitResult.factsStubbed says it from the code
        // side, and the loop's "fit" event carries it.
        if (factsText != null && factsText.length() > 0) {
            List<String> histNow = (noted != null) ? noted : stubbed;
            String autoNow = (noted != null) ? null : autoReadText;
            prompt = buildPrompt(goal, slim, mapNow, histNow, autoNow,
                    completeTotalChars, factsStubText);
            est = estTokens(prompt);
            if (est <= PROMPT_TOKEN_BUDGET) {
                return new FitResult(prompt, est, 5, true, true);
            }
            return new FitResult(null, est, 5, false, true);
        }

        return new FitResult(null, est, 4, false);
    }

    /**
     * v2.9 rung 1, per entry: shrink a history read result to its
     * fixed small form — everything up to the read header's colon
     * (the step line, the action JSON, and the "ok: read chars
     * S-E of T (tier); read again for more" accounting) plus
     * READ_STUB_TAIL. The chunk text is the only thing dropped.
     * An entry without the read framing (no "read chars", or no
     * header colon) is returned unchanged. Pure.
     */
    public static String stubReadChunk(String entry) {
        if (entry == null) return null;
        if (!entry.contains("read chars ")) return entry;
        int colon = entry.indexOf(":\n");
        if (colon < 0) return entry;
        return entry.substring(0, colon + 1) + " " + READ_STUB_TAIL;
    }

    /** v2.9 rung 1, over the history: stub every entry the
     *  parallel read-URL list marks as a read result. Always a
     *  fresh list; the inputs are not mutated. */
    public static List<String> stubReadChunksInHistory(
            List<String> history, List<String> historyReadUrls) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < history.size(); i++) {
            String readUrl = (historyReadUrls != null
                    && i < historyReadUrls.size())
                    ? historyReadUrls.get(i) : null;
            String entry = history.get(i);
            out.add(readUrl != null ? stubReadChunk(entry) : entry);
        }
        return out;
    }

    /** v2.9 rung 3: the reduced map's header line — the prompt
     *  itself states that the map is a reduced set, and of how
     *  many: "map: 24 of 40 elements (page dense)". */
    public static String mapFitHeader(int kept, int total) {
        return "map: " + kept + " of " + total
                + " elements (page dense)\n";
    }

    /**
     * v2.9 rung 4: the history note that replaces the auto-read
     * section — the read's accounting and nothing else: the page
     * WAS auto-read, over this range, and its text is one `read`
     * away. The range is read back out of the section's own
     * header ("Page text (auto-read, chars S-E of T (tier)):");
     * a section without a readable range gets the generic note.
     */
    public static String countsOnlyNote(String autoReadText) {
        String range = null;
        if (autoReadText != null) {
            int cs = autoReadText.indexOf("chars ");
            if (cs >= 0) {
                int close = autoReadText.indexOf("):", cs);
                if (close > cs) {
                    range = autoReadText.substring(cs, close);
                }
            }
        }
        if (range != null) {
            return "note: this page was auto-read (" + range
                    + ") but its text is omitted from this prompt "
                    + "to fit the budget; use read to see it.";
        }
        return "note: this page was auto-read but its text is "
                + "omitted from this prompt to fit the budget; "
                + "use read to see it.";
    }

    /** v2.9: the honest stop's summary when even the ladder's
     *  end does not fit — the run ends with THIS, plus stop()'s
     *  run summary, never with the engine's refusal. */
    public static String densePageSummary(int estTokens) {
        return "page too dense for the model's context (prompt "
                + "still " + estTokens + " est tokens after every "
                + "trim; budget " + PROMPT_TOKEN_BUDGET + ")";
    }

    // ---------------- v2.9: adaptive read chunks (item 3) ---------

    /**
     * The measured weight of the CURRENT perception, in the same
     * estimator terms the budget uses: the page line, the element
     * map and the text preview, laid out exactly as buildPrompt
     * lays them out (so the number is a true slice of the step
     * prompt's own cost).
     */
    public static int perceptionWeight(Eyes.Perception p,
                                       String rendered) {
        StringBuilder sb = new StringBuilder();
        sb.append("Page: ").append(p.url);
        if (p.title != null && p.title.length() > 0) {
            sb.append(" - ").append(p.title);
        }
        sb.append('\n');
        sb.append("Elements:\n");
        sb.append(rendered);
        if (p.textPreview != null && p.textPreview.length() > 0) {
            sb.append("Page text: ").append(p.textPreview).append('\n');
        }
        return estTokens(sb.toString());
    }

    /** Is a perception of this weight a dense page? */
    public static boolean densePerception(int weightEstTokens) {
        return weightEstTokens >= DENSE_PERCEPTION_TOKENS;
    }

    /** The read chunk for a perception of this weight: the
     *  smaller dense-page chunk when dense, else the standing
     *  Eyes.READ_CHUNK_CHARS. Chunk/cursor/end-of-text semantics
     *  are the read path's own and do not change with the size. */
    public static int readChunkChars(int weightEstTokens) {
        return densePerception(weightEstTokens)
                ? DENSE_READ_CHUNK_CHARS : Eyes.READ_CHUNK_CHARS;
    }
}
