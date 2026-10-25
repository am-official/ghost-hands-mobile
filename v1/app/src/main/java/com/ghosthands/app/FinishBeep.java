package com.ghosthands.app;

/**
 * v3.8 (ALERTS): the finish beep's firing decision, pure.
 *
 * A run ending beeps exactly when the setting is on AND the run
 * actually ended (it produced its finish handling — a card)
 * AND the ending is not one the app has promised to keep
 * silent: a watched run's NO_CHANGE (silence is that feature's
 * whole point) or a CHANGED verdict deferred by quiet hours
 * (the deferral covers every alert surface, the beep included).
 * Foreground or background makes no difference — the beep is
 * the on-device sound at the finish moment, where the buzz and
 * the notification are the other two surfaces. ONE beep per
 * ending: the caller fires this decision once, in the finish
 * path, and nowhere else.
 */
public final class FinishBeep {

    private FinishBeep() { }

    public static boolean shouldBeep(boolean settingOn, boolean runEnded,
                                     boolean silenced) {
        return settingOn && runEnded && !silenced;
    }
}
