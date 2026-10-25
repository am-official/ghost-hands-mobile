package com.ghosthands.app;

/**
 * v3.7 (DATA — clear browsing data): the scope of the third
 * clear action, as data. The clear itself is two framework
 * calls the Activity makes (CookieManager + WebStorage) plus
 * a reload of the open page — none of which can reach a file
 * or a preference — and this class is the pin that keeps it
 * that way: the complete list of stores the action CLEARS
 * and the complete list it KEEPS, disjoint by construction,
 * host-tested exhaustively. It is DataWipe's structural
 * promise restated for a wipe that deletes no files at all:
 * if a future change wants the browsing clear to touch
 * anything else, it has to amend this table — and the
 * harness will say so.
 *
 * v3.13 (Everything Train R5) amends the table exactly that
 * way, deliberately: SITE GRANTS join the clears. A grant is
 * browsing-derived trust — the user's standing approvals of
 * sites the browser has visited — so the action that makes
 * pages see this phone as a fresh visitor also withdraws the
 * approvals those pages accumulated. The Activity's clear
 * empties the grant store beside the two framework calls;
 * the harness pins the amended table.
 */
public final class BrowsingData {

    /** The stores the action clears: the WebView's cookies
     *  and its site storage (local storage / Web SQL / IndexedDB
     *  as the framework's WebStorage owns them) — and, since
     *  v3.13, the site grants (see the class doc). */
    public static final String COOKIES = "cookies";
    public static final String WEB_STORAGE = "web_storage";
    public static final String SITE_GRANTS = "site_grants";

    private static final String[] CLEARS = {COOKIES, WEB_STORAGE, SITE_GRANTS};

    /** The stores the action never touches — the app's own
     *  records, each named so the table reads as the promise
     *  the confirm dialog makes. */
    private static final String[] KEEPS = {
            "trails", "shots", "goals", "routes", "models",
            "settings"};

    private BrowsingData() { }

    /** The stores the clear action clears (a copy). */
    public static String[] clears() {
        return CLEARS.clone();
    }

    /** The stores the clear action keeps (a copy). */
    public static String[] keeps() {
        return KEEPS.clone();
    }

    /** True when `store` is one the action clears. An unknown
     *  name is cleared by nothing — the table is the whole
     *  scope, not a pattern. */
    public static boolean clears(String store) {
        return contains(CLEARS, store);
    }

    /** True when `store` is one the action keeps. */
    public static boolean keeps(String store) {
        return contains(KEEPS, store);
    }

    private static boolean contains(String[] set, String store) {
        if (store == null) return false;
        for (int i = 0; i < set.length; i++) {
            if (set[i].equals(store)) return true;
        }
        return false;
    }
}
