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
 *   {"action":"choose","el":N,"text":"..."}             (v3.9)
 *   {"action":"toggle","el":N,"text":"..."}             (v3.9)
 *   {"action":"ask","question":"..."}                   (v3.12)
 *   {"action":"open_tab","url":"..."}  (url optional)   (v3.15)
 *   {"action":"switch_tab","tab":N}                     (v3.15)
 *   {"action":"close_tab","tab":N}                      (v3.15)
 *   {"action":"read_file","name":"..."}                 (v3.16)
 *   {"action":"done","summary":"..."} {"action":"stuck","reason":"..."}
 */
public class GhAction {

    public static final String[] KINDS = {
            "tap", "type", "goto", "scroll", "back", "wait", "done", "stuck",
            "search", "read", "note", "choose", "toggle", "ask",
            "open_tab", "switch_tab", "close_tab", "read_file"
    };

    public String action;
    public int el = -1;          // element number for tap / type / choose / toggle
    public String text;          // type; choose (the option label);
                                 // toggle (the radio choice label, optional)
    public String url;           // goto
    public String dir;           // scroll: down | up
    public long ms;              // wait
    public String query;         // search (v1.1)
    public String summary;       // done
    public String reason;        // stuck
    public String fact;          // note (v3.0): the scratchpad fact
    public String question;      // ask (v3.12): the clarifying question
                                 // for the user; touches no page
    public int tab = -1;         // switch_tab / close_tab (v3.15): the
                                 // 1-based tab number, as the roster
                                 // shows it; open_tab reuses url above
                                 // for its optional starting page
    public String name;          // read_file (v3.16): the document's
                                 // name in the downloads store, as
                                 // the prompt's Files line lists it

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
        if (question != null) m.put("question", question);
        if (tab >= 0) m.put("tab", Long.valueOf(tab));
        if (name != null) m.put("name", name);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    /** Stable identity for the runner's stuck detection. */
    public String signature() {
        return action + "|" + el + "|" + text + "|" + url + "|" + query
                + "|" + question + "|" + tab + "|" + name;
    }

    /** Human-readable one-liner for the step feed / approval panel. */
    public String describe(ElementInfo element) {
        StringBuilder sb = new StringBuilder();
        sb.append(action);
        if ("tap".equals(action) || "type".equals(action)
                || "choose".equals(action) || "toggle".equals(action)) {
            sb.append(" #").append(el);
            if (element != null && element.name != null && element.name.length() > 0) {
                sb.append(" \"").append(element.name).append('"');
            }
            if (("type".equals(action) || "choose".equals(action)
                    || "toggle".equals(action)) && text != null) {
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
        } else if ("ask".equals(action)) {
            sb.append(" : \"").append(question == null ? "" : question).append('"');
        } else if ("open_tab".equals(action)) {
            if (url != null) sb.append(" ").append(url);
        } else if ("switch_tab".equals(action) || "close_tab".equals(action)) {
            sb.append(" tab ").append(tab);
        } else if ("read_file".equals(action)) {
            sb.append(" \"").append(name == null ? "" : name).append('"');
        } else if ("done".equals(action)) {
            sb.append(" : ").append(summary == null ? "" : summary);
        } else if ("stuck".equals(action)) {
            sb.append(" : ").append(reason == null ? "" : reason);
        }
        return sb.toString();
    }
}
