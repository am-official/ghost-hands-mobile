package com.ghosthands.app;

import java.util.Map;

/**
 * Strict extraction of the planner's one action object.
 *
 * The model may pretty-print JSON across many lines and may wrap it in
 * a code fence; whitespace and fences are tolerated. Anything else is
 * rejected — prose around the object, two objects, an unknown action,
 * a missing required field — and a rejection is a fail-closed deny in
 * the agent loop, never a guess.
 */
public final class PlannerParse {

    private PlannerParse() { }

    /** Returns the action, or null when the output is not exactly one
     *  valid action object. */
    public static GhAction extract(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("ERROR:")) return null;
        // Tolerate a markdown code fence.
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl < 0) return null;
            s = s.substring(nl + 1).trim();
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3).trim();
            }
        }
        if (!s.startsWith("{")) return null;
        int end = matchingBrace(s, 0);
        if (end < 0) return null;
        // Exactly one object: nothing but whitespace may follow it.
        if (s.substring(end + 1).trim().length() > 0) return null;
        Object parsed;
        try {
            parsed = Json.parse(s.substring(0, end + 1));
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;

        Object aObj = m.get("action");
        if (!(aObj instanceof String)) return null;
        String kind = (String) aObj;
        if (!GhAction.isKind(kind)) return null;
        GhAction a = new GhAction(kind);

        if ("tap".equals(kind) || "type".equals(kind)) {
            Integer el = asInt(m.get("el"));
            if (el == null || el.intValue() < 1) return null;
            a.el = el.intValue();
        }
        if ("type".equals(kind)) {
            Object t = m.get("text");
            if (!(t instanceof String)) return null;
            a.text = (String) t;
        }
        if ("goto".equals(kind)) {
            Object u = m.get("url");
            if (!(u instanceof String) || ((String) u).trim().length() == 0) return null;
            a.url = ((String) u).trim();
        }
        if ("scroll".equals(kind)) {
            Object d = m.get("dir");
            String dir = (d instanceof String) ? ((String) d).toLowerCase() : "down";
            if (!"down".equals(dir) && !"up".equals(dir)) return null;
            a.dir = dir;
        }
        if ("wait".equals(kind)) {
            Integer ms = asInt(m.get("ms"));
            long v = (ms == null) ? 1000L : ms.longValue();
            if (v < 0) v = 0;
            if (v > 10000) v = 10000;
            a.ms = v;
        }
        if ("search".equals(kind)) {
            Object q = m.get("query");
            if (!(q instanceof String) || ((String) q).trim().length() == 0) return null;
            a.query = ((String) q).trim();
        }
        if ("note".equals(kind)) {
            Object f = m.get("fact");
            if (!(f instanceof String) || ((String) f).trim().length() == 0) return null;
            a.fact = ((String) f).trim();
        }
        if ("done".equals(kind)) {
            Object sm = m.get("summary");
            a.summary = (sm instanceof String) ? (String) sm : "";
        }
        if ("stuck".equals(kind)) {
            Object r = m.get("reason");
            a.reason = (r instanceof String) ? (String) r : "";
        }
        return a;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Integer.valueOf((int) d);
            }
            return null;
        }
        if (o instanceof String) {
            try {
                return Integer.valueOf(((String) o).trim());
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Index of the '}' matching the '{' at start, string-aware; -1 if none. */
    private static int matchingBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
