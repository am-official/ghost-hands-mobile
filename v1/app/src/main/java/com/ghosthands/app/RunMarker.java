package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The unclean-shutdown marker (v2.6) — and, since v3.13
 * (Everything Train R5), the resume offer's evidence.
 *
 * At run start the app writes one of these to a small file in
 * its private storage; every normal stop path deletes it. If
 * the file is still there at the next launch, the previous run
 * never reached a stop — the app or the phone died mid-run —
 * and the launch surfaces that fact instead of letting it
 * vanish.
 *
 * The marker PROVES an ending was abrupt; it can never say why.
 * Field context: Ryan's "stopped twice and closed out the app
 * on me" during the v2.4 weather runs had three live suspects
 * (new native grammar code, the bigger Q5 model, a phone
 * browning out at 12-15% battery) and no evidence either way.
 * The class began as a diagnostic instrument, not a fix.
 *
 * v3.13 (R5 — resume interrupted runs): a marker written for a
 * plain manual agent run also carries the run's TRAIL NAME and
 * the CAPTURED RUN RULES (a string map in the Settings model's
 * own encodings: the effective pack, the grammar switch, the
 * step budget, the style, and the rest of the values the Start
 * captured). With those, a launch can offer to RESUME: a new
 * run of the same goal under the marker's own rules, its run
 * record stamped "resumed_from" with the interrupted trail's
 * name. The interrupted trail itself is never opened for
 * writing — it ends without a terminal event, and that absence
 * IS its record.
 *
 * Not every marker is resumable, by design:
 *  - route-replay markers carry no trail and no rules (a
 *    replay is the user's own steps; there is no goal run to
 *    resume);
 *  - scheduled-check and watched-goal runs write the plain
 *    diagnostic marker too — those runs are readonly and
 *    repeat by design, so they never leave a RESUMABLE marker;
 *  - markers written before v3.13 have neither field and read
 *    exactly as they always did (the abrupt line, then clear).
 *
 * The launch decision is pure (launchVerdict): a marker whose
 * trail already carries a terminal "stop" event is stale (the
 * run DID finish; the delete was what died) and clears
 * silently; a marker older than MAX_AGE_MS is auto-dismissed
 * silently; a resumable marker inside its age offers the
 * resume; anything else is the v2.6 diagnostic line. Belt and
 * braces, both stale rules first.
 *
 * Pure and host-tested: serialization is one JSON object
 * written with the app's own Json writer, parsing goes through
 * the app's Json parser and refuses anything malformed (a
 * corrupt marker reads as no marker, never as a crash).
 */
public class RunMarker {

    /** A marker older than this is auto-dismissed at launch —
     *  a week-old interruption is history, not an offer. */
    public static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

    // launchVerdict outcomes.
    public static final int VERDICT_CLEAR = 0;
    public static final int VERDICT_LEGACY = 1;
    public static final int VERDICT_OFFER = 2;

    /** The rules-map key for the run's effective pack (the one
     *  value with no Settings key of its own: a run's pack can
     *  come from the goal card, not just the default setting). */
    public static final String RULE_PACK = "pack";

    public final String goal;
    public final long startedAtMs;
    public final String modelId;
    /** The interrupted run's trail file NAME (not a path), or
     *  "" when this marker carries none. */
    public final String trailName;
    /** The captured run rules (Settings-keyed strings), or an
     *  empty map when this marker carries none. */
    public final Map<String, String> rules;

    public RunMarker(String goal, long startedAtMs, String modelId) {
        this(goal, startedAtMs, modelId, "", null);
    }

    public RunMarker(String goal, long startedAtMs, String modelId,
                     String trailName, Map<String, String> rules) {
        this.goal = goal == null ? "" : goal;
        this.startedAtMs = startedAtMs;
        this.modelId = modelId == null ? "" : modelId;
        this.trailName = trailName == null ? "" : trailName;
        this.rules = new LinkedHashMap<String, String>();
        if (rules != null) {
            for (Map.Entry<String, String> e : rules.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    this.rules.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /** True when this marker can power a resume offer: it names
     *  its trail and carries the captured rules. Route markers
     *  and pre-v3.13 markers never qualify. */
    public boolean resumable() {
        return trailName.length() > 0 && !rules.isEmpty();
    }

    /** One captured rule value, or null when the marker does
     *  not carry it (the caller then falls back to the standing
     *  default for that rule — a partial marker still resumes). */
    public String rule(String key) {
        return rules.get(key);
    }

    public String serialize() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("goal", goal);
        m.put("started_at_ms", Long.valueOf(startedAtMs));
        m.put("model", modelId);
        // v3.13: the resume fields ride only when present, so a
        // marker written through the old constructor keeps its
        // exact pre-v3.13 shape.
        if (trailName.length() > 0) {
            m.put("trail", trailName);
        }
        if (!rules.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, String> e : rules.entrySet()) {
                r.put(e.getKey(), e.getValue());
            }
            m.put("rules", r);
        }
        return Json.write(m);
    }

    /**
     * Parse a marker written by serialize(). Returns null when
     * the text is null, malformed, not a JSON object, or
     * carries no goal — the caller treats null as "no marker
     * worth showing". A JSON number for the start time parses
     * as a Double on some paths and a Long on others; any
     * Number is accepted. The v3.13 fields are read leniently:
     * a non-string trail or a non-object rules value reads as
     * absent, and a rules entry whose value is not a string is
     * dropped — a strange marker degrades to the legacy line,
     * never to a crash and never to a half-rules resume.
     */
    public static RunMarker parse(String text) {
        if (text == null) return null;
        Object o;
        try {
            o = Json.parse(text.trim());
        } catch (Exception e) {
            return null;
        }
        if (!(o instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) o;
        Object g = m.get("goal");
        if (!(g instanceof String) || ((String) g).length() == 0) {
            return null;
        }
        long started = 0;
        Object s = m.get("started_at_ms");
        if (s instanceof Number) started = ((Number) s).longValue();
        Object md = m.get("model");
        String model = (md instanceof String) ? (String) md : "";
        Object tr = m.get("trail");
        String trail = (tr instanceof String) ? (String) tr : "";
        Map<String, String> rules = new LinkedHashMap<String, String>();
        Object ru = m.get("rules");
        if (ru instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) ru).entrySet()) {
                if (e.getKey() instanceof String
                        && e.getValue() instanceof String) {
                    rules.put((String) e.getKey(), (String) e.getValue());
                }
            }
        }
        return new RunMarker((String) g, started, model, trail, rules);
    }

    /**
     * Does this trail text end the way a finished run ends —
     * with a "stop" event? The belt-and-braces staleness check:
     * a marker can outlive a finished run (the process died
     * between the stop and the marker delete), and such a
     * marker must not offer a resume for a run that completed.
     * Lines are parsed one by one; a malformed line is skipped
     * (a torn last line says nothing either way), and null or
     * empty text reads as NOT ended — the absence of evidence
     * is not evidence of a finish.
     */
    public static boolean trailEnded(String trailText) {
        if (trailText == null || trailText.length() == 0) return false;
        String[] lines = trailText.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            Object o;
            try {
                o = Json.parse(line);
            } catch (Exception e) {
                continue;
            }
            if (o instanceof Map
                    && "stop".equals(((Map<?, ?>) o).get("type"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The launch decision for a parsed marker, judged in order:
     *  1. the trail already ended (trailEnded) — stale, clear
     *     silently;
     *  2. older than MAX_AGE_MS (or carrying no start time at
     *     all) — auto-dismissed, clear silently;
     *  3. resumable — offer the resume card;
     *  4. anything else — the v2.6 legacy line, then clear.
     */
    public static int launchVerdict(RunMarker m, long nowMs,
                                    boolean trailEnded) {
        if (trailEnded) return VERDICT_CLEAR;
        if (m.startedAtMs <= 0 || nowMs - m.startedAtMs > MAX_AGE_MS) {
            return VERDICT_CLEAR;
        }
        if (m.resumable()) return VERDICT_OFFER;
        return VERDICT_LEGACY;
    }

    /** The one feed line a surviving non-resumable marker
     *  produces at launch. */
    public static String abruptLine(RunMarker m) {
        return "Last run ended abruptly (app or phone died) — goal: "
                + m.goal;
    }
}
