package com.ghosthands.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v3.0 (item 4): the answer card — a run's result, rebuilt
 * entirely from its trail events. When a run ends (done, stuck,
 * error, stopped, budget, denied) the app shows the card: the
 * goal, the outcome in plain words (the done summary for a
 * finish, the stop reason's account otherwise), the final page
 * as the source, the run's stats, and the facts the planner
 * noted along the way. A past run's card is the same object
 * rebuilt from that run's trail file — there is one formatter,
 * used for live runs and history alike, and it is pure: the
 * Activity only ever hands it events (or a trail file's text)
 * and displays what comes back.
 *
 * The card is a VIEW of the trail, never a second record: every
 * line on it is traceable to an event (the "run" event's goal,
 * the last "perceive" before the stop for the source, the
 * "stop" event's reason + summary, the "run_summary" event's
 * numbers, the "note" events' added facts). A trail with no
 * stop event — a run still in flight, or one that died before
 * the loop started — has no card (fromEvents returns null).
 *
 * Pre-v3.0 trails carry no "run" or "note" events; the caller
 * may pass a fallback goal for those, and the facts section
 * simply stays empty.
 *
 * v3.1: the findings fallback. Field evidence
 * (trail-20261025-025203, the Hawaii fares run): the run read
 * real fares (Honolulu $734, Kahului $698) at step 3, looped
 * on a repeated tap, and ended without an answer — its card
 * led with the bare error line, and the findings it HAD
 * gathered were nowhere on it. So a run that ends WITHOUT a
 * done summary and with an EMPTY scratchpad now carries a
 * "What it found" section: the opening text of the last
 * successful read the trail records (a planner `read`'s ok
 * result, or an auto-read's extraction), trimmed to
 * EXCERPT_CHARS and labelled as an excerpt from the source
 * page. A done summary stays the answer, noted facts stay
 * the facts section — the fallback fires only when both are
 * absent. And the outcome line no longer poses as the
 * answer: both renderings label it "Answer:" for a done and
 * "Outcome:" for everything else.
 *
 * v3.2: the watch banner. A run of a watched saved goal ends
 * with a "watch" trail event (its verdict against the goal's
 * baseline — see WatchCompare), and the card shows it: a quiet
 * "no change since <when>", a "changed since <when>" with the
 * diff lines, "baseline set" for the first watched check, or
 * "couldn't check this time" when the run ended without an
 * answer. Like everything else on the card, the banner is a
 * view of the trail, so a past watched run rebuilds with its
 * banner intact.
 */
public class AnswerCard {

    /** The findings excerpt's length cap, in chars. */
    public static final int EXCERPT_CHARS = 400;

    public String goal = "";
    public String outcome = "";
    public String outcomeLine = "";
    public String sourceTitle = "";
    public String sourceUrl = "";
    public long steps = 0;
    public long seconds = 0;
    public long reusePercent = 0;
    public boolean hasStats = false;
    /** Rendered fact lines: "- <fact> (from: <source label>)". */
    public final List<String> facts = new ArrayList<String>();
    /** v3.1: the findings fallback's excerpt — the opening
     *  text of the run's last successful read, or "" when the
     *  run does not qualify (a done, a note-carrying run, or
     *  a run that never read successfully). */
    public String findings = "";
    /** v3.2: the watch banner's raw material, from the run's
     *  "watch" trail event (the last one wins). watchVerdict is
     *  "" for a run that was not watched; watchSinceMs is the
     *  baseline's completion time the verdict compared against
     *  (0 when there was no baseline or it is unknown). */
    public String watchVerdict = "";
    public String watchGoal = "";
    public long watchSinceMs = 0;
    /** v3.8: the change arrived during watch quiet hours — it
     *  was recorded but neither notified nor buzzed, and the
     *  banner says so. Read from the "watch" event's "quiet"
     *  flag; absent (every pre-v3.8 trail, and every change
     *  outside the window) reads false. */
    public boolean watchQuiet = false;
    public final List<String> watchDiff = new ArrayList<String>();
    /** v3.3: the run's end-of-run screenshot — the file NAME in
     *  the app's private shots store (Shots), from the run's
     *  "screenshot" trail event; "" when the run has none. The
     *  card is a view of the trail, so a past run's card names
     *  its photo the same way; whether the file still exists is
     *  the Activity's check (retention prunes to the newest 20,
     *  so an old card's photo may honestly be gone). The share
     *  TEXT never carries it — the photo stays on the card. */
    public String screenshotFile = "";
    /** v3.7: the run's step photographs — the file names from
     *  its "step_screenshot" trail events, in trail order (the
     *  steps' own order), from runs made with step screenshots
     *  on. The card view shows them as a thumbnail strip under
     *  the end shot; like the end shot they never enter the
     *  card TEXT or the share text. Empty for every run made
     *  with the setting off — which is every run before v3.7
     *  and the default since. */
    public final List<String> stepScreenshotFiles =
            new ArrayList<String>();
    /** v3.12 (R4): the events this card was built from — the
     *  same list handed to fromEvents, kept so the Activity can
     *  run RetryStrategy's pure mapping over the run's recorded
     *  failure shape without re-reading the trail. A view, like
     *  everything else on the card: never rendered into the
     *  card text or the share text. */
    public List<Map<String, Object>> sourceEvents = null;

    private AnswerCard() { }

    // ---------------- building ----------------

    /** The card for a run's events, or null when the events hold
     *  no stop (no finished run, no card). fallbackGoal fills
     *  the goal line when the trail has no "run" event. */
    public static AnswerCard fromEvents(List<Map<String, Object>> events,
                                        String fallbackGoal) {
        if (events == null) return null;
        AnswerCard built = buildFromEvents(events, fallbackGoal);
        if (built != null) built.sourceEvents = events;
        return built;
    }

    private static AnswerCard buildFromEvents(List<Map<String, Object>> events,
                                        String fallbackGoal) {
        Map<String, Object> stopEv = null;
        Map<String, Object> summaryEv = null;
        Map<String, Object> watchEv = null;
        Map<String, Object> lastPerceive = null;
        String shotFile = "";
        List<String> stepShots = new ArrayList<String>();
        String runGoal = null;
        // v3.1: the findings fallback's raw material — the last
        // successful read result the events record. A planner
        // read reports through its execute -> result pair (the
        // result counts only when ok and the executed action was
        // a read); an auto-read reports through its own "read"
        // event, which the loop records only on success.
        String lastExecuteKind = null;
        String lastReadResult = null;
        List<Scratchpad.Fact> noted = new ArrayList<Scratchpad.Fact>();
        Set<String> notedTexts = new LinkedHashSet<String>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            String type = str(ev.get("type"));
            if ("run".equals(type)) {
                runGoal = str(ev.get("goal"));
            } else if ("perceive".equals(type)) {
                lastPerceive = ev;
            } else if ("execute".equals(type)) {
                Object a = ev.get("action");
                if (a instanceof Map) {
                    lastExecuteKind = str(((Map<?, ?>) a).get("action"));
                }
            } else if ("result".equals(type)) {
                if (Boolean.TRUE.equals(ev.get("ok"))
                        && "read".equals(lastExecuteKind)) {
                    String r = str(ev.get("result"));
                    if (r.length() > 0) lastReadResult = r;
                }
            } else if ("read".equals(type)) {
                String r = str(ev.get("result"));
                if (r.length() > 0) lastReadResult = r;
            } else if ("note".equals(type)) {
                String status = str(ev.get("status"));
                if (status.length() > 0
                        && !Scratchpad.ADDED.equals(status)) {
                    continue;
                }
                String fact = str(ev.get("fact"));
                if (fact.length() == 0 || !notedTexts.add(fact)) {
                    continue;
                }
                noted.add(new Scratchpad.Fact(fact,
                        str(ev.get("url")), str(ev.get("title"))));
            } else if ("stop".equals(type)) {
                stopEv = ev;
            } else if ("run_summary".equals(type)) {
                summaryEv = ev;
            } else if ("watch".equals(type)) {
                watchEv = ev;
            } else if ("screenshot".equals(type)) {
                String f = str(ev.get("file"));
                if (f.length() > 0) shotFile = f;
            } else if ("step_screenshot".equals(type)) {
                String f = str(ev.get("file"));
                if (f.length() > 0) stepShots.add(f);
            }
        }
        if (stopEv == null) return null;
        AnswerCard card = new AnswerCard();
        card.goal = (runGoal != null && runGoal.length() > 0)
                ? runGoal
                : (fallbackGoal == null ? "" : fallbackGoal);
        card.outcome = str(stopEv.get("reason"));
        card.outcomeLine = outcomeLine(card.outcome,
                str(stopEv.get("summary")));
        if (lastPerceive != null) {
            card.sourceUrl = str(lastPerceive.get("url"));
            card.sourceTitle = str(lastPerceive.get("title"));
        }
        if (summaryEv != null) {
            card.hasStats = true;
            card.steps = num(summaryEv.get("steps"));
            card.seconds = num(summaryEv.get("seconds"));
            card.reusePercent = num(summaryEv.get("reuse_percent"));
        }
        card.screenshotFile = shotFile;
        card.stepScreenshotFiles.addAll(stepShots);
        for (int i = 0; i < noted.size(); i++) {
            Scratchpad.Fact f = noted.get(i);
            String line = "- " + f.text;
            String src = f.sourceLabel();
            if (src.length() > 0) {
                line += " (from: " + src + ")";
            }
            card.facts.add(line);
        }
        // v3.1: the findings fallback — only when the run
        // produced neither an answer (a done summary) nor
        // noted facts to show for itself.
        if (!"done".equals(card.outcome) && card.facts.isEmpty()
                && lastReadResult != null) {
            card.findings = readExcerpt(lastReadResult);
        }
        // v3.2: the watch banner's material. The event records
        // its diff as a list of strings; anything else (a bare
        // string, junk) is read tolerantly.
        if (watchEv != null) {
            card.watchVerdict = str(watchEv.get("verdict"));
            card.watchGoal = str(watchEv.get("goal_name"));
            card.watchSinceMs = num(watchEv.get("since_ms"));
            card.watchQuiet = Boolean.TRUE.equals(
                    watchEv.get("quiet"));
            Object diff = watchEv.get("diff");
            if (diff instanceof List) {
                List<?> lines = (List<?>) diff;
                for (int i = 0; i < lines.size(); i++) {
                    String line = str(lines.get(i));
                    if (line.length() > 0) card.watchDiff.add(line);
                }
            } else {
                String line = str(diff);
                if (line.length() > 0) card.watchDiff.add(line);
            }
        }
        return card;
    }

    /**
     * v3.2: the watch banner, or "" when the run was not
     * watched. "since" names the baseline check the verdict
     * compared against — the previous check, as the baseline
     * is replaced by every watched done run.
     */
    public String watchBanner() {
        if (watchVerdict.length() == 0) return "";
        if (WatchCompare.BASELINE.equals(watchVerdict)) {
            return "Watch: baseline set — later checks compare "
                    + "against this run.";
        }
        String since = (watchSinceMs > 0)
                ? " since " + watchTimeLabel(watchSinceMs) : "";
        if (WatchCompare.NO_CHANGE.equals(watchVerdict)) {
            return "Watch: no change" + since + ".";
        }
        if (WatchCompare.CHANGED.equals(watchVerdict)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Watch: changed").append(since).append(':');
            for (int i = 0; i < watchDiff.size(); i++) {
                sb.append("\n- ").append(watchDiff.get(i));
            }
            if (watchQuiet) {
                sb.append("\nArrived during quiet hours — kept "
                        + "quiet until you opened the app.");
            }
            return sb.toString();
        }
        if (WatchCompare.NOT_DONE.equals(watchVerdict)) {
            return "Watch: couldn't check this time — the "
                    + "baseline is unchanged.";
        }
        return "";
    }

    /** The banner's timestamp form: "Sun Oct 25, 7:02 AM". */
    public static String watchTimeLabel(long tsMs) {
        return new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                .format(new Date(tsMs));
    }

    /**
     * v3.1: the opening text of a read result, for the findings
     * fallback: the framing header ("ok: read chars S-E of T
     * (tier); ...:") is stripped at its ":\n" boundary, the
     * dense-read note (Agent.DENSE_READ_NOTE rides at the
     * result's tail) is dropped when the excerpt reaches it,
     * and the text is trimmed to EXCERPT_CHARS. A result
     * without the read framing yields its own trimmed text —
     * the excerpt is a view of whatever was read, never an
     * invention. Pure: the harness pins the shapes.
     */
    public static String readExcerpt(String readResult) {
        if (readResult == null) return "";
        int headEnd = readResult.indexOf(":\n");
        String text = (headEnd >= 0)
                ? readResult.substring(headEnd + 2) : readResult;
        text = text.trim();
        if (text.endsWith(Agent.DENSE_READ_NOTE)) {
            text = text.substring(0, text.length()
                    - Agent.DENSE_READ_NOTE.length()).trim();
        }
        if (text.length() > EXCERPT_CHARS) {
            text = text.substring(0, EXCERPT_CHARS) + "…";
        }
        return text;
    }

    /** The card for a trail file's JSONL text (blank and junk
     *  lines are skipped; an unparseable line does not sink the
     *  runs around it). Same null rule as fromEvents. */
    public static AnswerCard fromTrailText(String jsonl,
                                           String fallbackGoal) {
        if (jsonl == null) return null;
        List<Map<String, Object>> events =
                new ArrayList<Map<String, Object>>();
        String[] lines = jsonl.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            Object parsed;
            try {
                parsed = Json.parse(line);
            } catch (Exception e) {
                continue;
            }
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = (Map<String, Object>) parsed;
                events.add(ev);
            }
        }
        return fromEvents(events, fallbackGoal);
    }

    /** The outcome in plain words: a done speaks for itself;
     *  every other ending is named before its account. */
    public static String outcomeLine(String outcome, String summary) {
        String s = (summary == null) ? "" : summary.trim();
        if ("done".equals(outcome)) {
            return s.length() > 0 ? s : "Goal complete.";
        }
        String head;
        if ("stuck".equals(outcome)) {
            head = "Got stuck";
        } else if ("error".equals(outcome)) {
            head = "Error";
        } else if ("stopped".equals(outcome)) {
            head = "Stopped";
        } else if ("denied".equals(outcome)) {
            head = "Denied";
        } else if ("budget".equals(outcome)) {
            head = "Stopped (budget)";
        } else {
            head = "Stopped (" + outcome + ")";
        }
        return s.length() > 0 ? head + " — " + s : head + ".";
    }

    // ---------------- rendering ----------------

    /** The card as shown on screen: goal, outcome, source,
     *  stats, findings, facts — only the sections the run
     *  produced. v3.1: the outcome line is labelled (a done is
     *  the "Answer:", every other ending an "Outcome:"), and
     *  the findings section rides when the fallback filled it. */
    public String cardText() {
        StringBuilder sb = new StringBuilder();
        if (goal.length() > 0) {
            sb.append("Goal: ").append(goal).append('\n');
        }
        sb.append("done".equals(outcome) ? "Answer: " : "Outcome: ")
                .append(outcomeLine);
        String banner = watchBanner();
        if (banner.length() > 0) {
            sb.append('\n').append(banner);
        }
        if (sourceUrl.length() > 0) {
            sb.append("\nSource: ");
            if (sourceTitle.length() > 0) {
                sb.append(sourceTitle).append(" — ");
            }
            sb.append(sourceUrl);
        }
        if (hasStats) {
            sb.append("\nRun: ").append(steps).append(" steps, ")
                    .append(seconds).append(" s, ")
                    .append(reusePercent)
                    .append("% of prompt reused");
        }
        if (findings.length() > 0) {
            sb.append("\nWhat it found (excerpt from the source "
                    + "page):\n").append(findings);
        }
        if (!facts.isEmpty()) {
            sb.append("\nFacts noted:");
            for (int i = 0; i < facts.size(); i++) {
                sb.append('\n').append(facts.get(i));
            }
        }
        return sb.toString();
    }

    /** The card as plain text for the share sheet: the same
     *  content, headed, dated, and with the done's line labelled
     *  "Answer" (an unanswered run's line stays "Outcome"). */
    public String shareText(String dateLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ghost Hands");
        if (dateLabel != null && dateLabel.length() > 0) {
            sb.append(" — ").append(dateLabel);
        }
        if (goal.length() > 0) {
            sb.append("\nGoal: ").append(goal);
        }
        sb.append("\n").append("done".equals(outcome)
                ? "Answer: " : "Outcome: ").append(outcomeLine);
        String banner = watchBanner();
        if (banner.length() > 0) {
            sb.append('\n').append(banner);
        }
        if (sourceUrl.length() > 0) {
            sb.append("\nSource: ");
            if (sourceTitle.length() > 0) {
                sb.append(sourceTitle).append(" (");
                sb.append(sourceUrl).append(')');
            } else {
                sb.append(sourceUrl);
            }
        }
        if (hasStats) {
            sb.append("\nRun: ").append(steps).append(" steps, ")
                    .append(seconds).append(" s, ")
                    .append(reusePercent)
                    .append("% of prompt reused");
        }
        if (findings.length() > 0) {
            sb.append("\nWhat it found (excerpt from the source "
                    + "page):\n").append(findings);
        }
        if (!facts.isEmpty()) {
            sb.append("\nFacts noted:");
            for (int i = 0; i < facts.size(); i++) {
                sb.append('\n').append(facts.get(i));
            }
        }
        return sb.toString();
    }

    /** The finish notification's one line: the outcome line
     *  itself, truncated to notification length — the answer (or
     *  the plain-words reason) is what the user glances for; the
     *  card carries the rest. */
    public String notificationLine() {
        String line = outcomeLine;
        if (line.length() > 90) {
            line = line.substring(0, 89) + "…";
        }
        return line;
    }

    // ---------------- small helpers ----------------

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : 0;
    }
}
