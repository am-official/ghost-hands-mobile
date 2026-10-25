package com.ghosthands.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v3.3 (item 2 — screenshot trail): when a run ends, the app
 * photographs the WebView's visible content and keeps the photo
 * with the run — the answer card shows it, live and rebuilt
 * from a past trail alike. This class is the pure half of that:
 * the file naming and the retention policy. The bitmap capture
 * itself is the Activity's (and is device-verified only).
 *
 * Naming: a run's screenshot carries its trail's name with the
 * image extension — "trail-20261025-123456.jsonl" photographs to
 * "trail-20261025-123456.jpg" — so a screenshot sorts with its
 * run, and name order is time order.
 *
 * Retention: at most MAX_KEPT screenshots are kept; when a new
 * one lands, the oldest beyond the cap are deleted. excess()
 * is the whole policy: given the directory's file names, it
 * returns the ones to delete.
 */
public final class Shots {

    /** How many run screenshots are kept, newest first. */
    public static final int MAX_KEPT = 20;

    /** The private-storage directory the screenshots live in
     *  (under the app's files dir). */
    public static final String DIR_NAME = "shots";

    private Shots() { }

    /**
     * The screenshot file name for a trail file name: the
     * trail's base name with ".jpg" in place of ".jsonl" (any
     * directory part is dropped). Null when there is no usable
     * base name.
     */
    public static String fileNameForTrail(String trailFileName) {
        if (trailFileName == null) return null;
        String base = trailFileName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".jsonl")) {
            base = base.substring(0, base.length() - ".jsonl".length());
        }
        if (base.length() == 0) return null;
        return base + ".jpg";
    }

    /**
     * The names to delete so that only the newest `keep` of
     * `names` remain: the (size - keep) oldest by name order
     * (the names are timestamps, so name order is time order),
     * returned oldest first. Empty when the count is within
     * the cap.
     */
    public static List<String> excess(List<String> names, int keep) {
        List<String> out = new ArrayList<String>();
        if (names == null) return out;
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        int n = sorted.size() - Math.max(0, keep);
        for (int i = 0; i < n; i++) {
            out.add(sorted.get(i));
        }
        return out;
    }
}
