package com.ghosthands.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v3.14 (Everything Train R6 — the accessibility spike): the
 * spike's own log discipline, pure. Every probe action — a
 * read, a tap, a refusal — is one line in a local file beside
 * the trails (a11y-spike-log.txt in the app's files dir), so
 * an experiment leaves the same kind of record a run does.
 *
 * A line is a timestamp, WHAT happened (read / tap /
 * tap_refused / gate_blocked), and a detail: the package
 * sketched, the node count, the point tapped, the platform's
 * answer. Details are newline-flattened before they land — a
 * foreign app's package name or window title is data, and
 * data does not get to forge log lines (the same hygiene
 * v2.38 taught the debug-info copy). The file is tail-trimmed
 * at MAX_KEPT_CHARS so the experiment's record stays bounded
 * without a retention setting of its own.
 *
 * The log is deliberately NOT a trail: no run reads it, no
 * verdict is computed from it, and it is outside the DATA
 * clear scopes (an app-data clear at the system level takes
 * it with everything else). It exists so the spike's evidence
 * survives the screen it was gathered on.
 */
public final class SpikeLog {

    private SpikeLog() { }

    /** The log file's name in the app's files dir. */
    public static final String FILE_NAME = "a11y-spike-log.txt";

    /** The kept tail's cap, in chars. */
    public static final int MAX_KEPT_CHARS = 64 * 1024;

    /** One log line (with its trailing newline). */
    public static String line(long epochMs, String what,
                              String detail) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.US).format(new Date(epochMs));
        return ts + "  " + clean(what) + "  " + clean(detail)
                + "\n";
    }

    /** Flatten a would-be line-breaker: newlines and carriage
     *  returns become spaces, and the result is capped so one
     *  entry cannot crowd out the record. */
    static String clean(String s) {
        if (s == null) return "";
        String out = s.replace('\n', ' ').replace('\r', ' ');
        return (out.length() <= 300) ? out : out.substring(0, 300);
    }

    /** Keep the newest tail of the log: at most maxChars, cut
     *  at a line boundary so no half-line survives at the top. */
    public static String trimToTail(String content, int maxChars) {
        if (content == null) return "";
        if (maxChars < 0) maxChars = 0;
        if (content.length() <= maxChars) return content;
        String tail = content.substring(content.length() - maxChars);
        int nl = tail.indexOf('\n');
        return (nl >= 0 && nl + 1 < tail.length())
                ? tail.substring(nl + 1) : tail;
    }
}
