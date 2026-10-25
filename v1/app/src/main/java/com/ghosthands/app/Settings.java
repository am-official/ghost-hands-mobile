package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v3.4 — the settings model: every user setting the app has,
 * in one pure class, over one string map (the Activity's
 * SharedPreferences, bridged in and out verbatim).
 *
 * THE ONE-CONTROL RULE. This app's family has been bitten by
 * a duplicated setting before (two controls, one key, the
 * second silently erasing the first). The defence is
 * structural: there is exactly one model (this class), exactly
 * one control per setting (in the settings accordion), and one
 * persistence path (fromMap / toMap). The BRAIN chips and the
 * grammar switch moved INTO the accordion in this release;
 * they read and write the same keys they always did —
 * "model_id" (v2.6) and "grammar_on" (v2.6.1) — through this
 * model, so an existing install's values survive the move
 * byte-for-byte: the model resolves them with the very
 * resolvers that always resolved them (ModelStore.resolveId,
 * ModelStore.resolveGrammarOn).
 *
 * Every field is resolved at load: unknown pack names fall
 * back to "standard", step/retention counts snap to the
 * nearest offered choice, unknown chunk preferences fall back
 * to Auto, and booleans parse leniently ("on"/"true"/"1").
 * toMap writes the canonical forms back, so a load-save round
 * trip is a fixed point — the harness pins it.
 *
 * Pure: no Android. The Activity owns the SharedPreferences
 * bridge and the accordion views; the loop receives the three
 * settings it obeys (max steps, co-pilot gate, auto-read gate,
 * chunk preference) as plain constructor values, never as this
 * object, so a run's rules cannot change mid-run.
 *
 * v3.6 adds seven more on the same discipline — CPU threads,
 * fallback search engine, desktop mode, needs-me notifications,
 * trail retention, live stats, prefix cache — each with a
 * default that reproduces the behaviour the app already had
 * (trail retention is the one honest exception: trails were
 * unbounded, so its default of 25 is a new cap, disclosed in
 * TRAILS_DEFAULT's doc and the release notes, not a preserved
 * behaviour). The DATA section's two clear buttons are actions,
 * not preferences: they deliberately have no keys here.
 *
 * v3.7 adds nine more on the same discipline — a whole
 * APPEARANCE section (accent color, feed text size, reduce
 * motion), auto-scroll and the end-of-run view (AGENT), speech
 * rate (VOICE & SPEECH), and step screenshots (DIAGNOSTICS) —
 * plus two DATA actions with no keys here (the model manager's
 * deletes and the browsing-data clear; BrowsingData carries
 * the latter's scope). Every default reproduces v3.6
 * behaviour: cyan is the accent the skin always had (the
 * resolver returns its packed values byte-for-byte), Comfortable
 * is the feed sizes the app always used, Card is the view a
 * finished run always landed on, 1.0 is the rate speech always
 * used, and the rest are the standing on/off states.
 *
 * v3.8 adds seven more on the same discipline — keep-screen-
 * awake and the low-battery start warning (AGENT), the finish
 * beep and watch quiet hours with its two window hours
 * (ALERTS), and button haptics (APPEARANCE) — plus three
 * DIAGNOSTICS entries that are actions and a readout, not
 * preferences (the engine self-test, Copy debug info, and the
 * lifetime stats; they have no keys here, like the clears).
 * One default breaks the preserve-rule DELIBERATELY: the
 * low-battery warning defaults ON (see BATTERY_WARN's doc) —
 * a new protective default the user was told about before the
 * build. Every other default reproduces v3.7 behaviour.
 *
 * v3.10 (Everything Train R2) adds one more on the same
 * discipline — the planner style (AGENT): Balanced / Careful
 * / Fast / Thorough. Balanced is today's posture byte-for-byte
 * (the loop's prompts for a Balanced run are v3.9's), so the
 * default again preserves behaviour exactly; the other three
 * are real postures with real loop consequences, defined in
 * Agent (the posture text, the Careful verify-read guard, the
 * Fast chunk precedence, the Thorough cross-check guard) and
 * recorded on the run record as "style".
 *
 * v3.14 (Everything Train R6) adds one more — the
 * accessibility spike's experimental toggle (DIAGNOSTICS),
 * default OFF. It is the spike's in-app gate only: the
 * capability it arms also needs the accessibility service the
 * user alone enables in Android Settings (SpikeGate), and no
 * run rule reads it.
 */
public final class Settings {

    // ---- preference keys -------------------------------------
    // model_id and grammar_on predate this class (v2.6 /
    // v2.6.1) and are NOT renamed: an upgrade's stored brain
    // and grammar choices must read identically through the
    // new layer.
    public static final String KEY_MODEL_ID = "model_id";
    public static final String KEY_GRAMMAR_ON = "grammar_on";
    public static final String KEY_DEFAULT_PACK = "default_pack";
    public static final String KEY_MAX_STEPS = "max_steps";
    public static final String KEY_COPILOT = "copilot_on";
    public static final String KEY_AUTO_READ = "auto_read";
    public static final String KEY_CHUNK_PREF = "read_chunk";
    public static final String KEY_SETTLE = "density_settle";
    public static final String KEY_VOICE = "voice_goals";
    public static final String KEY_SPEAK_FINISH = "speak_on_finish";
    public static final String KEY_BUZZ = "finish_buzz";
    public static final String KEY_SHOTS_KEPT = "shots_kept";
    public static final String KEY_HOME_PAGE = "home_page";
    // v3.6 keys.
    public static final String KEY_THREADS = "cpu_threads";
    public static final String KEY_FALLBACK_ENGINE = "fallback_engine";
    public static final String KEY_DESKTOP_MODE = "desktop_mode";
    public static final String KEY_NEEDS_ME = "needs_me";
    public static final String KEY_TRAILS_KEPT = "trails_kept";
    public static final String KEY_LIVE_STATS = "live_stats";
    public static final String KEY_PREFIX_CACHE = "prefix_cache";
    // v3.7 keys.
    public static final String KEY_ACCENT = "accent_color";
    public static final String KEY_FEED_TEXT = "feed_text_size";
    public static final String KEY_REDUCE_MOTION = "reduce_motion";
    public static final String KEY_AUTO_SCROLL = "auto_scroll";
    public static final String KEY_END_VIEW = "end_view";
    public static final String KEY_SPEECH_RATE = "speech_rate";
    public static final String KEY_STEP_SHOTS = "step_screenshots";
    // v3.8 keys.
    public static final String KEY_SCREEN_AWAKE = "screen_awake";
    public static final String KEY_BATTERY_WARN = "battery_warn";
    public static final String KEY_FINISH_BEEP = "finish_beep";
    public static final String KEY_QUIET_HOURS = "quiet_hours";
    public static final String KEY_QUIET_START = "quiet_start";
    public static final String KEY_QUIET_END = "quiet_end";
    public static final String KEY_BUTTON_HAPTICS = "button_haptics";
    // v3.10 key (Everything Train R2 — planner styles).
    public static final String KEY_PLANNER_STYLE = "planner_style";
    // v3.14 key (Everything Train R6 — the accessibility spike).
    public static final String KEY_A11Y_SPIKE = "a11y_spike";

    /** Every key the model reads/writes (the Activity's bridge
     *  walks this list, so a key cannot be forgotten on either
     *  side of the bridge). */
    public static final String[] ALL_KEYS = {
            KEY_MODEL_ID, KEY_GRAMMAR_ON, KEY_DEFAULT_PACK,
            KEY_MAX_STEPS, KEY_COPILOT, KEY_AUTO_READ,
            KEY_CHUNK_PREF, KEY_SETTLE, KEY_VOICE,
            KEY_SPEAK_FINISH, KEY_BUZZ, KEY_SHOTS_KEPT,
            KEY_HOME_PAGE,
            KEY_THREADS, KEY_FALLBACK_ENGINE, KEY_DESKTOP_MODE,
            KEY_NEEDS_ME, KEY_TRAILS_KEPT, KEY_LIVE_STATS,
            KEY_PREFIX_CACHE,
            KEY_ACCENT, KEY_FEED_TEXT, KEY_REDUCE_MOTION,
            KEY_AUTO_SCROLL, KEY_END_VIEW, KEY_SPEECH_RATE,
            KEY_STEP_SHOTS,
            KEY_SCREEN_AWAKE, KEY_BATTERY_WARN, KEY_FINISH_BEEP,
            KEY_QUIET_HOURS, KEY_QUIET_START, KEY_QUIET_END,
            KEY_BUTTON_HAPTICS,
            KEY_PLANNER_STYLE,
            KEY_A11Y_SPIKE,
    };

    // ---- choices ----------------------------------------------
    public static final int[] MAX_STEPS_CHOICES = {10, 25, 40};
    public static final int[] SHOTS_CHOICES = {10, 20, 50};
    /** v3.6: CPU thread choices; 4 is the value every build
     *  through v3.5 hardcoded in the shim. */
    public static final int[] THREADS_CHOICES = {2, 4, 6};
    /** v3.6: trail retention choices. Trails were NEVER pruned
     *  before this setting existed (unbounded); 25 is the new
     *  default the setting introduces, disclosed as such — it is
     *  not a preserved behaviour, it is the cap arriving. */
    public static final int[] TRAILS_CHOICES = {10, 25, 50};
    public static final int TRAILS_DEFAULT = 25;

    /** Read-chunk preference (setting g): Auto is the v2.9
     *  adaptive behaviour; Large always serves the standing
     *  chunk; Small always serves the dense-page chunk. */
    public static final int CHUNK_AUTO = 0;
    public static final int CHUNK_LARGE = 1;
    public static final int CHUNK_SMALL = 2;

    // ---- v3.7 choices ------------------------------------------
    /** APPEARANCE: the accent hues, in chip order. Only the
     *  accent moves — the deep navy surfaces and the red
     *  reserved for STOP / stuck / denied never change. */
    public static final String ACCENT_CYAN = "cyan";
    public static final String ACCENT_VIOLET = "violet";
    public static final String ACCENT_EMERALD = "emerald";
    public static final String ACCENT_AMBER = "amber";
    public static final String[] ACCENT_IDS = {
            ACCENT_CYAN, ACCENT_VIOLET, ACCENT_EMERALD, ACCENT_AMBER};
    public static final String[] ACCENT_NAMES = {
            "Cyan", "Violet", "Emerald", "Amber"};

    /** APPEARANCE: feed text sizes. COMFORTABLE is the size set
     *  the app has always used — feedTextSize's sizes below
     *  return those exact values for it. */
    public static final int FEED_COMPACT = 0;
    public static final int FEED_COMFORTABLE = 1;
    public static final int FEED_LARGE = 2;

    /** AGENT: the feed view a finished run lands on. The values
     *  deliberately match the Activity's log modes (0 steps,
     *  1 trail, 2 card) — feedLogModeFor is the one sanctioned
     *  crossing between the two vocabularies. */
    public static final int END_STEPS = 0;
    public static final int END_TRAIL = 1;
    public static final int END_CARD = 2;

    /** VOICE & SPEECH: the Listen / speak-on-finish rate.
     *  NORMAL is 1.0 — the rate the framework always used. */
    public static final int RATE_SLOWER = 0;
    public static final int RATE_NORMAL = 1;
    public static final int RATE_FASTER = 2;

    // ---- v3.10 choices (Everything Train R2) ------------------
    /** AGENT: the planner style, in chip order. BALANCED is
     *  the posture every run through v3.9 used — its prompts
     *  are byte-identical to v3.9's (Agent.postureTextFor
     *  returns null for it, and neither of the style guards
     *  exists under it). The other three are defined in
     *  Agent: CAREFUL adds the verify-read done guard, FAST
     *  prefers search and serves the small read chunk unless
     *  the user set a chunk size, THOROUGH adds the cross-
     *  check done guard for comparison goals. */
    public static final int STYLE_BALANCED = 0;
    public static final int STYLE_CAREFUL = 1;
    public static final int STYLE_FAST = 2;
    public static final int STYLE_THOROUGH = 3;
    public static final String[] STYLE_IDS = {
            "balanced", "careful", "fast", "thorough"};
    public static final String[] STYLE_NAMES = {
            "Balanced", "Careful", "Fast", "Thorough"};

    // ---- the settings -----------------------------------------
    /** BRAIN: the active model (a ModelStore registry id,
     *  always resolved to a known one). */
    public String modelId = ModelStore.DEFAULT_ID;
    /** BRAIN: the grammar kill-switch. Default OFF, always —
     *  ModelStore.GRAMMAR_DEFAULT_ON, the stable setting. */
    public boolean grammarOn = ModelStore.GRAMMAR_DEFAULT_ON;
    /** AGENT: the pack a new run starts from (a PolicyPacks
     *  name; a loaded saved goal still brings its own). */
    public String defaultPackId = "standard";
    /** AGENT: the step budget for a run (one of
     *  MAX_STEPS_CHOICES). */
    public int maxSteps = Agent.MAX_STEPS;
    /** AGENT: the co-pilot rescue (v3.3) may be offered. Off
     *  removes a rescue only — no guard's math changes. */
    public boolean copilotOn = true;
    /** AGENT: the loop reads an arrived page once, on arrival
     *  (v1.3). Off removes a convenience only. */
    public boolean autoReadOn = true;
    /** READING: one of CHUNK_AUTO / CHUNK_LARGE / CHUNK_SMALL. */
    public int chunkPref = CHUNK_AUTO;
    /** READING: the density settle (v2.8) probes run before a
     *  read session's first extraction. */
    public boolean settleOn = true;
    /** VOICE & SPEECH: the goal field's mic chip exists. */
    public boolean voiceOn = true;
    /** VOICE & SPEECH: a finished answer card is spoken aloud
     *  (foreground finishes with a done answer only). */
    public boolean speakOnFinish = false;
    /** ALERTS: the finish buzz (v3.0) sounds. Watched-goal
     *  change alerts are NOT gated by this — see the settings
     *  copy; they are the watch feature's core signal. */
    public boolean buzzOn = true;
    /** DATA: screenshots kept (one of SHOTS_CHOICES); the
     *  number is handed to Shots' retention policy verbatim. */
    public int shotsKept = Shots.MAX_KEPT;
    /** DATA: the page loaded at app launch; "" loads nothing
     *  (the behaviour the app has always had). Stored in
     *  normalized form (normalizeHomePage). */
    public String homePage = "";
    /** BRAIN (v3.6): CPU threads for the model context — one of
     *  THREADS_CHOICES; the engine reads it at model load, so a
     *  change applies at the NEXT load. Default 4, the value the
     *  shim hardcoded through v3.5. */
    public int threads = ModelEngine.DEFAULT_THREADS;
    /** READING (v3.6): the fallback search engine (a
     *  SearchEngines id) the search action uses when a page has
     *  no search box of its own. Default DuckDuckGo — the
     *  hardcoded fallback through v3.5. */
    public String fallbackEngine = SearchEngines.DEFAULT_ID;
    /** READING (v3.6): ask sites for their desktop pages (a
     *  desktop user agent; the WebView's wide viewport has been
     *  on since v1). Applies to pages loaded after the change.
     *  Default OFF — the default mobile UA, as always. */
    public boolean desktopMode = false;
    /** ALERTS (v3.6): a run waiting on the user (an approval, a
     *  co-pilot rescue) while the app is in the background posts
     *  a notification. Default OFF — through v3.5 waits never
     *  notified. See NeedsMe for the decision rule. */
    public boolean needsMeOn = false;
    /** DATA (v3.6): run trails kept (one of TRAILS_CHOICES);
     *  pruning happens at the screenshot housekeeping point and
     *  never takes a watch baseline's trail or the open run's.
     *  Default TRAILS_DEFAULT — new in v3.6; trails were
     *  unbounded before. */
    public int trailsKept = TRAILS_DEFAULT;
    /** DIAGNOSTICS (v3.6): live generation speed and prompt-
     *  cache reuse ride the run's status line and its summary
     *  line. Default OFF — the status area and the summary read
     *  exactly as they did through v3.5. */
    public boolean liveStatsOn = false;
    /** DIAGNOSTICS (v3.6): the v1.5 native prefix cache. Default
     *  ON — the behaviour since v1.5. OFF makes every step pay
     *  full prompt evaluation (the pre-v1.5 behaviour), for A/B
     *  diagnosis; recorded on the run record either way. */
    public boolean prefixCacheOn = true;
    /** APPEARANCE (v3.7): the skin's accent hue (an ACCENT_*
     *  id). Default cyan — the accent the skin has always had.
     *  Read once when the app builds its views, so a change
     *  applies when the app restarts; the accordion copy says
     *  exactly that. */
    public String accentId = ACCENT_CYAN;
    /** APPEARANCE (v3.7): the feed text size (a FEED_* value) —
     *  the steps timeline's lines, the trail view, and the
     *  answer cards; NOT every label in the app. Default
     *  COMFORTABLE, the sizes the app has always used; read at
     *  view-build time like the accent. */
    public int feedTextSize = FEED_COMFORTABLE;
    /** APPEARANCE (v3.7): still the moving surfaces — the
     *  AGENT ACTIVE pill holds a steady glow instead of
     *  breathing, and the drawer snaps instead of sliding.
     *  Default OFF (motion, as always). Read at animation
     *  time, so it applies live. */
    public boolean reduceMotion = false;
    /** AGENT (v3.7): the feed follows new lines to the bottom.
     *  Default ON — the behaviour the feed has always had. OFF
     *  means a render never moves the user's scroll position,
     *  mid-run or at the end (the end-of-run view still
     *  switches; only the scroll is the user's). */
    public boolean autoScrollOn = true;
    /** AGENT (v3.7): the feed view a finished run lands on (an
     *  END_* value). Default Card — where a finished run has
     *  always landed. Recorded on the run record either way. */
    public int endView = END_CARD;
    /** VOICE & SPEECH (v3.7): the spoken rate (a RATE_* value).
     *  Default NORMAL — 1.0, the rate speech has always used.
     *  Applies to the next utterance. */
    public int speechRate = RATE_NORMAL;
    /** DIAGNOSTICS (v3.7): photograph the page at every step's
     *  end as well as the run's end (the v3.3 capture), into
     *  the same shots store under the same retention count.
     *  Default OFF — through v3.6 only the run's end was
     *  photographed. Recorded on the run record either way. */
    public boolean stepShotsOn = false;
    /** AGENT (v3.8): hold the screen awake while a run or
     *  replay is active (FLAG_KEEP_SCREEN_ON, set at the start
     *  and cleared at every ending). Default OFF — the screen
     *  has always slept by its own timeout. Applies live to the
     *  next run; recorded on the run record either way. */
    public boolean screenAwakeOn = false;
    /** AGENT (v3.8): warn before a user-initiated start when
     *  the battery is under BatteryGuard.WARN_BELOW_PERCENT
     *  and not charging. Default ON — the deliberate exception
     *  to this class's preserve-rule: the guard is NEW
     *  protection (the user's battery died under runs twice
     *  before it existed), the user was told the default would
     *  be on, and the accordion copy notes it can be turned
     *  off. The warning only ever asks — BatteryGuard never
     *  hard-blocks. Recorded on the run record either way. */
    public boolean batteryWarnOn = true;
    /** ALERTS (v3.8): one short beep when a run ends (the
     *  FinishBeep decision: never for a watch NO_CHANGE, never
     *  for a quiet-hours-deferred change). Default OFF —
     *  finishes were silent apart from the buzz through v3.7. */
    public boolean finishBeepOn = false;
    /** ALERTS (v3.8): quiet hours for watched-goal changes —
     *  a CHANGED verdict inside the window records its verdict
     *  but raises no notification and no buzz; the banner on
     *  the run's card notes the quiet arrival, and nothing is
     *  sent when the window ends. Default OFF. The window is
     *  [quietStartHour, quietEndHour), 0-23, crossing midnight
     *  when start > end (QuietHours). */
    public boolean quietHoursOn = false;
    /** Default 23 (11 PM) — the hour the quiet window opens. */
    public int quietStartHour = 23;
    /** Default 7 (7 AM) — the hour the quiet window closes. */
    public int quietEndHour = 7;
    /** APPEARANCE (v3.8): primary action buttons give one light
     *  haptic tick (Haptics). Default OFF — through v3.7 no
     *  button ticked (the v2.6 Start/Stop acknowledgement is
     *  run machinery, not button furniture, and is NOT gated
     *  by this). Read at tap time, so it applies live. */
    public boolean hapticsOn = false;
    /** AGENT (v3.10, Everything Train R2): the planner style
     *  (a STYLE_* value). Default BALANCED — the posture every
     *  run through v3.9 used, byte-for-byte. Captured at Start
     *  like every run rule and recorded on the run record as
     *  "style". */
    public int plannerStyle = STYLE_BALANCED;

    /** DIAGNOSTICS (v3.14, Everything Train R6): the
     *  accessibility spike's experimental toggle. Default
     *  OFF — and off alone does not describe its power: the
     *  toggle is only ONE of the spike's two gates (SpikeGate).
     *  The other is the accessibility service itself, which
     *  only the user can enable, in Android Settings; the app
     *  cannot switch it on and this model cannot either. With
     *  either gate closed the probe code paths do not run.
     *  Nothing in any run reads this field: the browser agent
     *  is untouched by the spike's existence. */
    public boolean a11ySpikeOn = false;

    public Settings() { }

    // ---- persistence ------------------------------------------

    /** Resolve a whole settings object from persisted strings.
     *  Absent keys mean defaults; present-but-strange values
     *  resolve per field (see the class doc). */
    public static Settings fromMap(Map<String, String> m) {
        Settings s = new Settings();
        if (m == null) return s;
        s.modelId = ModelStore.resolveId(m.get(KEY_MODEL_ID));
        s.grammarOn = ModelStore.resolveGrammarOn(m.get(KEY_GRAMMAR_ON));
        s.defaultPackId = resolvePack(m.get(KEY_DEFAULT_PACK));
        s.maxSteps = snap(parseInt(m.get(KEY_MAX_STEPS), Agent.MAX_STEPS),
                MAX_STEPS_CHOICES, Agent.MAX_STEPS);
        s.copilotOn = parseOnOff(m.get(KEY_COPILOT), true);
        s.autoReadOn = parseOnOff(m.get(KEY_AUTO_READ), true);
        s.chunkPref = parseChunkPref(m.get(KEY_CHUNK_PREF));
        s.settleOn = parseOnOff(m.get(KEY_SETTLE), true);
        s.voiceOn = parseOnOff(m.get(KEY_VOICE), true);
        s.speakOnFinish = parseOnOff(m.get(KEY_SPEAK_FINISH), false);
        s.buzzOn = parseOnOff(m.get(KEY_BUZZ), true);
        s.shotsKept = snap(parseInt(m.get(KEY_SHOTS_KEPT), Shots.MAX_KEPT),
                SHOTS_CHOICES, Shots.MAX_KEPT);
        String hp = normalizeHomePage(m.get(KEY_HOME_PAGE));
        s.homePage = (hp == null) ? "" : hp;
        s.threads = snap(parseInt(m.get(KEY_THREADS),
                        ModelEngine.DEFAULT_THREADS),
                THREADS_CHOICES, ModelEngine.DEFAULT_THREADS);
        s.fallbackEngine = SearchEngines.resolveId(
                m.get(KEY_FALLBACK_ENGINE));
        s.desktopMode = parseOnOff(m.get(KEY_DESKTOP_MODE), false);
        s.needsMeOn = parseOnOff(m.get(KEY_NEEDS_ME), false);
        s.trailsKept = snap(parseInt(m.get(KEY_TRAILS_KEPT),
                        TRAILS_DEFAULT),
                TRAILS_CHOICES, TRAILS_DEFAULT);
        s.liveStatsOn = parseOnOff(m.get(KEY_LIVE_STATS), false);
        s.prefixCacheOn = parseOnOff(m.get(KEY_PREFIX_CACHE), true);
        s.accentId = resolveAccent(m.get(KEY_ACCENT));
        s.feedTextSize = parseFeedTextSize(m.get(KEY_FEED_TEXT));
        s.reduceMotion = parseOnOff(m.get(KEY_REDUCE_MOTION), false);
        s.autoScrollOn = parseOnOff(m.get(KEY_AUTO_SCROLL), true);
        s.endView = parseEndView(m.get(KEY_END_VIEW));
        s.speechRate = parseSpeechRate(m.get(KEY_SPEECH_RATE));
        s.stepShotsOn = parseOnOff(m.get(KEY_STEP_SHOTS), false);
        s.screenAwakeOn = parseOnOff(m.get(KEY_SCREEN_AWAKE), false);
        s.batteryWarnOn = parseOnOff(m.get(KEY_BATTERY_WARN), true);
        s.finishBeepOn = parseOnOff(m.get(KEY_FINISH_BEEP), false);
        s.quietHoursOn = parseOnOff(m.get(KEY_QUIET_HOURS), false);
        s.quietStartHour = clampHour(parseInt(
                m.get(KEY_QUIET_START), 23));
        s.quietEndHour = clampHour(parseInt(
                m.get(KEY_QUIET_END), 7));
        s.hapticsOn = parseOnOff(m.get(KEY_BUTTON_HAPTICS), false);
        s.plannerStyle = parsePlannerStyle(m.get(KEY_PLANNER_STYLE));
        s.a11ySpikeOn = parseOnOff(m.get(KEY_A11Y_SPIKE), false);
        return s;
    }

    /** The canonical persisted form of this object — the same
     *  keys fromMap reads, the legacy two in their legacy
     *  encodings (the model id verbatim; the grammar switch in
     *  ModelStore.grammarPrefValue's "on"/"off"). */
    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put(KEY_MODEL_ID, modelId);
        m.put(KEY_GRAMMAR_ON, ModelStore.grammarPrefValue(grammarOn));
        m.put(KEY_DEFAULT_PACK, defaultPackId);
        m.put(KEY_MAX_STEPS, String.valueOf(maxSteps));
        m.put(KEY_COPILOT, onOff(copilotOn));
        m.put(KEY_AUTO_READ, onOff(autoReadOn));
        m.put(KEY_CHUNK_PREF, chunkPrefName(chunkPref));
        m.put(KEY_SETTLE, onOff(settleOn));
        m.put(KEY_VOICE, onOff(voiceOn));
        m.put(KEY_SPEAK_FINISH, onOff(speakOnFinish));
        m.put(KEY_BUZZ, onOff(buzzOn));
        m.put(KEY_SHOTS_KEPT, String.valueOf(shotsKept));
        m.put(KEY_HOME_PAGE, homePage);
        m.put(KEY_THREADS, String.valueOf(threads));
        m.put(KEY_FALLBACK_ENGINE, fallbackEngine);
        m.put(KEY_DESKTOP_MODE, onOff(desktopMode));
        m.put(KEY_NEEDS_ME, onOff(needsMeOn));
        m.put(KEY_TRAILS_KEPT, String.valueOf(trailsKept));
        m.put(KEY_LIVE_STATS, onOff(liveStatsOn));
        m.put(KEY_PREFIX_CACHE, onOff(prefixCacheOn));
        m.put(KEY_ACCENT, accentId);
        m.put(KEY_FEED_TEXT, feedTextSizeName(feedTextSize));
        m.put(KEY_REDUCE_MOTION, onOff(reduceMotion));
        m.put(KEY_AUTO_SCROLL, onOff(autoScrollOn));
        m.put(KEY_END_VIEW, endViewName(endView));
        m.put(KEY_SPEECH_RATE, speechRateName(speechRate));
        m.put(KEY_STEP_SHOTS, onOff(stepShotsOn));
        m.put(KEY_SCREEN_AWAKE, onOff(screenAwakeOn));
        m.put(KEY_BATTERY_WARN, onOff(batteryWarnOn));
        m.put(KEY_FINISH_BEEP, onOff(finishBeepOn));
        m.put(KEY_QUIET_HOURS, onOff(quietHoursOn));
        m.put(KEY_QUIET_START, String.valueOf(quietStartHour));
        m.put(KEY_QUIET_END, String.valueOf(quietEndHour));
        m.put(KEY_BUTTON_HAPTICS, onOff(hapticsOn));
        m.put(KEY_PLANNER_STYLE, styleName(plannerStyle));
        m.put(KEY_A11Y_SPIKE, onOff(a11ySpikeOn));
        return m;
    }

    // ---- v3.10 helpers (Everything Train R2) -------------------

    /** The planner style's persisted name. */
    public static String styleName(int style) {
        int s = clampPlannerStyle(style);
        return STYLE_IDS[s];
    }

    /** Parse a persisted planner style; anything unknown is
     *  Balanced (the standing posture — a strange value must
     *  never invent a posture the user did not pick). */
    public static int parsePlannerStyle(String v) {
        if (v != null) {
            String s = v.trim().toLowerCase(java.util.Locale.US);
            for (int i = 0; i < STYLE_IDS.length; i++) {
                if (STYLE_IDS[i].equals(s)) return i;
            }
        }
        return STYLE_BALANCED;
    }

    /** A planner style value that exists, or Balanced — the
     *  clamp the Agent constructor applies to whatever it is
     *  handed, so a run record can never name a style the
     *  loop does not know. */
    public static int clampPlannerStyle(int style) {
        return (style >= STYLE_BALANCED && style <= STYLE_THOROUGH)
                ? style : STYLE_BALANCED;
    }

    // ---- v3.8 helpers ------------------------------------------

    /** An hour-of-day clamped into 0-23: a hand-edited or
     *  future quiet-hours value lands on the closest real hour,
     *  so the model and the chips can never disagree (the same
     *  clamp QuietHours applies inside its window math). */
    public static int clampHour(int h) {
        return QuietHours.clampHour(h);
    }

    /** A quiet-hours chip's label: the hour in 12-hour form
     *  ("11 PM", "12 AM", "7 AM"). */
    public static String hourLabel(int h) {
        int c = clampHour(h);
        if (c == 0) return "12 AM";
        if (c < 12) return c + " AM";
        if (c == 12) return "12 PM";
        return (c - 12) + " PM";
    }

    // ---- v3.6: desktop mode -----------------------------------

    /** The user agent desktop mode asks sites with: a plain
     *  desktop Chrome string (Linux desktop — the honest shape
     *  for "give me the desktop page"; the app makes no claim
     *  about the device beyond it). */
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** The user agent the WebView should carry: the desktop
     *  string when desktop mode is on, the WebView's own default
     *  (captured before any change) when it is off — off is the
     *  behaviour the app has always had, byte-for-byte. */
    public static String userAgentFor(boolean desktopOn,
                                      String defaultUa) {
        return desktopOn ? DESKTOP_USER_AGENT : defaultUa;
    }

    // ---- v3.7: appearance + the third wave's resolvers --------

    /** An accent id that exists, or cyan. Case-insensitive,
     *  trimmed; the canonical (lowercase) id survives. */
    public static String resolveAccent(String saved) {
        if (saved != null) {
            String key = saved.trim().toLowerCase(java.util.Locale.US);
            for (int i = 0; i < ACCENT_IDS.length; i++) {
                if (ACCENT_IDS[i].equals(key)) return key;
            }
        }
        return ACCENT_CYAN;
    }

    /** An accent id's chip label. */
    public static String accentName(String id) {
        String r = resolveAccent(id);
        for (int i = 0; i < ACCENT_IDS.length; i++) {
            if (ACCENT_IDS[i].equals(r)) return ACCENT_NAMES[i];
        }
        return ACCENT_NAMES[0];
    }

    /**
     * The accent color itself, as a packed ARGB int (the pure
     * twin of android.graphics.Color.rgb — this class carries
     * no Android, so the packing is done by hand and the
     * harness pins it). CYAN IS THE SKIN'S OWN ACCENT: it
     * returns Color.rgb(56, 198, 255) — #38C6FF — byte-for-byte,
     * so the default changes nothing anywhere. The other hues
     * sit in the same register (bright on deep navy); the navy
     * surfaces and the red are not the accent's to move.
     */
    public static int accentColor(String id) {
        String r = resolveAccent(id);
        if (ACCENT_VIOLET.equals(r)) return argb(167, 139, 250);
        if (ACCENT_EMERALD.equals(r)) return argb(52, 211, 153);
        if (ACCENT_AMBER.equals(r)) return argb(255, 176, 32);
        return argb(56, 198, 255);
    }

    /** The accent's pale tint — the trail view's text color,
     *  a washed-out sibling of the accent. Cyan's tint is the
     *  value the trail view has always used, rgb(159, 216,
     *  242), byte-for-byte. */
    public static int accentTintColor(String id) {
        String r = resolveAccent(id);
        if (ACCENT_VIOLET.equals(r)) return argb(196, 181, 253);
        if (ACCENT_EMERALD.equals(r)) return argb(167, 243, 208);
        if (ACCENT_AMBER.equals(r)) return argb(253, 230, 138);
        return argb(159, 216, 242);
    }

    private static int argb(int r, int g, int b) {
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    /** The feed-text preference's persisted name. */
    public static String feedTextSizeName(int pref) {
        if (pref == FEED_COMPACT) return "compact";
        if (pref == FEED_LARGE) return "large";
        return "comfortable";
    }

    /** Parse a persisted feed-text preference; anything
     *  unknown is Comfortable (the standing sizes). */
    public static int parseFeedTextSize(String v) {
        if (v != null) {
            String s = v.trim().toLowerCase(java.util.Locale.US);
            if ("compact".equals(s)) return FEED_COMPACT;
            if ("large".equals(s)) return FEED_LARGE;
            if ("comfortable".equals(s)) return FEED_COMFORTABLE;
        }
        return FEED_COMFORTABLE;
    }

    /** The steps timeline's main line size (sp) for a feed-text
     *  preference. COMFORTABLE is 12.5 — the size the timeline
     *  has always used, byte-for-byte. */
    public static float feedMainSp(int pref) {
        if (pref == FEED_COMPACT) return 11.5f;
        if (pref == FEED_LARGE) return 14f;
        return 12.5f;
    }

    /** The timeline rows' sub-line size (sp). COMFORTABLE is
     *  11 — the standing value. */
    public static float feedSubSp(int pref) {
        if (pref == FEED_COMPACT) return 10f;
        if (pref == FEED_LARGE) return 12.5f;
        return 11f;
    }

    /** The trail (raw JSON) view's size (sp). COMFORTABLE is
     *  11 — the standing value. */
    public static float trailTextSp(int pref) {
        if (pref == FEED_COMPACT) return 10f;
        if (pref == FEED_LARGE) return 12.5f;
        return 11f;
    }

    /** The answer cards' text size (sp) — the Agent screen's
     *  card and History's alike. COMFORTABLE is 13 — the
     *  standing value. */
    public static float cardTextSp(int pref) {
        if (pref == FEED_COMPACT) return 12f;
        if (pref == FEED_LARGE) return 15f;
        return 13f;
    }

    /** AGENT (v3.7): may a feed render move the scroll to the
     *  newest line? The whole decision, factored pure so the
     *  harness pins it: the feed follows new lines exactly
     *  when auto-scroll is on; off, no render — mid-run or
     *  end-of-run — ever moves the user's scroll position. */
    public static boolean feedScrollFollows(boolean autoScrollOn) {
        return autoScrollOn;
    }

    /** The end-of-run view's persisted name. */
    public static String endViewName(int view) {
        if (view == END_STEPS) return "steps";
        if (view == END_TRAIL) return "trail";
        return "card";
    }

    /** Parse a persisted end-of-run view; anything unknown is
     *  Card (where a finished run has always landed). */
    public static int parseEndView(String v) {
        if (v != null) {
            String s = v.trim().toLowerCase(java.util.Locale.US);
            if ("steps".equals(s)) return END_STEPS;
            if ("trail".equals(s)) return END_TRAIL;
            if ("card".equals(s)) return END_CARD;
        }
        return END_CARD;
    }

    /** The Activity log mode a finished run lands on for an
     *  end-of-run view (0 steps / 1 trail / 2 card — the END_*
     *  values were chosen to match; an out-of-range value
     *  lands on the card, the standing landing). */
    public static int feedLogModeFor(int endView) {
        if (endView == END_STEPS) return 0;
        if (endView == END_TRAIL) return 1;
        return 2;
    }

    /** The speech rate's persisted name. */
    public static String speechRateName(int rate) {
        if (rate == RATE_SLOWER) return "slower";
        if (rate == RATE_FASTER) return "faster";
        return "normal";
    }

    /** Parse a persisted speech rate; anything unknown is
     *  Normal. */
    public static int parseSpeechRate(String v) {
        if (v != null) {
            String s = v.trim().toLowerCase(java.util.Locale.US);
            if ("slower".equals(s)) return RATE_SLOWER;
            if ("faster".equals(s)) return RATE_FASTER;
            if ("normal".equals(s)) return RATE_NORMAL;
        }
        return RATE_NORMAL;
    }

    /** The TextToSpeech rate multiplier for a RATE_* value:
     *  0.85 / 1.0 / 1.15. NORMAL is exactly 1.0 — the rate
     *  every utterance used through v3.6. */
    public static float speechRateValue(int rate) {
        if (rate == RATE_SLOWER) return 0.85f;
        if (rate == RATE_FASTER) return 1.15f;
        return 1.0f;
    }

    // ---- field helpers ------------------------------------------

    /** A pack name that exists, or "standard". Case-insensitive;
     *  the canonical (lowercase) name is what survives. */
    public static String resolvePack(String saved) {
        if (saved != null) {
            String key = saved.trim().toLowerCase(java.util.Locale.US);
            for (int i = 0; i < PolicyPacks.NAMES.length; i++) {
                if (PolicyPacks.NAMES[i].equals(key)) return key;
            }
        }
        return "standard";
    }

    /** Lenient boolean: "on"/"true"/"1" (any case, trimmed) is
     *  true, "off"/"false"/"0" is false, anything else (or
     *  nothing) is the default. */
    public static boolean parseOnOff(String v, boolean dflt) {
        if (v == null) return dflt;
        String s = v.trim().toLowerCase(java.util.Locale.US);
        if ("on".equals(s) || "true".equals(s) || "1".equals(s)) {
            return true;
        }
        if ("off".equals(s) || "false".equals(s) || "0".equals(s)) {
            return false;
        }
        return dflt;
    }

    /** The persisted form of a boolean setting ("on"/"off" —
     *  the grammar switch's own encoding, generalized). */
    public static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    private static int parseInt(String v, int dflt) {
        if (v == null) return dflt;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /** Snap a number to the nearest offered choice (ties go to
     *  the smaller choice); an empty choice list yields the
     *  default. This is the clamp: a hand-edited or future
     *  value lands on the closest thing the UI can show, so
     *  model and control can never disagree about the value. */
    public static int snap(int v, int[] choices, int dflt) {
        if (choices == null || choices.length == 0) return dflt;
        int best = choices[0];
        int bestDiff = Math.abs(v - best);
        for (int i = 1; i < choices.length; i++) {
            int d = Math.abs(v - choices[i]);
            if (d < bestDiff) {
                best = choices[i];
                bestDiff = d;
            }
        }
        return best;
    }

    /** The chunk preference's persisted name. */
    public static String chunkPrefName(int pref) {
        if (pref == CHUNK_LARGE) return "large";
        if (pref == CHUNK_SMALL) return "small";
        return "auto";
    }

    /** Parse a persisted chunk preference; anything unknown is
     *  Auto (the adaptive behaviour is the safe default). */
    public static int parseChunkPref(String v) {
        if (v != null) {
            String s = v.trim().toLowerCase(java.util.Locale.US);
            if ("large".equals(s)) return CHUNK_LARGE;
            if ("small".equals(s)) return CHUNK_SMALL;
            if ("auto".equals(s)) return CHUNK_AUTO;
        }
        return CHUNK_AUTO;
    }

    /** The read chunk a preference serves for a perception of
     *  the given weight — the loop's one consultation of the
     *  setting (v2.9's adaptive choice, forced either way on
     *  request). Large is always Eyes.READ_CHUNK_CHARS, Small
     *  is always Agent.DENSE_READ_CHUNK_CHARS, Auto is
     *  Agent.readChunkChars' density judgement, unchanged. */
    public static int readChunkChars(int chunkPref, int weightEstTokens) {
        if (chunkPref == CHUNK_LARGE) return Eyes.READ_CHUNK_CHARS;
        if (chunkPref == CHUNK_SMALL) return Agent.DENSE_READ_CHUNK_CHARS;
        return Agent.readChunkChars(weightEstTokens);
    }

    /**
     * Normalize a home-page value the user typed: "" (or all
     * whitespace) stays "" — no home page, the app loads
     * nothing at launch, as it always has. A bare host gets
     * "https://" (the address bar's own rule). Anything with
     * whitespace inside, an empty host, or a host with no dot,
     * no port and no "localhost" name is not a web address the
     * app should load blind — null, and the caller keeps the
     * old value and says so.
     */
    public static String normalizeHomePage(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return null;
        }
        String url = (s.indexOf("://") >= 0) ? s : "https://" + s;
        String rest = url.substring(url.indexOf("://") + 3);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String host = rest.substring(0, cut);
        if (host.length() == 0) return null;
        if (host.indexOf('.') < 0 && host.indexOf(':') < 0
                && !"localhost".equals(host)) {
            return null;
        }
        return url;
    }
}
