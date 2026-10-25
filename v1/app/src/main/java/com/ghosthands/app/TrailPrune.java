package com.ghosthands.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * v3.6 (DATA setting — trail retention): how many run trails
 * the app keeps, and which ones go. Through v3.5 trails were
 * never pruned at all — every run's JSONL stayed forever (the
 * screenshot store had a cap since v3.3; the trails beside it
 * did not). This class is the policy, pure like Shots.excess
 * beside it: given the trails directory's file names, it
 * returns the ones to delete.
 *
 * The rule: keep the NEWEST `keep` trails (the names are
 * timestamps, so name order is time order), EXCEPT that a
 * protected name is never returned, and protected names do not
 * consume keep slots — the cap counts the unprotected trails.
 * The Activity protects two kinds of trail:
 *   - the currently open run's trail (housekeeping runs at a
 *     run's end, while its trail is still being written — the
 *     watch verdict lands in it moments later);
 *   - a trail a watched goal's baseline came from (the goal
 *     record names it since v3.6; the baseline is the "last
 *     check" every later verdict diffs against, and its run
 *     stays viewable in History for as long as it is the
 *     reference).
 */
public final class TrailPrune {

    private TrailPrune() { }

    /**
     * The names to delete so that the newest `keep` unprotected
     * of `names` remain and every protected name survives:
     * oldest first (like Shots.excess). Empty when the
     * unprotected count is within the cap. Null-safe: a null
     * list or null protection set reads as empty.
     */
    public static List<String> excess(List<String> names, int keep,
                                      Set<String> protectedNames) {
        List<String> out = new ArrayList<String>();
        if (names == null) return out;
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        int keepN = Math.max(0, keep);
        // Walk newest -> oldest, spending keep slots on
        // unprotected names only; everything unprotected past
        // the cap is excess, collected here newest-first and
        // reversed to oldest-first at the end.
        int kept = 0;
        List<String> excessNewestFirst = new ArrayList<String>();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            String n = sorted.get(i);
            if (protectedNames != null && protectedNames.contains(n)) {
                continue;
            }
            if (kept < keepN) {
                kept++;
            } else {
                excessNewestFirst.add(n);
            }
        }
        for (int i = excessNewestFirst.size() - 1; i >= 0; i--) {
            out.add(excessNewestFirst.get(i));
        }
        return out;
    }
}
