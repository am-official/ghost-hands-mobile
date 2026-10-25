package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.16 (Everything Train R8 — watch this page): URL watches,
 * the goal watches' sibling.
 *
 * A page watch is one URL plus the evidence signature of its
 * current readable text, signed with the goal watches' own
 * discipline (WatchCompare: distinct numeric values with
 * short labels, one entry per distinct value, and the
 * whitespace-normalized text fallback for a numberless page).
 * It lives in the same management family as watched goals —
 * the Goals screen, the daily-check alarms, notify-and-launch,
 * quiet hours — and its checks ARE goal-watch checks: a
 * scheduled check loads a generated readonly goal ("open the
 * page, read it to the end, report its facts"), and when that
 * run finishes DONE on the watched page itself, the page's
 * freshly extracted readable text is signed and compared.
 *
 * The baseline rules are the goal watches' rules, unchanged:
 * a baseline exists from creation (the capture the user
 * tapped Watch on); a check's verdict is judged against the
 * baseline it found; the baseline advances ONLY on a
 * completed check — a run that ended any other way, or that
 * finished on some other page, records NOT_DONE and moves
 * nothing. The baseline text rides along (capped, see
 * TEXT_CAP) because WatchCompare's numberless fallback
 * compares texts, not signatures.
 *
 * Creation is the USER's act — a tap on "Watch this page",
 * never a planner action. It therefore has no Governor
 * class and is never judged by one: it writes only the
 * app's own watch store, and when a run is live the creation
 * is recorded in that run's trail as a "page_watch" event
 * stamped by:"user", exactly as the user's ask answers are
 * stamped USER. The harness pins this choice.
 *
 * Pure: no Android, no I/O (SavedGoals' store pattern).
 */
public final class PageWatches {

    /** The preferences key the page-watch store lives under. */
    public static final String PREF_PAGE_WATCHES = "page_watches";

    /** schedMinutes value meaning "no daily check" (the same
     *  sentinel SavedGoals uses). */
    public static final int NO_SCHEDULE = -1;

    /** The stored baseline text's cap. WatchCompare's
     *  numberless fallback compares whitespace-normalized
     *  texts; the comparison is a blunt instrument by design
     *  there, and capping both sides at the same length keeps
     *  it consistent without keeping whole pages in the
     *  preferences. A page whose numbers exist never reaches
     *  the fallback at all. */
    public static final int TEXT_CAP = 4000;

    /** The alarm name prefix: a page watch's check alarm
     *  carries "page:" + its URL in the shared
     *  Alerts.EXTRA_GOAL_NAME extra, so the one receiver and
     *  the one intent handler route goal checks and page
     *  checks by name alone. */
    public static final String ALARM_PREFIX = "page:";

    // ---------------- one watch ----------------

    public static final class PageWatch {
        public String url;
        public int schedMinutes = NO_SCHEDULE;
        // The baseline: the signature + text of the page as
        // last COMPLETELY captured (at creation, or by the
        // last completed check), and when.
        public WatchCompare.Signature baseline = null;
        public String baselineText = "";
        public long baselineTsMs = 0;
        // The last verdict the user was told about ("" before
        // any check; WatchCompare states after).
        public String lastVerdict = "";
        public long lastCheckTsMs = 0;
        public final List<String> lastDiff = new ArrayList<String>();

        public PageWatch() { }

        public PageWatch(String url) {
            this.url = url;
        }

        public boolean hasSchedule() {
            return schedMinutes != NO_SCHEDULE;
        }

        public boolean hasBaseline() {
            return baseline != null;
        }

        /** Record a completed capture as the new baseline. */
        public void setBaseline(WatchCompare.Signature sig,
                                String text, long tsMs) {
            baseline = sig;
            baselineText = capText(text);
            baselineTsMs = tsMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("url", url);
            m.put("sched", Long.valueOf(schedMinutes));
            if (baseline != null) {
                Map<String, Object> b =
                        new LinkedHashMap<String, Object>();
                b.put("sig", baseline.toJsonList());
                b.put("text", baselineText);
                b.put("ts", Long.valueOf(baselineTsMs));
                m.put("baseline", b);
            }
            m.put("verdict", lastVerdict);
            m.put("checked", Long.valueOf(lastCheckTsMs));
            List<Object> diff = new ArrayList<Object>();
            for (int i = 0; i < lastDiff.size(); i++) {
                diff.add(lastDiff.get(i));
            }
            m.put("diff", diff);
            return m;
        }

        /** Tolerant per-record parse (SavedGoals' discipline):
         *  a record without a usable url is not a watch; a
         *  malformed baseline costs only the baseline. */
        public static PageWatch fromMap(Map<?, ?> m) {
            if (m == null) return null;
            Object u = m.get("url");
            if (!(u instanceof String)
                    || ((String) u).trim().length() == 0) {
                return null;
            }
            PageWatch w = new PageWatch(((String) u).trim());
            Object s = m.get("sched");
            if (s instanceof Number) {
                w.schedMinutes = ((Number) s).intValue();
            }
            Object b = m.get("baseline");
            if (b instanceof Map) {
                Map<?, ?> bm = (Map<?, ?>) b;
                WatchCompare.Signature sig =
                        WatchCompare.Signature.fromJsonList(
                                bm.get("sig"));
                if (sig != null) {
                    w.baseline = sig;
                    Object t = bm.get("text");
                    if (t instanceof String) {
                        w.baselineText = (String) t;
                    }
                    Object ts = bm.get("ts");
                    if (ts instanceof Number) {
                        w.baselineTsMs = ((Number) ts).longValue();
                    }
                }
            }
            Object v = m.get("verdict");
            if (v instanceof String) w.lastVerdict = (String) v;
            Object c = m.get("checked");
            if (c instanceof Number) {
                w.lastCheckTsMs = ((Number) c).longValue();
            }
            Object d = m.get("diff");
            if (d instanceof List) {
                List<?> arr = (List<?>) d;
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof String) {
                        w.lastDiff.add((String) arr.get(i));
                    }
                }
            }
            return w;
        }
    }

    // ---------------- the store ----------------

    private final List<PageWatch> watches = new ArrayList<PageWatch>();

    public PageWatches() { }

    public static PageWatches parse(String json) {
        PageWatches store = new PageWatches();
        if (json == null || json.length() == 0) return store;
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
            if (item instanceof Map) {
                PageWatch w = PageWatch.fromMap((Map<?, ?>) item);
                if (w != null && store.find(w.url) == null) {
                    store.watches.add(w);
                }
            }
        }
        return store;
    }

    public String serialize() {
        List<Object> arr = new ArrayList<Object>();
        for (int i = 0; i < watches.size(); i++) {
            arr.add(watches.get(i).toMap());
        }
        return Json.write(arr);
    }

    public int size() { return watches.size(); }

    public List<PageWatch> all() {
        return new ArrayList<PageWatch>(watches);
    }

    public PageWatch find(String url) {
        if (url == null) return null;
        for (int i = 0; i < watches.size(); i++) {
            if (watches.get(i).url.equals(url)) return watches.get(i);
        }
        return null;
    }

    public void put(PageWatch w) {
        if (w == null || w.url == null) return;
        PageWatch old = find(w.url);
        if (old != null) watches.remove(old);
        watches.add(w);
    }

    public boolean remove(String url) {
        PageWatch w = find(url);
        if (w == null) return false;
        watches.remove(w);
        return true;
    }

    // ---------------- signatures + verdicts ----------------

    /** Cap a page text for baseline storage (see TEXT_CAP). */
    public static String capText(String text) {
        if (text == null) return "";
        return (text.length() > TEXT_CAP)
                ? text.substring(0, TEXT_CAP) : text;
    }

    /** The evidence signature of one page text: the goal
     *  watches' discipline applied to the page itself (no
     *  noted facts — the page is the only witness). */
    public static WatchCompare.Signature signatureFor(String pageText) {
        return WatchCompare.signature(null, pageText == null
                ? "" : pageText);
    }

    /**
     * Judge one completed capture against a watch's baseline:
     * WatchCompare.compare, verbatim — a watch with no
     * baseline yields BASELINE, two numberless captures fall
     * back to the normalized-text comparison, anything else
     * is the pair-cancellation diff.
     */
    public static WatchCompare.Verdict verdictFor(PageWatch w,
                                                  String pageText) {
        WatchCompare.Signature sig = signatureFor(pageText);
        return WatchCompare.compare(
                (w == null) ? null : w.baseline,
                (w == null) ? "" : w.baselineText,
                sig, capText(pageText));
    }

    /**
     * Apply a COMPLETED check's verdict to the watch: the
     * verdict is recorded (state, time, diff) and the
     * baseline advances to the checked capture. This is the
     * only road to a new baseline after creation — the
     * caller (the Activity) reaches it only for a run that
     * ended done on the watched page; every other ending
     * goes through recordNotDone, which moves nothing.
     */
    public static void applyCompletedCheck(PageWatch w,
                                           String pageText,
                                           long nowMs) {
        if (w == null) return;
        WatchCompare.Verdict v = verdictFor(w, pageText);
        w.lastVerdict = v.state;
        w.lastCheckTsMs = nowMs;
        w.lastDiff.clear();
        w.lastDiff.addAll(v.diffLines);
        w.setBaseline(signatureFor(pageText), pageText, nowMs);
    }

    /** Record a check that did not complete: the verdict is
     *  NOT_DONE, and the baseline — signature, text, time —
     *  is exactly where it was. A failed check is not a new
     *  reading of the page. */
    public static void recordNotDone(PageWatch w, long nowMs) {
        if (w == null) return;
        w.lastVerdict = WatchCompare.NOT_DONE;
        w.lastCheckTsMs = nowMs;
        w.lastDiff.clear();
    }

    // ---------------- names, goals, matching ----------------

    /** The alarm / notification name for a watch. */
    public static String alarmName(PageWatch w) {
        return (w == null) ? null : ALARM_PREFIX + w.url;
    }

    public static boolean isPageAlarm(String name) {
        return name != null && name.startsWith(ALARM_PREFIX)
                && name.length() > ALARM_PREFIX.length();
    }

    public static String urlFromAlarm(String name) {
        return isPageAlarm(name)
                ? name.substring(ALARM_PREFIX.length()) : null;
    }

    /** The watch's short display name: "Page: host/path",
     *  trimmed to a card-sized line. */
    public static String label(String url) {
        if (url == null || url.length() == 0) return "Page";
        String host = SiteGrants.hostOf(url);
        String s = (host != null && host.length() > 0)
                ? "Page: " + host : "Page: " + url;
        String path = urlPath(url);
        if (path.length() > 1 && host != null) {
            s = "Page: " + host + path;
        }
        if (s.length() > 64) s = s.substring(0, 61) + "…";
        return s;
    }

    private static String urlPath(String url) {
        try {
            String p = new java.net.URL(url).getPath();
            return (p == null) ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    /** The generated goal a page-watch check runs, readonly:
     *  open the page, read it through, report its facts. The
     *  verdict never reads this goal's answer — it signs the
     *  page's own extracted text — so the goal's job is only
     *  to land the run, done, on the watched page. */
    public static String checkGoal(String url) {
        return "Open " + url + ", read the page to the end, "
                + "and report its key facts and numbers.";
    }

    /** Whether a capture taken at fetchedUrl is a capture OF
     *  the watched page: scheme and host must match
     *  (case-folded), the path must match but for a trailing
     *  slash, and query/fragment are ignored (a check that
     *  lands on the same page wearing a tracking parameter
     *  is still the same page; one that wandered to another
     *  path is not, and its capture must never move the
     *  baseline). */
    public static boolean urlsMatch(String fetchedUrl,
                                    String watchedUrl) {
        if (fetchedUrl == null || watchedUrl == null) return false;
        try {
            java.net.URL a = new java.net.URL(fetchedUrl);
            java.net.URL b = new java.net.URL(watchedUrl);
            if (!a.getProtocol().equalsIgnoreCase(b.getProtocol())) {
                return false;
            }
            String ah = a.getHost();
            String bh = b.getHost();
            if (ah == null || bh == null
                    || !ah.equalsIgnoreCase(bh)) {
                return false;
            }
            if (a.getPort() != b.getPort()) return false;
            return trimSlash(a.getPath()).equals(trimSlash(b.getPath()));
        } catch (Exception e) {
            return false;
        }
    }

    private static String trimSlash(String p) {
        if (p == null) return "";
        String s = p;
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
