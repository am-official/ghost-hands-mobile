package com.ghosthands.app;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.14 (Everything Train R6 — the accessibility spike): one
 * node of a FOREIGN app's accessibility tree, copied OUT of the
 * platform's AccessibilityNodeInfo into this plain shape by
 * GhostA11yService, so the copy can be reasoned about (and
 * tested) on the host with no Android in the room.
 *
 * The copy is deliberately field-for-field with what the
 * platform exposes: the node's class, its text and content
 * description, its bounds in screen pixels, and the action
 * flags the tree itself declares (clickable, editable,
 * password, ...). Nothing is inferred and nothing is enriched:
 * the spike's question is what the tree ACTUALLY exposes, so
 * this shape must not pretend to know more than the tree said.
 *
 * The password discipline starts HERE, at the glass: the
 * converter (GhostA11yService) never copies a password node's
 * text or description into this shape at all, so a node with
 * password=true carries nulls in text/desc no matter what the
 * platform would have handed over. NodeSketch redacts AGAIN at
 * render time — two layers, either of which alone suffices,
 * because this is the one rule the spike may not get wrong.
 *
 * `secure` marks a tree captured while the keyguard was up
 * (the converter stamps it on every node of such a tree): a
 * secure tree's bounds and classes are evidence, its text is
 * withheld by the adapter. It is an analogue, not the platform
 * FLAG_SECURE — that flag is not visible to accessibility
 * services at all, which the R6 report states plainly.
 */
public final class A11yNode {

    /** The node's class name as the platform reported it
     *  (e.g. "android.widget.Button"); "" when it said none. */
    public String className = "";

    /** The node's visible text, or null when it carries none —
     *  or when it was withheld (password node, see class doc). */
    public String text;

    /** The node's content description, or null — same rule. */
    public String desc;

    /** The node's view-id resource name (e.g.
     *  "com.example:id/ok"), or null. Carried as evidence of
     *  what the tree exposes; the sketch does not render it. */
    public String viewId;

    /** Bounds in screen pixels, as the platform reported. */
    public int left, top, right, bottom;

    public boolean clickable;
    public boolean longClickable;
    public boolean editable;
    public boolean password;
    public boolean checkable;
    public boolean checked;
    public boolean enabled;
    public boolean focused;
    public boolean visible;

    /** True when this node belongs to a tree captured under a
     *  locked keyguard: the adapter withholds its text. */
    public boolean secure;

    public final List<A11yNode> children = new ArrayList<A11yNode>();

    public int width() { return right - left; }

    public int height() { return bottom - top; }

    /** The tap point a gesture would aim at: the bounds'
     *  centre, in the platform's own pixel space. */
    public int centerX() { return (left + right) / 2; }

    public int centerY() { return (top + bottom) / 2; }
}
