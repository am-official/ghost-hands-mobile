package com.ghosthands.app;

/**
 * v3.8 (AGENT): the low-battery start warning's decision, pure.
 *
 * The rule, in full: warn when the setting is on AND the battery
 * level is known AND under {@link #WARN_BELOW_PERCENT} AND the
 * phone is not charging AND the start is one the user initiated
 * (a Start tap, a route replay's Run tap, Teach — every path
 * that reaches this class is user-initiated by construction, and
 * the flag is carried so the harness pins that a non-initiated
 * start can never warn). The warning only ever ASKS: "Start
 * anyway?" proceeds exactly as the start always has, Cancel
 * starts nothing, and there is no level at which this class
 * hard-blocks a start. The Activity reads the level from the
 * sticky battery intent at Start time and owns the dialog; this
 * class owns only the judgement and the wording.
 *
 * Default ON — the one deliberate exception to the waves'
 * defaults-preserve rule (the user's battery died under this
 * app's runs twice before the setting existed). The accordion
 * copy says it can be turned off, here in AGENT.
 */
public final class BatteryGuard {

    private BatteryGuard() { }

    /** Warn strictly below this charge percent. */
    public static final int WARN_BELOW_PERCENT = 20;

    /** The whole warning decision. An unknown level (negative —
     *  the sticky intent named no level) never warns: the dialog
     *  states a number, and a number the phone did not report is
     *  not one the app may invent. */
    public static boolean shouldWarn(boolean settingOn, int levelPercent,
                                     boolean charging,
                                     boolean userInitiated) {
        if (!settingOn) return false;
        if (!userInitiated) return false;
        if (charging) return false;
        if (levelPercent < 0) return false;
        return levelPercent < WARN_BELOW_PERCENT;
    }

    /** The confirm dialog's message: the level the phone
     *  reported, the honest cost of a run, and nothing else. */
    public static String message(int levelPercent) {
        return "Battery is at " + levelPercent + "% and not "
                + "charging. Runs can take several minutes. "
                + "Start anyway?";
    }
}
