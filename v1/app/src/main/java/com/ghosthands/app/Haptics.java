package com.ghosthands.app;

/**
 * v3.8 (APPEARANCE): the button-haptics decision, pure.
 *
 * One rule, consulted by the Activity's one haptic helper
 * (maybeHapticTick): a primary action button ticks exactly
 * when the setting is on. Default OFF — through v3.7 the only
 * haptic in the app was the v2.6 Start/Stop acknowledgement
 * tick, which is run machinery (it confirms a latched state
 * change), NOT button furniture, and is deliberately not
 * gated by this setting. The helper is wired centrally — the
 * shared chip-button factory every accordion chip, switch and
 * primary button is built through, the drawer items, and the
 * confirm dialogs' positive buttons — never scattered per-view.
 */
public final class Haptics {

    private Haptics() { }

    public static boolean shouldTick(boolean settingOn) {
        return settingOn;
    }
}
