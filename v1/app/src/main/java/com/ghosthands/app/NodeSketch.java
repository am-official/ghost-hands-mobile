package com.ghosthands.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.14 (Everything Train R6 — the accessibility spike): the
 * pure adapter that turns a foreign app's accessibility tree
 * (A11yNode) into a SKETCH in the same numbered-map discipline
 * the browser perception uses (Eyes.renderElements): entries
 * numbered in walk order, one line each, a hard entry cap and
 * char budget, and a "... truncated: K more node(s) not shown"
 * accounting line when the tree outgrows the budget. A future
 * planner would read a page of another app through this shape,
 * so the discipline matters more than the decoration.
 *
 * THE REDACTION DISCIPLINE IS HARD HERE, not advisory:
 *  - a node flagged password contributes NO text and NO
 *    description, ever — the entry's label is null and the
 *    rendered line says only that text was withheld. The
 *    adapter judges the FLAG, not the content, so even a tree
 *    whose converter misbehaved (a password node still carrying
 *    text) renders clean;
 *  - a node under a secure ancestor (a tree captured with the
 *    keyguard up) likewise contributes no text: the walk
 *    carries the secure flag DOWN, so a child whose own flag
 *    was never stamped is still withheld;
 *  - a withheld node's bounds and flags still render — where
 *    the field sits is evidence; what it holds is not ours.
 *
 * The walk is ITERATIVE (an explicit stack), pre-order like
 * the platform's own child order, and capped three ways: the
 * entries cap, the char budget, and a visited cap so a
 * pathological tree cannot keep the walk (or the caller)
 * busy forever. Numbers are the sketch's own and stable for
 * the life of the Result: tapTarget resolves a number back to
 * the screen point a gesture would aim at.
 *
 * Pure: no Android. GhostA11yService converts the platform
 * tree into A11yNode; MainActivity renders Results and arms
 * taps from tapTarget's points. Nothing here is reachable
 * from the browser agent: the spike shares no code path with
 * live runs.
 */
public final class NodeSketch {

    private NodeSketch() { }

    /** The rendered-sketch bounds (compare the browser map's
     *  40 elements / 1500 chars: a diagnostic readout may run
     *  a little larger, but it is still a bounded sketch, not
     *  a tree dump). */
    public static final int MAX_ENTRIES = 60;
    public static final int MAX_SKETCH_CHARS = 4000;
    /** The walk's own bound: nodes examined, rendered or not. */
    public static final int MAX_VISITED = 4096;
    /** A node's label cap — the extraction cap the browser
     *  eyes have always used (48). */
    public static final int LABEL_CAP = 48;

    /** One rendered node of the sketch. */
    public static class Entry {
        public final int number;
        public final int depth;
        public final A11yNode node;
        /** The label the line shows, or null when the node
         *  carries none — or when its text was withheld. */
        public final String label;
        /** True when text exists (or might) but the
         *  discipline withheld it: a password node, or any
         *  node under a secure ancestor. */
        public final boolean withheld;

        Entry(int number, int depth, A11yNode node,
              String label, boolean withheld) {
            this.number = number;
            this.depth = depth;
            this.node = node;
            this.label = label;
            this.withheld = withheld;
        }

        /** The screen point a tap on this entry would aim
         *  at — the node's bounds centre — or null when the
         *  node is not a tap target (not clickable, not
         *  visible, or zero-sized). A password node CAN be a
         *  tap target: aiming at a field reveals nothing the
         *  field holds. */
        public int[] tapPoint() {
            if (node == null || !node.clickable || !node.visible) {
                return null;
            }
            if (node.width() <= 0 || node.height() <= 0) return null;
            return new int[]{node.centerX(), node.centerY()};
        }
    }

    /** The sketch of one captured tree. */
    public static class Result {
        public final String packageName;
        public final List<Entry> entries;
        /** Nodes the walk visited — the tree's size, unless
         *  visitedCapped stopped the walk early. */
        public final int totalNodes;
        public final boolean visitedCapped;
        /** Visited nodes the sketch did not render (past the
         *  entry cap or the char budget). */
        public final int omitted;
        /** Password nodes among the visited, rendered or not. */
        public final int passwordNodes;
        /** Entries whose text the discipline withheld. */
        public final int withheldCount;

        Result(String packageName, List<Entry> entries,
               int totalNodes, boolean visitedCapped, int omitted,
               int passwordNodes, int withheldCount) {
            this.packageName = packageName;
            this.entries = entries;
            this.totalNodes = totalNodes;
            this.visitedCapped = visitedCapped;
            this.omitted = omitted;
            this.passwordNodes = passwordNodes;
            this.withheldCount = withheldCount;
        }

        /** The entry with the given sketch number, or null. */
        public Entry entry(int number) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).number == number) {
                    return entries.get(i);
                }
            }
            return null;
        }

        /** The screen point a tap on sketch number `number`
         *  would aim at, or null (see Entry.tapPoint). */
        public int[] tapTarget(int number) {
            Entry e = entry(number);
            return (e == null) ? null : e.tapPoint();
        }

        /** The entries a tap could be dispatched at, in
         *  sketch order. */
        public List<Entry> clickables() {
            List<Entry> out = new ArrayList<Entry>();
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).tapPoint() != null) out.add(entries.get(i));
            }
            return out;
        }

        /** The sketch text: one line per entry, in the map
         *  discipline, plus the truncation accounting line
         *  when the tree outgrew the budget. */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                sb.append(line(entries.get(i))).append('\n');
            }
            if (omitted > 0) {
                sb.append("... truncated: ").append(omitted)
                        .append(" more node(s) not shown\n");
            }
            return sb.toString();
        }
    }

    /** One walk frame: the node, its depth, and whether a
     *  secure ancestor already withholds its text. */
    private static class Frame {
        final A11yNode node;
        final int depth;
        final boolean secure;

        Frame(A11yNode node, int depth, boolean secure) {
            this.node = node;
            this.depth = depth;
            this.secure = secure;
        }
    }

    /** Sketch a captured tree. A null root sketches empty
     *  (the capture says why on its own line). */
    public static Result sketch(String packageName, A11yNode root) {
        List<Entry> entries = new ArrayList<Entry>();
        int visited = 0;
        int omitted = 0;
        int passwordNodes = 0;
        int withheldCount = 0;
        boolean capped = false;
        boolean budgetFull = false;
        int used = 0;
        if (root != null) {
            ArrayDeque<Frame> stack = new ArrayDeque<Frame>();
            stack.push(new Frame(root, 0, false));
            while (!stack.isEmpty()) {
                if (visited >= MAX_VISITED) {
                    capped = true;
                    break;
                }
                Frame f = stack.pop();
                A11yNode n = f.node;
                boolean secure = f.secure || n.secure;
                visited++;
                if (n.password) passwordNodes++;
                boolean withheld = n.password || secure;
                // Children ride in reverse so the pop order is
                // the platform's child order.
                for (int i = n.children.size() - 1; i >= 0; i--) {
                    stack.push(new Frame(n.children.get(i),
                            f.depth + 1, secure));
                }
                if (budgetFull || entries.size() >= MAX_ENTRIES) {
                    omitted++;
                    continue;
                }
                String label = withheld ? null : labelOf(n);
                Entry e = new Entry(entries.size() + 1, f.depth, n,
                        label, withheld);
                String line = line(e);
                if (used + line.length() + 1 > MAX_SKETCH_CHARS) {
                    budgetFull = true;
                    omitted++;
                    continue;
                }
                entries.add(e);
                used += line.length() + 1;
                if (withheld) withheldCount++;
            }
        }
        return new Result(packageName, entries, visited, capped,
                omitted, passwordNodes, withheldCount);
    }

    /** The label a node shows: its text, else its content
     *  description, else nothing — capped at LABEL_CAP. Never
     *  called for a withheld node. */
    static String labelOf(A11yNode n) {
        String s = (n.text != null && n.text.length() > 0)
                ? n.text : n.desc;
        if (s == null || s.length() == 0) return null;
        return (s.length() <= LABEL_CAP)
                ? s : s.substring(0, LABEL_CAP);
    }

    /** The rendered line for one entry, in the browser map's
     *  shape: [n] <Class> "label" flags bounds=L,T-R,B. */
    static String line(Entry e) {
        A11yNode n = e.node;
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(e.number).append("] <")
                .append(shortClass(n.className)).append('>');
        if (e.withheld) {
            sb.append(n.password
                    ? " (password — text withheld)"
                    : " (secure window — text withheld)");
        } else if (e.label != null) {
            sb.append(" \"").append(e.label).append('"');
        }
        if (n.clickable) sb.append(" clickable");
        if (n.longClickable) sb.append(" long-clickable");
        if (n.editable) sb.append(" editable");
        if (n.checkable) {
            sb.append(" checkable");
            if (n.checked) sb.append(" checked");
        }
        if (n.focused) sb.append(" focused");
        if (!n.enabled) sb.append(" disabled");
        if (!n.visible) sb.append(" hidden");
        sb.append(" bounds=").append(n.left).append(',')
                .append(n.top).append('-').append(n.right)
                .append(',').append(n.bottom);
        return sb.toString();
    }

    /** "android.widget.Button" -> "Button"; "" -> "(no class)". */
    static String shortClass(String className) {
        if (className == null || className.length() == 0) {
            return "(no class)";
        }
        int dot = className.lastIndexOf('.');
        return (dot >= 0 && dot + 1 < className.length())
                ? className.substring(dot + 1) : className;
    }
}
