package com.ghosthands.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * v3.14 (Everything Train R6 — the accessibility spike): the
 * declared AccessibilityService, and the ONLY Android-bound
 * piece of the spike. It exists to answer two questions with
 * evidence: can this app read the foreground app's node tree,
 * and can it dispatch a tap?
 *
 * THE FENCE, stated in the class that has to keep it:
 *  - The service is INERT unless the USER enables it in
 *    Android Settings (Accessibility > Installed services).
 *    Android owns that consent: the system shows what the
 *    service could do and the user decides. Nothing in this
 *    app can enable it, prompt-engineer it on, or detect-and-
 *    nag about it (one explanatory line in Settings is the
 *    whole of the app's advocacy).
 *  - Even enabled, the service DOES NOTHING ON ITS OWN.
 *    onAccessibilityEvent is empty: no event is recorded, no
 *    tree is read, no gesture is dispatched except when
 *    MainActivity's two probes call captureForeground() /
 *    tapAt() — and the Activity calls those only when
 *    SpikeGate.probesAllowed says BOTH gates are on (service
 *    connected AND the experimental toggle), re-checked at
 *    the moment each probe fires.
 *  - The service never stores a platform node. capture
 *    converts the tree into A11yNode (plain data) on the
 *    spot and recycles every AccessibilityNodeInfo it
 *    touched.
 *  - The password discipline starts at this glass: a
 *    password node's text and content description are NOT
 *    copied into A11yNode — they leave this method as nulls
 *    no matter what the platform handed over. NodeSketch
 *    redacts again at render; either layer alone suffices.
 *  - A tree captured while the keyguard is locked is stamped
 *    secure on every node, and the adapter withholds its
 *    text wholesale: the lock screen is not a perception
 *    source.
 *
 * Conversion bounds: at most MAX_CONVERT_NODES nodes and
 * MAX_CONVERT_DEPTH levels are copied — a real app tree is
 * far inside both, and a pathological one degrades to a
 * partial copy the Capture says is partial (convertedCapped).
 */
public class GhostA11yService extends AccessibilityService {

    static final int MAX_CONVERT_NODES = 512;
    static final int MAX_CONVERT_DEPTH = 64;

    /** The connected instance, or null. Set by the platform
     *  through onServiceConnected; the Activity reads it as
     *  the "service" half of SpikeGate. Volatile: the binding
     *  lives and dies on the system's thread. */
    private static volatile GhostA11yService instance;

    public static GhostA11yService instance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /** Deliberately empty — see the class doc. The config
     *  subscribes to window-state changes because Android
     *  asks a service to name its interests; the spike pulls
     *  on demand and consumes no event. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    /** One capture of the foreground window. */
    public static class Capture {
        /** The foreground app's package, or null when no
         *  active window could be read. */
        public String packageName;
        /** The converted tree's root, or null (see above). */
        public A11yNode root;
        /** Platform nodes visited during conversion. */
        public int visited;
        /** True when a conversion bound stopped the walk. */
        public boolean capped;
        /** True when the keyguard was locked at capture. */
        public boolean keyguardLocked;
    }

    /** Capture the active window's tree, converted to
     *  A11yNode. Runs on the caller's thread; the conversion
     *  is a bounded copy of at most MAX_CONVERT_NODES small
     *  records. */
    public Capture captureForeground() {
        Capture c = new Capture();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            root = null;
        }
        if (root == null) return c;
        try {
            CharSequence pkg = root.getPackageName();
            c.packageName = (pkg == null) ? null : pkg.toString();
            c.keyguardLocked = isKeyguardLocked();
            int[] budget = new int[]{MAX_CONVERT_NODES};
            c.root = convert(root, 0, c.keyguardLocked, budget, c);
        } finally {
            root.recycle();
        }
        return c;
    }

    private boolean isKeyguardLocked() {
        try {
            KeyguardManager km = (KeyguardManager)
                    getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** Copy one platform node (and its subtree, within the
     *  bounds) into the plain shape. The caller recycles
     *  `info`; this method recycles every child it obtains. */
    private A11yNode convert(AccessibilityNodeInfo info, int depth,
                             boolean secure, int[] budget,
                             Capture c) {
        A11yNode n = new A11yNode();
        c.visited++;
        budget[0]--;
        CharSequence cls = info.getClassName();
        n.className = (cls == null) ? "" : cls.toString();
        // THE PASSWORD RULE, at the glass: a password node's
        // text and description are never copied — not masked,
        // not truncated, absent. (What the platform returns
        // for such a node is the field's own business.)
        if (info.isPassword()) {
            n.password = true;
            n.text = null;
            n.desc = null;
        } else {
            CharSequence t = info.getText();
            n.text = (t == null) ? null : t.toString();
            CharSequence d = info.getContentDescription();
            n.desc = (d == null) ? null : d.toString();
        }
        n.viewId = info.getViewIdResourceName();
        Rect r = new Rect();
        info.getBoundsInScreen(r);
        n.left = r.left;
        n.top = r.top;
        n.right = r.right;
        n.bottom = r.bottom;
        n.clickable = info.isClickable();
        n.longClickable = info.isLongClickable();
        n.editable = info.isEditable();
        n.checkable = info.isCheckable();
        n.checked = info.isChecked();
        n.enabled = info.isEnabled();
        n.focused = info.isFocused();
        n.visible = info.isVisibleToUser();
        n.secure = secure;
        if (depth >= MAX_CONVERT_DEPTH) {
            if (info.getChildCount() > 0) c.capped = true;
            return n;
        }
        int kids = info.getChildCount();
        for (int i = 0; i < kids; i++) {
            if (budget[0] <= 0) {
                c.capped = true;
                break;
            }
            AccessibilityNodeInfo child = null;
            try {
                child = info.getChild(i);
            } catch (Exception e) {
                child = null;
            }
            if (child == null) continue;
            try {
                n.children.add(convert(child, depth + 1, secure,
                        budget, c));
            } finally {
                child.recycle();
            }
        }
        return n;
    }

    /** The tap probe's answer channel: exactly one of the two
     *  methods fires, on the platform's callback thread. */
    public interface TapCallback {
        /** The gesture was injected. This confirms delivery,
         *  NOT effect: the platform offers no way to learn
         *  what the tap hit. */
        void onCompleted();
        /** The gesture was cancelled (another gesture, a
         *  window change, the service being interrupted). */
        void onCancelled();
    }

    /** Dispatch ONE tap at screen point (x, y) — a single
     *  short stroke, no drag, no second contact. Returns the
     *  platform's own boolean: true when the gesture was
     *  accepted for dispatch (the callback then reports its
     *  fate), false when the service refused it outright
     *  (not connected, gestures not permitted by the config,
     *  or an invalid description). */
    public boolean tapAt(int x, int y, final TapCallback cb) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription desc;
        try {
            desc = new GestureDescription.Builder()
                    .addStroke(new GestureDescription
                            .StrokeDescription(path, 0, 80))
                    .build();
        } catch (Exception e) {
            return false;
        }
        try {
            return dispatchGesture(desc,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(
                                GestureDescription d) {
                            if (cb != null) cb.onCompleted();
                        }

                        @Override
                        public void onCancelled(
                                GestureDescription d) {
                            if (cb != null) cb.onCancelled();
                        }
                    }, null);
        } catch (Exception e) {
            return false;
        }
    }
}
