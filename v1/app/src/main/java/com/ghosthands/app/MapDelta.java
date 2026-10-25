package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.10 (Everything Train R2 — perception deltas): what the
 * prompt carries per step, decided purely.
 *
 * Perception itself is untouched: the loop still perceives the
 * FULL map every step, and the stash, the heal, the fit ladder
 * and the trail all work from the complete perception exactly
 * as before. Only the prompt's map block changes. The first
 * block a run sends for a page — and any block after a URL
 * change — is the full map, as always. On later steps of the
 * SAME page the block can be a DELTA: the elements added,
 * removed or changed since the last block actually sent, each
 * entry carrying its stable element number (the numbering is
 * the full map's, shared — an element keeps its number for as
 * long as it keeps its identity). A delta that changed nothing
 * is one line.
 *
 * The diff runs over SENT SETS: the elements whose lines were
 * actually in the last full block (the standing render stops
 * at its char budget, so the tail of a long map was never
 * sent; an element past the cut is "added" the first time it
 * enters the sent set, which is the truth from the planner's
 * side of the glass).
 *
 * Safety valves — any one of them sends the full map instead:
 * - the identity (the heal's: tag + name + href for a link,
 *   tag + name + type for anything else) occurs twice in
 *   either sent set: the diff cannot say which twin changed;
 * - a matched pair's NUMBER differs between the two maps:
 *   the numbering the planner holds would be wrong, and a
 *   delta that renumbers silently is worse than no delta;
 * - the delta's own estimated size is not smaller than the
 *   full map's: churn has eaten the saving;
 * - RESYNC_AFTER_DELTAS consecutive deltas have been sent:
 *   a full resync goes out regardless, so a planner that
 *   lost the thread re-grounds on a complete map.
 * The loop also discards this state whenever the fit ladder
 * replaces the block with its own full rendering (rung 3+):
 * what the planner actually saw is then a full map, and the
 * next block must start from a fresh baseline.
 *
 * Element state for change detection is the element's rendered
 * line itself (the standing render at its standing caps) —
 * it carries exactly what the planner sees, including the
 * v3.9 form state (a checkbox's [x], a select's starred
 * option, a radio group's starred choice), so a pure state
 * flip with an unmoved DOM still diffs as a change.
 */
public final class MapDelta {

    private MapDelta() { }

    /** The block kinds (the trail's "map_block" values). */
    public static final String FULL = "full";
    public static final String DELTA = "delta";
    public static final String RESYNC = "resync";

    /** Consecutive delta blocks a run may send before a full
     *  resync goes out regardless of the diff. */
    public static final int RESYNC_AFTER_DELTAS = 8;

    /** The whole delta block when nothing changed: one line. */
    public static final String UNCHANGED_TEXT =
            "Map unchanged since the last full map.\n";

    /** The delta block's header when something changed. */
    public static final String HEADER =
            "Map changes since the last full map "
            + "(numbers unchanged):\n";

    /** The loop's delta state for one run: the last block
     *  actually sent (its page, its sent set and their
     *  rendered lines) and how many deltas have ridden on it.
     *  Mutated only by choose() (and reset() when the fit
     *  ladder overrode a block). */
    public static final class State {
        /** The URL of the page the baseline describes; null
         *  until the first block is sent (or after reset()). */
        public String url;
        /** The baseline's sent set, in map order. */
        public List<ElementInfo> sent =
                new ArrayList<ElementInfo>();
        /** sent's rendered lines (parallel to sent), without
         *  trailing newlines. */
        public List<String> sentLines = new ArrayList<String>();
        /** Consecutive DELTA blocks sent on this baseline. */
        public int deltasSinceFull = 0;

        /** Forget the baseline: the next choose() sends a
         *  full map. The loop calls this when the fit ladder
         *  replaced the chosen block with a full rendering —
         *  the planner's picture no longer matches what
         *  choose() believes it sent. */
        public void reset() {
            url = null;
            sent = new ArrayList<ElementInfo>();
            sentLines = new ArrayList<String>();
            deltasSinceFull = 0;
        }
    }

    /** A chosen block: its kind (FULL / DELTA / RESYNC) and
     *  the exact text the prompt carries in its map slot. */
    public static final class Block {
        public final String kind;
        public final String text;

        public Block(String kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    /**
     * The identity the diff matches on — the heal's identity
     * (Eyes.identityMatches), as a key: for a link, tag +
     * name + href; for anything else, tag + name + type. The
     * tag is case-folded (identityMatches compares it
     * case-insensitively). Two elements the heal would call
     * the same element share a key, and only then.
     */
    public static String identityKey(ElementInfo e) {
        if (e == null) return "|";
        String tag = (e.tag == null) ? ""
                : e.tag.toLowerCase(java.util.Locale.US);
        if (Eyes.isLink(e)) {
            return tag + "|L|" + nn(e.name) + "|" + nn(e.href);
        }
        return tag + "|C|" + nn(e.name) + "|" + nn(e.type);
    }

    private static String nn(String s) {
        return (s == null) ? "" : s;
    }

    /** One element's rendered map line (no trailing newline),
     *  at the standing render's caps — the same line the full
     *  map carries for it. */
    public static String lineOf(ElementInfo e, String goal) {
        List<ElementInfo> one = new ArrayList<ElementInfo>();
        one.add(e);
        String r = Eyes.renderElements(one, goal, Integer.MAX_VALUE,
                80, Integer.MAX_VALUE);
        if (r.endsWith("\n")) r = r.substring(0, r.length() - 1);
        return r;
    }

    /**
     * The sent set of a perception: the prefix of its element
     * list whose rendered lines fit the standing render's char
     * budget (Eyes.MAP_BUDGET_CHARS), accumulated exactly as
     * Eyes.renderElements accumulates them. These are the
     * elements a full block for this page actually shows.
     */
    public static List<ElementInfo> sentSet(Eyes.Perception p,
                                            String goal) {
        List<ElementInfo> out = new ArrayList<ElementInfo>();
        if (p == null) return out;
        int used = 0;
        for (int i = 0; i < p.elements.size(); i++) {
            ElementInfo e = p.elements.get(i);
            String line = lineOf(e, goal);
            if (used + line.length() + 1 > Eyes.MAP_BUDGET_CHARS) {
                break;
            }
            used += line.length() + 1;
            out.add(e);
        }
        return out;
    }

    /** The rendered lines of a sent set (parallel, no trailing
     *  newlines). */
    public static List<String> linesOf(List<ElementInfo> els,
                                       String goal) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < els.size(); i++) {
            out.add(lineOf(els.get(i), goal));
        }
        return out;
    }

    /** Identity key -> index in the set, or null when any key
     *  occurs twice (the diff cannot tell twins apart). */
    private static Map<String, Integer> keyIndex(
            List<ElementInfo> els) {
        Map<String, Integer> m =
                new LinkedHashMap<String, Integer>();
        for (int i = 0; i < els.size(); i++) {
            String k = identityKey(els.get(i));
            if (m.containsKey(k)) return null;
            m.put(k, Integer.valueOf(i));
        }
        return m;
    }

    /** Commit a full block (kind FULL or RESYNC) as the new
     *  baseline and return it. */
    private static Block sendFull(State st, Eyes.Perception p,
                                  List<ElementInfo> cur,
                                  List<String> curLines,
                                  String fullRendered, String kind) {
        st.url = p.url;
        st.sent = cur;
        st.sentLines = curLines;
        st.deltasSinceFull = 0;
        return new Block(kind, fullRendered);
    }

    /**
     * Choose the map block for this step's prompt and advance
     * the state to describe what was sent. `fullRendered` is
     * the current perception's standing full render (what a
     * full block carries); the state's baseline is the last
     * block choose() sent. See the class doc for the valves.
     */
    public static Block choose(State st, Eyes.Perception p,
                               String goal, String fullRendered) {
        List<ElementInfo> cur = sentSet(p, goal);
        List<String> curLines = linesOf(cur, goal);
        // First block for a page (or after a reset, or after
        // any navigation): the full map.
        if (st.url == null || !st.url.equals(p.url)) {
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        // The periodic resync: a full map regardless.
        if (st.deltasSinceFull >= RESYNC_AFTER_DELTAS) {
            return sendFull(st, p, cur, curLines, fullRendered,
                    RESYNC);
        }
        Map<String, Integer> baseIdx = keyIndex(st.sent);
        Map<String, Integer> curIdx = keyIndex(cur);
        if (baseIdx == null || curIdx == null) {
            // Identity ambiguity in either set.
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        StringBuilder adds = new StringBuilder();
        StringBuilder changes = new StringBuilder();
        boolean[] matched = new boolean[st.sent.size()];
        for (int j = 0; j < cur.size(); j++) {
            ElementInfo e = cur.get(j);
            Integer bi = baseIdx.get(identityKey(e));
            if (bi == null) {
                adds.append("+ ").append(curLines.get(j))
                        .append('\n');
                continue;
            }
            matched[bi.intValue()] = true;
            ElementInfo before = st.sent.get(bi.intValue());
            if (before.number != e.number) {
                // Numbering mismatch: the numbers the planner
                // holds for this element would be wrong.
                return sendFull(st, p, cur, curLines, fullRendered,
                        FULL);
            }
            if (!st.sentLines.get(bi.intValue())
                    .equals(curLines.get(j))) {
                changes.append("~ ").append(curLines.get(j))
                        .append('\n');
            }
        }
        StringBuilder removes = new StringBuilder();
        for (int i = 0; i < st.sent.size(); i++) {
            if (!matched[i]) {
                removes.append("- ").append(st.sentLines.get(i))
                        .append('\n');
            }
        }
        String text;
        if (adds.length() == 0 && changes.length() == 0
                && removes.length() == 0) {
            text = UNCHANGED_TEXT;
        } else {
            text = HEADER + adds.toString() + changes.toString()
                    + removes.toString();
        }
        // The size valve: a delta that costs what the full map
        // costs buys nothing.
        if (Agent.estTokens(text) >= Agent.estTokens(fullRendered)) {
            return sendFull(st, p, cur, curLines, fullRendered, FULL);
        }
        st.sent = cur;
        st.sentLines = curLines;
        st.deltasSinceFull++;
        return new Block(DELTA, text);
    }
}
