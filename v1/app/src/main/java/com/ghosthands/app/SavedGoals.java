package com.ghosthands.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * v3.0 (item 2): saved goals — a small persisted list of goals
 * the user runs more than once, each optionally carrying a daily
 * check time. A saved goal is data only: a name, the goal text,
 * the policy pack id it was saved under, and an optional daily
 * time (minutes after local midnight; NO_SCHEDULE when unset).
 *
 * The store serializes to one JSON string (Json.write) so the
 * Activity can keep it in a single preferences value; parsing is
 * tolerant — a corrupt or foreign value parses to an empty store
 * rather than crashing the launch over a settings file.
 *
 * Scheduling stance (the honest v3.0 shape): a scheduled check is
 * NOTIFY-AND-LAUNCH. Android restricts unattended background
 * browsing, so at the set time the app posts a notification whose
 * tap opens the app with the goal loaded and ready to start —
 * under the readonly pack, whatever pack the goal was saved
 * with. Nothing browses while nobody is looking. The schedule
 * math here (nextFireMillis) is pure; the AlarmManager wiring is
 * the Activity's and the receiver's.
 *
 * v3.2 (watch mode): a saved goal can also be WATCHED. A watched
 * goal carries a baseline — the comparable signature (see
 * WatchCompare), summary, and timestamp of its last watched DONE
 * run — and each later watched done run is compared against it,
 * then replaces it (the diff is always against the previous
 * check, as the UI copy says). The baseline lives inside the
 * goal's record, so deleting the goal deletes its baseline, and
 * the serialization stays backward-tolerant: records written
 * before v3.2 simply have no watch keys and parse unwatched,
 * and a malformed baseline parses as no baseline rather than
 * sinking the goal.
 *
 * Pure: no Android — the host harness pins serialization and the
 * next-fire computation.
 */
public class SavedGoals {

    /** schedMinutes value meaning "no daily check". */
    public static final int NO_SCHEDULE = -1;

    /** One saved goal. Fields are plain and mutable: the store's
     *  unit of editing is the whole record. */
    public static final class SavedGoal {
        public String name;
        public String goal;
        public String packId;
        public int schedMinutes = NO_SCHEDULE;

        // v3.2 (watch mode): the watch flag and the baseline —
        // the last watched DONE run's signature, summary and
        // completion time. baseline == null means no watched
        // done run has completed yet (or the watch was cleared);
        // an EMPTY signature is a real baseline (a numberless
        // answer — the comparator falls back to its summary).
        public boolean watched = false;
        public WatchCompare.Signature baseline = null;
        public String baselineSummary = "";
        public long baselineTsMs = 0;
        // v3.6: the FILE NAME of the trail the baseline came
        // from ("" when unknown — every baseline set before
        // v3.6, whose producing run was never named). Trail
        // retention (TrailPrune) protects a named baseline
        // trail for as long as it is the reference; the name
        // rides inside the baseline's own record below.
        public String baselineTrailName = "";

        public SavedGoal() { }

        public SavedGoal(String name, String goal, String packId,
                         int schedMinutes) {
            this.name = name;
            this.goal = goal;
            this.packId = packId;
            this.schedMinutes = schedMinutes;
        }

        public boolean hasSchedule() {
            return schedMinutes >= 0 && schedMinutes < 24 * 60;
        }

        public boolean hasBaseline() {
            return baseline != null;
        }

        /** Record a watched done run as the new baseline: the
         *  comparison for the next check runs against THIS run,
         *  exactly as the card and list copy say. */
        public void setBaseline(WatchCompare.Signature sig,
                                String summary, long tsMs) {
            setBaseline(sig, summary, tsMs, "");
        }

        /** v3.6: as above, also naming the trail file the run
         *  was recorded in, so retention can protect the
         *  baseline's own run while it is the reference. */
        public void setBaseline(WatchCompare.Signature sig,
                                String summary, long tsMs,
                                String trailName) {
            baseline = sig;
            baselineSummary = (summary == null) ? "" : summary;
            baselineTsMs = tsMs;
            baselineTrailName = (trailName == null) ? "" : trailName;
        }

        /** Drop the baseline (unwatching does this: a watch that
         *  starts again starts fresh, with no stale verdicts). */
        public void clearBaseline() {
            baseline = null;
            baselineSummary = "";
            baselineTsMs = 0;
            baselineTrailName = "";
        }

        /** Carry the watch state onto a replacement record (a
         *  re-save under the same name keeps the watch and its
         *  baseline, as it keeps the schedule). */
        public void copyWatchFrom(SavedGoal other) {
            if (other == null) return;
            watched = other.watched;
            baseline = other.baseline;
            baselineSummary = other.baselineSummary;
            baselineTsMs = other.baselineTsMs;
            baselineTrailName = other.baselineTrailName;
        }
    }

    private final List<SavedGoal> goals = new ArrayList<SavedGoal>();

    public int size() {
        return goals.size();
    }

    /** All saved goals, in saved order (a copy of the list; the
     *  records themselves are shared). */
    public List<SavedGoal> all() {
        return new ArrayList<SavedGoal>(goals);
    }

    /** The saved goal with this exact name, or null. */
    public SavedGoal find(String name) {
        if (name == null) return null;
        for (int i = 0; i < goals.size(); i++) {
            if (name.equals(goals.get(i).name)) return goals.get(i);
        }
        return null;
    }

    /** Add a goal, replacing any existing goal of the same name
     *  (the replacement keeps the original's list position). */
    public void put(SavedGoal g) {
        if (g == null || g.name == null) return;
        for (int i = 0; i < goals.size(); i++) {
            if (g.name.equals(goals.get(i).name)) {
                goals.set(i, g);
                return;
            }
        }
        goals.add(g);
    }

    /** Remove by name; true when something was removed. */
    public boolean remove(String name) {
        if (name == null) return false;
        for (int i = 0; i < goals.size(); i++) {
            if (name.equals(goals.get(i).name)) {
                goals.remove(i);
                return true;
            }
        }
        return false;
    }

    // ---------------- serialization ----------------

    /** The whole store as one JSON string (an array of records;
     *  an empty store serializes as "[]"). */
    public String serialize() {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < goals.size(); i++) {
            SavedGoal g = goals.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", g.name);
            m.put("goal", g.goal);
            m.put("pack", g.packId);
            m.put("sched", Long.valueOf(g.schedMinutes));
            // v3.2: the watch state rides in the same record;
            // older builds ignore keys they do not know.
            m.put("watch", Boolean.valueOf(g.watched));
            if (g.hasBaseline()) {
                Map<String, Object> base =
                        new LinkedHashMap<String, Object>();
                base.put("ts", Long.valueOf(g.baselineTsMs));
                base.put("summary", g.baselineSummary);
                base.put("sig", g.baseline.toJsonList());
                // v3.6: the baseline's source trail, when known
                // (older records simply lack the key).
                if (g.baselineTrailName != null
                        && g.baselineTrailName.length() > 0) {
                    base.put("trail", g.baselineTrailName);
                }
                m.put("base", base);
            }
            out.add(m);
        }
        return Json.write(out);
    }

    /** Parse a store from its serialized form. Anything that is
     *  not a JSON array of records with a non-empty string name
     *  and goal yields an empty (or partial, for mixed arrays)
     *  store — never an exception. */
    public static SavedGoals parse(String json) {
        SavedGoals store = new SavedGoals();
        if (json == null || json.trim().length() == 0) return store;
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (Exception e) {
            return store;
        }
        if (!(parsed instanceof List)) return store;
        List<?> arr = (List<?>) parsed;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Object name = m.get("name");
            Object goal = m.get("goal");
            if (!(name instanceof String) || !(goal instanceof String)) {
                continue;
            }
            if (((String) name).trim().length() == 0
                    || ((String) goal).trim().length() == 0) {
                continue;
            }
            SavedGoal g = new SavedGoal();
            g.name = (String) name;
            g.goal = (String) goal;
            Object pack = m.get("pack");
            g.packId = (pack instanceof String
                    && ((String) pack).trim().length() > 0)
                    ? (String) pack : "standard";
            Object sched = m.get("sched");
            g.schedMinutes = (sched instanceof Number)
                    ? ((Number) sched).intValue() : NO_SCHEDULE;
            if (g.schedMinutes < NO_SCHEDULE
                    || g.schedMinutes >= 24 * 60) {
                g.schedMinutes = NO_SCHEDULE;
            }
            // v3.2: the watch flag (absent or non-boolean reads
            // as unwatched — records from before v3.2 stay valid)
            // and the baseline, which counts only when its whole
            // shape is sound: a Map with a parseable signature.
            // A malformed baseline costs the baseline, never the
            // goal or its watch flag.
            g.watched = Boolean.TRUE.equals(m.get("watch"));
            Object base = m.get("base");
            if (base instanceof Map) {
                Map<?, ?> bm = (Map<?, ?>) base;
                WatchCompare.Signature sig =
                        WatchCompare.Signature.fromJsonList(bm.get("sig"));
                if (sig != null) {
                    Object ts = bm.get("ts");
                    Object sum = bm.get("summary");
                    Object tr = bm.get("trail");
                    g.setBaseline(sig,
                            (sum instanceof String) ? (String) sum : "",
                            (ts instanceof Number)
                                    ? ((Number) ts).longValue() : 0,
                            (tr instanceof String) ? (String) tr : "");
                }
            }
            store.put(g);
        }
        return store;
    }

    // ---------------- schedule math ----------------

    /** Minutes after midnight for an hour/minute pair. */
    public static int minutesOfDay(int hour, int minute) {
        return hour * 60 + minute;
    }

    /** "07:30" — the zero-padded 24-hour label of a minutes-of-
     *  day value ("" for NO_SCHEDULE and out-of-range values). */
    public static String timeLabel(int schedMinutes) {
        if (schedMinutes < 0 || schedMinutes >= 24 * 60) return "";
        int h = schedMinutes / 60;
        int m = schedMinutes % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** How a goal's schedule reads in lists: "daily 07:30", or
     *  "" when the goal carries no check time. */
    public static String scheduleLabel(SavedGoal g) {
        if (g == null || !g.hasSchedule()) return "";
        return "daily " + timeLabel(g.schedMinutes);
    }

    /**
     * The next instant (epoch millis) at which a daily check for
     * schedMinutes should fire, strictly after nowMs, in the
     * given timezone: today's occurrence when it is still ahead,
     * tomorrow's once today's has passed (or is exactly now — a
     * check armed at its own minute belongs to the next day, so
     * re-arming from inside its own firing cannot loop).
     */
    public static long nextFireMillis(long nowMs, int schedMinutes,
                                      TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(nowMs);
        cal.set(Calendar.HOUR_OF_DAY, schedMinutes / 60);
        cal.set(Calendar.MINUTE, schedMinutes % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }
}
