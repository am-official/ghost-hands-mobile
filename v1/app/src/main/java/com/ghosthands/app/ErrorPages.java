package com.ghosthands.app;

/**
 * Error-page detection (v1.6) — pure helpers over a perception, no
 * Android, host-testable.
 *
 * Why it exists: the v1.5 field run (run 2, same Clinch River goal)
 * died of a NETWORK error, not a planner failure. A `search`
 * re-navigation aborted (net::ERR_CONNECTION_ABORTED) and the
 * WebView landed on Chrome's error page — url
 * "chrome-error://chromewebdata/", title "Webpage not available",
 * a preview naming the failed URL and the net error, and ZERO
 * elements. The planner then hallucinated taps on elements 1/2/3
 * ("error: element N not on page" three times) until the stuck
 * detector stopped the run. The agent loop now asks this class
 * two questions per perception: is this a load-failure page, and
 * which URL failed — so it can reload once and, failing that,
 * stop honestly instead of burning steps on a dead page.
 *
 * Detection is deliberately narrow and evidence-based: the
 * WebView's own error URL scheme, or the literal net::ERR_ token
 * Chrome prints on its error page (in the title or the text
 * preview), or that page's exact title. A healthy page that merely
 * TALKS about an error in its body text does not trip the title
 * checks; the preview check keys on the net::ERR_ token only,
 * which ordinary prose does not contain.
 */
public final class ErrorPages {

    private ErrorPages() { }

    /** True when this perception is a browser load-failure page. */
    public static boolean isErrorPage(String url, String title,
                                      String textPreview) {
        if (url != null && url.startsWith("chrome-error://")) return true;
        if (containsNetError(title) || containsNetError(textPreview)) {
            return true;
        }
        if (title != null && title.contains("Webpage not available")) {
            return true;
        }
        return false;
    }

    private static boolean containsNetError(String s) {
        return s != null && s.contains("net::ERR_");
    }

    /**
     * The URL that failed to load, parsed from the error page's own
     * text: "The webpage at <url> could not be loaded because: ...".
     * The perception's url field is the chrome-error placeholder,
     * NOT the failed page, so the preview text is the only place
     * the real URL survives. Returns null when the page does not
     * name one (the caller then falls back to a plain reload).
     */
    public static String failedUrl(Eyes.Perception p) {
        if (p == null) return null;
        return failedUrl(p.textPreview);
    }

    /** As failedUrl(Perception), over a raw preview string. */
    public static String failedUrl(String textPreview) {
        if (textPreview == null) return null;
        String marker = "The webpage at ";
        int start = textPreview.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = textPreview.indexOf(" could not be loaded", start);
        if (end < 0) return null;
        String url = textPreview.substring(start, end).trim();
        return url.length() > 0 ? url : null;
    }

    /**
     * The net error token the error page reports (e.g.
     * "net::ERR_CONNECTION_ABORTED"), from the preview first, then
     * the title. Returns null when neither carries one.
     */
    public static String netError(String title, String textPreview) {
        String e = netErrorIn(textPreview);
        if (e != null) return e;
        return netErrorIn(title);
    }

    private static String netErrorIn(String s) {
        if (s == null) return null;
        int start = s.indexOf("net::ERR_");
        if (start < 0) return null;
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == ':') {
                end++;
            } else {
                break;
            }
        }
        return s.substring(start, end);
    }
}
