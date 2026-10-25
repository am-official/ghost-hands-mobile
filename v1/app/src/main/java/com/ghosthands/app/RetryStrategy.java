package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v3.12 (R4 — strategy retry): which counter-moves a stuck run's
 * answer card offers, decided PURELY from the run's own trail.
 *
 * A blind "Retry as-is" is not a strategy and is never offered.
 * Each offered button names the ONE override the retry run will
 * carry — for that run only; the user's saved settings are never
 * read for modification here and never modified anywhere by a
 * retry (this class never sees Settings at all: the run record's
 * own stamps say what the failed run ran under, and the Activity
 * folds the chosen override into the new run's construction
 * values).
 *
 * The mapping, over the trail's recorded failure shape:
 *
 *   element / navigation-shaped failures (results that died
 *   "element ... not on page" — the stale-element shape the
 *   loop's race handling keys on) -> "Retry with desktop mode
 *   on", only when the run record says desktop mode was OFF
 *   (a wider, desktop-shaped page is the counter-move for
 *   elements a mobile layout hid or reflowed away).
 *
 *   search-shaped failures (dead-result detours the loop took
 *   back to a results page, plus failed executions of the
 *   search action itself) -> "Retry with Google as the
 *   fallback engine" — or Bing when Google already IS the
 *   run's fallback: the button names the actual alternate,
 *   never the engine that just failed the run.
 *
 *   thin / churning reads (successful reads whose extracted
 *   total Eyes calls thin) -> "Retry with the Thorough
 *   style", only when the run's style was not already
 *   Thorough (Thorough's two-source cross-check and verify
 *   posture are the counter-move for answers built on too
 *   little text).
 *
 *   repeated-action blocks and engine errors are counted for
 *   ranking honesty — a run dominated by them simply has less
 *   mapped evidence — but map to NO button: there is no honest
 *   one-setting counter-move for them. They never block a
 *   lower-ranked mapped shape from being offered.
 *
 * Eligibility is narrow on purpose: only a run whose stop
 * record's reason is "stuck" (the unchanged-map stop, the
 * planner's own stuck, the third guard-family stuck stop, and
 * v3.12's unanswered ask, which stops stuck by design) earns
 * offers. Done, denied, error and budget stops offer nothing —
 * a budget stop's counter-move is a settings change the user
 * makes themselves, not a button here. At most TWO options,
 * ranked by their shape's count, ties broken by the table's
 * order above.
 */
public final class RetryStrategy {

    private RetryStrategy() { }

    /** One offered counter-move. kind names the override slot
     *  ("desktop_mode" / "fallback_engine" / "style"); value is
     *  the override's value in that slot (the engine id for a
     *  fallback retry, "thorough" for a style retry, "" for
     *  desktop mode, which is a boolean on); label is the
     *  button text; stamp is what the retry's run record
     *  carries as "retry_override". */
    public static final class Option {
        public final String kind;
        public final String value;
        public final String label;
        public final String stamp;

        public Option(String kind, String value, String label,
                      String stamp) {
            this.kind = kind;
            this.value = value;
            this.label = label;
            this.stamp = stamp;
        }
    }

    public static final String KIND_DESKTOP = "desktop_mode";
    public static final String KIND_FALLBACK = "fallback_engine";
    public static final String KIND_STYLE = "style";

    /** The options for a finished run's trail events, in offer
     *  order; empty when the run is not eligible or no mapped
     *  shape applies. */
    public static List<Option> optionsFor(
            List<Map<String, Object>> events) {
        List<Option> out = new ArrayList<Option>();
        if (events == null) return out;

        Map<String, Object> run = null;
        Map<String, Object> stop = null;
        int elementFails = 0;
        int searchFails = 0;
        int thinReads = 0;
        int repeatBlocks = 0;
        String lastExecuteKind = null;

        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if (ev == null) continue;
            Object type = ev.get("type");
            if ("run".equals(type)) {
                run = ev;
            } else if ("stop".equals(type)) {
                stop = ev;
            } else if ("execute".equals(type)) {
                lastExecuteKind = actionKind(ev.get("action"));
            } else if ("detour".equals(type)) {
                // A dead-result detour is the loop walking back
                // from a destination a search produced: search-
                // shaped evidence, whatever the detour's fields.
                searchFails++;
            } else if ("guard".equals(type)) {
                Object reason = ev.get("reason");
                if (reason instanceof String
                        && ((String) reason).startsWith(
                                "repeat action corrected by guard")) {
                    repeatBlocks++;
                }
            } else if ("result".equals(type)) {
                boolean ok = Boolean.TRUE.equals(ev.get("ok"));
                Object r = ev.get("result");
                String result = (r instanceof String)
                        ? (String) r : null;
                if (!ok && result != null
                        && result.startsWith("error: element")
                        && result.contains("not on page")) {
                    elementFails++;
                } else if (!ok && "search".equals(lastExecuteKind)) {
                    searchFails++;
                } else if (ok && "read".equals(lastExecuteKind)
                        && result != null) {
                    int total = Agent.readTotalChars(result);
                    if (total >= 0 && Eyes.isThinRead(total)) {
                        thinReads++;
                    }
                }
            }
        }

        // Eligibility: a stuck stop only, and a run record to
        // judge applicability against. Anything else — done,
        // denied, error, budget, or a trail too old to carry a
        // run record — offers nothing.
        if (run == null || stop == null) return out;
        if (!"stuck".equals(stop.get("reason"))) return out;

        boolean desktopOn = Boolean.TRUE.equals(
                run.get("desktop_mode"));
        Object fb = run.get("fallback_engine");
        String fallback = (fb instanceof String)
                ? (String) fb : SearchEngines.DEFAULT_ID;
        Object st = run.get("style");
        String styleName = (st instanceof String)
                ? (String) st : Settings.styleName(
                        Settings.STYLE_BALANCED);

        // Rank the shapes: count descending, ties by table
        // order (element, search, thin, repeat). Only the three
        // mapped shapes can produce an option; the walk keeps
        // going past unmapped ones, so a dominant repeat-block
        // shape never hides a real counter-move beneath it.
        int[] counts = { elementFails, searchFails, thinReads,
                repeatBlocks };
        int[] order = { 0, 1, 2, 3 };
        for (int a = 0; a < order.length - 1; a++) {
            for (int b = a + 1; b < order.length; b++) {
                if (counts[order[b]] > counts[order[a]]
                        || (counts[order[b]] == counts[order[a]]
                                && order[b] < order[a])) {
                    int tmp = order[a];
                    order[a] = order[b];
                    order[b] = tmp;
                }
            }
        }
        for (int k = 0; k < order.length && out.size() < 2; k++) {
            int shape = order[k];
            if (counts[shape] <= 0) continue;
            if (shape == 0 && !desktopOn) {
                out.add(new Option(KIND_DESKTOP, "",
                        "Retry with desktop mode on",
                        "desktop_mode:on"));
            } else if (shape == 1) {
                String alt = alternateEngine(fallback);
                if (alt != null) {
                    out.add(new Option(KIND_FALLBACK, alt,
                            "Retry with "
                                    + SearchEngines.displayName(alt)
                                    + " as the fallback engine",
                            "fallback_engine:" + alt));
                }
            } else if (shape == 2
                    && !Settings.styleName(Settings.STYLE_THOROUGH)
                            .equals(styleName)) {
                out.add(new Option(KIND_STYLE,
                        Settings.styleName(Settings.STYLE_THOROUGH),
                        "Retry with the Thorough style",
                        "style:thorough"));
            }
            // shape 3 (repeat blocks) maps to nothing, by design.
        }
        return out;
    }

    /** The fallback alternate a search-shaped retry names: the
     *  engine that is NOT the one the failed run used. Google is
     *  the standing alternate; when Google already was the
     *  run's fallback, Bing is named instead. Returns null only
     *  when no honest alternate exists (unreachable with the
     *  shipped engine set; the null keeps the mapping total). */
    public static String alternateEngine(String currentFallbackId) {
        String current = SearchEngines.resolveId(currentFallbackId);
        if (SearchEngines.GOOGLE.equals(current)) {
            return SearchEngines.BING;
        }
        return SearchEngines.GOOGLE;
    }

    /** The kind string of an execute event's action map, or
     *  null when the event carries none. */
    private static String actionKind(Object actionObj) {
        if (actionObj instanceof Map) {
            Object a = ((Map<?, ?>) actionObj).get("action");
            if (a instanceof String) return (String) a;
        }
        return null;
    }
}
