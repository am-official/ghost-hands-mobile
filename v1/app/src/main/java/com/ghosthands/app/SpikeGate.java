package com.ghosthands.app;

/**
 * v3.14 (Everything Train R6 — the accessibility spike): the
 * gate the whole spike stands behind, kept pure so the harness
 * pins its truth table.
 *
 * TWO gates, BOTH required, because they answer different
 * questions:
 *  - the accessibility SERVICE is enabled by the user, in
 *    Android's own settings, outside the app's power entirely
 *    ("the platform's owner said yes, once, in the system's
 *    own consent surface") — the app can observe the resulting
 *    connection but can never create it;
 *  - the experimental TOGGLE in Settings > DIAGNOSTICS is on
 *    ("the user wants the spike's probes live right now").
 *
 * Either one alone runs nothing: a connected service with the
 * toggle off is inert (the service class holds no behaviour
 * of its own — it answers the Activity's probe calls, and the
 * Activity makes none), and a toggle on with no service is a
 * readout line, not a capability. Every probe path in
 * MainActivity consults probesAllowed at the moment it runs,
 * so disabling either gate mid-experiment stops the next
 * probe, not just the next launch.
 */
public final class SpikeGate {

    private SpikeGate() { }

    /** The whole gate: both halves, at the moment of asking. */
    public static boolean probesAllowed(boolean serviceConnected,
                                        boolean toggleOn) {
        return serviceConnected && toggleOn;
    }

    /** The spike area's state line for the four combinations —
     *  what is missing, named, never a nag (it is shown where
     *  the user went looking for the state, and nowhere else). */
    public static String gateLine(boolean serviceConnected,
                                  boolean toggleOn) {
        if (serviceConnected && toggleOn) {
            return "Spike armed: the service is connected and "
                    + "the experimental toggle is on. The "
                    + "probes below are live.";
        }
        if (toggleOn) {
            return "The toggle is on, but the accessibility "
                    + "service is not connected — nothing can "
                    + "run until it is enabled in Android "
                    + "Settings.";
        }
        if (serviceConnected) {
            return "The accessibility service is connected, "
                    + "but the experimental toggle is off — the "
                    + "probes stay inert.";
        }
        return "Probes are inert: the accessibility service "
                + "is not enabled and the experimental toggle "
                + "is off.";
    }
}
