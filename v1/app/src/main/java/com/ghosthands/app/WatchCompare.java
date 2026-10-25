package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v3.2 (watch mode): the comparator — how a watched goal's
 * scheduled checks produce a verdict without crying wolf.
 *
 * The design problem: the on-device 1.5B phrases every answer
 * differently, so comparing done summaries as TEXT would report
 * "changed" on every check. The comparison therefore runs on
 * the run's EVIDENCE, not its phrasing: the signature of a
 * finished run is the numeric values found in (a) the facts the
 * planner noted in the scratchpad and (b) the done summary,
 * each DISTINCT value once, carrying a short label — up to
 * LABEL_WORDS normalized words from the text between the
 * previous number and this one — so a difference reads like
 * "kahului fare: $698 -> $617" instead of a bare "changed".
 * Each value once, deliberately: a fare the planner noted and
 * then repeated in its summary is one piece of evidence, not
 * two, and whether the next check's planner bothers to note it
 * again must not change the count and fake a difference. Facts
 * are scanned first, so when a value appears in both, the
 * noted fact's label — the planner's own name for it — is the
 * one the signature keeps.
 *
 * Comparison, in order: exact (label, value) pairs cancel;
 * leftover values that still exist under a DRIFTED label cancel
 * too (the same fare reworded is the same fare — this is what
 * makes two differently-worded summaries with the same numbers
 * a NO CHANGE); leftovers pair up under a matching label as
 * changes; a single leftover on each side pairs as one change;
 * anything else is reported as "new:" / "gone:". A run whose
 * signature has no numbers at all falls back to comparing the
 * normalized summaries for equality (see BUILD_NOTES — the
 * fallback is stated plainly there and in the card copy's
 * ancestry: it exists for numberless goals, and only there).
 *
 * Only DONE runs have a signature: signatureFromEvents returns
 * null for every other ending, and the caller (the Activity's
 * watch processing) never lets a non-done run touch the
 * baseline.
 *
 * Pure: no Android, no I/O — the host harness pins every rule,
 * including the reworded-same-numbers case that is the whole
 * point of the design.
 */
public final class WatchCompare {

    private WatchCompare() { }

    // verdict states (also the "watch" trail event's verdict).
    public static final String BASELINE = "baseline";
    public static final String NO_CHANGE = "no_change";
    public static final String CHANGED = "changed";
    public static final String NOT_DONE = "not_done";

    /** Words of label a number carries at most. */
    public static final int LABEL_WORDS = 3;

    /** The list baseline line's length cap, ellipsis included. */
    public static final int ENTRIES_LINE_CHARS = 90;

    /** One numeric value and where it was found: the normalized
     *  label window, the normalized value (grouping commas
     *  dropped, decimal point kept — RelevanceGuard.numberTokens'
     *  tokenization, so "1,117" and "1117" are the same value),
     *  and the value as written ("$698", "1,117", "10%") for
     *  display. */
    public static final class Entry {
        public final String label;
        public final String value;
        public final String raw;

        public Entry(String label, String value, String raw) {
            this.label = (label == null) ? "" : label;
            this.value = (value == null) ? "" : value;
            this.raw = (raw == null) ? this.value : raw;
        }
    }

    /** A run's comparable signature: its entries in discovery
     *  order, one per distinct value. (fromJsonList preserves
     *  whatever was stored; signatures built here are already
     *  deduplicated by signature().) */
    public static final class Signature {
        public final List<Entry> entries = new ArrayList<Entry>();

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        /** The signature as JSON-shaped data (a list of
         *  {l, v, r} maps) for the baseline store. */
        public List<Object> toJsonList() {
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("l", e.label);
                m.put("v", e.value);
                m.put("r", e.raw);
                out.add(m);
            }
            return out;
        }

        /** Parse a signature back from its JSON shape. Null when
         *  the value is not a list at all (a missing or corrupt
         *  baseline has no signature); malformed entries inside
         *  a list are skipped, and an empty list is an empty —
         *  but real — signature (a numberless baseline). */
        public static Signature fromJsonList(Object o) {
            if (!(o instanceof List)) return null;
            Signature sig = new Signature();
            List<?> arr = (List<?>) o;
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (!(item instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) item;
                Object v = m.get("v");
                if (!(v instanceof String)) continue;
                Object l = m.get("l");
                Object r = m.get("r");
                sig.entries.add(new Entry(
                        (l instanceof String) ? (String) l : "",
                        (String) v,
                        (r instanceof String) ? (String) r : (String) v));
            }
            return sig;
        }
    }

    /** The verdict of one comparison. diffLines is empty unless
     *  the state is CHANGED. */
    public static final class Verdict {
        public final String state;
        public final List<String> diffLines;

        public Verdict(String state, List<String> diffLines) {
            this.state = state;
            this.diffLines = diffLines;
        }

        public boolean changed() {
            return CHANGED.equals(state);
        }
    }

    // ---------------- signature extraction ----------------

    /**
     * The signature of a finished run: the numbers in the noted
     * facts (in note order) followed by the numbers in the done
     * summary, deduplicated to one entry per distinct value
     * (the first sighting wins — see the class doc for why).
     * Facts are scanned one text at a time — a label window
     * never spans two facts.
     */
    public static Signature signature(List<String> factTexts,
                                      String summary) {
        Signature sig = new Signature();
        if (factTexts != null) {
            for (int i = 0; i < factTexts.size(); i++) {
                scanInto(sig, factTexts.get(i));
            }
        }
        scanInto(sig, summary);
        Set<String> seenValues = new LinkedHashSet<String>();
        for (int i = 0; i < sig.entries.size();) {
            if (seenValues.add(sig.entries.get(i).value)) {
                i++;
            } else {
                sig.entries.remove(i);
            }
        }
        return sig;
    }

    /**
     * The signature of a finished run from its trail events, or
     * null when the run did not end done — only done runs have a
     * signature, and a non-done run must never reach the
     * baseline. The fact extraction mirrors AnswerCard's: a
     * "note" event counts when its status is absent or
     * Scratchpad.ADDED, and identical fact texts count once
     * (the notebook itself never holds the same text twice).
     */
    public static Signature signatureFromEvents(
            List<Map<String, Object>> events) {
        String summary = doneSummaryFromEvents(events);
        if (summary == null) return null;
        List<String> facts = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if (!"note".equals(str(ev.get("type")))) continue;
            String status = str(ev.get("status"));
            if (status.length() > 0 && !Scratchpad.ADDED.equals(status)) {
                continue;
            }
            String fact = str(ev.get("fact"));
            if (fact.length() == 0 || !seen.add(fact)) continue;
            facts.add(fact);
        }
        return signature(facts, summary);
    }

    /** The done summary a trail records, or null when the run's
     *  stop was any other ending (or there is no stop). */
    public static String doneSummaryFromEvents(
            List<Map<String, Object>> events) {
        if (events == null) return null;
        Map<String, Object> stopEv = null;
        for (int i = 0; i < events.size(); i++) {
            if ("stop".equals(str(events.get(i).get("type")))) {
                stopEv = events.get(i);
            }
        }
        if (stopEv == null) return null;
        if (!"done".equals(str(stopEv.get("reason")))) return null;
        return str(stopEv.get("summary"));
    }

    /** One text's numbers, appended to the signature: digit runs
     *  tokenized exactly as RelevanceGuard.numberTokens does
     *  (grouping commas dropped, a decimal point between digits
     *  kept), the raw form keeping an attached currency symbol
     *  or trailing %, and the label taken from the words between
     *  the previous number and this one. */
    private static void scanInto(Signature sig, String text) {
        if (text == null || text.length() == 0) return;
        int segStart = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') { i++; continue; }
            int start = i;
            StringBuilder tok = new StringBuilder();
            boolean dot = false;
            while (i < text.length()) {
                char d = text.charAt(i);
                if (d >= '0' && d <= '9') {
                    tok.append(d);
                    i++;
                } else if (d == ',' && i + 1 < text.length()
                        && text.charAt(i + 1) >= '0'
                        && text.charAt(i + 1) <= '9') {
                    i++;
                } else if (d == '.' && i + 1 < text.length()
                        && text.charAt(i + 1) >= '0'
                        && text.charAt(i + 1) <= '9' && !dot) {
                    tok.append(d);
                    dot = true;
                    i++;
                } else {
                    break;
                }
            }
            int end = i;
            String raw = text.substring(start, end);
            if (start > 0 && isCurrency(text.charAt(start - 1))) {
                raw = text.charAt(start - 1) + raw;
            }
            if (end < text.length() && text.charAt(end) == '%') {
                raw = raw + "%";
            }
            String label = labelWindow(text.substring(segStart, start));
            sig.entries.add(new Entry(label, tok.toString(), raw));
            segStart = end;
        }
    }

    private static boolean isCurrency(char c) {
        return c == '$' || c == '€' || c == '£';
    }

    /** The label of a number: the last LABEL_WORDS words of its
     *  segment, normalized — lowercased, stripped of surrounding
     *  punctuation, whitespace collapsed by construction. A word
     *  containing a digit is dropped: it belongs to some earlier
     *  value, not to this one's name. */
    public static String labelWindow(String segment) {
        if (segment == null) return "";
        String[] words = segment.split("\\s+");
        List<String> kept = new ArrayList<String>();
        for (int i = 0; i < words.length; i++) {
            String w = normalizeWord(words[i]);
            if (w.length() == 0) continue;
            boolean hasDigit = false;
            for (int j = 0; j < w.length(); j++) {
                char c = w.charAt(j);
                if (c >= '0' && c <= '9') { hasDigit = true; break; }
            }
            if (!hasDigit) kept.add(w);
        }
        int from = Math.max(0, kept.size() - LABEL_WORDS);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < kept.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(kept.get(i));
        }
        return sb.toString();
    }

    /** One word, label-normalized: lowercase, leading and
     *  trailing punctuation stripped (interior apostrophes and
     *  hyphens survive — "tonight's" stays "tonight's"). */
    public static String normalizeWord(String word) {
        if (word == null) return "";
        String w = word.toLowerCase();
        int a = 0;
        int b = w.length();
        while (a < b && !isWordChar(w.charAt(a))) a++;
        while (b > a && !isWordChar(w.charAt(b - 1))) b--;
        return w.substring(a, b);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '-';
    }

    /** The numberless fallback's text form: lowercase, every
     *  run of non-alphanumerics collapsed to one space, trimmed.
     *  Two summaries equal in this form count as the same answer
     *  however they were punctuated. */
    public static String normalizeText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean pendingSpace = false;
        String lower = s.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && sb.length() > 0) sb.append(' ');
                pendingSpace = false;
                sb.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return sb.toString();
    }

    // ---------------- comparison ----------------

    /**
     * Compare a baseline against a fresh signature. A null
     * baseline (no watched done run yet) yields BASELINE — the
     * caller stores the fresh signature and reports the
     * baseline being set, never a change.
     */
    public static Verdict compare(Signature baseline,
                                  String baselineSummary,
                                  Signature sig, String summary) {
        if (baseline == null) {
            return new Verdict(BASELINE, new ArrayList<String>());
        }
        if (sig == null) sig = new Signature();
        // The numberless fallback: neither side carries a value
        // to compare, so the summaries themselves decide.
        if (baseline.isEmpty() && sig.isEmpty()) {
            if (normalizeText(baselineSummary)
                    .equals(normalizeText(summary))) {
                return new Verdict(NO_CHANGE, new ArrayList<String>());
            }
            List<String> lines = new ArrayList<String>();
            lines.add("answer changed: " + displaySummary(summary));
            return new Verdict(CHANGED, lines);
        }

        List<Entry> oldLeft = new ArrayList<Entry>(baseline.entries);
        List<Entry> newLeft = new ArrayList<Entry>(sig.entries);
        // Phase 1: identical (label, value) pairs cancel.
        cancel(oldLeft, newLeft, true);
        // Phase 2: a value that survives under a drifted label
        // cancels too — the rewording shield.
        cancel(oldLeft, newLeft, false);
        if (oldLeft.isEmpty() && newLeft.isEmpty()) {
            return new Verdict(NO_CHANGE, new ArrayList<String>());
        }
        List<String> lines = new ArrayList<String>();
        // Phase 3: leftovers sharing a label pair up as changes,
        // labels in first-appearance order (baseline side first).
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < oldLeft.size(); i++) {
            if (!labels.contains(oldLeft.get(i).label)) {
                labels.add(oldLeft.get(i).label);
            }
        }
        for (int i = 0; i < newLeft.size(); i++) {
            if (!labels.contains(newLeft.get(i).label)) {
                labels.add(newLeft.get(i).label);
            }
        }
        for (int li = 0; li < labels.size(); li++) {
            String label = labels.get(li);
            List<Entry> olds = takeLabel(oldLeft, label);
            List<Entry> news = takeLabel(newLeft, label);
            int pairs = Math.min(olds.size(), news.size());
            for (int i = 0; i < pairs; i++) {
                lines.add(changeLine(label, olds.get(i), news.get(i)));
            }
            for (int i = pairs; i < olds.size(); i++) {
                oldLeft.add(olds.get(i));
            }
            for (int i = pairs; i < news.size(); i++) {
                newLeft.add(news.get(i));
            }
        }
        // Phase 4: one leftover on each side is one change whose
        // label drifted — pair it rather than crying gone + new.
        if (oldLeft.size() == 1 && newLeft.size() == 1) {
            Entry o = oldLeft.remove(0);
            Entry n = newLeft.remove(0);
            String label = o.label.length() > 0 ? o.label : n.label;
            lines.add(changeLine(label, o, n));
        }
        for (int i = 0; i < oldLeft.size(); i++) {
            lines.add("gone: " + named(oldLeft.get(i)));
        }
        for (int i = 0; i < newLeft.size(); i++) {
            lines.add("new: " + named(newLeft.get(i)));
        }
        return new Verdict(CHANGED, lines);
    }

    /** Remove matched pairs from both leftover lists. With
     *  byLabel, a pair needs the same label and value; without,
     *  the same value suffices. Each old entry removes at most
     *  one new entry. */
    private static void cancel(List<Entry> oldLeft, List<Entry> newLeft,
                               boolean byLabel) {
        for (int i = 0; i < oldLeft.size();) {
            Entry o = oldLeft.get(i);
            int hit = -1;
            for (int j = 0; j < newLeft.size(); j++) {
                Entry n = newLeft.get(j);
                if (o.value.equals(n.value)
                        && (!byLabel || o.label.equals(n.label))) {
                    hit = j;
                    break;
                }
            }
            if (hit >= 0) {
                oldLeft.remove(i);
                newLeft.remove(hit);
            } else {
                i++;
            }
        }
    }

    /** Drain and return one label's entries from a leftover
     *  list, in order. */
    private static List<Entry> takeLabel(List<Entry> left, String label) {
        List<Entry> out = new ArrayList<Entry>();
        for (int i = 0; i < left.size();) {
            if (left.get(i).label.equals(label)) {
                out.add(left.remove(i));
            } else {
                i++;
            }
        }
        return out;
    }

    private static String changeLine(String label, Entry o, Entry n) {
        String head = (label == null || label.length() == 0)
                ? "" : label + ": ";
        return head + o.raw + " -> " + n.raw;
    }

    private static String named(Entry e) {
        return e.label.length() == 0 ? e.raw : e.label + " " + e.raw;
    }

    /** A summary as one display line for the fallback's diff:
     *  whitespace collapsed, capped at 80 chars. */
    private static String displaySummary(String summary) {
        if (summary == null) return "(no summary)";
        String s = summary.replaceAll("\\s+", " ").trim();
        if (s.length() == 0) return "(no summary)";
        if (s.length() > 80) s = s.substring(0, 79) + "…";
        return s;
    }

    // ---------------- the baseline line (saved-goals list) ----------------

    /**
     * The one-line account of a baseline signature for the
     * saved-goals list: "Kahului $698, Honolulu $734" — each
     * distinct (label, value) once, label first (capitalized),
     * raw form for the value, capped at ENTRIES_LINE_CHARS.
     * "" for an empty signature (the caller falls back to the
     * baseline summary there — a numberless check).
     */
    public static String entriesLine(Signature sig) {
        if (sig == null || sig.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Set<String> shown = new LinkedHashSet<String>();
        for (int i = 0; i < sig.entries.size(); i++) {
            Entry e = sig.entries.get(i);
            if (!shown.add(e.label + "" + e.value)) continue;
            String part = e.label.length() == 0
                    ? e.raw
                    : capitalize(e.label) + " " + e.raw;
            if (sb.length() > 0) {
                if (sb.length() + 2 + part.length()
                        > ENTRIES_LINE_CHARS - 1) {
                    sb.append(", …");
                    break;
                }
                sb.append(", ");
            } else if (part.length() > ENTRIES_LINE_CHARS) {
                part = part.substring(0, ENTRIES_LINE_CHARS - 1) + "…";
            }
            sb.append(part);
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s.length() == 0) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }
}
