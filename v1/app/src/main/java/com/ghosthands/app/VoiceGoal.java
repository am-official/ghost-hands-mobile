package com.ghosthands.app;

import java.util.List;

/**
 * v3.0 (item 3): voice goals — the pure half of the goal field's
 * microphone button. The Activity owns the framework
 * SpeechRecognizer; everything decidable without one lives here:
 *
 * - which recognition candidate becomes the goal text (the
 *   first non-blank one — the recognizer ranks its guesses, and
 *   the user reviews the text before anything runs: voice input
 *   FILLS the goal field, it never starts a run);
 * - the button's label and enabled state for its three states
 *   (ready / listening / unavailable on this device).
 *
 * Honest limit, stated in the release notes: whether recognition
 * happens on the device or in the phone's speech service is the
 * DEVICE's choice, not this app's — on many phones the framework
 * recognizer is network-backed. The mic button therefore fills a
 * text field, exactly like the keyboard does; nothing is sent
 * anywhere by the act of dictating beyond what the phone's own
 * speech service does with it.
 *
 * Pure: no Android — the host harness pins the pure parts; the
 * recognizer wiring itself is device-verified.
 */
public final class VoiceGoal {

    private VoiceGoal() { }

    /** The heard text to put in the goal field: the first
     *  candidate that is non-blank after trimming, trimmed, or
     *  null when the recognizer offered nothing usable. */
    public static String pickHeard(List<String> results) {
        if (results == null) return null;
        for (int i = 0; i < results.size(); i++) {
            String r = results.get(i);
            if (r != null && r.trim().length() > 0) {
                return r.trim();
            }
        }
        return null;
    }

    /** The mic button's label: while listening it says so; when
     *  the device has no recognizer it carries the short
     *  unavailable label (and is disabled, per buttonEnabled). */
    public static String buttonLabel(boolean available,
                                     boolean listening) {
        if (!available) return "Voice n/a";
        if (listening) return "Listening…";
        return "🎤 Voice";
    }

    /** The mic button is tappable only when a recognizer exists
     *  and is not already listening. */
    public static boolean buttonEnabled(boolean available,
                                        boolean listening) {
        return available && !listening;
    }
}
