package com.ghosthands.app;

/**
 * v3.6 (READING setting — fallback search engine): the engines
 * the search action's fallback path can aim at when a page has
 * no usable search box of its own, and the pure mapping from an
 * engine choice to the URL the fallback navigates to and the
 * line it reports.
 *
 * The fallback has existed since v1.1 (path (c) of the search
 * action) and pointed at DuckDuckGo's HTML endpoint, hardcoded.
 * The results-page recognition that keeps the loop honest on
 * whatever comes back (RelevanceGuard) has covered DuckDuckGo,
 * Google and Bing since v2.1 — so the choice is exactly the
 * navigation target and its report line, nothing deeper: the
 * planner reads whichever results page arrives through the same
 * perception as any other page.
 *
 * The DuckDuckGo mapping reproduces the pre-v3.6 behaviour
 * byte-for-byte — the same endpoint and the same report string —
 * because it is the default: an install that never touches the
 * setting searches exactly where it always searched.
 *
 * Pure: no Android. The Activity owns the WebView navigation;
 * this class only decides where "fallback" points.
 */
public final class SearchEngines {

    public static final String DDG = "duckduckgo";
    public static final String GOOGLE = "google";
    public static final String BING = "bing";

    /** The offered choices, in chip order. */
    public static final String[] NAMES = {DDG, GOOGLE, BING};

    /** The default (the behaviour every build through v3.5 had). */
    public static final String DEFAULT_ID = DDG;

    private SearchEngines() { }

    /** A stored choice resolved to a known engine id: unknown
     *  or absent values are the default. Case-insensitive; the
     *  canonical (lowercase) id is what survives. */
    public static String resolveId(String saved) {
        if (saved != null) {
            String key = saved.trim().toLowerCase(java.util.Locale.US);
            for (int i = 0; i < NAMES.length; i++) {
                if (NAMES[i].equals(key)) return key;
            }
        }
        return DEFAULT_ID;
    }

    /** The chip / report name of an engine id. */
    public static String displayName(String id) {
        String r = resolveId(id);
        if (GOOGLE.equals(r)) return "Google";
        if (BING.equals(r)) return "Bing";
        return "DuckDuckGo";
    }

    /**
     * The results URL the fallback navigates to for an
     * already-URL-encoded query. The DuckDuckGo form is the
     * pre-v3.6 URL verbatim (the html. endpoint, which serves a
     * plain results page the perception reads well); Google and
     * Bing use their standard search endpoints.
     */
    public static String searchUrl(String id, String encodedQuery) {
        String q = (encodedQuery == null) ? "" : encodedQuery;
        String r = resolveId(id);
        if (GOOGLE.equals(r)) return "https://www.google.com/search?q=" + q;
        if (BING.equals(r)) return "https://www.bing.com/search?q=" + q;
        return "https://html.duckduckgo.com/html/?q=" + q;
    }

    /**
     * The search action's fallback report line for a (raw,
     * unencoded) query — for DuckDuckGo this is byte-for-byte
     * the string the action has returned since v1.1.
     */
    public static String searchedMessage(String id, String query) {
        return "ok: no search box on this page; searched "
                + displayName(id) + " for \"" + query + "\"";
    }
}
