package com.ghosthands.app;

/**
 * v3.8 (ALERTS): watch quiet hours, pure.
 *
 * The window is named by two hours of day (0-23): the start
 * hour is IN the window, the end hour is OUT of it, and a
 * window whose start is after its end crosses midnight (23 to
 * 7 is the default: 23:00 through 06:59). A window whose start
 * equals its end is EMPTY — it quiets nothing; a user who
 * wants no quiet hours turns the switch off, and an accidental
 * equal pair must not silence a whole day.
 *
 * The effect is deferral, never delivery: during the window a
 * watched check still runs, its verdict is still judged and
 * recorded in its trail, but a CHANGED verdict raises no
 * notification and no buzz. The change waits on the run's
 * answer card, whose watch banner notes it arrived during
 * quiet hours (the trail's "watch" event carries the flag
 * this class's decision produced). Nothing fires later when
 * the window ends — the deferred notification is never sent.
 * NO_CHANGE stays silent as it always has, in or out of the
 * window.
 */
public final class QuietHours {

    private QuietHours() { }

    /** Is hourOfDay (0-23) inside the window [startHour,
     *  endHour)? Correct across midnight; an out-of-range hour
     *  or an empty window (start == end) is never inside. */
    public static boolean inWindow(int hourOfDay, int startHour,
                                   int endHour) {
        if (hourOfDay < 0 || hourOfDay > 23) return false;
        int s = clampHour(startHour);
        int e = clampHour(endHour);
        if (s == e) return false;
        if (s < e) return hourOfDay >= s && hourOfDay < e;
        return hourOfDay >= s || hourOfDay < e;
    }

    /** The deferral decision: the setting is on AND the verdict
     *  is a change AND the moment falls in the window. Anything
     *  less is the standing behaviour (a change alerts at once). */
    public static boolean shouldDefer(boolean settingOn,
                                      boolean changedVerdict,
                                      int hourOfDay, int startHour,
                                      int endHour) {
        if (!settingOn) return false;
        if (!changedVerdict) return false;
        return inWindow(hourOfDay, startHour, endHour);
    }

    /** Clamp an hour-of-day into 0-23 (the Settings model's
     *  stored hours are clamped the same way). */
    public static int clampHour(int h) {
        if (h < 0) return 0;
        if (h > 23) return 23;
        return h;
    }
}
