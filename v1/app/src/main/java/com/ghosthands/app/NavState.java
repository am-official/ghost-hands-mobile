package com.ghosthands.app;

/**
 * v3.5: the navigation drawer's DECISIONS, pure and host-tested.
 *
 * The layout itself is MainActivity's (screens as view groups
 * swapped by visibility, a drawer panel sliding over a scrim);
 * everything the layout must DECIDE lives here so the harness
 * can pin it: which screen is current, whether the drawer is
 * open, what a Back press does, and where a launch lands.
 *
 * The model is deliberately tiny — one current screen and one
 * drawer flag — because the navigation is FLAT (drawer item ->
 * screen, never deeper): there is no stack to model. Back is
 * resolved in a fixed order (drawer first, then home, then the
 * system), and goTo always closes the drawer, because an item
 * tap is the drawer's whole job.
 */
public final class NavState {

    // Screens (the drawer's items, in drawer order).
    public static final int AGENT = 0;
    public static final int GOALS = 1;
    public static final int ROUTES = 2;
    public static final int HISTORY = 3;
    public static final int SETTINGS = 4;
    public static final int SCREEN_COUNT = 5;

    // Back-press resolutions (back() returns one of these AND
    // applies the state change it names).
    public static final int BACK_CLOSE_DRAWER = 1;
    public static final int BACK_TO_AGENT = 2;
    public static final int BACK_PASS_THROUGH = 3;

    // Launch kinds handleIntent distinguishes.
    public static final int LANDING_NONE = 0;
    public static final int LANDING_CARD = 1;   // finish / watch notification
    public static final int LANDING_GOAL = 2;   // scheduled-check notification

    private int screen = AGENT;
    private boolean drawerOpen = false;

    public int screen() {
        return screen;
    }

    public boolean drawerOpen() {
        return drawerOpen;
    }

    public void openDrawer() {
        drawerOpen = true;
    }

    public void closeDrawer() {
        drawerOpen = false;
    }

    /** Select a destination (a drawer item tap, a deep link, a
     *  flow hand-off): the screen becomes current and the drawer
     *  closes. An unknown id lands on AGENT rather than nowhere —
     *  the home screen is the only safe default a layout has. */
    public void goTo(int s) {
        screen = (s >= 0 && s < SCREEN_COUNT) ? s : AGENT;
        drawerOpen = false;
    }

    /** The Back button's resolution order, applied as decided:
     *  an open drawer eats the press (and closes); any screen
     *  but AGENT returns home; on AGENT the press passes through
     *  to the system, which is what Back did before the drawer
     *  existed. */
    public int back() {
        if (drawerOpen) {
            drawerOpen = false;
            return BACK_CLOSE_DRAWER;
        }
        if (screen != AGENT) {
            screen = AGENT;
            return BACK_TO_AGENT;
        }
        return BACK_PASS_THROUGH;
    }

    /** Where a launch lands. Every landing the app handles is
     *  the AGENT screen: a finished run's answer card lives in
     *  its log card, and a scheduled check's goal waits in its
     *  goal field — the two things a notification tap can mean.
     *  A plain launch (LANDING_NONE) lands there too: AGENT is
     *  the home screen. */
    public static int landingScreen(int landingKind) {
        return AGENT;
    }

    /** Where flows that live at the browser run: a route
     *  replay started from the Routes screen, a teach recording
     *  entered from it. The WebView has exactly one home, so
     *  these hand-offs always switch to AGENT before they
     *  begin. */
    public static int screenForBrowserFlow() {
        return AGENT;
    }
}
