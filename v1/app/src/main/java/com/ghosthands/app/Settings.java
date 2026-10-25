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

    /** Every key the model reads/writes (the Activity's bridge
     *  walks this list, so a key cannot be forgotten on either
     *  side of the bridge). */
    public static final String[] ALL_KEYS = {
            KEY_MODEL_ID, KEY_GRAMMAR_ON, KEY_DEFAULT_PACK,
            KEY_MAX_STEPS, KEY_COPILOT, KEY_AUTO_READ,
            KEY_CHUNK_PREF, KEY_SETTLE, KEY_VOICE,
            KEY_SPEAK_FINISH, KEY_BUZZ, KEY_SHOTS_KEPT,
            KEY_HOME_PAGE,
    };

    // ---- choices ----------------------------------------------
    public static final int[] MAX_STEPS_CHOICES = {10, 25, 40};
    public static final int[] SHOTS_CHOICES = {10, 20, 50};

    /** Read-chunk preference (setting g): Auto is the v2.9
     *  adaptive behaviour; Large always serves the standing
     *  chunk; Small always serves the dense-page chunk. */
    public static final int CHUNK_AUTO = 0;
    public static final int CHUNK_LARGE = 1;
    public static final int CHUNK_SMALL = 2;

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
        return m;
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
