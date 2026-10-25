package com.ghosthands.app;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Trail — a port of ghost_hands/trail.py: an append-only JSONL
 * provenance log of everything a run did and why. The govern and
 * execute events are written (and flushed to disk) BEFORE the action
 * runs — record-before-execute — so even a crash leaves an honest
 * account of what was about to happen.
 *
 * Event types mirror the Python set relevant on a phone:
 * perceive / decide / govern / approval / execute / result / stop /
 * dialog / download, plus "guard" (v1.1): the agent-loop relevance
 * guard correcting an off-goal proposal before the Governor's turn —
 * a relevance event, not a safety verdict, and "read" (v1.3): an
 * agent-initiated auto-read extraction on arrival (phase "auto"),
 * as distinct from a planner-chosen read action, which is recorded
 * as ordinary execute/result events, and "recover" (v1.6): the
 * agent loop's deterministic reload of a page that failed to load
 * (carries the error page's url and the failed_url it names), and
 * "race" (v2.2): an executed action failed against a page that had
 * already navigated away while the planner was deciding (the stale
 * "element N not on page" shape); the loop re-perceived and
 * re-decided the same step (carries the action, the perceived_url
 * the decision was made against, the current_url found after, and
 * the stale result), and "detour" (v2.5): a result tapped from a
 * search-results page failed to load and its one reload did not
 * save it, so instead of stopping the loop went back to that
 * results page to let the planner pick another (carries the
 * failed_url, the results_url returned to, and detours_used), and
 * "run_summary" (v2.5): the run's closing account of itself —
 * outcome, steps, wall-clock seconds, total prompt tokens, total
 * reused tokens and the reuse percent, recorded next to every
 * "stop" event, and "stale_stash" (v2.7): an executed action
 * failed against a page that had NOT moved — same URL, identical
 * signature — so the loop refreshed the perception and re-decided
 * the step (the stale-stash sibling of "race"; registered here
 * in v2.9 — the loop has recorded it since v2.7 but the type was
 * missing from EVENT_TYPES, and record() refuses unknown types),
 * and "fit" (v2.9): the pre-flight fit degraded a step prompt
 * down its ladder before the engine call (carries the rungs
 * applied and the fitted estimate), and "run" (v3.0): the run's
 * opening record — the goal, the pack, the model — so a trail
 * file is self-describing (the answer card reads its goal from
 * here), and "note" (v3.0): a fact the planner recorded in the
 * scratchpad, with the page stamp it was taken from and the
 * notebook's verdict (added / duplicate / full); the loop
 * records it BEFORE the notebook is touched, and its execute
 * record precedes it, so record-before-execute holds for the
 * new action exactly as for the old ones, and "watch" (v3.2):
 * a watched saved goal's run verdict — the goal's name, the
 * verdict (baseline / no_change / changed / not_done), the
 * baseline timestamp the comparison ran against (since_ms) and,
 * for a change, its diff lines; recorded by the Activity after
 * the run's stop, when the verdict exists, so the answer card
 * (live or rebuilt from this file) can show its watch banner.
 * v3.3 adds three: "route" (a taught route's replay — its start,
 * and each redacted step's pause for the user's own fill; route
 * steps' govern / execute / result records carry the same
 * provenance instead of route events of their own), "screenshot"
 * (a finished run's end-of-run photograph, naming the file in
 * the app's private shots store; recorded by the Activity after
 * the stop, before the answer card is built, so the card can
 * show it), and "assist" (a co-pilot pause: offered at a stuck
 * stop, then tapped — with provenance USER, because the tap was
 * the user's own hand, never the agent's — or declined).
 * v3.12 adds two: "ask" (the planner asked the user a clarifying
 * question — the question text; recorded by the loop BEFORE the
 * question is put to the user, so record-before-execute holds
 * for the new action exactly as for the old ones) and "answer"
 * (the user's reply — the question it answers, the answer text
 * and provenance USER, because the words are the user's own,
 * never the agent's; an unanswered ask records no answer event —
 * the run stops instead, and the stop record says why).
 * v3.15 adds three: "tab_opened" (a browser tab came into being —
 * the tab's 1-based number, its url and how it was opened: the
 * planner's open_tab or the user's + chip), "tab_switched" (the
 * active tab changed — the tab's number and its url) and
 * "tab_closed" (a tab went away — the closed tab's number and
 * url, and the number of the tab active after). Tab numbers in
 * trails are the planner-facing 1-based numbers the tab roster
 * shows; the loop records each tab event BEFORE the hands move,
 * so record-before-execute holds for the tab actions exactly as
 * for the old ones.
 * v3.16 adds "page_watch" (R8): a page watch's CREATION,
 * recorded in the live run's trail when one is running
 * (stamped by:"user" — creation is the user's own tap, never
 * a planner action, and no Governor ever judges it; idle
 * creations have no trail to record in and live in the watch
 * store itself). A page watch's check VERDICTS ride the
 * existing "watch" event instead — goal_name carrying the
 * watch's label, a url field and a page_watch flag added —
 * so the answer card's watch banner reads them exactly as
 * it reads a goal watch's, untouched.
 */
public class Trail {

    public static final String[] EVENT_TYPES = {
            "perceive", "decide", "govern", "approval", "execute",
            "result", "stop", "dialog", "download", "guard", "read",
            "recover", "race", "detour", "run_summary", "stale_stash",
            "fit", "run", "note", "watch", "route", "screenshot",
            "assist", "step_screenshot", "ask", "answer",
            "tab_opened", "tab_switched", "tab_closed",
            "page_watch",
    };

    private final File path;
    private final List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();

    public Trail(File path) {
        this.path = path;
        if (path != null) {
            File parent = path.getParentFile();
            if (parent != null) parent.mkdirs();
        }
    }

    public File getPath() {
        return path;
    }

    public synchronized Map<String, Object> record(String type, int step,
                                                   Map<String, Object> fields) {
        boolean known = false;
        for (int i = 0; i < EVENT_TYPES.length; i++) {
            if (EVENT_TYPES[i].equals(type)) { known = true; break; }
        }
        if (!known) {
            throw new IllegalArgumentException("unknown trail event type: " + type);
        }
        Map<String, Object> ev = new LinkedHashMap<String, Object>();
        ev.put("type", type);
        ev.put("step", Long.valueOf(step));
        ev.put("ts", Double.valueOf(System.currentTimeMillis() / 1000.0));
        if (fields != null) ev.putAll(fields);
        events.add(ev);
        if (path != null) {
            try {
                FileOutputStream out = new FileOutputStream(path, true);
                out.write((Json.write(ev) + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
                out.close();
            } catch (Exception ignored) {
                // The in-memory copy remains; a trail write failure must
                // not crash the run, but record-before-execute callers
                // check fileWritten() when it matters.
            }
        }
        return ev;
    }

    /** Convenience: build the fields map from alternating key/value pairs. */
    public static Map<String, Object> fields(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public synchronized List<Map<String, Object>> events() {
        return new ArrayList<Map<String, Object>>(events);
    }

    /** The whole trail as JSONL text (for the trail screen / export). */
    public synchronized String toJsonLines() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> ev : events) {
            sb.append(Json.write(ev)).append('\n');
        }
        return sb.toString();
    }

    /** Per-run accounting, mirroring trail.py's accounting(). */
    public synchronized Map<String, Object> accounting() {
        long steps = 0;
        long mapChars = 0;
        Map<String, Object> byClass = new LinkedHashMap<String, Object>();
        for (Map<String, Object> ev : events) {
            String t = String.valueOf(ev.get("type"));
            if ("execute".equals(t)) {
                steps++;
            } else if ("perceive".equals(t)) {
                Object mc = ev.get("map_chars");
                if (mc instanceof Number) mapChars += ((Number) mc).longValue();
            } else if ("govern".equals(t)) {
                String cls = String.valueOf(ev.get("classification"));
                Object cur = byClass.get(cls);
                byClass.put(cls, Long.valueOf(cur instanceof Number
                        ? ((Number) cur).longValue() + 1 : 1));
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("steps", Long.valueOf(steps));
        out.put("map_chars", Long.valueOf(mapChars));
        out.put("est_tokens", Long.valueOf(Math.round(mapChars / 4.0)));
        out.put("actions_by_class", byClass);
        return out;
    }
}
