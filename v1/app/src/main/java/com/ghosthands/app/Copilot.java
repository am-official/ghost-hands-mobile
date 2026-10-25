package com.ghosthands.app;

/**
 * v3.3 (item 4 — co-pilot tap): when an agent run would stop as
 * STUCK, the user can take the wheel for one tap: the loop
 * pauses, the user taps the element themselves in the page,
 * presses Resume, and the run continues from the page as the
 * user's tap left it. The tap is the USER's action — the trail
 * records it as an assist event with provenance USER, never as
 * an agent action.
 *
 * The rules, kept pure here so the host harness can pin them
 * and the loop and the Activity share one reading of them:
 *
 *   - Eligibility: the stop reason is exactly "stuck" (never an
 *     error, a budget stop, a denial, or the user's own Stop),
 *     the app is in the foreground (a pause nobody can see is a
 *     hang, not a rescue — backgrounded, the run stops stuck as
 *     it always has), and the run has spent fewer than
 *     MAX_ASSISTS assists.
 *   - The pause itself is the small state machine below: a
 *     pause is offered, waits for a tap to be identified, and
 *     only a pause whose tap was identified can be resumed —
 *     a Resume pressed with no identified tap keeps waiting
 *     (the Activity says so), which is what "if the tap can't
 *     be identified, say so and keep waiting" means in code.
 *     Stop, or the wait expiring, declines the pause.
 *
 * The pause's CLOCK: the loop hands the Ui the pack's approval
 * timeout for the wait, exactly the budget an approval wait
 * gets — the pause counts against the pack's leash the same
 * way an approval does, and expiry resolves as a decline (the
 * run stops stuck), never as consent to continue blind.
 */
public final class Copilot {

    /** Assists a single run may spend. */
    public static final int MAX_ASSISTS = 2;

    private Copilot() { }

    /** May this stop become a co-pilot pause? */
    public static boolean eligible(String stopReason, int assistsUsed,
                                   boolean foreground) {
        return "stuck".equals(stopReason)
                && foreground
                && assistsUsed < MAX_ASSISTS;
    }

    /** The pause's states. IDLE: no pause. WAITING: offered, no
     *  identified tap yet. TAPPED: a tap was identified; Resume
     *  is armed. RESUMED / DECLINED: the pause is over, one way
     *  or the other, and nothing leaves a terminal state. */
    public enum State { IDLE, WAITING, TAPPED, RESUMED, DECLINED }

    /** One pause. Transitions return whether they took — a
     *  refused transition changes nothing, so the Activity can
     *  wire buttons straight to it and let it be the judge. */
    public static final class Pause {
        private State state = State.IDLE;

        public State state() {
            return state;
        }

        /** IDLE -> WAITING. */
        public boolean offer() {
            if (state != State.IDLE) return false;
            state = State.WAITING;
            return true;
        }

        /** WAITING -> TAPPED; TAPPED -> TAPPED (the user tapped
         *  something else: the latest identified tap is the one
         *  that counts). Anything else refuses. */
        public boolean tapIdentified() {
            if (state == State.WAITING || state == State.TAPPED) {
                state = State.TAPPED;
                return true;
            }
            return false;
        }

        /** TAPPED -> RESUMED. From WAITING it refuses: there is
         *  nothing to resume FROM until a tap was identified. */
        public boolean resume() {
            if (state != State.TAPPED) return false;
            state = State.RESUMED;
            return true;
        }

        /** WAITING / TAPPED -> DECLINED (the user pressed Stop,
         *  or the wait expired — silence is a decline here as
         *  everywhere in this app). */
        public boolean decline() {
            if (state == State.WAITING || state == State.TAPPED) {
                state = State.DECLINED;
                return true;
            }
            return false;
        }

        /** The pause is still open (waiting or tapped). */
        public boolean isOpen() {
            return state == State.WAITING || state == State.TAPPED;
        }

        /** The pause ended in a resume. */
        public boolean resumed() {
            return state == State.RESUMED;
        }
    }
}
