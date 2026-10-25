package com.ghosthands.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.8 (DIAGNOSTICS): the lifetime-stats readout's computation,
 * pure — over per-trail facts drawn from the trail files'
 * parsed events.
 *
 * Honesty rules, the reason this class exists separately from
 * its caller:
 * - A trail counts as a run when its events carry any of the
 *   run's own marks (a "run" opening record, a "stop", a
 *   "run_summary"). A file with none of those is not a run
 *   record and is not counted at all.
 * - A run is FINISHED when it has a "stop" event; its outcome
 *   is that event's reason. Legacy and interrupted trails may
 *   lack a stop — they count toward the total and nothing else.
 * - Steps come only from the "run_summary" event that carries
 *   them (v2.5+). A finished run without one is skipped by the
 *   average, NOT zero-filled: an average that quietly counts
 *   missing steps as zero steps would understate every old
 *   run's length.
 * - The most recent run date is the newest event timestamp
 *   any counted run carries; a trail with no timestamps
 *   contributes none.
 */
public final class TrailStats {

    private TrailStats() { }

    /** One trail's distilled facts (see the class doc). */
    public static final class RunFacts {
        public boolean isRun = false;
        public boolean finished = false;
        public String outcome = "";
        /** Null when the trail carries no step count. */
        public Long steps = null;
        /** Newest event timestamp in the trail, ms; 0 = none. */
        public long lastTsMs = 0;
    }

    /** The aggregate the readout shows. */
    public static final class Stats {
        public int totalRuns = 0;
        public int finishedRuns = 0;
        public int doneRuns = 0;
        /** doneRuns as a percent of finishedRuns; -1 when no run
         *  has finished (there is no honest percent of nothing). */
        public int donePercent = -1;
        /** Mean steps over finished runs that carry a count;
         *  -1 when none do. */
        public double avgSteps = -1;
        public int stepRuns = 0;
        public long lastRunMs = 0;
    }

    /** Distill one trail's events into its facts. Tolerant by
     *  construction: unknown shapes read as absent, never as
     *  errors. */
    public static RunFacts factsFromEvents(
            List<Map<String, Object>> events) {
        RunFacts f = new RunFacts();
        if (events == null) return f;
        Map<String, Object> stopEv = null;
        Map<String, Object> summaryEv = null;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if (ev == null) continue;
            String type = str(ev.get("type"));
            if ("run".equals(type) || "stop".equals(type)
                    || "run_summary".equals(type)) {
                f.isRun = true;
            }
            if ("stop".equals(type)) stopEv = ev;
            if ("run_summary".equals(type)) summaryEv = ev;
            Object ts = ev.get("ts");
            if (ts instanceof Number) {
                long ms = Math.round(((Number) ts).doubleValue()
                        * 1000.0);
                if (ms > f.lastTsMs) f.lastTsMs = ms;
            }
        }
        if (stopEv != null) {
            f.finished = true;
            f.outcome = str(stopEv.get("reason"));
        }
        if (summaryEv != null) {
            Object steps = summaryEv.get("steps");
            if (steps instanceof Number) {
                f.steps = Long.valueOf(
                        ((Number) steps).longValue());
            }
        }
        return f;
    }

    /** Aggregate the counted runs' facts. */
    public static Stats compute(List<RunFacts> facts) {
        Stats s = new Stats();
        if (facts == null) return s;
        long stepTotal = 0;
        for (int i = 0; i < facts.size(); i++) {
            RunFacts f = facts.get(i);
            if (f == null || !f.isRun) continue;
            s.totalRuns++;
            if (f.lastTsMs > s.lastRunMs) s.lastRunMs = f.lastTsMs;
            if (!f.finished) continue;
            s.finishedRuns++;
            if ("done".equals(f.outcome)) s.doneRuns++;
            if (f.steps != null) {
                stepTotal += f.steps.longValue();
                s.stepRuns++;
            }
        }
        if (s.finishedRuns > 0) {
            s.donePercent = (int) Math.round(
                    100.0 * s.doneRuns / s.finishedRuns);
        }
        if (s.stepRuns > 0) {
            s.avgSteps = (double) stepTotal / s.stepRuns;
        }
        return s;
    }

    /** The readout itself: one line per fact, each present only
     *  when its number exists. No runs at all reads as the
     *  honest empty state, not a row of zeros. */
    public static String format(Stats s) {
        if (s == null || s.totalRuns == 0) {
            return "No runs recorded yet.";
        }
        List<String> lines = new ArrayList<String>();
        lines.add("Runs: " + s.totalRuns);
        if (s.finishedRuns > 0) {
            lines.add("Done: " + s.doneRuns + " of "
                    + s.finishedRuns + " finished ("
                    + s.donePercent + "%)");
        } else {
            lines.add("Done: none finished yet");
        }
        if (s.avgSteps >= 0) {
            lines.add("Average steps: " + oneDecimal(s.avgSteps)
                    + " per finished run");
        }
        if (s.lastRunMs > 0) {
            lines.add("Most recent: " + dateLabel(s.lastRunMs));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** "Sun Oct 25, 4:12 AM" — the answer cards' own format. */
    public static String dateLabel(long tsMs) {
        return new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US)
                .format(new Date(tsMs));
    }

    private static String oneDecimal(double v) {
        return String.format(Locale.US, "%.1f",
                Double.valueOf(v));
    }

    private static String str(Object o) {
        return (o == null) ? "" : String.valueOf(o);
    }
}
