package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One atomic move the phone planner may choose — the mobile subset of
 * the Ghost Hands action vocabulary (actions.py), addressed by element
 * number from the current numbered map, never by raw selector.
 *
 *   {"action":"tap","el":N}            {"action":"type","el":N,"text":"..."}
 *   {"action":"goto","url":"..."}     {"action":"scroll","dir":"down|up"}
 *   {"action":"back"}                 {"action":"wait","ms":N}
 *   {"action":"search","query":"..."}                   (v1.1)
 *   {"action":"read"}                                   (v1.2)
 *   {"action":"note","fact":"..."}                      (v3.0)
 *   {"action":"done","summary":"..."} {"action":"stuck","reason":"..."}
 */
public class GhAction {

    public static final String[] KINDS = {
            "tap", "type", "goto", "scroll", "back", "wait", "done", "stuck",
            "search", "read", "note"
    };

    public String action;
    public int el = -1;          // element number for tap / type
    public String text;          // type
    public String url;           // goto
    public String dir;           // scroll: down | up
    public long ms;              // wait
    public String query;         // search (v1.1)
    public String summary;       // done
    public String reason;        // stuck
    public String fact;          // note (v3.0): the scratchpad fact

    public GhAction(String action) {
        this.action = action;
    }

    public static boolean isKind(String s) {
        for (int i = 0; i < KINDS.length; i++) {
            if (KINDS[i].equals(s)) return true;
        }
        return false;
    }

    /** Trail/feed record: only the fields that are set, like actions.py. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("action", action);
        if (el >= 0) m.put("el", Long.valueOf(el));
        if (text != null) m.put("text", text);
        if (url != null) m.put("url", url);
        if (dir != null) m.put("dir", dir);
        if ("wait".equals(action)) m.put("ms", Long.valueOf(ms));
        if (query != null) m.put("query", query);
        if (summary != null) m.put("summary", summary);
        if (reason != null) m.put("reason", reason);
        if (fact != null) m.put("fact", fact);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** Stable identity for the runner's stuck detection. */
    public String signature() {
        return action + "|" + el + "|" + text + "|" + url + "|" + query;
    }

    /** Human-readable one-liner for the step feed / approval panel. */
    public String describe(ElementInfo element) {
        StringBuilder sb = new StringBuilder();
        sb.append(action);
        if ("tap".equals(action) || "type".equals(action)) {
            sb.append(" #").append(el);
            if (element != null && element.name != null && element.name.length() > 0) {
                sb.append(" \"").append(element.name).append('"');
            }
            if ("type".equals(action) && text != null) {
                sb.append(" : \"").append(text).append('"');
            }
        } else if ("goto".equals(action)) {
            sb.append(" ").append(url);
        } else if ("scroll".equals(action)) {
            sb.append(" ").append(dir == null ? "down" : dir);
        } else if ("wait".equals(action)) {
            sb.append(" ").append(ms).append("ms");
        } else if ("search".equals(action)) {
            sb.append(" \"").append(query == null ? "" : query).append('"');
        } else if ("note".equals(action)) {
            sb.append(" : \"").append(fact == null ? "" : fact).append('"');
        } else if ("done".equals(action)) {
            sb.append(" : ").append(summary == null ? "" : summary);
        } else if ("stuck".equals(action)) {
            sb.append(" : ").append(reason == null ? "" : reason);
        }
        return sb.toString();
    }
}
