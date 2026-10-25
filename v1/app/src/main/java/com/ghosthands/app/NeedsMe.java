package com.ghosthands.app;

/**
 * v3.6 (ALERTS setting — needs-me notifications): the whole
 * decision, factored pure so the harness can pin it.
 *
 * A run waiting on the user is easy to miss when the phone has
 * been put down: the approval panel and the co-pilot pause panel
 * sit on the Agent screen, burning their timeouts in a pocket.
 * When the setting is on, a wait that begins while the app is
 * NOT in the foreground also posts a notification (Alerts)
 * whose tap brings the app forward to the waiting panel.
 *
 * The decision is deliberately a single conjunction of three
 * legs, and any leg false means silence:
 *   - the setting is on (default OFF — through v3.5, nothing
 *     ever notified about a wait);
 *   - a wait is actually pending (the only callers are the two
 *     wait entry points — a governor approval, a co-pilot
 *     rescue offer — so this leg is true by construction there;
 *     it exists so the rule reads whole and tests can pin it);
 *   - the app is in the background (a foreground wait never
 *     notifies: the panel is already on screen, and a
 *     notification over it would be noise).
 *
 * The decision is taken when the wait BEGINS. A wait that starts
 * in the foreground and is then carried into a pocket does not
 * retro-notify — the panel was seen; the timeout is the pack's
 * own discipline, unchanged.
 */
public final class NeedsMe {

    private NeedsMe() { }

    /** True exactly when a needs-me notification should post. */
    public static boolean shouldNotify(boolean settingOn,
                                       boolean waiting,
                                       boolean inForeground) {
        return settingOn && waiting && !inForeground;
    }
}
