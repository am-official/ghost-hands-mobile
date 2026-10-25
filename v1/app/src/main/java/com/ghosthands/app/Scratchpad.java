package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.0 (item 1): the fact scratchpad — a governed notebook for
 * multi-page goals. The planner records a fact with the dedicated
 * {"action":"note","fact":"..."} action as it finds one; every
 * fact is stamped with the page it was taken from (URL + title),
 * so the notebook is provenance, not hearsay. The facts ride in
 * the prompt as a compact FACTS block, and the v2.1 done-grounding
 * guard counts them as evidence: a number the planner noted from
 * an earlier page grounds a done long after that page's read text
 * has fallen out of the history window.
 *
 * Limits, all deliberate: at most MAX_FACTS facts (a notebook,
 * not an archive — the notebook-full result tells the planner to
 * answer from what it has), each fact at most MAX_FACT_CHARS
 * chars (truncated with TRUNC_MARKER — a fact is a value, not a
 * paragraph), and an identical fact text is never recorded twice
 * (facts are compared by their trimmed text; the stamp of the
 * first sighting is the one kept).
 *
 * Honest framing, kept from the release notes: facts are
 * PLANNER-ASSERTED. The stamp proves which page the planner
 * claimed to be reading; it does not prove the page said it. The
 * done guard therefore treats notes as evidence (they join the
 * read text the guard already trusted), never as proof.
 *
 * Pure: no Android, no I/O — the host harness pins every rule.
 */
public class Scratchpad {

    /** The notebook's capacity, in facts. */
    public static final int MAX_FACTS = 12;

    /** One fact's length cap, in chars, marker included. */
    public static final int MAX_FACT_CHARS = 200;

    /** Appended where a fact was cut to MAX_FACT_CHARS. */
    public static final String TRUNC_MARKER = "…";

    /** A fact's source stamp is shown trimmed to this many chars
     *  in the prompt block (the trail keeps the full stamp). */
    public static final int SOURCE_LABEL_CHARS = 48;

    // add() outcomes (also the "note" trail event's status).
    public static final String ADDED = "added";
    public static final String DUPLICATE = "duplicate";
    public static final String FULL = "full";
    public static final String EMPTY = "empty";

    /** One noted fact and the page it was taken from. */
    public static final class Fact {
        public final String text;
        public final String url;
        public final String title;

        public Fact(String text, String url, String title) {
            this.text = text;
            this.url = url;
            this.title = title;
        }

        /** The compact source label the prompt block shows:
         *  the page title when there is one, else the URL,
         *  trimmed to SOURCE_LABEL_CHARS either way. */
        public String sourceLabel() {
            String s = (title != null && title.trim().length() > 0)
                    ? title.trim() : (url == null ? "" : url.trim());
            if (s.length() > SOURCE_LABEL_CHARS) {
                s = s.substring(0, SOURCE_LABEL_CHARS) + TRUNC_MARKER;
            }
            return s;
        }
    }

    private final List<Fact> facts = new ArrayList<Fact>();

    public int size() {
        return facts.size();
    }

    public boolean isFull() {
        return facts.size() >= MAX_FACTS;
    }

    /** The recorded facts, oldest first (a copy). */
    public List<Fact> facts() {
        return new ArrayList<Fact>(facts);
    }

    /** The fact a text would be recorded as: trimmed, and cut
     *  to MAX_FACT_CHARS with TRUNC_MARKER when longer. Null in,
     *  null out. */
    public static String normalise(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.length() > MAX_FACT_CHARS) {
            t = t.substring(0, MAX_FACT_CHARS - TRUNC_MARKER.length())
                    + TRUNC_MARKER;
        }
        return t;
    }

    /** What add() would do with this text, without doing it —
     *  the agent loop asks first so the "note" trail event can
     *  be recorded (with the outcome) before the notebook is
     *  touched: record-before-execute, as everywhere else. */
    public String statusFor(String text) {
        String t = normalise(text);
        if (t == null || t.length() == 0) return EMPTY;
        for (int i = 0; i < facts.size(); i++) {
            if (facts.get(i).text.equals(t)) return DUPLICATE;
        }
        if (isFull()) return FULL;
        return ADDED;
    }

    /** Record a fact. Returns the outcome (ADDED / DUPLICATE /
     *  FULL / EMPTY); the notebook changes only on ADDED. */
    public String add(String text, String url, String title) {
        String status = statusFor(text);
        if (!ADDED.equals(status)) return status;
        facts.add(new Fact(normalise(text), url, title));
        return ADDED;
    }

    /** The FACTS block for the prompt, or "" when the notebook
     *  is empty (an empty notebook leaves the prompt byte-
     *  identical to its pre-v3.0 shape). One line per fact:
     *  "- <fact> (from: <source label>)". */
    public String render() {
        if (facts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Facts noted:\n");
        for (int i = 0; i < facts.size(); i++) {
            Fact f = facts.get(i);
            sb.append("- ").append(f.text);
            String src = f.sourceLabel();
            if (src.length() > 0) {
                sb.append(" (from: ").append(src).append(')');
            }
            if (i + 1 < facts.size()) sb.append('\n');
        }
        return sb.toString();
    }

    /** The fit ladder's last-rung form of the block: the count
     *  survives, the texts do not (the agent can re-note or
     *  re-read what it needs). "" when the notebook is empty. */
    public String renderStubbed() {
        if (facts.isEmpty()) return "";
        return "Facts noted: " + facts.size()
                + " (texts omitted to fit the prompt budget; the "
                + "values in them still count as your evidence)";
    }

    /** Every fact's text, one per line — the grounding guard's
     *  evidence view of the notebook. "" when empty. */
    public String factsText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < facts.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(facts.get(i).text);
        }
        return sb.toString();
    }
}
