package com.ghosthands.app;

import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The relevance guard (v1.1) — an agent-loop correction, NOT part of
 * the safety Governor. The Governor answers "is this action safe /
 * permitted"; this guard answers "is this tap even aimed at the goal".
 *
 * Why it exists: on the Wikipedia Main Page a 1.5B planner twice made
 * the identical first move — tapping a featured-article link ("War of
 * the First Coalition") for search goals it had not yet searched. A
 * plain link tap is readonly, so the Governor rightly allows it; the
 * failure is relevance, not safety. This guard catches exactly that
 * shape, narrowly:
 *
 *   - the goal is search-like (search / look up / find / what / who /
 *     how long / which ...),
 *   - no successful `search` action has happened yet in this run,
 *   - the current page has not already arrived at the goal target
 *     (its title/URL matches a significant goal term that is not just
 *     the site's own name),
 *   - the proposal is a tap on an <a> whose visible name matches NO
 *     significant goal token and which is not a search/navigation
 *     control.
 *
 * A blocked tap is recorded in the trail as a "guard" event with
 * outcome "correct", the planner is told in its history to use
 * `search`, and the loop continues without executing anything. The
 * Governor's semantics are untouched.
 *
 * v1.3 additions, from the v1.2 field run (same Clinch River goal):
 * the repeat-search correction above keys on agent state derived
 * from the last execution (onSearchResults), and in the field it
 * did not fire — the step-2 perception can race the navigation and
 * the agent's picture of "where it is" lags the execution record.
 * searchRepeatByHistoryReason keys purely on the EXECUTION history
 * (the previous executed action was a successful search for the
 * same query, and nothing that moves or reads has executed since),
 * which no perception race can falsify. The same run also showed
 * the planner arriving on the article and then tapping the
 * Wikipedia "Article" page tab twice — it never recognised arrival.
 * Agent therefore auto-reads on arrival (shouldAutoRead below) and
 * this guard corrects chrome-tab taps on an arrived page
 * (chromeTabReason), once per label per run.
 */
public final class RelevanceGuard {

    private RelevanceGuard() { }

    /** Words that never identify a goal target (all would-be tokens of
     *  length >= 4); function words and goal-shape verbs. */
    private static final String[] STOPWORDS = {
            "search", "look", "lookup", "find", "tell", "about", "open",
            "read", "show", "give", "page", "article", "what", "who",
            "whom", "whose", "when", "where", "which", "that", "this",
            "with", "from", "into", "does", "have", "been", "will",
            "there", "their", "then", "than", "and", "the", "for",
            "how", "long",
    };

    /** Substrings that make a goal search-like. */
    private static final String[] SEARCH_LIKE_MARKERS = {
            "search", "look up", "lookup", "look for", "find",
            "what", "who", "how long", "which",
    };

    /** Names of generic navigation controls that are never "content"
     *  taps (a search control is recognised separately, by the word
     *  "search" in name/label/placeholder/href). */
    private static final String[] NAV_NAMES = {
            "home", "main page", "menu", "navigation", "contents",
    };

    /** Substrings that make a goal a question / information goal:
     *  the user is asking for a fact, not asking for a change. */
    private static final String[] QUESTION_MARKERS = {
            "?", "tell me", "what", "how", "which", "who", "whom",
            "when", "where", "why",
    };

    /** v1.3: page-chrome tab labels (Wikipedia-shaped). Tapping one
     *  of these on the article you already arrived at is a no-op the
     *  planner mistakes for progress. Compared case-insensitively
     *  against the element's whole trimmed visible name. */
    private static final String[] CHROME_TAB_NAMES = {
            "article", "talk", "read", "edit", "view history",
            "contents",
    };

    public static boolean isSearchLike(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        for (int i = 0; i < SEARCH_LIKE_MARKERS.length; i++) {
            if (g.contains(SEARCH_LIKE_MARKERS[i])) return true;
        }
        return false;
    }

    /** Goal words of length >= 4 that are not stopwords: the terms a
     *  target link or page would actually be named by. */
    public static Set<String> significantTokens(String goal) {
        Set<String> out = new LinkedHashSet<String>();
        for (String w : words(goal)) {
            if (w.length() >= 4 && !isStopword(w)) out.add(w);
        }
        return out;
    }

    /** True when the current page already looks like the destination:
     *  its title or URL contains a significant goal token that is not
     *  merely a token of the site's own host name (every Wikipedia
     *  page "matches" the token wikipedia via its host — that must
     *  not count as arrival). */
    public static boolean pageArrived(String goal, String title, String url) {
        Set<String> tokens = significantTokens(goal);
        tokens.removeAll(hostTokens(url));
        if (tokens.isEmpty()) return false;
        String hay = ((title == null ? "" : title) + " "
                + (url == null ? "" : url)).toLowerCase();
        for (String t : tokens) {
            if (hay.contains(t)) return true;
        }
        return false;
    }

    /**
     * Returns a human-readable reason when this tap should be blocked
     * as off-goal, or null when the guard has nothing to say.
     */
    public static String blockReason(String goal, boolean searched,
                                     String pageTitle, String pageUrl,
                                     GhAction action, ElementInfo element) {
        if (action == null || !"tap".equals(action.action)) return null;
        if (element == null || !"a".equals(element.tag)) return null;
        if (searched) return null;
        if (!isSearchLike(goal)) return null;
        if (pageArrived(goal, pageTitle, pageUrl)) return null;
        if (isSearchOrNavControl(element)) return null;
        Set<String> tokens = significantTokens(goal);
        if (tokens.isEmpty()) return null;
        if (nameMatches(element.name, tokens)) return null;
        return "off-goal tap blocked by relevance guard: link \""
                + (element.name == null ? "" : element.name)
                + "\" matches no goal term; the goal asks to search -"
                + " use search first";
    }

    /**
     * v1.2: the repeat-search correction. Field result from v1.1: the
     * search action fixed the opening move, then the planner repeated
     * the IDENTICAL search twice more on its own results page (where
     * the result links were crowded out of the map) and the run died
     * on the stuck counter. When the planner proposes the same query
     * again (case-insensitive) while the agent is still sitting on
     * that query's results page — no navigating action has executed
     * since that search — the repeat is NOT executed: the agent loop
     * records a "guard" / "correct" event, tells the planner to tap
     * the result link or read instead, and continues. This check lives
     * outside the stuck counter, so a correction costs no stuck
     * credit. Returns the correction reason, or null when the search
     * may proceed.
     */
    public static String searchRepeatReason(GhAction action,
                                            String lastSearchQuery,
                                            boolean onSearchResults) {
        if (action == null || !"search".equals(action.action)) return null;
        if (!onSearchResults || lastSearchQuery == null) return null;
        String q = (action.query == null) ? "" : action.query.trim();
        if (q.length() == 0) return null;
        if (!q.equalsIgnoreCase(lastSearchQuery.trim())) return null;
        return "repeat search corrected by guard: already on the results "
                + "page for \"" + lastSearchQuery + "\" - tap the result "
                + "link that matches the goal, or use read";
    }

    /**
     * v1.3: the hardened, history-based repeat-search correction.
     * The v1.2 condition above derives "still on the results page"
     * from agent state that the field run showed can lag reality
     * (a perception racing the navigation). This condition needs no
     * perception at all: if the immediately previous EXECUTED action
     * was a successful `search` for the same query (case-insensitive,
     * trimmed) and no tap / goto / read / type action has executed
     * since, a repeat of that search is corrected — regardless of
     * what the current perception claims the URL is (even
     * about:blank). Returns the correction reason, or null.
     */
    public static String searchRepeatByHistoryReason(GhAction action,
                                                     String lastExecutedSearchQuery,
                                                     boolean navigatedSinceSearch) {
        if (action == null || !"search".equals(action.action)) return null;
        if (lastExecutedSearchQuery == null || navigatedSinceSearch) return null;
        String q = (action.query == null) ? "" : action.query.trim();
        if (q.length() == 0) return null;
        if (!q.equalsIgnoreCase(lastExecutedSearchQuery.trim())) return null;
        return "repeat search corrected by guard: the previous executed "
                + "action was already a successful search for \""
                + lastExecutedSearchQuery + "\" - tap the result link "
                + "that matches the goal, or use read";
    }

    /** True when the goal asks a question / wants information (as
     *  opposed to asking for something to be changed or done). */
    public static boolean isQuestionGoal(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        for (int i = 0; i < QUESTION_MARKERS.length; i++) {
            if (g.contains(QUESTION_MARKERS[i])) return true;
        }
        return false;
    }

    /**
     * v1.3 auto-read trigger: the agent has ARRIVED (pageArrived's
     * token rule — title/URL matches a significant goal token, host
     * tokens excluded) on a page a question goal is about, and this
     * URL has not been auto-read yet this run. When all three hold,
     * the loop performs one read extraction itself and injects the
     * chunk into the prompt — the deterministic counterpart of the
     * v1.1 `search` fix: arrival recognition is not left to the
     * 1.5B planner, which in the field tapped page tabs instead.
     */
    public static boolean shouldAutoRead(String goal, String title,
                                         String url,
                                         Set<String> alreadyAutoReadUrls) {
        if (!isQuestionGoal(goal)) return false;
        if (url == null || url.length() == 0) return false;
        if (alreadyAutoReadUrls != null
                && alreadyAutoReadUrls.contains(url)) return false;
        // v1.6: a search-results page is a waypoint, not arrival.
        // Its title carries the target's name, so the token rule
        // below fired on it (v1.3 limitation, proven wasteful in
        // the v1.5 field run: ~600 extra prompt tokens / ~30 s of
        // prompt eval for snippets the preview already carries).
        if (isSearchResultsPage(title, url)) return false;
        return pageArrived(goal, title, url);
    }

    /**
     * Is this page a search-results page? Recognised by ANY of:
     *   - v1.6 rules: its title ("... Search results ...", any
     *     case) or a Special:Search path in the URL
     *     (Wikipedia-shaped; harmless elsewhere, since the path
     *     segment is distinctive);
     *   - v2.1, generalised after the first non-Wikipedia field
     *     test (trail-20261024-230228, weather.gov forecast goal):
     *     the generic search fell back to DuckDuckGo and the
     *     planner read the DDG results page to end-of-text as if
     *     it were the arrived article — auto-read fired, arrival
     *     armed, completion armed — because the v1.6 rules only
     *     knew Wikipedia's shapes. Now also:
     *       * host html.duckduckgo.com with a path starting /html/
     *       * host duckduckgo.com with a q= query parameter
     *       * a title ending " at DuckDuckGo" (any case)
     *       * host www.google.com or google.com with path /search
     *         and a q= parameter
     *       * host www.bing.com or bing.com with path /search and
     *         a q= parameter
     * A page that matches is a WAYPOINT: shouldAutoRead stands
     * down (no auto-read, so no arrival arming), and the agent
     * loop refuses to record read-completion for it (no
     * completion arming) — the planner must tap through to a
     * real result. Used to keep auto-read off waypoints and
     * available for same-query search detection
     * (searchSamePageReason) and the pagination guard.
     */
    public static boolean isSearchResultsPage(String title, String url) {
        if (title != null) {
            String t = title.toLowerCase();
            if (t.contains("search results")) return true;
            // v2.1: DuckDuckGo titles read "<query> at DuckDuckGo".
            if (t.trim().endsWith(" at duckduckgo")) return true;
        }
        if (url != null) {
            String host = "";
            String path = url;
            try {
                URL u = new URL(url);
                if (u.getHost() != null) host = u.getHost().toLowerCase();
                String p = u.getPath();
                if (p != null) path = p;
            } catch (Exception ignored) { }
            if (path.contains("Special:Search")) return true;
            // v2.1: the non-Wikipedia results shapes.
            if ("html.duckduckgo.com".equals(host)
                    && path.startsWith("/html/")) return true;
            if ("duckduckgo.com".equals(host)
                    && queryParam(url, "q") != null) return true;
            if (("www.google.com".equals(host) || "google.com".equals(host))
                    && "/search".equals(path)
                    && queryParam(url, "q") != null) return true;
            if (("www.bing.com".equals(host) || "bing.com".equals(host))
                    && "/search".equals(path)
                    && queryParam(url, "q") != null) return true;
        }
        return false;
    }

    /**
     * The decoded value of the named query parameter of a URL, or
     * null when the URL has no such parameter. Decoding handles
     * both '+' and %20 space forms; a malformed escape yields the
     * raw value rather than failing.
     */
    private static String queryParam(String url, String key) {
        if (url == null || key == null) return null;
        int qm = url.indexOf('?');
        if (qm < 0) return null;
        String qs = url.substring(qm + 1);
        int hash = qs.indexOf('#');
        if (hash >= 0) qs = qs.substring(0, hash);
        String[] pairs = qs.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            String k = (eq >= 0) ? pairs[i].substring(0, eq) : pairs[i];
            if (!key.equals(k)) continue;
            String val = (eq >= 0) ? pairs[i].substring(eq + 1) : "";
            try {
                return URLDecoder.decode(val, "UTF-8");
            } catch (Exception e) {
                return val;
            }
        }
        return null;
    }

    /**
     * v1.6: the perception-based same-query search condition. The
     * v1.5 field run's opening failure: a FRESH run started with
     * the WebView still sitting on the previous run's results
     * page, so in-run history was empty, neither older repeat
     * condition could fire, and the planner's `search` re-navigated
     * — the load that aborted and killed the run. This condition
     * needs no history at all: the CURRENT page already is the
     * results page for the proposed query when its URL carries
     * search=<same query> (URL-decoded, trimmed, case-insensitive)
     * or its title has the form "<query> - Search results ...".
     * v2.7: the URL derivation goes through resultsPageQuery, so a
     * recognised results page's q=<same query> (DuckDuckGo, Google,
     * Bing) and the "<query> at DuckDuckGo" title form fire too.
     * Returns the correction reason, or null when the search may
     * proceed.
     */
    public static String searchSamePageReason(GhAction action,
                                              String pageTitle,
                                              String pageUrl) {
        if (action == null || !"search".equals(action.action)) return null;
        String q = (action.query == null) ? "" : action.query.trim();
        if (q.length() == 0) return null;
        String pageQuery = resultsPageQuery(pageTitle, pageUrl);
        boolean same = pageQuery != null
                && q.equalsIgnoreCase(pageQuery.trim());
        if (!same && pageTitle != null) {
            String t = pageTitle.trim().toLowerCase();
            String ql = q.toLowerCase();
            same = t.startsWith(ql + " - search results")
                    // v2.7: DuckDuckGo titles read "<query> at
                    // DuckDuckGo" — the field page's exact form.
                    || t.equals(ql + " at duckduckgo");
        }
        if (!same) return null;
        return "repeat search corrected by guard: already on the results "
                + "page for \"" + q + "\" - tap the result "
                + "link that matches the goal, or use read";
    }

    /**
     * v2.7: the query the CURRENT page is a results page FOR, from
     * any recognised results shape — or null when the page carries
     * no derivable query. Wikipedia's results URLs carry the query
     * in `search=` (queryFromResultsUrl, the v1.6 derivation); the
     * other recognised engines (DuckDuckGo incl. the html. lite
     * endpoint, Google, Bing) carry it in `q=`, which is consulted
     * only when isSearchResultsPage recognises the page, so a stray
     * q= on an unrelated page derives nothing. Through v2.6.1 the
     * same-page repeat-search correction knew only `search=`, so
     * on the v2.6.1 field run (trail-20261025-003150) the planner
     * re-searched the exact query already in the DuckDuckGo page's
     * q= and the guard never fired.
     */
    public static String resultsPageQuery(String pageTitle,
                                          String pageUrl) {
        String q = queryFromResultsUrl(pageUrl);
        if (q != null) return q;
        if (isSearchResultsPage(pageTitle, pageUrl)) {
            return queryParam(pageUrl, "q");
        }
        return null;
    }

    /**
     * The decoded value of the `search` query parameter of a URL
     * (Wikipedia's results pages carry the query there), or null
     * when the URL has no such parameter. Decoding handles both
     * '+' and %20 space forms; a malformed escape yields the raw
     * value rather than failing.
     */
    public static String queryFromResultsUrl(String url) {
        return queryParam(url, "search");
    }

    /** v1.6: the empty-map correction cap, per run (not per action
     *  signature — a planner hallucinating elements 1, 2, 3 uses a
     *  fresh signature each time, which is exactly the v1.5 field
     *  shape this rule answers). */
    public static final int EMPTY_MAP_CORRECTION_CAP = 3;

    /**
     * v1.6: the empty-map correction decision, factored purely so
     * the host harness can pin it. A proposed tap or type against
     * a perception with ZERO elements can only fail ("element N
     * not on page"): the page is blank, still loading, canvas-only,
     * or a load failure the error-page recovery did not recognise.
     * Correcting the proposal (guard machinery, history note) beats
     * executing a guaranteed failure; once correctionsSoFar reaches
     * EMPTY_MAP_CORRECTION_CAP the rule falls silent and the
     * governor / stuck logic owns the repeat. Returns the reason,
     * or null.
     */
    public static String emptyMapReason(GhAction action, int elementCount,
                                        int correctionsSoFar) {
        if (action == null) return null;
        if (!"tap".equals(action.action) && !"type".equals(action.action)) {
            return null;
        }
        if (elementCount > 0) return null;
        if (correctionsSoFar >= EMPTY_MAP_CORRECTION_CAP) return null;
        return "empty page map corrected by guard: the page shows no "
                + "interactive elements - it may have failed to load "
                + "or still be loading; use goto, back, wait or read "
                + "instead of tap/type";
    }

    /** v1.7: the arrival-guard correction cap, per run (not per
     *  action signature, for the same reason as the empty-map cap:
     *  a planner abandoning its page can vary the leaving action's
     *  shape each time). After the cap the guard falls silent and
     *  the planner's proposals execute — no permanent trap: a goal
     *  that genuinely needs to follow a citation off an auto-read
     *  page spends corrections first, then goes. */
    public static final int ARRIVAL_CORRECTION_CAP = 3;

    /**
     * v1.7: the arrival (commitment) guard decision, factored
     * purely so the host harness can pin it. Once auto-read has
     * fired for a URL this run, that page's opening text is already
     * in the prompt — the agent has ARRIVED and holds the answer's
     * evidence. The v1.6 field run (Clinch River goal) showed what
     * the planner does with that: standing on the article with the
     * auto-read text in its step-5 prompt, it proposed `search`
     * again and walked away. No older guard applied — the current
     * page was the article, not a results page (same-query and
     * state-based repeat conditions silent), and the intervening
     * taps had disarmed the history-based one. So while the agent
     * is still ON the auto-read URL, a proposal that would LEAVE
     * the page — `search`, `goto`, `back`, or a tap on an <a>
     * element — is corrected: answer done from the auto-read text,
     * or read for more. Everything else proceeds untouched: read,
     * scroll, wait, done, stuck, type, and taps on non-link
     * elements. The rule disarms the moment the current URL is not
     * the auto-read URL, and falls silent after
     * ARRIVAL_CORRECTION_CAP corrections. Returns the reason, or
     * null when the action may proceed.
     */
    public static String arrivalLeaveReason(GhAction action,
                                            ElementInfo element,
                                            String currentUrl,
                                            String autoReadUrl,
                                            int correctionsSoFar) {
        if (action == null) return null;
        if (autoReadUrl == null || autoReadUrl.length() == 0) return null;
        if (currentUrl == null || !currentUrl.equals(autoReadUrl)) {
            return null;
        }
        if (correctionsSoFar >= ARRIVAL_CORRECTION_CAP) return null;
        String kind = action.action;
        boolean leaves = "search".equals(kind) || "goto".equals(kind)
                || "back".equals(kind);
        if (!leaves && "tap".equals(kind)) {
            // Only a link tap navigates; a tap whose element cannot
            // be resolved to an <a> is not provably a departure.
            leaves = element != null && "a".equals(element.tag);
        }
        if (!leaves) return null;
        return "page-leaving action corrected by arrival guard: you "
                + "are on the target page and its opening text was "
                + "auto-read above - do not leave it; answer "
                + "{\"action\":\"done\"} from that text, or use "
                + "{\"action\":\"read\"} for more";
    }

    /** v1.9: the end-of-text correction cap, per run (shared across
     *  pages, in the style of the arrival and empty-map caps).
     *  After the cap the guard falls silent and reads execute
     *  again — the correction buys three explicit "you already
     *  hold this page" nudges, not a lock. */
    public static final int READ_COMPLETE_CORRECTION_CAP = 3;

    /**
     * v1.8, generalised in v1.9: the read-after-complete
     * correction, factored purely so the host harness can pin it.
     * When a page's extracted text has been read to the END — the
     * read result carried the "end of page text" marker — the
     * planner already holds the page's full text in its history;
     * another `read` has nothing new to fetch: the shared cursor
     * sits at the end, so executing it wraps to the top and
     * repeats the first chunk (v1.7 field run: exactly this, and
     * the duplicate chunk helped overflow the next prompt; v1.8
     * field run, step 7: the same wrap after the PLANNER's own
     * reads had paged to the end — v1.8's rule only recognised
     * completion reached by the auto-read, so it stood silent).
     * v1.9's completion state is fed by ANY read, auto or planner:
     * the caller passes the completed page's URL and total when
     * the agent is still on that page, and this rule corrects the
     * read — answer {"action":"done"} from the text already read.
     * It falls silent after READ_COMPLETE_CORRECTION_CAP
     * corrections in a run (shared counter, caller's), and never
     * fires for a page whose text has not been read to the end.
     * completeTotalChars <= 0 leaves the totals out of the reason
     * (completion known, total unparsed). Returns the reason, or
     * null when the read may proceed.
     */
    public static String readAfterCompleteReason(GhAction action,
                                                 String currentUrl,
                                                 String completeUrl,
                                                 int completeTotalChars,
                                                 int correctionsSoFar) {
        if (action == null || !"read".equals(action.action)) return null;
        if (completeUrl == null || completeUrl.length() == 0) {
            return null;
        }
        if (currentUrl == null || !currentUrl.equals(completeUrl)) {
            return null;
        }
        if (correctionsSoFar >= READ_COMPLETE_CORRECTION_CAP) {
            return null;
        }
        String range = (completeTotalChars > 0)
                ? " (chars 0-" + completeTotalChars + " of "
                        + completeTotalChars + ")"
                : "";
        return "read corrected by guard: you have read this entire "
                + "page" + range + " - reading again only repeats "
                + "it from the top; answer {\"action\":\"done\"} "
                + "from the text you have read";
    }

    /** v2.0: the pagination correction cap, per run. Two explicit
     *  "the result is on this page" corrections; after that the
     *  planner's paging executes (a determined planner may be
     *  paging for a reason the map match cannot see). */
    public static final int PAGINATION_CORRECTION_CAP = 2;

    /** v2.0: whole-name pagination controls (compared trimmed,
     *  case-insensitively); bare page numbers ("2", "13") are
     *  recognised separately in isPaginationName. */
    private static final String[] PAGINATION_NAMES = {
            "next page", "previous page", "next »", "next ›",
            "more results",
    };

    /** True when an element's whole trimmed name is a pagination
     *  control: one of PAGINATION_NAMES, or a bare page number of
     *  one to three digits. */
    public static boolean isPaginationName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase();
        if (n.length() == 0) return false;
        for (int i = 0; i < PAGINATION_NAMES.length; i++) {
            if (PAGINATION_NAMES[i].equals(n)) return true;
        }
        if (n.length() <= 3) {
            boolean digits = true;
            for (int i = 0; i < n.length(); i++) {
                if (!Character.isDigit(n.charAt(i))) {
                    digits = false;
                    break;
                }
            }
            if (digits) return true;
        }
        return false;
    }

    /**
     * The first link in the map whose name matches a significant
     * goal token (the guard family's nameMatches rule — for the
     * Clinch River goal, the result link literally named "Clinch
     * River"), or null when the map holds no goal-matching result.
     * This is the guard's licence to discourage paging: the match
     * must be provably on THIS page.
     */
    public static ElementInfo goalMatchingLink(String goal,
                                               List<ElementInfo> elements) {
        if (elements == null) return null;
        Set<String> tokens = significantTokens(goal);
        if (tokens.isEmpty()) return null;
        for (ElementInfo e : elements) {
            if (e != null && "a".equals(e.tag)
                    && nameMatches(e.name, tokens)) {
                return e;
            }
        }
        return null;
    }

    /**
     * v2.0: the results-page pagination correction, factored
     * purely so the host harness can pin it. Field evidence
     * (v1.9's first passing run): on the Clinch River results
     * page the planner tapped "Next page" twice (steps 3-4),
     * paging away from a page whose map held the exact-match
     * result link; it recovered by itself at step 5, but nothing
     * discouraged leaving. So: while the agent is ON a
     * search-results page (isSearchResultsPage) and the current
     * map provably contains a goal-matching result link, a
     * proposed tap on a pagination control is corrected — tap the
     * match instead. When NO goal-matching link is in the map the
     * rule is silent and paging proceeds untouched: paging is
     * legitimate when the results are poor. Falls silent after
     * PAGINATION_CORRECTION_CAP corrections per run. Returns the
     * reason (naming the matching element), or null.
     */
    public static String paginationReason(String goal, String pageTitle,
                                          String pageUrl, GhAction action,
                                          ElementInfo element,
                                          List<ElementInfo> elements,
                                          int correctionsSoFar) {
        if (action == null || !"tap".equals(action.action)) return null;
        if (element == null) return null;
        if (correctionsSoFar >= PAGINATION_CORRECTION_CAP) return null;
        if (!isSearchResultsPage(pageTitle, pageUrl)) return null;
        if (!isPaginationName(element.name)) return null;
        ElementInfo match = goalMatchingLink(goal, elements);
        if (match == null) return null;
        return "pagination tap corrected by guard: a result matching "
                + "your goal is on THIS page (#" + match.number + " \""
                + match.name + "\") - tap it instead of paging away";
    }

    /** v3.1: the repeat-action correction cap, per semantic key
     *  per run. Two explicit "you already did that" corrections
     *  for a key; after that the proposal executes again and the
     *  run's stuck machinery owns any further repetition. */
    public static final int REPEAT_ACTION_CORRECTION_CAP = 2;

    /**
     * v3.1: the semantic key under which an executed action is
     * remembered by the repeat-action guard, or null when the
     * action carries no repeat identity. Field evidence
     * (trail-20261025-025203, the v3.0.0 Hawaii fares run): the
     * planner tapped element #1 "Google Search" at step 2,
     * read the fares at step 3, then proposed the SAME tap at
     * step 4 — and nothing objected, because the older guards
     * key on consecutive state (the stuck counter's per-page
     * counts, the repeat-search rule's "nothing moved since")
     * and the intervening read disarmed each of them in turn.
     * A repeat is a property of the RUN, not of the last step,
     * so the ledger is run-scoped and the key is semantic, not
     * positional:
     *   - tap / type: the element identity the v2.0 heal itself
     *     verifies — name + href when the element carries an
     *     href, else name + tag + type. Two elements with the
     *     same visible name but different hrefs are DIFFERENT
     *     keys (tapping the second is not a repeat), and an
     *     element whose identity genuinely changed (a renamed
     *     button, an updated href) keys differently too. An
     *     element that cannot be resolved, or carries neither
     *     name, href nor type, has no provable identity and
     *     keys to null: the guard stays silent rather than
     *     guess.
     *   - goto: the (trimmed) URL.
     *   - search: null — the dedicated repeat-search
     *     corrections above own it, with their own conditions
     *     and caps.
     *   - note / done / stuck: null, exempt by nature.
     *   - read / scroll / back / wait: null, exempt —
     *     pagination and navigation are legitimately
     *     repetitive.
     */
    public static String repeatActionKey(GhAction action,
                                         ElementInfo element) {
        if (action == null) return null;
        if ("goto".equals(action.action)) {
            String u = (action.url == null) ? "" : action.url.trim();
            return (u.length() == 0) ? null : "goto|" + u;
        }
        if ("tap".equals(action.action)
                || "type".equals(action.action)) {
            if (element == null) return null;
            String name = (element.name == null) ? ""
                    : element.name.trim();
            String href = (element.href == null) ? ""
                    : element.href.trim();
            String tag = (element.tag == null) ? ""
                    : element.tag.trim();
            String type = (element.type == null) ? ""
                    : element.type.trim();
            if (name.length() == 0 && href.length() == 0
                    && type.length() == 0) {
                // Nothing to identify it by: a repeat cannot be
                // proven, so the guard stays silent.
                return null;
            }
            String identity = (href.length() > 0)
                    ? name + "\n" + href
                    : name + "\n" + tag + "\n" + type;
            return action.action + "|" + identity;
        }
        return null;
    }

    /**
     * v3.1: the repeat-action correction, factored purely so
     * the host harness can pin it. When the planner proposes a
     * keyed action whose key this run has already EXECUTED —
     * executedKeys is the loop's ledger of SUCCESSFULLY executed
     * actions (a failed attempt did nothing and proves nothing,
     * so it is never in the ledger; its retries belong to the
     * navigation-race / stale-stash machinery and the stuck
     * counter) — the proposal is corrected rather than
     * repeated: the loop records the correction, names what was
     * already done, and steers at the moves that make progress
     * (read more, note the facts, or finish). Because the
     * ledger is run-scoped, interleaved actions do not disarm
     * the rule — the field's tap / read / tap pattern is
     * exactly the shape it exists for. Falls silent after
     * REPEAT_ACTION_CORRECTION_CAP corrections FOR THAT KEY;
     * after the cap the proposal executes and the stuck
     * counter owns it. Returns the reason, or null when the
     * action may proceed.
     */
    public static String repeatActionReason(GhAction action,
                                            ElementInfo element,
                                            Set<String> executedKeys,
                                            int correctionsSoFar) {
        if (action == null) return null;
        if (correctionsSoFar >= REPEAT_ACTION_CORRECTION_CAP) {
            return null;
        }
        String key = repeatActionKey(action, element);
        if (key == null) return null;
        if (executedKeys == null || !executedKeys.contains(key)) {
            return null;
        }
        String what;
        if ("goto".equals(action.action)) {
            what = "went to " + (action.url == null ? ""
                    : action.url.trim());
        } else if ("tap".equals(action.action)) {
            what = "tapped " + elementDisplayName(element,
                    "that element");
        } else {
            what = "typed into " + elementDisplayName(element,
                    "that field");
        }
        return "repeat action corrected by guard: you already "
                + what + " earlier in this run - repeating it "
                + "changes nothing; read more of this page, note "
                + "the key facts with {\"action\":\"note\","
                + "\"fact\":\"...\"}, or answer "
                + "{\"action\":\"done\"}";
    }

    /** The display form of an element for the repeat-action
     *  reason: its quoted visible name when it has one, its
     *  destination for a nameless link, else the caller's
     *  fallback phrase. */
    private static String elementDisplayName(ElementInfo element,
                                             String fallback) {
        if (element == null) return fallback;
        String name = (element.name == null) ? "" : element.name.trim();
        if (name.length() > 0) return "\"" + name + "\"";
        String href = (element.href == null) ? "" : element.href.trim();
        if (href.length() > 0) return "the link to " + href;
        return fallback;
    }

    /** v2.1: the done-grounding correction cap, per run. Two
     *  explicit "your summary states no values" corrections; after
     *  that dones execute exactly as before — the guard buys two
     *  pointed re-asks, never a trap. */
    public static final int DONE_CORRECTION_CAP = 2;

    /** v2.1: substrings that make a goal request number-shaped
     *  facts (matched case-insensitively against the goal text).
     *  Only goals carrying one of these cues are held to the
     *  done-grounding rule; every other done is the planner's. */
    private static final String[] NUMBER_CUES = {
            "temperature", "chance of rain", "rain chance", "percent",
            "%", "price", "cost", "how much", "how many", "how long",
            "how far", "when is", "what date", "what time",
            "population", "distance",
    };

    /** v2.1: phrases that plainly state inability — an honest
     *  "I could not find it" done is GROUNDED and always accepted,
     *  digits or not (matched case-insensitively). */
    private static final String[] INABILITY_PHRASES = {
            "could not find", "couldn't find", "not available",
            "unable to", "no information", "not found",
    };

    /** True when the goal asks for number-shaped facts (a
     *  temperature, a price, a count, a date...): it carries one
     *  of NUMBER_CUES. Only then must a done summary carry a
     *  value. */
    public static boolean goalRequestsNumbers(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        for (int i = 0; i < NUMBER_CUES.length; i++) {
            if (g.contains(NUMBER_CUES[i])) return true;
        }
        return false;
    }

    /**
     * True when a done summary is grounded: it contains at least
     * one digit (a value the planner can only state by having
     * read one) or it plainly states inability. This checks the
     * summary's SHAPE only — whether the number is the RIGHT one
     * remains the planner's honesty, exactly as the read-after-
     * complete correction's limit noted in v1.9.
     */
    public static boolean doneSummaryGrounded(String summary) {
        if (summary == null) return false;
        for (int i = 0; i < summary.length(); i++) {
            if (Character.isDigit(summary.charAt(i))) return true;
        }
        String s = summary.toLowerCase();
        for (int i = 0; i < INABILITY_PHRASES.length; i++) {
            if (s.contains(INABILITY_PHRASES[i])) return true;
        }
        return false;
    }

    /**
     * v2.1: the done-grounding correction, factored purely so the
     * host harness can pin it. Field evidence (trail-20261024-
     * 230228, the weather.gov forecast goal — the first non-
     * Wikipedia test): the run survived every mechanical hazard
     * (missed search box, mid-navigation tap, results page read
     * to end-of-text) and then declared done with the summary
     * "Oakdale, Tennessee forecast provides tonight's low
     * temperature and chance of rain." — a restatement of the
     * goal carrying NEITHER requested value. The machinery held;
     * the answer discipline did not. So when the goal requests
     * number-shaped facts (goalRequestsNumbers) and the proposed
     * done's summary is ungrounded (doneSummaryGrounded — no
     * digit, no plain inability), the done is corrected through
     * the standard guard machinery: open the most relevant
     * result, read its actual values, answer with the numbers —
     * or say plainly that they could not be found. Falls silent
     * after DONE_CORRECTION_CAP corrections per run; a done for
     * a non-numeric goal is never touched. Returns the reason,
     * or null when the done may proceed.
     */
    public static String doneGroundingReason(GhAction action, String goal,
                                             int correctionsSoFar) {
        return doneGroundingReason(action, goal, correctionsSoFar, null);
    }

    /**
     * v3.0 (item 1): the evidence-aware done-grounding correction.
     * Through v2.9 the guard checked the summary's SHAPE only: any
     * digit grounded it. That let a plausible-but-invented number
     * pass (the shape is right; the value was never read). With
     * the scratchpad, the run now HAS an evidence base — the text
     * it read (the history's read chunks) union the facts it noted
     * — so the check becomes: a number in the summary grounds the
     * done only when that same number appears in the evidence.
     * A summary whose numbers are all absent from the evidence is
     * corrected exactly like a numberless one (same reason, same
     * cap). A plain statement of inability grounds as before —
     * honesty about failure needs no digits. When evidence is
     * null the v2.1 shape rule applies unchanged (the older call
     * shape, kept for its own pins); the loop always passes the
     * real evidence.
     */
    public static String doneGroundingReason(GhAction action, String goal,
                                             int correctionsSoFar,
                                             String evidence) {
        if (action == null || !"done".equals(action.action)) return null;
        if (correctionsSoFar >= DONE_CORRECTION_CAP) return null;
        if (!goalRequestsNumbers(goal)) return null;
        if (doneSummaryGrounded(action.summary, evidence)) return null;
        return "done corrected by answer-grounding guard: your summary "
                + "gives none of the values the goal asks for - open "
                + "the most relevant result and read its actual "
                + "values, then answer {\"action\":\"done\"} with the "
                + "numbers in the summary, or say plainly that you "
                + "could not find them";
    }

    /**
     * v3.0: is this done summary grounded against the given
     * evidence? An inability phrase grounds it outright. Else it
     * needs at least one number, and — when evidence is provided
     * (non-null) — at least one of the summary's numbers must
     * appear among the evidence's numbers (numberTokens on both
     * sides, so "1,117" the planner read matches "1117" it
     * answers, and "43.5" matches "43.5"). With null evidence
     * the v2.1 shape rule decides (any digit grounds).
     */
    public static boolean doneSummaryGrounded(String summary,
                                              String evidence) {
        if (summary == null) return false;
        String s = summary.toLowerCase();
        for (int i = 0; i < INABILITY_PHRASES.length; i++) {
            if (s.contains(INABILITY_PHRASES[i])) return true;
        }
        List<String> nums = numberTokens(summary);
        if (nums.isEmpty()) return false;
        if (evidence == null) return true;
        Set<String> evNums = new LinkedHashSet<String>(
                numberTokens(evidence));
        for (int i = 0; i < nums.size(); i++) {
            if (evNums.contains(nums.get(i))) return true;
        }
        return false;
    }

    // ---------------- v3.10 (R2): the planner styles' guards ---

    /** v3.10 (Careful): the verify-read correction cap, per
     *  run. ONE explicit "read this page before finishing"
     *  correction; after that dones execute exactly as before —
     *  like the family's other caps, the guard buys a pointed
     *  re-ask, never a trap. */
    public static final int VERIFY_READ_CORRECTION_CAP = 1;

    /**
     * v3.10 (Everything Train R2 — the Careful style): the
     * verify-read correction. Careful's one REAL guard change:
     * a done proposed on a page that has not been read since
     * the last navigation is corrected once — the planner is
     * finishing from a page whose text it has never taken in
     * (no read executed on it since arriving, no auto-read
     * section shown for it, and it was not read to completion
     * earlier this run — the loop assembles those three into
     * pageRead, this rule judges only the proposal). A done on
     * a read page, a non-done action, and every done after
     * VERIFY_READ_CORRECTION_CAP corrections are untouched.
     * Returns the reason, or null when the done may proceed.
     */
    public static String doneVerifyReadReason(GhAction action,
                                              boolean pageRead,
                                              int correctionsSoFar) {
        if (action == null || !"done".equals(action.action)) {
            return null;
        }
        if (pageRead) return null;
        if (correctionsSoFar >= VERIFY_READ_CORRECTION_CAP) {
            return null;
        }
        return "done corrected by verify guard (careful style): "
                + "you have not read the page you are on - verify: "
                + "read the current page before finishing, then "
                + "answer {\"action\":\"done\"} from its text";
    }

    /** v3.10 (Thorough): the cross-check correction cap, per
     *  run — the same count as the done-grounding guard's
     *  (DONE_CORRECTION_CAP): two pointed re-asks, then the
     *  guard falls silent. */
    public static final int CROSS_CHECK_CORRECTION_CAP = 2;

    /** v3.10: substrings that make a goal COMPARISON-shaped
     *  (matched case-insensitively against the goal text):
     *  the goal sets two or more candidates side by side and
     *  asks for a verdict between them. Only then does the
     *  Thorough cross-check ask for a second source. */
    private static final String[] COMPARISON_CUES = {
            "compare", "comparison", "cheaper", "cheapest",
            "better deal", "best price", "best value", "versus",
            " vs ", "vs.", "difference between", "which is",
            "which one", "which has", "which offers",
    };

    /** True when the goal is comparison-shaped (see
     *  COMPARISON_CUES). */
    public static boolean isComparisonGoal(String goal) {
        if (goal == null) return false;
        String g = goal.toLowerCase();
        for (int i = 0; i < COMPARISON_CUES.length; i++) {
            if (g.contains(COMPARISON_CUES[i])) return true;
        }
        return false;
    }

    /** True when a text plainly states inability (the
     *  INABILITY_PHRASES the grounding guard accepts as an
     *  honest answer). Factored out for the cross-check: an
     *  honest "could not find it" owes no second source. */
    public static boolean statesInability(String text) {
        if (text == null) return false;
        String s = text.toLowerCase();
        for (int i = 0; i < INABILITY_PHRASES.length; i++) {
            if (s.contains(INABILITY_PHRASES[i])) return true;
        }
        return false;
    }

    /**
     * v3.10 (Everything Train R2 — the Thorough style): the
     * cross-check correction, the done-grounding guard's
     * numeric demand extended. When the goal both requests
     * numbers (goalRequestsNumbers — the grounding guard's
     * own gate, so this rule lives strictly inside that
     * guard's family) and is comparison-shaped, a done whose
     * evidence comes from fewer than two DISTINCT read pages
     * is corrected: a comparison verdict from one source is a
     * claim, not a comparison. distinctReadPages counts the
     * pages the run has actually read (planner reads and
     * auto-reads alike — the loop keeps the set). A plain
     * statement of inability is never corrected (it grounds
     * as honesty under the grounding guard too). Falls silent
     * after CROSS_CHECK_CORRECTION_CAP corrections. The loop
     * consults this rule only after the grounding guard has
     * passed the done, and only under the Thorough style.
     * Returns the reason, or null when the done may proceed.
     */
    public static String crossCheckReason(GhAction action,
                                          String goal,
                                          int correctionsSoFar,
                                          int distinctReadPages) {
        if (action == null || !"done".equals(action.action)) {
            return null;
        }
        if (correctionsSoFar >= CROSS_CHECK_CORRECTION_CAP) {
            return null;
        }
        if (!goalRequestsNumbers(goal)) return null;
        if (!isComparisonGoal(goal)) return null;
        if (statesInability(action.summary)) return null;
        if (distinctReadPages >= 2) return null;
        return "done corrected by cross-check guard (thorough "
                + "style): this goal compares, but your evidence "
                + "comes from " + distinctReadPages + " read "
                + "page(s) - cross-check: read a second source "
                + "before finishing, then answer "
                + "{\"action\":\"done\"} with both sides' values";
    }

    /**
     * The number tokens of a text: maximal digit runs, with a
     * decimal point kept when it sits between digits ("43.5")
     * and grouping commas dropped ("1,117" -> "1117"), so the
     * same value tokenizes identically however it was written.
     */
    public static List<String> numberTokens(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null) return out;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') { i++; continue; }
            StringBuilder tok = new StringBuilder();
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d >= '0' && d <= '9') {
                    tok.append(d);
                    i++;
                } else if (d == ',' && i + 1 < s.length()
                        && s.charAt(i + 1) >= '0'
                        && s.charAt(i + 1) <= '9') {
                    i++; // a grouping comma is not part of the value
                } else if (d == '.' && i + 1 < s.length()
                        && s.charAt(i + 1) >= '0'
                        && s.charAt(i + 1) <= '9'
                        && tok.indexOf(".") < 0) {
                    tok.append(d);
                    i++;
                } else {
                    break;
                }
            }
            out.add(tok.toString());
        }
        return out;
    }

    /** The normalised chrome-tab label of an element, or null when
     *  the element is not a page-chrome tab. */
    public static String chromeTabLabel(ElementInfo element) {
        if (element == null || !"a".equals(element.tag)) return null;
        String n = (element.name == null ? "" : element.name.trim()
                .toLowerCase());
        for (int i = 0; i < CHROME_TAB_NAMES.length; i++) {
            if (CHROME_TAB_NAMES[i].equals(n)) return n;
        }
        return null;
    }

    /**
     * v1.3: the chrome-tab correction. On an ARRIVED page (title/URL
     * matches a significant goal token), a tap on a page-chrome tab
     * link ("Article", "Talk", "Read", "Edit", "View history",
     * "Contents") changes nothing — the planner is already looking
     * at the article — yet the v1.2 field run tapped "Article" twice
     * in a row because the goal's words said "open the article".
     * The tap is corrected (not executed) once per label per run;
     * the caller tracks the labels in alreadyCorrected and adds the
     * label after each correction. Returns the reason, or null.
     */
    public static String chromeTabReason(String goal, String pageTitle,
                                         String pageUrl, GhAction action,
                                         ElementInfo element,
                                         Set<String> alreadyCorrected) {
        if (action == null || !"tap".equals(action.action)) return null;
        String label = chromeTabLabel(element);
        if (label == null) return null;
        if (alreadyCorrected != null
                && alreadyCorrected.contains(label)) return null;
        if (!pageArrived(goal, pageTitle, pageUrl)) return null;
        return "page tab tap corrected by guard: \"" + element.name
                + "\" is a page tab - it does not change the page; "
                + "you are already on the article - use read";
    }

    /** Package-private (not private) since v2.4: Eyes' goal-match
     *  map highlighting shares THIS matcher — one implementation
     *  of "does this link's name match the goal", used by the
     *  guards that judge taps and by the map that steers them. */
    static boolean nameMatches(String name, Set<String> tokens) {
        if (name == null) return false;
        for (String w : words(name)) {
            for (String t : tokens) {
                if (w.equals(t) || (w.length() >= 4 && w.contains(t))
                        || (t.length() >= 4 && t.contains(w) && w.length() >= 4)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSearchOrNavControl(ElementInfo e) {
        String[] fields = { e.name, e.label, e.placeholder, e.href };
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null && fields[i].toLowerCase().contains("search")) {
                return true;
            }
        }
        String n = (e.name == null ? "" : e.name.trim().toLowerCase());
        for (int i = 0; i < NAV_NAMES.length; i++) {
            if (n.equals(NAV_NAMES[i])) return true;
        }
        return false;
    }

    private static Set<String> hostTokens(String url) {
        Set<String> out = new LinkedHashSet<String>();
        if (url == null) return out;
        try {
            String host = new URL(url).getHost();
            if (host != null) {
                for (String w : words(host)) out.add(w);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static boolean isStopword(String w) {
        for (int i = 0; i < STOPWORDS.length; i++) {
            if (STOPWORDS[i].equals(w)) return true;
        }
        return false;
    }

    /** Lowercased alphanumeric words of a string. */
    private static Set<String> words(String s) {
        Set<String> out = new LinkedHashSet<String>();
        if (s == null) return out;
        String low = s.toLowerCase();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i <= low.length(); i++) {
            char c = (i < low.length()) ? low.charAt(i) : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        return out;
    }

    /** v3.12 (R4): the per-run cap on executed asks. Asking is the
     *  safe move, but an uncapped ask channel is an interrogation
     *  loop: two genuine clarifications per run, then the planner
     *  decides from what it has (or answers done / stuck). The
     *  budget is spent only by asks that EXECUTE — a corrected ask
     *  consumes nothing, exactly like the other guards' corrected
     *  proposals. */
    public static final int ASK_CAP = 2;

    /**
     * v3.12 (R4): the ask correction. Pure, like every guard here;
     * consulted by the loop for ASK proposals only, in the guard
     * family before the governor. Returns a correction reason when
     * the proposal must not execute:
     *   - the run's ask budget (ASK_CAP) is already spent, or
     *   - the immediately previous EXECUTED action was itself an
     *     ask — two questions in a row with no intervening page
     *     action are an interrogation loop, however the budget
     *     stands.
     * Non-ask actions and asks inside both limits return null.
     * There is deliberately NO correction cap of its own: after
     * the budget is spent every further ask corrects forever, and
     * the run's step budget is the backstop, as with the other
     * permanent corrections.
     */
    public static String askCorrectionReason(GhAction action,
                                             int asksUsed,
                                             String lastExecutedKind) {
        if (action == null || !"ask".equals(action.action)) return null;
        if (asksUsed >= ASK_CAP) {
            return "ask limit reached: you have already asked "
                    + asksUsed + " questions this run (the limit is "
                    + ASK_CAP + "). Decide from what you have, or "
                    + "answer done or stuck.";
        }
        if ("ask".equals(lastExecutedKind)) {
            return "two questions in a row: ask one question, then "
                    + "act on the page before asking another.";
        }
        return null;
    }

    /** v3.15 (R7): the hard cap on open browser tabs. Three tabs
     *  is what the phone can hold beside the 1.5B model with the
     *  inactive WebViews paused; a fourth open_tab is refused
     *  with a legible correction (the guard family's shape), not
     *  a crash — the planner closes or reuses a tab instead. The
     *  execution path enforces the same cap (defense in depth),
     *  so a proposal that somehow slips the guard still cannot
     *  open a fourth tab. */
    public static final int TAB_CAP = 3;

    /**
     * v3.15 (R7): the tab-limit correction. Pure, like every guard
     * here; consulted by the loop for open_tab proposals only, in
     * the guard family before the governor. Returns a correction
     * reason when the run already has TAB_CAP tabs open; switch
     * and close proposals, runs under the cap, and null actions
     * return null. There is deliberately NO correction cap of its
     * own: a planner that keeps proposing a fourth tab keeps
     * being corrected, and the run's step budget is the backstop,
     * as with the ask cap.
     */
    public static String tabLimitReason(GhAction action, int openTabs) {
        if (action == null || !"open_tab".equals(action.action)) {
            return null;
        }
        if (openTabs >= TAB_CAP) {
            return "tab limit: " + TAB_CAP + " tabs are already open "
                    + "(the limit is " + TAB_CAP + "). Switch to a tab "
                    + "with switch_tab, or close one with close_tab, "
                    + "instead of opening another.";
        }
        return null;
    }
}
