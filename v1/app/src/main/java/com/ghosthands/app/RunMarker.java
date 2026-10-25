package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v2.6: the unclean-shutdown marker. At run start the app writes
 * one of these (goal, start time, active model) to a small file in
 * its private storage; every normal stop path deletes it. If the
 * file is still there at the next launch, the previous run never
 * reached a stop — the app or the phone died mid-run — and the
 * launch surfaces that fact instead of letting it vanish.
 *
 * The marker PROVES an ending was abrupt; it can never say why.
 * Field context: Ryan's "stopped twice and closed out the app on
 * me" during the v2.4 weather runs had three live suspects (new
 * native grammar code, the bigger Q5 model, a phone browning out
 * at 12-15% battery) and no evidence either way. This class is a
 * diagnostic instrument, not a fix.
 *
 * Pure and host-tested: serialization is one JSON object written
 * with the app's own Json writer, parsing goes through the app's
 * Json parser and refuses anything malformed (a corrupt marker
 * reads as no marker, never as a crash).
 */
public class RunMarker {

    public final String goal;
    public final long startedAtMs;
    public final String modelId;

    public RunMarker(String goal, long startedAtMs, String modelId) {
        this.goal = goal == null ? "" : goal;
        this.startedAtMs = startedAtMs;
        this.modelId = modelId == null ? "" : modelId;
    }

    public String serialize() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("goal", goal);
        m.put("started_at_ms", Long.valueOf(startedAtMs));
        m.put("model", modelId);
        return Json.write(m);
    }

    /**
     * Parse a marker written by serialize(). Returns null when the
     * text is null, malformed, not a JSON object, or carries no
     * goal — the caller treats null as "no marker worth showing".
     * A JSON number for the start time parses as a Double on some
     * paths and a Long on others; any Number is accepted.
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
        return new RunMarker((String) g, started, model);
    }

    /** The one feed line a surviving marker produces at launch. */
    public static String abruptLine(RunMarker m) {
        return "Last run ended abruptly (app or phone died) — goal: "
                + m.goal;
    }
}
