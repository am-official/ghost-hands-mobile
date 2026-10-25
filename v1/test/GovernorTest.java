import com.ghosthands.app.Agent;
import com.ghosthands.app.AnswerCard;
import com.ghosthands.app.Copilot;
import com.ghosthands.app.ElementInfo;
import com.ghosthands.app.ErrorPages;
import com.ghosthands.app.Eyes;
import com.ghosthands.app.GhAction;
import com.ghosthands.app.Governor;
import com.ghosthands.app.ModelEngine;
import com.ghosthands.app.PlannerParse;
import com.ghosthands.app.PolicyPacks;
import com.ghosthands.app.RelevanceGuard;
import com.ghosthands.app.Routes;
import com.ghosthands.app.SavedGoals;
import com.ghosthands.app.Scratchpad;
import com.ghosthands.app.Shots;
import com.ghosthands.app.SpeakText;
import com.ghosthands.app.VoiceGoal;
import com.ghosthands.app.WatchCompare;

/**
 * Host-side smoke harness for the Ghost Hands Mobile governor port.
 * The classification cases are ported from the Python suite
 * (ghost-hands/tests/test_governor.py) and mapped onto the mobile
 * action vocabulary: click -> tap, navigate -> goto.
 *
 * Pure JVM: compiles the Android-free classes (Governor, PolicyPacks,
 * GhAction, ElementInfo, PlannerParse, Json) and runs assertions.
 * Exit code 0 = all pass.
 */
public class GovernorTest {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, Object actual, Object expected) {
        boolean ok = (expected == null) ? (actual == null) : expected.equals(actual);
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  expected=" + expected + " actual=" + actual);
        }
    }

    private static ElementInfo el(int n, String tag, String name) {
        ElementInfo e = new ElementInfo();
        e.number = n;
        e.tag = tag;
        e.name = name;
        return e;
    }

    private static GhAction tap(int n) {
        GhAction a = new GhAction("tap");
        a.el = n;
        return a;
    }

    private static GhAction type(int n, String text) {
        GhAction a = new GhAction("type");
        a.el = n;
        a.text = text;
        return a;
    }

    private static GhAction gotoUrl(String url) {
        GhAction a = new GhAction("goto");
        a.url = url;
        return a;
    }

    /** v3.2: a finished run's events in the shape the watch
     *  comparator and the answer card read — one "added" note
     *  event per fact, then the stop. */
    private static java.util.List<java.util.Map<String, Object>>
            finishedRunEvents(String[] facts, String reason,
                              String summary) {
        java.util.List<java.util.Map<String, Object>> evs =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int i = 0; i < facts.length; i++) {
            evs.add(com.ghosthands.app.Trail.fields(
                    "type", "note", "status", "added",
                    "fact", facts[i],
                    "url", "https://example.test/",
                    "title", "Example"));
        }
        evs.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", reason,
                "summary", summary));
        return evs;
    }

    /**
     * v2.7: structural sanity for a generated JS string — the
     * check whose absence let the heal scripts ship syntactically
     * invalid from v2.0 to v2.6.1. Scans with string literals and
     * comments skipped; returns null when every (), {} and [] is
     * balanced and nesting never goes negative, else a short
     * description of the first problem. Not a parser — just enough
     * to catch a misplaced brace in a string-built script.
     */
    private static String jsStructureError(String js) {
        if (js == null) return "null script";
        java.util.ArrayDeque<Character> stack =
                new java.util.ArrayDeque<Character>();
        int i = 0;
        int n = js.length();
        char lastSig = 0;
        while (i < n) {
            char c = js.charAt(i);
            if (c == '/' && i + 1 < n && js.charAt(i + 1) != '/'
                    && js.charAt(i + 1) != '*'
                    && (lastSig == 0 || "(,=:[!&|?{};".indexOf(lastSig) >= 0)) {
                // A regex literal (the generated scripts use them
                // only as call arguments, e.g. replace(/\s+/g, ' '),
                // where a division can never appear): consume to
                // the closing slash, honoring escapes and classes.
                i++;
                boolean inClass = false;
                while (i < n) {
                    char d = js.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    if (d == '[') inClass = true;
                    if (d == ']') inClass = false;
                    if (d == '/' && !inClass) { i++; break; }
                    if (d == '\n') break;
                    i++;
                }
                while (i < n && Character.isLetter(js.charAt(i))) i++;
                lastSig = '/';
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n) {
                    char d = js.charAt(i);
                    if (d == '\\') { i += 2; continue; }
                    if (d == q) { i++; break; }
                    i++;
                }
                lastSig = q;
                continue;
            }
            if (c == '/' && i + 1 < n && js.charAt(i + 1) == '/') {
                while (i < n && js.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && js.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n
                        && !(js.charAt(i) == '*' && js.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '(' || c == '{' || c == '[') {
                stack.push(Character.valueOf(c));
            } else if (c == ')' || c == '}' || c == ']') {
                if (stack.isEmpty()) {
                    return "unmatched '" + c + "' at index " + i;
                }
                char open = stack.pop().charValue();
                boolean pairs = (open == '(' && c == ')')
                        || (open == '{' && c == '}')
                        || (open == '[' && c == ']');
                if (!pairs) {
                    return "'" + open + "' closed by '" + c
                            + "' at index " + i;
                }
            }
            if (!Character.isWhitespace(c)) lastSig = c;
            i++;
        }
        if (!stack.isEmpty()) return "unclosed '" + stack.peek() + "'";
        return null;
    }

    /** v2.8: one scored read candidate, metrics as the collector
     *  would report them (fixtures below are measured on real
     *  pages — see the v2.8 section in BUILD_NOTES). */
    private static Eyes.ReadCandidate readCand(String tag, int len,
            int para, int link, boolean chrome) {
        Eyes.ReadCandidate c = new Eyes.ReadCandidate();
        c.tag = tag;
        c.textLen = len;
        c.paraLen = para;
        c.linkLen = link;
        c.chrome = chrome;
        return c;
    }

    /** A raw (unnumbered, DOM-order) candidate for Eyes.shapeMap. */
    private static ElementInfo rawEl(int dom, String tag, String type, String name) {
        ElementInfo e = new ElementInfo();
        e.domIndex = dom;
        e.tag = tag;
        e.type = type;
        e.name = name;
        return e;
    }

    /** n copies of c (sizing filler for the v1.8 budget tests). */
    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    /** v2.5 test-only: the v2.4 prompt layout, frozen verbatim so
     *  the cache-geometry measurement compares the layout change
     *  and nothing else. NOT used by the app; Agent.buildPrompt is
     *  the only production assembler. */
    private static String buildPromptV24Layout(String goal,
            Eyes.Perception p, String rendered,
            java.util.List<String> history, String autoReadText,
            int completeTotalChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(Agent.CONTRACT_BLOCK);
        sb.append("Goal: ").append(goal).append('\n');
        sb.append("Page: ").append(p.url);
        if (p.title != null && p.title.length() > 0) {
            sb.append(" - ").append(p.title);
        }
        sb.append('\n');
        if (p.textPreview != null && p.textPreview.length() > 0) {
            sb.append("Page text: ").append(p.textPreview).append('\n');
        }
        if (autoReadText != null && autoReadText.length() > 0) {
            sb.append(autoReadText).append('\n');
        }
        sb.append("Recent steps:\n");
        if (history.isEmpty()) {
            sb.append("(none)\n");
        } else {
            for (String h : history) sb.append(h).append('\n');
        }
        sb.append("Elements:\n");
        sb.append(rendered);
        sb.append("Goal: ").append(goal).append('\n');
        if (autoReadText != null && autoReadText.length() > 0) {
            sb.append("You are ON the target page and its text is "
                    + "above. Answer done from it, or read for more.\n");
        }
        if (completeTotalChars > 0) {
            sb.append("You have read this entire page. Answer done "
                    + "from it now.\n");
        }
        sb.append("Next action JSON:");
        return sb.toString();
    }

    /** The longest common prefix of two strings (the native prefix
     *  cache's reuse unit, simulated for the v2.5 measurement). */
    private static String lcp(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return a.substring(0, i);
    }

    /** A string's lines as a sorted list — two prompts carry the
     *  same information iff their line multisets are equal. */
    private static java.util.List<String> sortedLines(String s) {
        java.util.List<String> lines = new java.util.ArrayList<String>(
                java.util.Arrays.asList(s.split("\n", -1)));
        java.util.Collections.sort(lines);
        return lines;
    }

    /** v2.9 test-only: the v2.2-v2.8 token estimator, frozen
     *  verbatim (chars/4 base; lines containing "http" surcharged
     *  to chars/3) so the Item 2 audit measures the estimator
     *  change and nothing else. NOT used by the app;
     *  Agent.estTokens is the only production estimator. */
    private static int estTokensV22(String s) {
        if (s == null) return 0;
        int tokens = (s.length() + 3) / 4;
        int start = 0;
        while (start < s.length()) {
            int nl = s.indexOf('\n', start);
            int end = (nl < 0) ? s.length() : nl;
            boolean http = false;
            for (int i = start; i + 4 <= end && !http; i++) {
                char a = s.charAt(i);
                if ((a == 'h' || a == 'H')
                        && (s.charAt(i + 1) == 't' || s.charAt(i + 1) == 'T')
                        && (s.charAt(i + 2) == 't' || s.charAt(i + 2) == 'T')
                        && (s.charAt(i + 3) == 'p' || s.charAt(i + 3) == 'P')) {
                    http = true;
                }
            }
            if (http) {
                int len = end - start;
                tokens += (len + 2) / 3 - (len + 3) / 4;
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return tokens;
    }

    /** v2.9 test fixture: ~target chars of AccuWeather-class
     *  hourly text, fragment lines in the shape the visibility-
     *  blind walk produces (the trail-20261025-015133 step-4
     *  chunk's own opening lines, then per-hour fragments). */
    private static String hourlyChunk(int startHour, int target) {
        StringBuilder sb = new StringBuilder();
        sb.append("View hourly forecasts 10-days ahead\n");
        sb.append("Activate Your Premium+ Subscription\n");
        sb.append("Chevron right\n");
        String[] conds = {"Clear", "Partly cloudy", "Cloudy",
                "Mostly cloudy", "Clear"};
        int h = startHour;
        int i = 0;
        while (sb.length() < target) {
            int temp = 54 + (i % 11);
            int rain = 10 + (i % 4) * 10;
            sb.append(h).append(" AM\n");
            sb.append(temp).append("°\n");
            sb.append(conds[i % conds.length]).append('\n');
            sb.append(rain).append("%\n");
            sb.append("RealFeel® ").append(temp + 1).append("°\n");
            sb.append("Wind NNE ").append(4 + (i % 6)).append(" mph\n");
            h = h % 12 + 1;
            i++;
        }
        return sb.substring(0, target);
    }

    private static ElementInfo elx(int n, String tag, String name,
                                   String href) {
        ElementInfo e = new ElementInfo();
        e.number = n;
        e.tag = tag;
        e.name = name;
        e.href = href;
        return e;
    }

    /** v2.9 test fixture: the v2.8 field run's AccuWeather-class
     *  perception — 40 elements, long names, long hrefs, and a
     *  content-led (hourly-fragment) preview. */
    private static Eyes.Perception accuPage(String preview) {
        Eyes.Perception wx = new Eyes.Perception();
        wx.url = "https://www.accuweather.com/en/us/oakdale/37368/"
                + "hourly-weather-forecast/335578";
        wx.title = "Oakdale, TN Hourly Weather | AccuWeather";
        wx.textPreview = preview;
        String base = "https://www.accuweather.com/en/us/oakdale/37368/";
        ElementInfo search = elx(1, "input", "Search", null);
        search.type = "search";
        wx.elements.add(search);
        String[] names = {
            "Today's Weather", "Hourly Weather Forecast for Oakdale",
            "10-Day Weather Forecast", "Radar and Satellite Maps",
            "MinuteCast Precipitation Forecast", "Air Quality Index",
            "Allergy and Pollen Outlook", "Fishing and Game Forecast",
            "Hurricane Center", "Severe Weather Alerts",
            "Winter Storm Warning", "Heat Advisory in Effect",
            "Sun and Moon Times", "Monthly Weather Calendar",
            "Weekend Weather Outlook", "Traffic and Road Conditions",
            "Local News and Weather", "Sports Weather Forecast",
            "Ski and Snow Report", "Boat and Beach Forecast"};
        String[] paths = {
            "weather-forecast/335578", "hourly-weather-forecast/335578",
            "10-day-weather-forecast/335578", "radar/335578",
            "minutecast/335578", "air-quality/335578", "allergy/335578",
            "fishing/335578", "hurricane/335578", "alerts/335578",
            "winter/335578", "heat/335578", "astronomy/335578",
            "monthly/335578", "weekend/335578", "traffic/335578",
            "news/335578", "sports/335578", "ski/335578", "beach/335578"};
        int n = 2;
        for (int i = 0; i < names.length; i++) {
            wx.elements.add(elx(n++, "a", names[i], base + paths[i]));
        }
        for (int i = 0; i < 9; i++) {
            wx.elements.add(elx(n++, "button",
                    "Hour " + (i + 2) + " details", null));
        }
        for (int i = 0; i < 10; i++) {
            wx.elements.add(elx(n++, "a",
                    "Nearby city weather " + i,
                    base + "nearby/" + (335600 + i)));
        }
        return wx;
    }

    public static void main(String[] args) {
        Governor standard = new Governor(PolicyPacks.loadPack("standard").policy);

        // -- classification, ported from test_governor.py ----------------

        // test_click_plain_link_readonly
        ElementInfo about = el(1, "a", "About");
        about.href = "https://x.local/about";
        check("plain link tap is readonly", standard.classify(tap(1), about, ""), "readonly");
        check("plain link tap allowed",
                standard.evaluate(tap(1), about, "").outcome, "allow");

        // test_click_submit_consequential (button type=submit in a form)
        ElementInfo signIn = el(2, "button", "Sign in");
        signIn.type = "submit";
        signIn.formAction = "https://x.local/go";
        check("submit button tap is consequential",
                standard.classify(tap(2), signIn, ""), "consequential");
        check("submit button asks under standard",
                standard.evaluate(tap(2), signIn, "").outcome, "ask");

        // test_click_in_form_consequential (plain button inside a form)
        ElementInfo inForm = el(3, "button", "Whatever");
        inForm.formAction = "https://x.local/go";
        check("in-form button tap is consequential",
                standard.classify(tap(3), inForm, ""), "consequential");

        // test_consequential_keywords_on_click (label set)
        String[] labels = {
                "Pay now", "Buy now", "Place order", "Delete account",
                "Remove account", "Publish post", "Send message",
                "Transfer funds", "Confirm payment", "Complete purchase",
                "Checkout",
        };
        for (int i = 0; i < labels.length; i++) {
            ElementInfo b = el(10 + i, "button", labels[i]);
            check("keyword tap '" + labels[i] + "' is consequential",
                    standard.classify(tap(10 + i), b, ""), "consequential");
            check("keyword tap '" + labels[i] + "' asks",
                    standard.evaluate(tap(10 + i), b, "").outcome, "ask");
        }

        // test_click_plain_button_write
        ElementInfo addItem = el(30, "button", "Add item");
        check("plain button tap is write", standard.classify(tap(30), addItem, ""), "write");
        check("plain button allowed under standard",
                standard.evaluate(tap(30), addItem, "").outcome, "allow");

        // test_click_without_element_is_write
        check("tap without element is write", standard.classify(tap(5), null, ""), "write");

        // test_type_write_and_consequential
        ElementInfo username = el(40, "input", "Username");
        username.placeholder = "Username";
        check("type into plain field is write",
                standard.classify(type(40, "ryan"), username, ""), "write");
        ElementInfo payment = el(41, "input", "Payment card");
        payment.placeholder = "Payment card";
        check("type into payment field is consequential",
                standard.classify(type(41, "4242"), payment, ""), "consequential");
        check("type into payment field asks",
                standard.evaluate(type(41, "4242"), payment, "").outcome, "ask");

        // test_link_with_pay_href_consequential
        ElementInfo details = el(50, "a", "Details");
        details.href = "https://x.local/pay";
        check("link with pay href is consequential",
                standard.classify(tap(50), details, ""), "consequential");

        // test_navigate_classes
        check("goto docs is readonly",
                standard.classify(gotoUrl("https://x.local/docs"), null, ""), "readonly");
        check("goto account/delete is consequential",
                standard.classify(gotoUrl("https://x.local/account/delete"), null, ""),
                "consequential");
        check("goto checkout is consequential",
                standard.classify(gotoUrl("https://x.local/checkout"), null, ""), "consequential");
        check("goto checkout asks",
                standard.evaluate(gotoUrl("https://x.local/checkout"), null, "").outcome, "ask");

        // test_readonly_kinds (mobile vocabulary)
        check("scroll is readonly", standard.classify(new GhAction("scroll"), null, ""), "readonly");
        check("wait is readonly", standard.classify(new GhAction("wait"), null, ""), "readonly");
        check("back is readonly", standard.classify(new GhAction("back"), null, ""), "readonly");
        check("done is readonly", standard.classify(new GhAction("done"), null, ""), "readonly");

        // -- policy outcomes (test_governor.py policy cases) -------------

        Governor.Policy denyConseq = new Governor.Policy(
                Governor.ALLOW, Governor.ALLOW, Governor.DENY, null, null, null);
        check("policy deny consequential -> deny",
                new Governor(denyConseq).evaluate(tap(2), signIn, "").outcome, "deny");

        Governor.Policy denyWrite = new Governor.Policy(
                Governor.ALLOW, Governor.DENY, Governor.ASK, null, null, null);
        check("policy deny write -> deny",
                new Governor(denyWrite).evaluate(tap(30), addItem, "").outcome, "deny");

        Governor.Policy allowAll = new Governor.Policy(
                Governor.ALLOW, Governor.ALLOW, Governor.ALLOW, null, null, null);
        check("policy allow consequential -> allow",
                new Governor(allowAll).evaluate(tap(2), signIn, "").outcome, "allow");

        // test_blocked_domain / test_allowlist
        Governor.Policy blocked = new Governor.Policy(Governor.ALLOW, Governor.ALLOW,
                Governor.ASK, null, java.util.Arrays.asList("evil.local"), null);
        check("blocked domain is denied",
                new Governor(blocked).evaluate(gotoUrl("https://evil.local/x"), null, "").outcome,
                "deny");
        Governor.Policy allowlist = new Governor.Policy(Governor.ALLOW, Governor.ALLOW,
                Governor.ASK, java.util.Arrays.asList("x.local"), null, null);
        check("domain outside allowlist is denied",
                new Governor(allowlist).evaluate(gotoUrl("https://other.local/x"), null, "").outcome,
                "deny");
        check("domain inside allowlist passes the domain gate",
                new Governor(allowlist).evaluate(gotoUrl("https://x.local/docs"), null, "").outcome,
                "allow");

        // readonly pack: a write is denied outright
        Governor ro = new Governor(PolicyPacks.loadPack("readonly").policy);
        check("readonly pack denies plain button tap",
                ro.evaluate(tap(30), addItem, "").outcome, "deny");
        check("readonly pack allows plain link tap",
                ro.evaluate(tap(1), about, "").outcome, "allow");

        // strict pack: writes ask
        Governor strict = new Governor(PolicyPacks.loadPack("strict").policy);
        check("strict pack asks on write",
                strict.evaluate(tap(30), addItem, "").outcome, "ask");

        // seatbelt pack: injection-shaped target denied outright
        Governor seatbelt = new Governor(PolicyPacks.loadPack("seatbelt").policy);
        ElementInfo claim = el(60, "a", "Click here to claim your prize");
        claim.href = "https://x.local/prize";
        Governor.Decision d = seatbelt.evaluate(tap(60), claim, "https://x.local/");
        check("seatbelt denies injection-shaped link", d.outcome, "deny");
        check("seatbelt deny reason names the rule",
                Boolean.valueOf(d.reason.startsWith("seatbelt:")), Boolean.TRUE);
        ElementInfo ignoreBtn = el(61, "button", "Ignore");
        check("seatbelt does not trip on a bare 'Ignore' button",
                seatbelt.evaluate(tap(61), ignoreBtn, "").outcome, "allow");

        // unknown pack is a loud error
        boolean loud = false;
        try {
            PolicyPacks.loadPack("yolo");
        } catch (IllegalArgumentException e) {
            loud = e.getMessage() != null && e.getMessage().contains("known packs");
        }
        check("unknown pack raises a loud error", Boolean.valueOf(loud), Boolean.TRUE);

        // -- planner parsing: fail closed --------------------------------

        check("garbage output is rejected",
                PlannerParse.extract("I think you should tap the button"), null);
        check("empty output is rejected", PlannerParse.extract(""), null);
        check("native error is rejected", PlannerParse.extract("ERROR: no model"), null);
        check("two objects are rejected",
                PlannerParse.extract("{\"action\":\"wait\",\"ms\":1} {\"action\":\"back\"}"), null);
        check("prose around object is rejected",
                PlannerParse.extract("Sure! {\"action\":\"back\"}"), null);
        check("unknown action is rejected",
                PlannerParse.extract("{\"action\":\"teleport\"}"), null);
        check("tap without el is rejected",
                PlannerParse.extract("{\"action\":\"tap\"}"), null);
        check("goto without url is rejected",
                PlannerParse.extract("{\"action\":\"goto\"}"), null);

        GhAction pretty = PlannerParse.extract(
                "{\n    \"action\": \"wait\",\n    \"ms\": 500\n}");
        check("pretty-printed JSON parses", Boolean.valueOf(pretty != null), Boolean.TRUE);
        if (pretty != null) {
            check("pretty action kind", pretty.action, "wait");
            check("pretty action ms", Long.valueOf(pretty.ms), Long.valueOf(500));
        }
        GhAction fenced = PlannerParse.extract(
                "```json\n{\"action\":\"scroll\",\"dir\":\"up\"}\n```");
        check("code-fenced JSON parses", Boolean.valueOf(fenced != null), Boolean.TRUE);
        if (fenced != null) {
            check("fenced action dir", fenced.dir, "up");
        }
        GhAction typed = PlannerParse.extract(
                "{\"action\":\"type\",\"el\":3,\"text\":\"hello\"}");
        check("type action parses", Boolean.valueOf(typed != null), Boolean.TRUE);
        if (typed != null) {
            check("type action el", Integer.valueOf(typed.el), Integer.valueOf(3));
            check("type action text", typed.text, "hello");
        }

        // -- v1.1: dedicated search action --------------------------------

        GhAction search = new GhAction("search");
        search.query = "Clinch River";
        check("search is readonly", standard.classify(search, null, ""), "readonly");
        check("search allowed under standard",
                standard.evaluate(search, null, "").outcome, "allow");
        check("search describe names the query", search.describe(null),
                "search \"Clinch River\"");

        GhAction parsedSearch = PlannerParse.extract(
                "{\"action\":\"search\",\"query\":\"Clinch River\"}");
        check("search action parses", Boolean.valueOf(parsedSearch != null), Boolean.TRUE);
        if (parsedSearch != null) {
            check("search action query", parsedSearch.query, "Clinch River");
        }
        check("search without query is rejected",
                PlannerParse.extract("{\"action\":\"search\"}"), null);
        check("search with empty query is rejected",
                PlannerParse.extract("{\"action\":\"search\",\"query\":\"\"}"), null);
        check("search with blank query is rejected",
                PlannerParse.extract("{\"action\":\"search\",\"query\":\"   \"}"), null);
        check("search with non-string query is rejected",
                PlannerParse.extract("{\"action\":\"search\",\"query\":5}"), null);

        // -- v1.1: prompt contract ----------------------------------------

        String wikiGoal = "Search Wikipedia for the Clinch River, open the "
                + "article, and tell me how long it is and which dam is on it.";
        Eyes.Perception mainPage = new Eyes.Perception();
        mainPage.url = "https://en.wikipedia.org/wiki/Main_Page";
        mainPage.title = "Wikipedia, the free encyclopedia";
        String prompt = Agent.buildPrompt(wikiGoal, mainPage, "[1] <a> \"x\"\n",
                new java.util.ArrayList<String>());
        check("prompt offers the search action",
                Boolean.valueOf(prompt.contains("{\"action\":\"search\",\"query\":\"...\"}")),
                Boolean.TRUE);
        check("prompt carries the search-first rule",
                Boolean.valueOf(prompt.contains("use search first")), Boolean.TRUE);
        check("prompt forbids random homepage taps",
                Boolean.valueOf(prompt.contains("Do not tap featured or random homepage content")),
                Boolean.TRUE);
        int goalCount = prompt.split(java.util.regex.Pattern.quote(wikiGoal), -1).length - 1;
        check("prompt repeats the goal (header + reminder)",
                Integer.valueOf(goalCount), Integer.valueOf(2));

        // -- v1.1: relevance guard ----------------------------------------

        ElementInfo war = el(16, "a", "War of the First Coalition");
        war.href = "https://en.wikipedia.org/wiki/War_of_the_First_Coalition";
        check("guard blocks the random featured link before any search",
                Boolean.valueOf(RelevanceGuard.blockReason(wikiGoal, false,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(16), war) != null),
                Boolean.TRUE);

        ElementInfo clinch = el(20, "a", "Clinch River");
        clinch.href = "https://en.wikipedia.org/wiki/Clinch_River";
        check("guard allows a link whose text matches the goal target",
                RelevanceGuard.blockReason(wikiGoal, false,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(20), clinch),
                null);

        check("guard stands down once a search has happened",
                RelevanceGuard.blockReason(wikiGoal, true,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(16), war),
                null);

        ElementInfo norris = el(21, "a", "Norris Dam");
        norris.href = "https://en.wikipedia.org/wiki/Norris_Dam";
        check("guard stands down on the arrived target page",
                RelevanceGuard.blockReason(wikiGoal, false,
                        "Clinch River - Wikipedia",
                        "https://en.wikipedia.org/wiki/Clinch_River",
                        tap(21), norris),
                null);

        ElementInfo searchCtl = el(1, "a", "Search");
        check("guard never blocks a search control",
                RelevanceGuard.blockReason(wikiGoal, false,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(1), searchCtl),
                null);

        String plainGoal = "Open the settings page and turn on dark mode.";
        check("guard ignores non-search goals",
                RelevanceGuard.blockReason(plainGoal, false,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(16), war),
                null);

        ElementInfo warBtn = el(16, "button", "War of the First Coalition");
        check("guard only judges link taps, not buttons",
                RelevanceGuard.blockReason(wikiGoal, false,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(16), warBtn),
                null);

        check("guard tokenises the goal's real targets",
                Boolean.valueOf(RelevanceGuard.significantTokens(wikiGoal)
                        .contains("clinch")
                        && !RelevanceGuard.significantTokens(wikiGoal)
                        .contains("search")),
                Boolean.TRUE);

        // -- v1.2: read action --------------------------------------------

        check("read is readonly",
                standard.classify(new GhAction("read"), null, ""), "readonly");
        check("read allowed under standard",
                standard.evaluate(new GhAction("read"), null, "").outcome, "allow");
        GhAction parsedRead = PlannerParse.extract("{\"action\":\"read\"}");
        check("read action parses", Boolean.valueOf(parsedRead != null), Boolean.TRUE);
        if (parsedRead != null) {
            check("read action kind", parsedRead.action, "read");
        }
        check("read describe", new GhAction("read").describe(null), "read");

        // -- v1.2: prompt carries the read contract + page text -----------

        Eyes.Perception article = new Eyes.Perception();
        article.url = "https://en.wikipedia.org/wiki/Clinch_River";
        article.title = "Clinch River - Wikipedia";
        article.textPreview = "The Clinch River is a river that flows "
                + "through southwestern Virginia and Tennessee.";
        String prompt2 = Agent.buildPrompt(wikiGoal, article, "[1] <a> \"x\"\n",
                new java.util.ArrayList<String>());
        check("prompt offers the read action",
                Boolean.valueOf(prompt2.contains("{\"action\":\"read\"}")),
                Boolean.TRUE);
        check("prompt carries the question rule",
                Boolean.valueOf(prompt2.contains(
                        "use read (again for more text if needed) before answering done")),
                Boolean.TRUE);
        check("prompt requires summaries rest on read text",
                Boolean.valueOf(prompt2.contains(
                        "Base your done summary only on text you read")),
                Boolean.TRUE);
        check("prompt shows the page-text preview",
                Boolean.valueOf(prompt2.contains(
                        "Page text: The Clinch River is a river")),
                Boolean.TRUE);
        Eyes.Perception noText = new Eyes.Perception();
        noText.url = "https://x.local/";
        String prompt3 = Agent.buildPrompt(wikiGoal, noText, "",
                new java.util.ArrayList<String>());
        check("prompt omits the page-text line when there is no preview",
                Boolean.valueOf(!prompt3.contains("Page text:")), Boolean.TRUE);

        // -- v1.2: repeat-search correction --------------------------------

        GhAction searchAgain = new GhAction("search");
        searchAgain.query = "Clinch River";
        check("guard corrects the identical search on its results page",
                Boolean.valueOf(RelevanceGuard.searchRepeatReason(
                        searchAgain, "clinch river", true) != null),
                Boolean.TRUE);
        check("guard lets the same search run after navigating away",
                RelevanceGuard.searchRepeatReason(searchAgain, "Clinch River", false),
                null);
        GhAction otherSearch = new GhAction("search");
        otherSearch.query = "Norris Dam";
        check("guard lets a different search run on a results page",
                RelevanceGuard.searchRepeatReason(otherSearch, "Clinch River", true),
                null);
        check("guard never corrects a non-search action",
                RelevanceGuard.searchRepeatReason(tap(3), "Clinch River", true),
                null);
        check("guard stays quiet before any search has run",
                RelevanceGuard.searchRepeatReason(searchAgain, null, true),
                null);

        // -- v1.2: map shaping (tiers, caps, numbering) ---------------------

        check("tier: search input first", Integer.valueOf(Eyes.tierOf(
                rawEl(0, "input", "search", ""))), Integer.valueOf(0));
        check("tier: text input", Integer.valueOf(Eyes.tierOf(
                rawEl(0, "input", "text", ""))), Integer.valueOf(1));
        check("tier: button", Integer.valueOf(Eyes.tierOf(
                rawEl(0, "button", "", "Go"))), Integer.valueOf(2));
        check("tier: checkbox demoted below buttons", Integer.valueOf(Eyes.tierOf(
                rawEl(0, "input", "checkbox", "ns0"))), Integer.valueOf(3));
        check("tier: plain link last", Integer.valueOf(Eyes.tierOf(
                rawEl(0, "a", "", "Clinch River"))), Integer.valueOf(4));
        ElementInfo phSearch = rawEl(0, "input", "text", "");
        phSearch.placeholder = "Search Wikipedia";
        check("tier: placeholder can mark a search control",
                Integer.valueOf(Eyes.tierOf(phSearch)), Integer.valueOf(0));

        // DOM order scrambled across tiers: shaping must sort by tier,
        // keep DOM order within a tier, and number sequentially.
        java.util.List<ElementInfo> mixed = new java.util.ArrayList<ElementInfo>();
        mixed.add(rawEl(0, "a", "", "Some link"));
        mixed.add(rawEl(1, "input", "checkbox", "ns0"));
        mixed.add(rawEl(2, "button", "", "Go"));
        mixed.add(rawEl(3, "input", "text", "Query"));
        mixed.add(rawEl(4, "input", "search", "Search"));
        java.util.List<ElementInfo> shaped = Eyes.shapeMap(mixed);
        StringBuilder order = new StringBuilder();
        for (ElementInfo e : shaped) {
            if (order.length() > 0) order.append(',');
            order.append(e.name);
        }
        check("shaping sorts controls ahead of links",
                order.toString(), "Search,Query,Go,ns0,Some link");
        boolean numberingOk = shaped.size() == 5;
        for (int i = 0; i < shaped.size(); i++) {
            if (shaped.get(i).number != i + 1) numberingOk = false;
        }
        check("shaping numbers sequentially from 1",
                Boolean.valueOf(numberingOk), Boolean.TRUE);
        check("shaping preserves domIndex for the stash",
                Integer.valueOf(shaped.get(0).domIndex), Integer.valueOf(4));

        // Checkbox flood (the Special:Search failure shape): the cap
        // keeps 6, and the result links survive in the map.
        java.util.List<ElementInfo> flood = new java.util.ArrayList<ElementInfo>();
        for (int i = 0; i < 10; i++) {
            flood.add(rawEl(i, "input", "checkbox", "ns" + i));
        }
        for (int i = 0; i < 3; i++) {
            flood.add(rawEl(10 + i, "a", "", "Result " + i));
        }
        java.util.List<ElementInfo> floodShaped = Eyes.shapeMap(flood);
        int boxes = 0, links = 0;
        for (ElementInfo e : floodShaped) {
            if (Eyes.isCheckable(e)) boxes++;
            if (Eyes.isLink(e)) links++;
        }
        check("checkbox cap keeps at most 6", Integer.valueOf(boxes),
                Integer.valueOf(6));
        check("result links survive a checkbox flood", Integer.valueOf(links),
                Integer.valueOf(3));

        // Control flood: at most 18 non-links may precede the first link.
        java.util.List<ElementInfo> controls = new java.util.ArrayList<ElementInfo>();
        for (int i = 0; i < 25; i++) {
            controls.add(rawEl(i, "input", "text", "field" + i));
        }
        for (int i = 0; i < 4; i++) {
            controls.add(rawEl(25 + i, "a", "", "Link " + i));
        }
        java.util.List<ElementInfo> controlShaped = Eyes.shapeMap(controls);
        int beforeLink = 0;
        for (ElementInfo e : controlShaped) {
            if (Eyes.isLink(e)) break;
            beforeLink++;
        }
        check("pre-link control cap is 18", Integer.valueOf(beforeLink),
                Integer.valueOf(18));
        check("links still present after the control cap",
                Boolean.valueOf(Eyes.isLink(
                        controlShaped.get(controlShaped.size() - 1))),
                Boolean.TRUE);

        // Total cap: 60 links shape down to 40, numbered 1..40.
        java.util.List<ElementInfo> many = new java.util.ArrayList<ElementInfo>();
        for (int i = 0; i < 60; i++) {
            many.add(rawEl(i, "a", "", "L" + i));
        }
        java.util.List<ElementInfo> manyShaped = Eyes.shapeMap(many);
        check("total cap is 40", Integer.valueOf(manyShaped.size()),
                Integer.valueOf(40));
        check("total cap keeps numbering tight",
                Integer.valueOf(manyShaped.get(39).number), Integer.valueOf(40));

        // -- v1.2: parseMap shapes + preview end to end ---------------------

        StringBuilder elsJson = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) elsJson.append(',');
            elsJson.append("{\"tag\":\"input\",\"role\":\"checkbox\",")
                    .append("\"name\":\"ns").append(i).append("\",")
                    .append("\"type\":\"checkbox\",\"href\":\"\",\"label\":\"\",")
                    .append("\"formAction\":\"\",\"placeholder\":\"\",")
                    .append("\"domId\":\"\",\"domName\":\"\"}");
        }
        elsJson.append(",{\"tag\":\"a\",\"role\":\"link\",\"name\":\"Clinch River\",")
                .append("\"type\":\"\",\"href\":\"https://en.wikipedia.org/wiki/Clinch_River\",")
                .append("\"label\":\"\",\"formAction\":\"\",\"placeholder\":\"\",")
                .append("\"domId\":\"\",\"domName\":\"\"}");
        String innerJson = "{\"url\":\"https://en.wikipedia.org/wiki/Special:Search\","
                + "\"title\":\"Search results\",\"text\":\"preview text here\",\"els\":["
                + elsJson + "]}";
        Eyes.Perception parsed = Eyes.parseMap(
                com.ghosthands.app.Json.quote(innerJson));
        int parsedBoxes = 0;
        boolean parsedHasLink = false;
        for (ElementInfo e : parsed.elements) {
            if (Eyes.isCheckable(e)) parsedBoxes++;
            if (Eyes.isLink(e)) parsedHasLink = true;
        }
        check("parseMap applies the checkbox cap",
                Integer.valueOf(parsedBoxes), Integer.valueOf(6));
        check("parseMap keeps the result link",
                Boolean.valueOf(parsedHasLink), Boolean.TRUE);
        check("parseMap carries the text preview", parsed.textPreview,
                "preview text here");

        // -- v1.2: read chunk plumbing --------------------------------------

        Eyes.ReadChunk chunk = Eyes.parseRead(
                "{\"total\":15233,\"start\":2400,\"text\":\"abc\"}");
        check("parseRead total", Integer.valueOf(chunk.total), Integer.valueOf(15233));
        check("parseRead start", Integer.valueOf(chunk.start), Integer.valueOf(2400));
        check("parseRead text", chunk.text, "abc");
        check("formatReadChunk mid-page",
                Eyes.formatReadChunk(0, "abc", 100),
                "ok: read chars 0-3 of 100; read again for more:\nabc");
        check("formatReadChunk last chunk",
                Eyes.formatReadChunk(90, "0123456789", 100),
                "ok: read chars 90-100 of 100; end of page text:\n0123456789");
        Eyes.ReadCursor cursor = new Eyes.ReadCursor();
        cursor.resetFor("https://x.local/a");
        cursor.offset = 4800;
        cursor.resetFor("https://x.local/a");
        check("read cursor keeps offset on the same page",
                Integer.valueOf(cursor.offset), Integer.valueOf(4800));
        cursor.resetFor("https://x.local/b");
        check("read cursor restarts on a new page",
                Integer.valueOf(cursor.offset), Integer.valueOf(0));

        // -- v1.3: question goals + auto-read trigger -------------------

        check("question goal: the Clinch River goal asks questions",
                Boolean.valueOf(RelevanceGuard.isQuestionGoal(wikiGoal)),
                Boolean.TRUE);
        check("question goal: a bare how-question counts",
                Boolean.valueOf(RelevanceGuard.isQuestionGoal(
                        "How long is the Clinch River?")),
                Boolean.TRUE);
        check("question goal: a settings task is not a question",
                Boolean.valueOf(RelevanceGuard.isQuestionGoal(plainGoal)),
                Boolean.FALSE);
        String openGoal = "Open the Clinch River article and read it.";
        check("question goal: an open/read task is not a question",
                Boolean.valueOf(RelevanceGuard.isQuestionGoal(openGoal)),
                Boolean.FALSE);

        String articleUrl = "https://en.wikipedia.org/wiki/Clinch_River";
        String articleTitle = "Clinch River - Wikipedia";
        java.util.Set<String> noneRead =
                new java.util.LinkedHashSet<String>();
        check("auto-read fires on the arrived page of a question goal",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        articleTitle, articleUrl, noneRead)),
                Boolean.TRUE);
        check("auto-read does not fire for a non-question goal",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(openGoal,
                        articleTitle, articleUrl, noneRead)),
                Boolean.FALSE);
        check("auto-read does not fire on a non-matching page",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        noneRead)),
                Boolean.FALSE);
        java.util.Set<String> articleRead =
                new java.util.LinkedHashSet<String>();
        articleRead.add(articleUrl);
        check("auto-read fires at most once per URL",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        articleTitle, articleUrl, articleRead)),
                Boolean.FALSE);
        check("auto-read still fires for a different arrived URL",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        articleTitle,
                        articleUrl + "#Course",
                        articleRead)),
                Boolean.TRUE);

        // -- v1.3: auto-read section framing ------------------------------

        check("auto-read section relabels a read result",
                Agent.autoReadSection(Eyes.formatReadChunk(0, "abc", 100)),
                "Page text (auto-read, chars 0-3 of 100):\nabc");
        check("auto-read section rejects an error result",
                Agent.autoReadSection(
                        "error: no readable text found on this page"),
                null);
        check("auto-read section rejects null",
                Agent.autoReadSection(null), null);

        // -- v1.3: prompt carries the auto-read section + new rules -------

        String section = Agent.autoReadSection(
                Eyes.formatReadChunk(0, "abc", 100));
        String prompt4 = Agent.buildPrompt(wikiGoal, article, "[1] <a> \"x\"\n",
                new java.util.ArrayList<String>(), section);
        check("prompt injects the auto-read section",
                Boolean.valueOf(prompt4.contains(
                        "Page text (auto-read, chars 0-3 of 100):")),
                Boolean.TRUE);
        check("prompt says to answer from the auto-read text first",
                Boolean.valueOf(prompt4.contains(
                        "answer from it; use read again only if the fact "
                                + "you need is not in it")),
                Boolean.TRUE);
        check("prompt carries the chrome-tab rule",
                Boolean.valueOf(prompt4.contains("you are ON the article")
                        && prompt4.contains("Do not tap page tabs such as "
                        + "Article, Talk, Read, or Edit; use read")),
                Boolean.TRUE);
        check("prompt without an auto-read has no auto-read section",
                Boolean.valueOf(!prompt2.contains("Page text (auto-read, chars")),
                Boolean.TRUE);

        // -- v1.3: hardened (history-based) repeat-search guard -----------

        GhAction searchClinch = new GhAction("search");
        searchClinch.query = "Clinch River";
        check("history guard fires on execution history alone "
                        + "(perceived URL irrelevant, even about:blank)",
                Boolean.valueOf(RelevanceGuard.searchRepeatByHistoryReason(
                        searchClinch, "clinch river", false) != null),
                Boolean.TRUE);
        check("history guard disarms once a navigating action executed",
                RelevanceGuard.searchRepeatByHistoryReason(
                        searchClinch, "Clinch River", true),
                null);
        check("history guard lets a different search run",
                RelevanceGuard.searchRepeatByHistoryReason(
                        otherSearch, "Clinch River", false),
                null);
        check("history guard stays quiet before any search executed",
                RelevanceGuard.searchRepeatByHistoryReason(
                        searchClinch, null, false),
                null);
        check("history guard never corrects a non-search action",
                RelevanceGuard.searchRepeatByHistoryReason(
                        tap(3), "Clinch River", false),
                null);
        GhAction blankSearch = new GhAction("search");
        blankSearch.query = "   ";
        check("history guard stays quiet for an empty proposed query",
                RelevanceGuard.searchRepeatByHistoryReason(
                        blankSearch, "Clinch River", false),
                null);

        // -- v1.3: chrome-tab correction ------------------------------------

        ElementInfo articleTab = el(16, "a", "Article");
        articleTab.href = "https://en.wikipedia.org/wiki/Clinch_River";
        java.util.Set<String> noTabsCorrected =
                new java.util.LinkedHashSet<String>();
        check("chrome guard corrects the Article tab on the arrived page",
                Boolean.valueOf(RelevanceGuard.chromeTabReason(wikiGoal,
                        articleTitle, articleUrl, tap(16), articleTab,
                        noTabsCorrected) != null),
                Boolean.TRUE);
        ElementInfo talkTab = el(17, "a", "Talk");
        check("chrome guard corrects the Talk tab too",
                Boolean.valueOf(RelevanceGuard.chromeTabReason(wikiGoal,
                        articleTitle, articleUrl, tap(17), talkTab,
                        noTabsCorrected) != null),
                Boolean.TRUE);
        java.util.Set<String> articleCorrected =
                new java.util.LinkedHashSet<String>();
        articleCorrected.add("article");
        check("chrome guard corrects a label at most once per run",
                RelevanceGuard.chromeTabReason(wikiGoal, articleTitle,
                        articleUrl, tap(16), articleTab, articleCorrected),
                null);
        check("chrome guard still corrects other labels",
                Boolean.valueOf(RelevanceGuard.chromeTabReason(wikiGoal,
                        articleTitle, articleUrl, tap(17), talkTab,
                        articleCorrected) != null),
                Boolean.TRUE);
        check("chrome guard stays quiet before arrival",
                RelevanceGuard.chromeTabReason(wikiGoal,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page",
                        tap(16), articleTab, noTabsCorrected),
                null);
        check("chrome guard ignores ordinary links on the arrived page",
                RelevanceGuard.chromeTabReason(wikiGoal, articleTitle,
                        articleUrl, tap(21), norris, noTabsCorrected),
                null);
        ElementInfo articleBtn = el(16, "button", "Article");
        check("chrome guard only judges link taps, not buttons",
                RelevanceGuard.chromeTabReason(wikiGoal, articleTitle,
                        articleUrl, tap(16), articleBtn, noTabsCorrected),
                null);
        check("chrome guard never corrects a non-tap action",
                RelevanceGuard.chromeTabReason(wikiGoal, articleTitle,
                        articleUrl, gotoUrl(articleUrl), articleTab,
                        noTabsCorrected),
                null);
        check("chrome tab labels normalise case and padding",
                RelevanceGuard.chromeTabLabel(el(1, "a", " View history ")),
                "view history");
        check("chrome tab label is null for a plain link",
                RelevanceGuard.chromeTabLabel(clinch), null);

        // -- v1.5: ModelEngine stats parsing ------------------------------
        // parseStats maps the native String[7]: [1] prompt tokens,
        // [5] reused, [6] evaluated, [2] prompt ms, [3] gen tokens,
        // [4] gen ms. It must never throw on a malformed array.

        ModelEngine.Stats full = ModelEngine.parseStats(new String[]{
                "some text", "812", "1204", "42", "380", "640", "172"});
        check("stats well-formed: prompt tokens",
                Long.valueOf(full.lastPromptTokens), Long.valueOf(812));
        check("stats well-formed: reused tokens",
                Long.valueOf(full.lastReusedTokens), Long.valueOf(640));
        check("stats well-formed: evaluated tokens",
                Long.valueOf(full.lastEvaluatedTokens), Long.valueOf(172));
        check("stats well-formed: prompt ms",
                Long.valueOf(full.lastPromptMs), Long.valueOf(1204));
        check("stats well-formed: gen tokens",
                Long.valueOf(full.lastGenTokens), Long.valueOf(42));
        check("stats well-formed: gen ms",
                Long.valueOf(full.lastGenMs), Long.valueOf(380));

        ModelEngine.Stats legacy = ModelEngine.parseStats(new String[]{
                "some text", "812", "1204", "42", "380"});
        check("stats legacy 5-field array: prompt tokens still parsed",
                Long.valueOf(legacy.lastPromptTokens), Long.valueOf(812));
        check("stats legacy 5-field array: prompt ms still parsed",
                Long.valueOf(legacy.lastPromptMs), Long.valueOf(1204));
        check("stats legacy 5-field array: reused defaults to zero",
                Long.valueOf(legacy.lastReusedTokens), Long.valueOf(0));
        check("stats legacy 5-field array: evaluated defaults to zero",
                Long.valueOf(legacy.lastEvaluatedTokens), Long.valueOf(0));

        ModelEngine.Stats nullArr = ModelEngine.parseStats(null);
        check("stats null array: prompt tokens zero",
                Long.valueOf(nullArr.lastPromptTokens), Long.valueOf(0));
        check("stats null array: reused zero",
                Long.valueOf(nullArr.lastReusedTokens), Long.valueOf(0));

        ModelEngine.Stats empty = ModelEngine.parseStats(new String[0]);
        check("stats empty array: prompt tokens zero",
                Long.valueOf(empty.lastPromptTokens), Long.valueOf(0));

        ModelEngine.Stats oneSlot = ModelEngine.parseStats(
                new String[]{"ERROR: no model loaded"});
        check("stats error-only array: prompt tokens zero",
                Long.valueOf(oneSlot.lastPromptTokens), Long.valueOf(0));

        ModelEngine.Stats garbage = ModelEngine.parseStats(new String[]{
                "x", null, "abc", "7x", "", " 15 ", "3.5"});
        check("stats garbage: null slot parses as zero",
                Long.valueOf(garbage.lastPromptTokens), Long.valueOf(0));
        check("stats garbage: non-numeric prompt ms parses as zero",
                Long.valueOf(garbage.lastPromptMs), Long.valueOf(0));
        check("stats garbage: trailing-junk gen tokens parse as zero",
                Long.valueOf(garbage.lastGenTokens), Long.valueOf(0));
        check("stats garbage: empty gen ms parses as zero",
                Long.valueOf(garbage.lastGenMs), Long.valueOf(0));
        check("stats garbage: padded numeric reused is trimmed and parsed",
                Long.valueOf(garbage.lastReusedTokens), Long.valueOf(15));
        check("stats garbage: decimal evaluated parses as zero",
                Long.valueOf(garbage.lastEvaluatedTokens), Long.valueOf(0));

        check("stats feed line format",
                Agent.modelStatsLine(full),
                " (model: 812 prompt tokens, 640 reused, 1204 ms)");
        check("stats feed line tolerates a null snapshot",
                Agent.modelStatsLine(null),
                " (model: 0 prompt tokens, 0 reused, 0 ms)");

        // -- v1.6: error-page detection ----------------------------------
        // The v1.5 field run's dead page, verbatim in shape: Chrome's
        // error URL, its title, and a preview naming the failed URL
        // and the net error.

        String errUrl = "chrome-error://chromewebdata/";
        String errTitle = "Webpage not available";
        String errPreview = "Webpage not available The webpage at "
                + "https://en.wikipedia.org/wiki/Special:Search"
                + "?search=Clinch+River&fulltext=1 could not be loaded "
                + "because: net::ERR_CONNECTION_ABORTED";
        check("error page: chrome-error URL is detected",
                Boolean.valueOf(ErrorPages.isErrorPage(
                        errUrl, "", "")),
                Boolean.TRUE);
        check("error page: net error in the preview is detected",
                Boolean.valueOf(ErrorPages.isErrorPage(
                        "https://x.local/", "Some page", errPreview)),
                Boolean.TRUE);
        check("error page: net error in the title is detected",
                Boolean.valueOf(ErrorPages.isErrorPage(
                        "https://x.local/", "Failed: net::ERR_FAILED", "")),
                Boolean.TRUE);
        check("error page: the error title alone is detected",
                Boolean.valueOf(ErrorPages.isErrorPage(
                        "https://x.local/", errTitle, "body text")),
                Boolean.TRUE);
        check("error page: a normal article is not an error page",
                Boolean.valueOf(ErrorPages.isErrorPage(articleUrl,
                        articleTitle,
                        "The Clinch River is a river that flows "
                                + "through Virginia and Tennessee.")),
                Boolean.FALSE);
        check("error page: nulls are not an error page",
                Boolean.valueOf(ErrorPages.isErrorPage(null, null, null)),
                Boolean.FALSE);

        Eyes.Perception errPage = new Eyes.Perception();
        errPage.url = errUrl;
        errPage.title = errTitle;
        errPage.textPreview = errPreview;
        check("error page: failed URL is parsed from the preview",
                ErrorPages.failedUrl(errPage),
                "https://en.wikipedia.org/wiki/Special:Search"
                        + "?search=Clinch+River&fulltext=1");
        check("error page: no naming phrase means no failed URL",
                ErrorPages.failedUrl("Webpage not available, somehow"),
                null);
        check("error page: null perception has no failed URL",
                ErrorPages.failedUrl((Eyes.Perception) null), null);
        check("error page: the net error token is extracted",
                ErrorPages.netError(errTitle, errPreview),
                "net::ERR_CONNECTION_ABORTED");
        check("error page: no net error token means null",
                ErrorPages.netError(articleTitle, "plain text"), null);

        // -- v1.6: no auto-read on search-results pages ------------------

        String resultsUrl = "https://en.wikipedia.org/wiki/Special:Search"
                + "?search=Clinch+River&fulltext=1&ns0=1";
        String resultsTitle = "Clinch River - Search results - Wikipedia";
        check("results page: recognised by title",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        resultsTitle, articleUrl)),
                Boolean.TRUE);
        check("results page: recognised by Special:Search path",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        articleTitle, resultsUrl)),
                Boolean.TRUE);
        check("results page: a plain article is not one",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        articleTitle, articleUrl)),
                Boolean.FALSE);
        check("auto-read does NOT fire on the results page (title)",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        resultsTitle, articleUrl, noneRead)),
                Boolean.FALSE);
        check("auto-read does NOT fire on a Special:Search URL",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        articleTitle, resultsUrl, noneRead)),
                Boolean.FALSE);
        check("auto-read still fires on the article itself",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        articleTitle, articleUrl, noneRead)),
                Boolean.TRUE);
        check("auto-read question gating intact on a results page",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(openGoal,
                        resultsTitle, resultsUrl, noneRead)),
                Boolean.FALSE);

        // -- v1.6: same-query search correction (perception-based) -------

        check("same-query: the results URL names its query (+ form)",
                RelevanceGuard.queryFromResultsUrl(resultsUrl),
                "Clinch River");
        check("same-query: %20 form decodes too",
                RelevanceGuard.queryFromResultsUrl(
                        "https://en.wikipedia.org/wiki/Special:Search"
                                + "?search=Clinch%20River"),
                "Clinch River");
        check("same-query: no search param means no query",
                RelevanceGuard.queryFromResultsUrl(articleUrl), null);

        GhAction searchClinch2 = new GhAction("search");
        searchClinch2.query = "Clinch River";
        check("same-query: correction fires on the matching results URL",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchClinch2, resultsTitle, resultsUrl) != null),
                Boolean.TRUE);
        GhAction searchLower = new GhAction("search");
        searchLower.query = "  clinch river ";
        check("same-query: case and padding do not matter",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchLower, resultsTitle, resultsUrl) != null),
                Boolean.TRUE);
        check("same-query: title form alone fires (no search param)",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchClinch2, resultsTitle,
                        "https://en.wikipedia.org/wiki/Special:Search")
                        != null),
                Boolean.TRUE);
        check("same-query: a different query may proceed",
                RelevanceGuard.searchSamePageReason(
                        otherSearch, resultsTitle, resultsUrl),
                null);
        check("same-query: an unrelated page does not fire",
                RelevanceGuard.searchSamePageReason(searchClinch2,
                        "Wikipedia, the free encyclopedia",
                        "https://en.wikipedia.org/wiki/Main_Page"),
                null);
        check("same-query: a non-search action is never corrected",
                RelevanceGuard.searchSamePageReason(
                        tap(3), resultsTitle, resultsUrl),
                null);

        // -- v2.7: the same-page derivation learns q= ------------------
        // Field case, verbatim (trail-20261025-003150): a fresh run
        // started already sitting on the DuckDuckGo lite results
        // page for the weather goal. The v1.6 derivation knew only
        // Wikipedia's search= parameter, so the planner's re-search
        // of the query already in the page's q= executed and
        // reloaded the same page. Wikipedia behaviour above is the
        // unchanged baseline.
        String ddgLiteUrl = "https://html.duckduckgo.com/html/"
                + "?q=forecast+for+Oakdale+TN+chance+of+rain";
        String ddgLiteTitle =
                "forecast for Oakdale TN chance of rain at DuckDuckGo";
        GhAction searchWeather = new GhAction("search");
        searchWeather.query = "forecast for Oakdale TN chance of rain";
        check("q= guard: page query derives from the DDG lite q= param",
                RelevanceGuard.resultsPageQuery(ddgLiteTitle, ddgLiteUrl),
                "forecast for Oakdale TN chance of rain");
        check("q= guard: the field case fires verbatim",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchWeather, ddgLiteTitle, ddgLiteUrl) != null),
                Boolean.TRUE);
        GhAction searchWeatherPadded = new GhAction("search");
        searchWeatherPadded.query =
                "  Forecast For Oakdale TN Chance Of Rain ";
        check("q= guard: case and padding do not matter",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchWeatherPadded, ddgLiteTitle, ddgLiteUrl)
                        != null),
                Boolean.TRUE);
        check("q= guard: the DDG title form alone fires (no q= param)",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchWeather, ddgLiteTitle,
                        "https://html.duckduckgo.com/html/") != null),
                Boolean.TRUE);
        GhAction searchOther = new GhAction("search");
        searchOther.query = "hourly forecast for Kingston TN";
        check("q= guard: a different query on the same page proceeds",
                RelevanceGuard.searchSamePageReason(
                        searchOther, ddgLiteTitle, ddgLiteUrl),
                null);
        String googleUrl = "https://www.google.com/search"
                + "?q=forecast+for+Oakdale+TN+chance+of+rain";
        check("q= guard: Google results pages derive their q= too",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchWeather,
                        "forecast for Oakdale TN chance of rain"
                                + " - Google Search",
                        googleUrl) != null),
                Boolean.TRUE);
        String bingUrl = "https://www.bing.com/search"
                + "?q=forecast+for+Oakdale+TN+chance+of+rain";
        check("q= guard: Bing results pages derive their q= too",
                Boolean.valueOf(RelevanceGuard.searchSamePageReason(
                        searchWeather, "forecast for Oakdale TN"
                                + " chance of rain - Search",
                        bingUrl) != null),
                Boolean.TRUE);
        check("q= guard: a q= on an unrecognised page derives nothing",
                RelevanceGuard.resultsPageQuery(
                        "Some video - Watch",
                        "https://videos.example.test/watch?q=anything"),
                null);
        check("q= guard: Wikipedia's search= still wins over any q=",
                RelevanceGuard.resultsPageQuery(resultsTitle,
                        resultsUrl + "&q=decoy"),
                "Clinch River");

        // -- v1.6: empty-map correction decision ---------------------------

        check("empty map: a tap into zero elements is corrected",
                Boolean.valueOf(RelevanceGuard.emptyMapReason(
                        tap(1), 0, 0) != null),
                Boolean.TRUE);
        check("empty map: a type into zero elements is corrected",
                Boolean.valueOf(RelevanceGuard.emptyMapReason(
                        type(1, "x"), 0, 0) != null),
                Boolean.TRUE);
        check("empty map: a tap with elements present proceeds",
                RelevanceGuard.emptyMapReason(tap(1), 5, 0), null);
        check("empty map: read is never corrected",
                RelevanceGuard.emptyMapReason(
                        new GhAction("read"), 0, 0),
                null);
        check("empty map: goto is never corrected",
                RelevanceGuard.emptyMapReason(
                        gotoUrl(articleUrl), 0, 0),
                null);
        check("empty map: the last correction under the cap still fires",
                Boolean.valueOf(RelevanceGuard.emptyMapReason(tap(2), 0,
                        RelevanceGuard.EMPTY_MAP_CORRECTION_CAP - 1) != null),
                Boolean.TRUE);
        check("empty map: the cap silences the rule",
                RelevanceGuard.emptyMapReason(tap(1), 0,
                        RelevanceGuard.EMPTY_MAP_CORRECTION_CAP),
                null);

        // -- v1.7: arrival (commitment) guard -----------------------------
        // Field shape (v1.6 run): step 5 stood ON the auto-read Clinch
        // River article and proposed `search "Clinch River"` again.

        ElementInfo clinchLink = el(20, "a", "Clinch River");
        clinchLink.href = "https://en.wikipedia.org/wiki/Clinch_River";
        ElementInfo plainButton = el(5, "button", "Dismiss");
        GhAction leaveSearch = new GhAction("search");
        leaveSearch.query = "Clinch River";
        GhAction scrollDown = new GhAction("scroll");
        scrollDown.dir = "down";

        check("arrival guard corrects a search on the arrived page",
                Boolean.valueOf(RelevanceGuard.arrivalLeaveReason(
                        leaveSearch, null, articleUrl, articleUrl, 0) != null),
                Boolean.TRUE);
        check("arrival guard corrects a goto on the arrived page",
                Boolean.valueOf(RelevanceGuard.arrivalLeaveReason(
                        gotoUrl("https://en.wikipedia.org/wiki/Norris_Dam"),
                        null, articleUrl, articleUrl, 0) != null),
                Boolean.TRUE);
        check("arrival guard corrects back on the arrived page",
                Boolean.valueOf(RelevanceGuard.arrivalLeaveReason(
                        new GhAction("back"), null,
                        articleUrl, articleUrl, 0) != null),
                Boolean.TRUE);
        check("arrival guard corrects a link tap on the arrived page",
                Boolean.valueOf(RelevanceGuard.arrivalLeaveReason(
                        tap(20), clinchLink, articleUrl, articleUrl, 0)
                        != null),
                Boolean.TRUE);
        check("arrival guard lets a non-link tap proceed",
                RelevanceGuard.arrivalLeaveReason(
                        tap(5), plainButton, articleUrl, articleUrl, 0),
                null);
        check("arrival guard lets read proceed",
                RelevanceGuard.arrivalLeaveReason(
                        new GhAction("read"), null,
                        articleUrl, articleUrl, 0),
                null);
        check("arrival guard lets scroll proceed",
                RelevanceGuard.arrivalLeaveReason(
                        scrollDown, null, articleUrl, articleUrl, 0),
                null);
        check("arrival guard lets done proceed",
                RelevanceGuard.arrivalLeaveReason(
                        new GhAction("done"), null,
                        articleUrl, articleUrl, 0),
                null);
        check("arrival guard lets type proceed",
                RelevanceGuard.arrivalLeaveReason(
                        type(5, "x"), plainButton, articleUrl, articleUrl, 0),
                null);
        check("arrival guard is not armed on another page",
                RelevanceGuard.arrivalLeaveReason(
                        leaveSearch, null, resultsUrl, articleUrl, 0),
                null);
        check("arrival guard is not armed before any auto-read",
                RelevanceGuard.arrivalLeaveReason(
                        leaveSearch, null, articleUrl, null, 0),
                null);
        check("arrival guard: an unresolvable tap is not provably leaving",
                RelevanceGuard.arrivalLeaveReason(
                        tap(20), null, articleUrl, articleUrl, 0),
                null);
        check("arrival guard: the last correction under the cap still fires",
                Boolean.valueOf(RelevanceGuard.arrivalLeaveReason(
                        leaveSearch, null, articleUrl, articleUrl,
                        RelevanceGuard.ARRIVAL_CORRECTION_CAP - 1) != null),
                Boolean.TRUE);
        check("arrival guard: the cap silences the rule",
                RelevanceGuard.arrivalLeaveReason(
                        leaveSearch, null, articleUrl, articleUrl,
                        RelevanceGuard.ARRIVAL_CORRECTION_CAP),
                null);

        // -- v1.7: prompt arrival banner -----------------------------------

        String prompt5 = Agent.buildPrompt(wikiGoal, article,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>(), section);
        check("prompt with an auto-read carries the arrival banner",
                Boolean.valueOf(prompt5.contains(
                        "You are ON the target page and its text is above. "
                                + "Answer done from it, or read for more.")),
                Boolean.TRUE);
        check("arrival banner sits after the repeated goal, before the cue",
                Boolean.valueOf(prompt5.indexOf("You are ON the target page")
                        > prompt5.lastIndexOf("Goal: " + wikiGoal)
                        && prompt5.indexOf("You are ON the target page")
                        < prompt5.lastIndexOf("Next action JSON:")),
                Boolean.TRUE);
        check("prompt without an auto-read has no arrival banner",
                Boolean.valueOf(!prompt2.contains("You are ON the target page")),
                Boolean.TRUE);

        // -- v1.7: context-aware repeat key ---------------------------------
        // Field shape (v1.6 run): tap #20 on the results page, tap #20
        // again on the results page after a re-search, tap #20 on the
        // Clinch page — three pages, one bare signature, one wrong stop.

        ElementInfo resultsLink = el(20, "a", "Clinch River");
        resultsLink.href = "https://en.wikipedia.org/wiki/Clinch_River";
        ElementInfo clinchLinkAlt = el(20, "a", "Clinch");
        clinchLinkAlt.href = "https://en.wikipedia.org/wiki/Clinch";
        String clinchPageUrl = "https://en.wikipedia.org/wiki/Clinch";

        check("repeat key: same page, same action, same element keys together",
                Boolean.valueOf(Agent.repeatKey(resultsUrl, tap(20), resultsLink)
                        .equals(Agent.repeatKey(resultsUrl, tap(20), resultsLink))),
                Boolean.TRUE);
        check("repeat key: a different page separates the count",
                Boolean.valueOf(!Agent.repeatKey(resultsUrl, tap(20), resultsLink)
                        .equals(Agent.repeatKey(clinchPageUrl, tap(20),
                                resultsLink))),
                Boolean.TRUE);
        check("repeat key: a different element on the same page separates",
                Boolean.valueOf(!Agent.repeatKey(resultsUrl, tap(20), resultsLink)
                        .equals(Agent.repeatKey(resultsUrl, tap(20),
                                clinchLinkAlt))),
                Boolean.TRUE);
        ElementInfo anonymousEl = el(7, "a", "");
        check("repeat key: an unidentifiable element falls back to its number",
                Boolean.valueOf(Agent.repeatKey(resultsUrl, tap(7), null)
                        .equals(Agent.repeatKey(resultsUrl, tap(7), anonymousEl))),
                Boolean.TRUE);
        check("repeat key: the number fallback still separates numbers",
                Boolean.valueOf(!Agent.repeatKey(resultsUrl, tap(7), null)
                        .equals(Agent.repeatKey(resultsUrl, tap(8), null))),
                Boolean.TRUE);
        check("repeat key: an identified element differs from the bare number",
                Boolean.valueOf(!Agent.repeatKey(resultsUrl, tap(20), resultsLink)
                        .equals(Agent.repeatKey(resultsUrl, tap(20), null))),
                Boolean.TRUE);
        check("repeat key: non-element actions key on page plus signature",
                Boolean.valueOf(Agent.repeatKey(resultsUrl, leaveSearch, null)
                        .equals(Agent.repeatKey(resultsUrl, leaveSearch, null))
                        && !Agent.repeatKey(resultsUrl, leaveSearch, null)
                        .equals(Agent.repeatKey(clinchPageUrl, leaveSearch,
                                null))),
                Boolean.TRUE);

        // -- v1.8: token estimate --------------------------------------
        // The budget is a ceiling in the project's chars/4 estimate
        // (Trail.accounting's est_tokens), rounded up.

        check("token estimate: the empty string is zero tokens",
                Integer.valueOf(Agent.estTokens("")),
                Integer.valueOf(0));
        check("token estimate: four chars is one token",
                Integer.valueOf(Agent.estTokens("abcd")),
                Integer.valueOf(1));
        check("token estimate: five chars rounds up to two",
                Integer.valueOf(Agent.estTokens("abcde")),
                Integer.valueOf(2));
        check("token estimate: null is zero",
                Integer.valueOf(Agent.estTokens(null)),
                Integer.valueOf(0));
        check("prompt budget leaves room for the reply in the context",
                Boolean.valueOf(Agent.PROMPT_TOKEN_BUDGET
                        + Agent.MAX_TOKENS_PER_STEP <= 2048),
                Boolean.TRUE);

        // -- v1.8: engine-error fast stop --------------------------------
        // Field shape (v1.7 run, step 4): generate() returned
        // "ERROR: prompt too long for context"; the unparseable path
        // treated it as planner gibberish and burned three steps.

        check("engine error: the overflow string is recognised",
                Boolean.valueOf(Agent.isEngineError(
                        "ERROR: prompt too long for context")),
                Boolean.TRUE);
        check("engine error: a JSON action is not an engine error",
                Boolean.valueOf(Agent.isEngineError(
                        "{\"action\":\"read\"}")),
                Boolean.FALSE);
        check("engine error: null is not an engine error",
                Boolean.valueOf(Agent.isEngineError(null)),
                Boolean.FALSE);
        check("engine error: an action result is not an engine error",
                Boolean.valueOf(Agent.isEngineError(
                        "error: element 3 not on page")),
                Boolean.FALSE);
        check("engine error summary carries the engine's message",
                Agent.engineErrorSummary(
                        "ERROR: prompt too long for context"),
                "model engine error: ERROR: prompt too long for context");

        // -- v1.8: read-history dedupe -----------------------------------
        // The v1.7 shape: the planner's read entry carries the same
        // page text the auto-read section shows, at full weight.

        java.util.List<String> hist1 = new java.util.ArrayList<String>();
        hist1.add("step 1: {\"action\":\"search\",\"query\":\"Clinch River\"}"
                + " -> ok: searched Wikipedia for \"Clinch River\"");
        hist1.add("step 2: {\"action\":\"tap\",\"el\":16} -> ok: tapped #16");
        hist1.add("step 3: {\"action\":\"read\"} -> ok: read chars 0-2400 "
                + "of 6000; read again for more:\n" + rep('A', 2400));
        hist1.add("step 4: {\"action\":\"tap\",\"el\":20} -> ok: tapped #20");
        hist1.add("step 5: {\"action\":\"scroll\",\"dir\":\"down\"}"
                + " -> ok: scrolled down");
        java.util.List<String> urls1 = new java.util.ArrayList<String>();
        urls1.add(null);
        urls1.add(null);
        urls1.add(articleUrl);
        urls1.add(null);
        urls1.add(null);

        java.util.List<String> deduped =
                Agent.dedupeReadHistory(hist1, urls1, articleUrl);
        check("dedupe stubs the chunk the auto-read section already shows",
                Boolean.valueOf(deduped.get(2).contains(
                        "(page text shown above in the auto-read "
                                + "section; not repeated here)")),
                Boolean.TRUE);
        check("dedupe keeps the step line and the action JSON",
                Boolean.valueOf(deduped.get(2).startsWith(
                        "step 3: {\"action\":\"read\"} -> ")),
                Boolean.TRUE);
        check("dedupe does not carry the chunk text a second time",
                Boolean.valueOf(!deduped.get(2).contains(rep('A', 100))),
                Boolean.TRUE);
        check("dedupe leaves non-read entries untouched",
                deduped.get(0), hist1.get(0));
        java.util.List<String> histOther = new java.util.ArrayList<String>();
        histOther.add("step 1: {\"action\":\"read\"} -> ok: read chars "
                + "0-100 of 900; read again for more:\n" + rep('Z', 100));
        java.util.List<String> urlsOther = new java.util.ArrayList<String>();
        urlsOther.add("https://en.wikipedia.org/wiki/Norris_Dam");
        check("dedupe leaves a read of a different page untouched",
                Agent.dedupeReadHistory(histOther, urlsOther, articleUrl)
                        .get(0),
                histOther.get(0));
        check("dedupe stubs nothing when no auto-read section is shown",
                Agent.dedupeReadHistory(hist1, urls1, null).get(2),
                hist1.get(2));

        // -- v1.8: prompt budget — under-budget identity -----------------

        java.util.List<String> noUrls = new java.util.ArrayList<String>();
        String smallPrompt = Agent.buildPromptWithinBudget(wikiGoal,
                article, "[1] <a> \"x\"\n",
                new java.util.ArrayList<String>(), noUrls, null, null, 0);
        check("budget: an under-budget prompt is byte-identical",
                smallPrompt,
                Agent.buildPrompt(wikiGoal, article, "[1] <a> \"x\"\n",
                        new java.util.ArrayList<String>()));
        java.util.List<String> histSmall = new java.util.ArrayList<String>();
        histSmall.add(histOther.get(0));
        java.util.List<String> urlsSmall = new java.util.ArrayList<String>();
        urlsSmall.add(articleUrl);
        String smallSecPrompt = Agent.buildPromptWithinBudget(wikiGoal,
                article, "[1] <a> \"x\"\n", histSmall, urlsSmall,
                section, articleUrl, 0);
        check("budget: under-budget dedupe does not fire either",
                smallSecPrompt,
                Agent.buildPrompt(wikiGoal, article, "[1] <a> \"x\"\n",
                        histSmall, section));

        // -- v1.8: prompt budget — stage (i) dedupe decides --------------
        // Sized so the full prompt is over budget and stubbing the
        // one duplicated read chunk brings it back under: nothing
        // else may be trimmed.

        StringBuilder mapB = new StringBuilder();
        for (int i = 1; i <= 26; i++) {
            mapB.append("[").append(i)
                    .append("] <a> \"x\" href=https://en.wikipedia.org/wiki/X\n");
        }
        String map26 = mapB.toString();
        Eyes.Perception bp1 = new Eyes.Perception();
        bp1.url = articleUrl;
        bp1.title = articleTitle;
        bp1.textPreview = rep('p', 240);
        String sectionA = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('A', 2400), 6000));
        String over1 = Agent.buildPromptWithinBudget(wikiGoal, bp1,
                map26, hist1, urls1, sectionA, articleUrl, 0);
        check("budget(i): the result fits the budget",
                Boolean.valueOf(Agent.estTokens(over1)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("budget(i): the duplicated read was stubbed",
                Boolean.valueOf(over1.contains(
                        "(page text shown above in the auto-read "
                                + "section; not repeated here)")),
                Boolean.TRUE);
        check("budget(i): the preview survived (later stages did not run)",
                Boolean.valueOf(over1.contains("Page text: " + rep('p', 40))),
                Boolean.TRUE);
        check("budget(i): all five history entries survived",
                Boolean.valueOf(over1.contains("step 1:")
                        && over1.contains("step 5:")),
                Boolean.TRUE);
        check("budget(i): the auto-read section was not truncated",
                Boolean.valueOf(!over1.contains(
                        "(auto-read text truncated")),
                Boolean.TRUE);
        int goalCount1 = over1.split(
                java.util.regex.Pattern.quote(wikiGoal), -1).length - 1;
        check("budget(i): the goal still appears twice and the cue ends it",
                Boolean.valueOf(goalCount1 == 2
                        && over1.endsWith("Next action JSON:")),
                Boolean.TRUE);

        // -- v1.8: prompt budget — stage (ii) preview drop decides -------
        // No read history to dedupe; the overage is smaller than the
        // preview line, so dropping the preview alone must suffice
        // and the auto-read section must survive whole.
        // v2.2 re-size: the map below carries an https href on every
        // line, and the density-aware estimator charges those lines
        // at chars/3 — at the v1.8 size (36 lines) the surcharge
        // alone (~150 tokens) exceeded the preview's weight, so no
        // fixture could keep this premise. 26 lines restore it:
        // the overage is again smaller than the preview line.

        Eyes.Perception bp2 = new Eyes.Perception();
        bp2.url = articleUrl;
        bp2.title = articleTitle;
        bp2.textPreview = "PREVIEWTOKEN " + rep('p', 227);
        java.util.List<String> hist2 = new java.util.ArrayList<String>();
        for (int i = 1; i <= 5; i++) {
            hist2.add("step " + i + ": {\"action\":\"tap\",\"el\":16}"
                    + " -> ok: tapped #16");
        }
        java.util.List<String> urls2 = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) urls2.add(null);
        StringBuilder mapB26 = new StringBuilder();
        for (int i = 1; i <= 26; i++) {
            mapB26.append("[").append(i)
                    .append("] <a> \"x\" href=https://en.wikipedia.org/wiki/X\n");
        }
        String sectionB = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('B', 3000), 9000));
        String over2 = Agent.buildPromptWithinBudget(wikiGoal, bp2,
                mapB26.toString(), hist2, urls2, sectionB, articleUrl, 0);
        check("budget(ii): the result fits the budget",
                Boolean.valueOf(Agent.estTokens(over2)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("budget(ii): the preview was dropped",
                Boolean.valueOf(!over2.contains("PREVIEWTOKEN")),
                Boolean.TRUE);
        check("budget(ii): the auto-read section survived whole",
                Boolean.valueOf(over2.contains(rep('B', 100))
                        && !over2.contains("(auto-read text truncated")),
                Boolean.TRUE);
        check("budget(ii): the history survived whole",
                Boolean.valueOf(over2.contains("step 1:")
                        && over2.contains("step 5:")),
                Boolean.TRUE);

        // -- v1.8: prompt budget — stage (iii) history trim decides ------
        // No preview to drop, no reads to dedupe; five fat history
        // entries carry the overage: keeping the recent three must
        // suffice, with the auto-read section and map intact.

        Eyes.Perception bp3 = new Eyes.Perception();
        bp3.url = articleUrl;
        bp3.title = articleTitle;
        bp3.textPreview = "";
        java.util.List<String> hist3 = new java.util.ArrayList<String>();
        for (int i = 1; i <= 5; i++) {
            hist3.add("step " + i + ": HIST" + i + " " + rep('h', 680)
                    + " -> ok");
        }
        java.util.List<String> urls3 = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) urls3.add(null);
        StringBuilder mapB8 = new StringBuilder();
        mapB8.append("[1] <a> \"MAPLINE\" href=https://en.wikipedia.org/wiki/X\n");
        for (int i = 2; i <= 8; i++) {
            mapB8.append("[").append(i)
                    .append("] <a> \"x\" href=https://en.wikipedia.org/wiki/X\n");
        }
        String sectionC = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('C', 1500), 6000));
        String over3 = Agent.buildPromptWithinBudget(wikiGoal, bp3,
                mapB8.toString(), hist3, urls3, sectionC, articleUrl, 0);
        check("budget(iii): the result fits the budget",
                Boolean.valueOf(Agent.estTokens(over3)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("budget(iii): the oldest entries were dropped",
                Boolean.valueOf(!over3.contains("HIST1")
                        && !over3.contains("HIST2")),
                Boolean.TRUE);
        check("budget(iii): the recent three entries survived",
                Boolean.valueOf(over3.contains("HIST3")
                        && over3.contains("HIST5")),
                Boolean.TRUE);
        check("budget(iii): the auto-read section and map survived",
                Boolean.valueOf(over3.contains(rep('C', 100))
                        && over3.contains("MAPLINE")
                        && !over3.contains("(auto-read text truncated")),
                Boolean.TRUE);

        // -- v1.8: prompt budget — stage (iv) auto-read cut decides ------
        // A huge auto-read section carries the overage; its tail is
        // cut (with marker) and the map stays whole.

        java.util.List<String> hist4 = new java.util.ArrayList<String>();
        hist4.add("step 1: {\"action\":\"search\",\"query\":\"Clinch River\"}"
                + " -> ok: searched Wikipedia");
        java.util.List<String> urls4 = new java.util.ArrayList<String>();
        urls4.add(null);
        StringBuilder mapB8t = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            mapB8t.append("[").append(i)
                    .append("] <a> \"x\" href=https://en.wikipedia.org/wiki/X\n");
        }
        mapB8t.append("[8] <a> \"MAPTAIL\" href=https://en.wikipedia.org/wiki/X\n");
        String sectionD = Agent.autoReadSection(Eyes.formatReadChunk(
                0, rep('D', 5188) + "AUTOREADTAIL", 9000));
        String over4 = Agent.buildPromptWithinBudget(wikiGoal, bp3,
                mapB8t.toString(), hist4, urls4, sectionD, articleUrl, 0);
        check("budget(iv): the result fits the budget",
                Boolean.valueOf(Agent.estTokens(over4)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("budget(iv): the auto-read tail was cut with its marker",
                Boolean.valueOf(over4.contains(
                        "(auto-read text truncated to fit the "
                                + "prompt budget)")
                        && !over4.contains("AUTOREADTAIL")),
                Boolean.TRUE);
        check("budget(iv): the map survived whole",
                Boolean.valueOf(over4.contains("MAPTAIL")
                        && !over4.contains("(element map truncated")),
                Boolean.TRUE);

        // -- v1.8: prompt budget — stage (v) map cut decides -------------
        // The element map dwarfs everything (the auto-read section
        // is cut to its stub first and it is still not enough), so
        // the map's tail is cut, at the last stage.

        java.util.List<String> hist5 = new java.util.ArrayList<String>();
        hist5.add("step 1: {\"action\":\"tap\",\"el\":1} -> ok: tapped #1");
        java.util.List<String> urls5 = new java.util.ArrayList<String>();
        urls5.add(null);
        StringBuilder mapBig = new StringBuilder();
        for (int i = 1; i <= 129; i++) {
            mapBig.append("[").append(i)
                    .append("] <a> \"x\" href=https://en.wikipedia.org/wiki/X\n");
        }
        mapBig.append("MAPTAIL\n");
        String sectionE = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('E', 600), 6000));
        String over5 = Agent.buildPromptWithinBudget(wikiGoal, bp3,
                mapBig.toString(), hist5, urls5, sectionE, articleUrl, 0);
        check("budget(v): the result fits the budget",
                Boolean.valueOf(Agent.estTokens(over5)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("budget(v): the map tail was cut with its marker",
                Boolean.valueOf(over5.contains(
                        "(element map truncated to fit the "
                                + "prompt budget)")
                        && !over5.contains("MAPTAIL")),
                Boolean.TRUE);
        check("budget(v): contract, goal and banner were never trimmed",
                Boolean.valueOf(over5.startsWith(
                        "You are Ghost Hands, a careful web agent.")
                        && over5.contains("You are ON the target page")
                        && over5.endsWith("Next action JSON:")),
                Boolean.TRUE);

        // -- v1.9: end-of-text correction (v1.8's rule, generalised) -----
        // v1.8 field shape (trail-20261024-222519): the auto-read
        // stopped at 2,400 of 11,604; PLANNER reads paged to the end
        // ("end of page text" at step 6) and step 7's read wrapped
        // the cursor to the top, uncorrected — v1.8's rule only
        // recognised completion reached by the auto-read. The
        // unified rule keys on completion from ANY read path. The
        // v1.8 assertions keep their shape against the new
        // signature; the once-per-run limit widens to the shared
        // cap of 3 (the tasking for this release).

        GhAction readAct = new GhAction("read");
        String doneReason = RelevanceGuard.readAfterCompleteReason(
                readAct, articleUrl, articleUrl, 11604, 0);
        check("read-after-complete fires on the fully-read page",
                Boolean.valueOf(doneReason != null),
                Boolean.TRUE);
        check("read-after-complete names the totals it holds",
                Boolean.valueOf(doneReason != null
                        && doneReason.contains("chars 0-11604 of 11604")),
                Boolean.TRUE);
        check("read-after-complete: last correction under the cap fires",
                Boolean.valueOf(RelevanceGuard.readAfterCompleteReason(
                        readAct, articleUrl, articleUrl, 11604,
                        RelevanceGuard.READ_COMPLETE_CORRECTION_CAP - 1)
                        != null),
                Boolean.TRUE);
        check("read-after-complete falls silent at the cap",
                RelevanceGuard.readAfterCompleteReason(readAct,
                        articleUrl, articleUrl, 11604,
                        RelevanceGuard.READ_COMPLETE_CORRECTION_CAP),
                null);
        check("read-after-complete ignores other actions",
                RelevanceGuard.readAfterCompleteReason(
                        tap(3), articleUrl, articleUrl, 11604, 0),
                null);
        check("read-after-complete ignores a different page",
                RelevanceGuard.readAfterCompleteReason(readAct,
                        "https://en.wikipedia.org/wiki/Norris_Dam",
                        articleUrl, 11604, 0),
                null);
        check("read-after-complete is quiet when no page completed",
                RelevanceGuard.readAfterCompleteReason(
                        readAct, articleUrl, null, 0, 0),
                null);

        // -- v1.9: read-result end/total parsing (Agent) -----------------
        // The completion state is fed by parsing real read results
        // (Eyes.formatReadChunk output), whichever path produced
        // them. Field numbers: the Clinch River article, 11,604
        // chars paged in 2,400-char chunks.

        String midChunk = Eyes.formatReadChunk(2400, rep('m', 2400), 11604);
        String lastChunk = Eyes.formatReadChunk(9600, rep('l', 2004), 11604);
        check("a mid-text planner read has not reached the end",
                Boolean.valueOf(Agent.readReachedEnd(midChunk)),
                Boolean.FALSE);
        check("a mid-text planner read still reports its total",
                Integer.valueOf(Agent.readTotalChars(midChunk)),
                Integer.valueOf(11604));
        check("the final planner read reaches the end",
                Boolean.valueOf(Agent.readReachedEnd(lastChunk)),
                Boolean.TRUE);
        check("the final planner read reports the same total",
                Integer.valueOf(Agent.readTotalChars(lastChunk)),
                Integer.valueOf(11604));
        check("completion from a planner read arms the correction",
                Boolean.valueOf(Agent.readReachedEnd(lastChunk)
                        && RelevanceGuard.readAfterCompleteReason(
                                readAct, articleUrl, articleUrl,
                                Agent.readTotalChars(lastChunk), 0)
                                != null),
                Boolean.TRUE);
        String wholePage = Eyes.formatReadChunk(0, rep('w', 1473), 1473);
        check("a one-chunk page completes on its first read",
                Boolean.valueOf(Agent.readReachedEnd(wholePage)
                        && Agent.readTotalChars(wholePage) == 1473),
                Boolean.TRUE);
        check("null and garbage results parse safely",
                Boolean.valueOf(!Agent.readReachedEnd(null)
                        && Agent.readTotalChars(null) == -1
                        && !Agent.readReachedEnd("ok: tapped #3")
                        && Agent.readTotalChars("ok: tapped #3") == -1),
                Boolean.TRUE);

        // -- v1.9: completion banner --------------------------------------

        String promptDone = Agent.buildPrompt(wikiGoal, article,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>(),
                null, 11604);
        check("prompt for a fully-read page carries the completion banner",
                Boolean.valueOf(promptDone.contains(
                        "You have read this entire page. Answer done "
                                + "from it now.")),
                Boolean.TRUE);
        check("completion banner sits after the repeated goal, before the cue",
                Boolean.valueOf(promptDone.indexOf(
                        "You have read this entire page")
                        > promptDone.lastIndexOf("Goal: " + wikiGoal)
                        && promptDone.indexOf(
                                "You have read this entire page")
                        < promptDone.lastIndexOf("Next action JSON:")),
                Boolean.TRUE);
        check("no completion banner before the page is fully read",
                Boolean.valueOf(!prompt2.contains(
                        "You have read this entire page")),
                Boolean.TRUE);
        String promptBoth = Agent.buildPrompt(wikiGoal, article,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>(),
                section, 11604);
        check("both banners: arrival first, completion after",
                Boolean.valueOf(promptBoth.contains(
                        "You are ON the target page")
                        && promptBoth.contains(
                                "You have read this entire page")
                        && promptBoth.indexOf("You are ON the target page")
                        < promptBoth.indexOf(
                                "You have read this entire page")),
                Boolean.TRUE);
        String smallDonePrompt = Agent.buildPromptWithinBudget(wikiGoal,
                article, "[1] <a> \"x\"\n",
                new java.util.ArrayList<String>(), noUrls, null, null,
                11604);
        check("budget: under-budget completion prompt is byte-identical",
                smallDonePrompt,
                Agent.buildPrompt(wikiGoal, article, "[1] <a> \"x\"\n",
                        new java.util.ArrayList<String>(), null, 11604));

        // -- v1.8: read extraction walks the DOM, not innerText ------------
        // The v1.7 field total was 1,473 chars for a full article:
        // innerText returns only rendered text, and the mobile
        // presentation collapses sections (display:none) — the
        // sections were in the DOM but invisible to the extractor.

        String readJs = Eyes.readTextJs(0, Eyes.READ_CHUNK_CHARS);
        check("read JS no longer extracts via innerText",
                Boolean.valueOf(!readJs.contains("innerText")),
                Boolean.TRUE);
        check("read JS walks DOM text nodes (visibility-blind)",
                Boolean.valueOf(readJs.contains("childNodes")),
                Boolean.TRUE);
        check("read JS caps the total at READ_TOTAL_CAP_CHARS",
                Boolean.valueOf(readJs.contains("60000")
                        && Eyes.READ_TOTAL_CAP_CHARS == 60000),
                Boolean.TRUE);

        // -- v2.0: identity-based re-find (self-healing hands) ------------
        // Field evidence (v1.9's first passing run): two taps died
        // with "element 11 not on page" because a 40-100 s model
        // generation sits between perception and execution and the
        // live DOM churns in that window. The heal DECISION is pure
        // and pinned here; the JS DOM search itself is not
        // host-executable (its builders are shape-checked below).

        ElementInfo perceivedLink = el(16, "a", "Clinch River");
        perceivedLink.href = "https://en.wikipedia.org/wiki/Clinch_River";
        ElementInfo sameLink = el(0, "a", "Clinch River");
        sameLink.href = "https://en.wikipedia.org/wiki/Clinch_River";
        ElementInfo otherHrefLink = el(0, "a", "Clinch River");
        otherHrefLink.href = "https://en.wikipedia.org/wiki/Clinch_River_(disambiguation)";
        ElementInfo otherNameLink = el(0, "a", "Clinch River (disambiguation)");
        otherNameLink.href = "https://en.wikipedia.org/wiki/Clinch_River";
        check("heal identity: link matches on name + href",
                Boolean.valueOf(Eyes.identityMatches(perceivedLink, sameLink)),
                Boolean.TRUE);
        check("heal identity: link with a different href does not match",
                Boolean.valueOf(Eyes.identityMatches(perceivedLink, otherHrefLink)),
                Boolean.FALSE);
        check("heal identity: link with a different name does not match",
                Boolean.valueOf(Eyes.identityMatches(perceivedLink, otherNameLink)),
                Boolean.FALSE);
        ElementInfo searchBox = el(3, "input", "Search");
        searchBox.type = "search";
        ElementInfo searchBoxSame = el(0, "input", "Search");
        searchBoxSame.type = "search";
        ElementInfo searchBoxOtherType = el(0, "input", "Search");
        searchBoxOtherType.type = "text";
        ElementInfo searchButton = el(0, "button", "Search");
        searchButton.type = "search";
        check("heal identity: control matches on name + tag + type",
                Boolean.valueOf(Eyes.identityMatches(searchBox, searchBoxSame)),
                Boolean.TRUE);
        check("heal identity: control with a different type does not match",
                Boolean.valueOf(Eyes.identityMatches(searchBox, searchBoxOtherType)),
                Boolean.FALSE);
        check("heal identity: tag mismatch never matches",
                Boolean.valueOf(Eyes.identityMatches(searchBox, searchButton)),
                Boolean.FALSE);
        check("heal identity: an empty stash slot never matches",
                Boolean.valueOf(Eyes.identityMatches(perceivedLink, null)),
                Boolean.FALSE);

        ElementInfo englishLink = el(0, "a", "English");
        englishLink.href = "https://en.wikipedia.org/wiki/Main_Page";
        java.util.List<ElementInfo> scanOne = new java.util.ArrayList<ElementInfo>();
        scanOne.add(englishLink);
        scanOne.add(sameLink);
        scanOne.add(el(0, "button", "Menu"));
        Eyes.Heal healMatch = Eyes.decideHeal(perceivedLink, sameLink, scanOne);
        check("heal decision: stash still holding the element is a MATCH",
                Boolean.valueOf(healMatch.kind == Eyes.Heal.MATCH),
                Boolean.TRUE);
        Eyes.Heal healFound = Eyes.decideHeal(perceivedLink, englishLink, scanOne);
        check("heal decision: unique scan match is REFOUND at its scan index",
                Boolean.valueOf(healFound.kind == Eyes.Heal.REFOUND
                        && healFound.scanIndex == 1),
                Boolean.TRUE);
        Eyes.Heal healEmptySlot = Eyes.decideHeal(perceivedLink, null, scanOne);
        check("heal decision: emptied slot still heals from the scan",
                Boolean.valueOf(healEmptySlot.kind == Eyes.Heal.REFOUND
                        && healEmptySlot.scanIndex == 1),
                Boolean.TRUE);
        java.util.List<ElementInfo> scanNone = new java.util.ArrayList<ElementInfo>();
        scanNone.add(englishLink);
        scanNone.add(el(0, "button", "Menu"));
        check("heal decision: no scan match is NONE",
                Boolean.valueOf(Eyes.decideHeal(perceivedLink, englishLink,
                        scanNone).kind == Eyes.Heal.NONE),
                Boolean.TRUE);
        ElementInfo readMoreA = el(0, "a", "Read more");
        readMoreA.href = "https://x.local/post";
        ElementInfo readMoreB = el(0, "a", "Read more");
        readMoreB.href = "https://x.local/post";
        java.util.List<ElementInfo> scanDup = new java.util.ArrayList<ElementInfo>();
        scanDup.add(readMoreA);
        scanDup.add(sameLink);
        scanDup.add(readMoreB);
        check("heal decision: several identical links are MULTIPLE (refused)",
                Boolean.valueOf(Eyes.decideHeal(readMoreA, null,
                        scanDup).kind == Eyes.Heal.MULTIPLE),
                Boolean.TRUE);
        check("heal decision: a null perceived element is NONE",
                Boolean.valueOf(Eyes.decideHeal(null, null,
                        null).kind == Eyes.Heal.NONE),
                Boolean.TRUE);

        // The heal JS builders (not host-executable; shape only).
        check("identity JS reads the stash slot by planner number",
                Boolean.valueOf(Eyes.identityJs(11).contains("__gh.els[10]")),
                Boolean.TRUE);
        check("identity scan JS enumerates like MAP_JS",
                Boolean.valueOf(Eyes.identityScanJs().contains("querySelectorAll")
                        && Eyes.identityScanJs().contains(
                                "a,button,input,select,textarea")),
                Boolean.TRUE);
        check("tap-scan JS acts on the scan position, errors with the planner number",
                Boolean.valueOf(Eyes.tapScanJs(3, 11).contains("collectNodes()[3]")
                        && Eyes.tapScanJs(3, 11).contains(
                                "error: element 11 not on page")),
                Boolean.TRUE);
        check("type-scan JS acts on the scan position",
                Boolean.valueOf(Eyes.typeScanJs(3, 11, "x").contains(
                        "collectNodes()[3]")),
                Boolean.TRUE);
        // -- v2.7: every generated script must be structurally valid --
        // From v2.0 through v2.6.1 the four heal scripts were
        // syntactically invalid JS (one closing brace too many in
        // collectNodes) and could never execute; nothing host-side
        // ever looked at the emitted text as code. These checks do.
        java.util.List<ElementInfo> stashSample =
                new java.util.ArrayList<ElementInfo>();
        stashSample.add(perceivedLink);
        stashSample.add(englishLink);
        check("js validity: the perception script",
                jsStructureError(Eyes.MAP_JS), null);
        check("js validity: the stash script",
                jsStructureError(Eyes.stashJs(stashSample)), null);
        check("js validity: the identity script (was invalid v2.0-v2.6.1)",
                jsStructureError(Eyes.identityJs(3)), null);
        check("js validity: the identity scan script (was invalid)",
                jsStructureError(Eyes.identityScanJs()), null);
        check("js validity: the tap-scan script (was invalid)",
                jsStructureError(Eyes.tapScanJs(2, 3)), null);
        check("js validity: the type-scan script (was invalid)",
                jsStructureError(Eyes.typeScanJs(2, 3, "x")), null);
        check("js validity: the tap and type scripts",
                jsStructureError(Eyes.tapJs(3) + Eyes.typeJs(3, "x")),
                null);
        check("js validity: the submit-search script",
                jsStructureError(Eyes.submitSearchJs(3)), null);
        check("js validity: the read and scroll scripts",
                jsStructureError(Eyes.readTextJs(0, Eyes.READ_CHUNK_CHARS)
                        + Eyes.scrollJs("down")),
                null);
        check("js validity: collectNodes returns inside its own body",
                Boolean.valueOf(Eyes.identityScanJs().contains(
                        "out.push(ex[j]);return out;}")),
                Boolean.TRUE);
        check("js validity: the validator itself catches an extra brace",
                Boolean.valueOf(jsStructureError(
                        "(function(){var x=1;}})()") != null),
                Boolean.TRUE);

        // -- v2.7: map / stash / scan parity, end to end ---------------
        // Modelled on the field page (trail-20261025-003150): a
        // candidate flood of repeated boilerplate links ahead of the
        // search controls and the result links. The invariant: the
        // map, the stash and the heal scan agree on ONE candidate
        // sequence — the shaped number N, the stash slot N, and the
        // scan position of the same raw candidate all name the same
        // element, and its tap resolves by identity.
        String tendayName =
                "10-day weather forecast for Oakdale, TN, United States";
        String tendayHref = "https://duckduckgo.com/l/?uddg="
                + "https%3A%2F%2Fweather.com%2Fen%2DNA%2Fweather%2Ftenday"
                + "%2Fl%2FOakdale%2BTN";
        StringBuilder modelEls = new StringBuilder();
        StringBuilder scanEls = new StringBuilder();
        int boiler = 130;
        for (int i = 0; i < boiler; i++) {
            String b = "{\"tag\":\"a\",\"role\":\"link\","
                    + "\"name\":\"Regional hub\",\"type\":\"\","
                    + "\"href\":\"https://example.test/hub\"}";
            if (i > 0) { modelEls.append(','); scanEls.append(','); }
            modelEls.append(b); scanEls.append(b);
        }
        String modelTail = ",{\"tag\":\"input\",\"role\":\"textbox\","
                + "\"name\":\"q\",\"type\":\"text\",\"href\":\"\"}"
                + ",{\"tag\":\"select\",\"role\":\"combobox\","
                + "\"name\":\"All Regions\",\"type\":\"\",\"href\":\"\"}"
                + ",{\"tag\":\"a\",\"role\":\"link\",\"name\":\"top\","
                + "\"type\":\"\",\"href\":\"\"}"
                + ",{\"tag\":\"a\",\"role\":\"link\",\"name\":\""
                + tendayName + "\",\"type\":\"\",\"href\":\""
                + tendayHref + "\"}"
                + ",{\"tag\":\"a\",\"role\":\"link\","
                + "\"name\":\"Oakdale, TN Hourly Weather\","
                + "\"type\":\"\",\"href\":\"https://example.test/hourly\"}";
        modelEls.append(modelTail); scanEls.append(modelTail);
        String modelMapJson = "{\"url\":\"https://html.duckduckgo.com"
                + "/html/?q=oakdale\",\"title\":\"oakdale at "
                + "DuckDuckGo\",\"text\":\"results\",\"els\":["
                + modelEls + "]}";
        Eyes.Perception modelP = Eyes.parseMap(
                com.ghosthands.app.Json.quote(modelMapJson));
        ElementInfo tenday = null;
        for (ElementInfo e : modelP.elements) {
            if (tendayHref.equals(e.href)) tenday = e;
        }
        check("parity: the distinctive link survives shaping "
                        + "(boilerplate deduped)",
                Boolean.valueOf(tenday != null), Boolean.TRUE);
        int rawCount = boiler + 5;
        check("parity: the scan holds every raw candidate in order",
                Boolean.valueOf(Eyes.parseIdentityScan("{\"els\":["
                        + scanEls + "]}").size() == rawCount),
                Boolean.TRUE);
        java.util.List<ElementInfo> modelScan = Eyes.parseIdentityScan(
                "{\"els\":[" + scanEls + "]}");
        check("parity: scan position domIndex finds the same element",
                Boolean.valueOf(tenday != null
                        && Eyes.identityMatches(tenday,
                                modelScan.get(tenday.domIndex))),
                Boolean.TRUE);
        // The stash script's index list IS the map<->stash contract:
        // slot (number - 1) must hold the candidate domIndex.
        String stash = Eyes.stashJs(modelP.elements);
        String idxPart = stash.substring(stash.indexOf("var idx=[") + 9,
                stash.indexOf(']', stash.indexOf("var idx=[")));
        String[] idxTokens = idxPart.split(",");
        check("parity: the stash index list has one slot per map element",
                Boolean.valueOf(idxTokens.length == modelP.elements.size()),
                Boolean.TRUE);
        check("parity: the stash slot for the number holds its domIndex",
                Boolean.valueOf(tenday != null
                        && Integer.parseInt(idxTokens[tenday.number - 1]
                                .trim()) == tenday.domIndex),
                Boolean.TRUE);
        // The tap resolves by identity: stash slot content (the raw
        // candidate at domIndex) matches the perceived element.
        ElementInfo modelAtStash = (tenday == null) ? null
                : modelScan.get(tenday.domIndex);
        check("parity: an unmoved page heals as MATCH (tap via stash)",
                Boolean.valueOf(tenday != null
                        && Eyes.decideHeal(tenday, modelAtStash, modelScan)
                                .kind == Eyes.Heal.MATCH),
                Boolean.TRUE);
        // Moved variant (the lab's re-render simulation): the slot's
        // node was replaced by a different link and a fresh copy of
        // the target lives at the very end of the scan.
        java.util.List<ElementInfo> movedScan =
                new java.util.ArrayList<ElementInfo>(modelScan);
        ElementInfo replacement = el(0, "a", "Weather Forecast and Conditions");
        replacement.href = "https://example.test/other";
        movedScan.set(tenday.domIndex, replacement);
        ElementInfo freshCopy = el(0, "a", tendayName);
        freshCopy.href = tendayHref;
        movedScan.add(freshCopy);
        Eyes.Heal movedHeal = Eyes.decideHeal(tenday, replacement,
                movedScan);
        check("parity: a replaced slot heals as REFOUND at the scan index",
                Boolean.valueOf(movedHeal.kind == Eyes.Heal.REFOUND
                        && movedHeal.scanIndex == movedScan.size() - 1),
                Boolean.TRUE);
        check("parity: tap-scan acts on that same scan position",
                Boolean.valueOf(Eyes.tapScanJs(movedHeal.scanIndex,
                        tenday.number).contains(
                                "collectNodes()[" + movedHeal.scanIndex
                                        + "]")),
                Boolean.TRUE);
        // No-evidence boundary (the field failure's second half):
        // with the slot unreadable AND no scan, the heal must not
        // veto — but a DIFFERENT element proven at the slot still
        // refuses even without a scan.
        check("heal decision: no identity evidence is a MATCH, not a veto",
                Boolean.valueOf(Eyes.decideHeal(perceivedLink, null,
                        null).kind == Eyes.Heal.MATCH
                        && Eyes.decideHeal(perceivedLink, null,
                                new java.util.ArrayList<ElementInfo>())
                                .kind == Eyes.Heal.MATCH),
                Boolean.TRUE);
        check("heal decision: a proven-different slot still refuses "
                        + "without a scan",
                Boolean.valueOf(Eyes.decideHeal(perceivedLink, englishLink,
                        null).kind == Eyes.Heal.NONE),
                Boolean.TRUE);

        ElementInfo parsedIdentity = Eyes.parseIdentity(
                "{\"found\":true,\"el\":{\"tag\":\"a\",\"name\":\"Clinch River\","
                        + "\"href\":\"https://en.wikipedia.org/wiki/Clinch_River\"}}");
        check("identity parse: a found node carries its identity",
                Boolean.valueOf(parsedIdentity != null
                        && Eyes.identityMatches(perceivedLink, parsedIdentity)),
                Boolean.TRUE);
        check("identity parse: an empty slot parses to null",
                Eyes.parseIdentity("{\"found\":false}"), null);
        java.util.List<ElementInfo> parsedScan = Eyes.parseIdentityScan(
                "{\"els\":[{\"tag\":\"a\",\"name\":\"English\"},"
                        + "{\"tag\":\"a\",\"name\":\"Clinch River\","
                        + "\"href\":\"https://en.wikipedia.org/wiki/Clinch_River\"}]}");
        check("identity scan parse: order and positions preserved",
                Boolean.valueOf(parsedScan.size() == 2
                        && parsedScan.get(1).domIndex == 1
                        && Eyes.identityMatches(perceivedLink,
                                parsedScan.get(1))),
                Boolean.TRUE);

        // -- v2.0: results-page pagination guard --------------------------
        // Field evidence (same run): on the Clinch River results
        // page the planner tapped "Next page" twice while the
        // exact-match result link sat in the map. The guard fires
        // only when a goal-matching link is provably in the map.

        java.util.List<ElementInfo> resultsMap =
                new java.util.ArrayList<ElementInfo>();
        resultsMap.add(el(1, "a", "Main Page"));
        ElementInfo clinchResult = el(16, "a", "Clinch River");
        clinchResult.href = "https://en.wikipedia.org/wiki/Clinch_River";
        resultsMap.add(clinchResult);
        ElementInfo nextPageEl = el(24, "a", "Next page");
        resultsMap.add(nextPageEl);
        String pagReason = RelevanceGuard.paginationReason(wikiGoal,
                resultsTitle, resultsUrl, tap(24), nextPageEl, resultsMap, 0);
        check("pagination guard: fires with a goal-matching result in the map",
                Boolean.valueOf(pagReason != null),
                Boolean.TRUE);
        check("pagination guard: the reason names the matching result",
                Boolean.valueOf(pagReason != null
                        && pagReason.contains("#16")
                        && pagReason.contains("\"Clinch River\"")),
                Boolean.TRUE);
        java.util.List<ElementInfo> poorResultsMap =
                new java.util.ArrayList<ElementInfo>();
        poorResultsMap.add(el(2, "a", "Norris Lake"));
        poorResultsMap.add(el(3, "a", "Melton Hill Lake"));
        poorResultsMap.add(nextPageEl);
        check("pagination guard: silent when no result matches the goal",
                RelevanceGuard.paginationReason(wikiGoal, resultsTitle,
                        resultsUrl, tap(24), nextPageEl, poorResultsMap, 0),
                null);
        check("pagination guard: silent for a tap on the result itself",
                RelevanceGuard.paginationReason(wikiGoal, resultsTitle,
                        resultsUrl, tap(16), clinchResult, resultsMap, 0),
                null);
        check("pagination guard: silent off results pages",
                RelevanceGuard.paginationReason(wikiGoal, articleTitle,
                        articleUrl, tap(24), nextPageEl, resultsMap, 0),
                null);
        check("pagination guard: silent at the correction cap",
                RelevanceGuard.paginationReason(wikiGoal, resultsTitle,
                        resultsUrl, tap(24), nextPageEl, resultsMap,
                        RelevanceGuard.PAGINATION_CORRECTION_CAP),
                null);
        check("pagination guard: still fires one below the cap",
                Boolean.valueOf(RelevanceGuard.paginationReason(wikiGoal,
                        resultsTitle, resultsUrl, tap(24), nextPageEl,
                        resultsMap,
                        RelevanceGuard.PAGINATION_CORRECTION_CAP - 1) != null),
                Boolean.TRUE);
        ElementInfo pageTwoEl = el(25, "a", "2");
        resultsMap.add(pageTwoEl);
        check("pagination guard: a bare page number is pagination",
                Boolean.valueOf(RelevanceGuard.paginationReason(wikiGoal,
                        resultsTitle, resultsUrl, tap(25), pageTwoEl,
                        resultsMap, 0) != null),
                Boolean.TRUE);
        ElementInfo moreResultsEl = el(26, "a", "More results");
        resultsMap.add(moreResultsEl);
        check("pagination guard: 'More results' is pagination",
                Boolean.valueOf(RelevanceGuard.paginationReason(wikiGoal,
                        resultsTitle, resultsUrl, tap(26), moreResultsEl,
                        resultsMap, 0) != null),
                Boolean.TRUE);
        ElementInfo lowerNextEl = el(27, "a", "next page");
        resultsMap.add(lowerNextEl);
        check("pagination guard: name matching is case-insensitive",
                Boolean.valueOf(RelevanceGuard.paginationReason(wikiGoal,
                        resultsTitle, resultsUrl, tap(27), lowerNextEl,
                        resultsMap, 0) != null),
                Boolean.TRUE);
        check("pagination guard: a non-tap action is untouched",
                RelevanceGuard.paginationReason(wikiGoal, resultsTitle,
                        resultsUrl, type(24, "x"), nextPageEl, resultsMap, 0),
                null);
        check("pagination names: whole-name, trimmed, bounded",
                Boolean.valueOf(RelevanceGuard.isPaginationName(" Next page ")
                        && RelevanceGuard.isPaginationName("Previous page")
                        && RelevanceGuard.isPaginationName("Next »")
                        && RelevanceGuard.isPaginationName("13")
                        && !RelevanceGuard.isPaginationName("Next")
                        && !RelevanceGuard.isPaginationName("Page 2")
                        && !RelevanceGuard.isPaginationName("1234")
                        && !RelevanceGuard.isPaginationName("")
                        && !RelevanceGuard.isPaginationName(null)),
                Boolean.TRUE);
        check("goal-matching link: found by number in the map",
                Boolean.valueOf(RelevanceGuard.goalMatchingLink(wikiGoal,
                        resultsMap) == clinchResult),
                Boolean.TRUE);
        check("goal-matching link: null when nothing matches",
                RelevanceGuard.goalMatchingLink(wikiGoal, poorResultsMap),
                null);

        // -- v2.1: generalised search-results recognition ------------------
        // Field shape (trail-20261024-230228, the first non-Wikipedia
        // test): the generic search fell back to DuckDuckGo, whose
        // results page the v1.6 rules (Wikipedia shapes only) did
        // not recognise — auto-read fired on it and the planner
        // read snippets to end-of-text as if they were the article.
        String weatherGoal = "Find the forecast for Oakdale, Tennessee, "
                + "and tell me tonight's low temperature and the chance "
                + "of rain.";
        String ddgTitle = "Oakdale, Tennessee forecast at DuckDuckGo";
        String ddgUrl = "https://html.duckduckgo.com/html/"
                + "?q=Oakdale%2C+Tennessee+forecast";
        check("results page: DDG html host + /html/ path",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        ddgTitle, ddgUrl)),
                Boolean.TRUE);
        check("results page: duckduckgo.com with a q= param",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "Clinch River at DuckDuckGo",
                        "https://duckduckgo.com/?q=clinch+river")),
                Boolean.TRUE);
        check("results page: the ' at DuckDuckGo' title alone",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        ddgTitle, "https://example.com/some/page")),
                Boolean.TRUE);
        check("results page: google /search with a q= param",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "oakdale weather - Google Search",
                        "https://www.google.com/search?q=oakdale+weather")),
                Boolean.TRUE);
        check("results page: bare google.com host",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "x - Google Search",
                        "https://google.com/search?q=x")),
                Boolean.TRUE);
        check("results page: bing /search with a q= param",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "x - Search", "https://www.bing.com/search?q=x")),
                Boolean.TRUE);
        check("results page: bare bing.com host",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "x - Search", "https://bing.com/search?q=x")),
                Boolean.TRUE);
        check("results page: a normal page on duckduckgo.com, no q=",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "About DuckDuckGo", "https://duckduckgo.com/about")),
                Boolean.FALSE);
        check("results page: the html host off its /html/ path",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "DuckDuckGo", "https://html.duckduckgo.com/")),
                Boolean.FALSE);
        check("results page: a weather.gov forecast page is not results",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "National Weather Service",
                        "https://forecast.weather.gov/MapClick.php"
                                + "?lat=36.03&lon=-84.43")),
                Boolean.FALSE);
        check("results page: google /search without q= is not results",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "Google", "https://www.google.com/search")),
                Boolean.FALSE);
        check("results page: google /maps with q= is not results",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "Google Maps", "https://www.google.com/maps?q=oakdale")),
                Boolean.FALSE);
        check("results page: bing /search without q= is not results",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        "Bing", "https://www.bing.com/search")),
                Boolean.FALSE);
        // The consequence that matters: the DDG page's title matches
        // the goal's tokens (arrival WOULD fire) — only the results
        // exclusion stands auto-read down on it now.
        check("results page: the DDG title does match the weather goal",
                Boolean.valueOf(RelevanceGuard.pageArrived(
                        weatherGoal, ddgTitle, ddgUrl)),
                Boolean.TRUE);
        check("results page: no auto-read on the DDG results page",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(weatherGoal,
                        ddgTitle, ddgUrl,
                        new java.util.LinkedHashSet<String>())),
                Boolean.FALSE);

        // -- v2.1: done-grounding guard -----------------------------------
        // The weather run's actual done summary: a restatement of
        // the goal carrying neither requested value.
        GhAction doneEmpty = new GhAction("done");
        doneEmpty.summary = "Oakdale, Tennessee forecast provides "
                + "tonight's low temperature and chance of rain.";
        String doneGuardReason = RelevanceGuard.doneGroundingReason(
                doneEmpty, weatherGoal, 0);
        check("done guard: a valueless summary on a numeric goal fires",
                Boolean.valueOf(doneGuardReason != null), Boolean.TRUE);
        check("done guard: the reason says the summary states no values",
                Boolean.valueOf(doneGuardReason != null
                        && doneGuardReason.contains("none of the values")),
                Boolean.TRUE);
        GhAction doneDigit = new GhAction("done");
        doneDigit.summary = "Tonight's low is 44 degrees with a 10% "
                + "chance of rain.";
        check("done guard: silent when the summary carries the numbers",
                RelevanceGuard.doneGroundingReason(doneDigit, weatherGoal, 0),
                null);
        GhAction doneUnable = new GhAction("done");
        doneUnable.summary = "I could not find the forecast for Oakdale.";
        check("done guard: a plain inability statement is accepted",
                RelevanceGuard.doneGroundingReason(doneUnable, weatherGoal, 0),
                null);
        GhAction doneNotAvail = new GhAction("done");
        doneNotAvail.summary = "The forecast is not available for that "
                + "location.";
        check("done guard: 'not available' is accepted too",
                RelevanceGuard.doneGroundingReason(doneNotAvail,
                        weatherGoal, 0),
                null);
        check("done guard: silent for a non-numeric goal",
                RelevanceGuard.doneGroundingReason(doneEmpty,
                        "Open the Clinch River article and read it to me.", 0),
                null);
        check("done guard: silent at the correction cap",
                RelevanceGuard.doneGroundingReason(doneEmpty, weatherGoal,
                        RelevanceGuard.DONE_CORRECTION_CAP),
                null);
        check("done guard: still fires one below the cap",
                Boolean.valueOf(RelevanceGuard.doneGroundingReason(doneEmpty,
                        weatherGoal,
                        RelevanceGuard.DONE_CORRECTION_CAP - 1) != null),
                Boolean.TRUE);
        check("done guard: a non-done action is untouched",
                RelevanceGuard.doneGroundingReason(tap(1), weatherGoal, 0),
                null);
        check("numeric goals: cue detection",
                Boolean.valueOf(RelevanceGuard.goalRequestsNumbers(weatherGoal)
                        && RelevanceGuard.goalRequestsNumbers(
                                "How long is the Clinch River?")
                        && RelevanceGuard.goalRequestsNumbers(
                                "What is the population of Oakdale?")
                        && RelevanceGuard.goalRequestsNumbers(
                                "When is the next train to Knoxville?")
                        && !RelevanceGuard.goalRequestsNumbers(
                                "Tell me about the Clinch River.")
                        && !RelevanceGuard.goalRequestsNumbers(null)),
                Boolean.TRUE);
        check("grounded summaries: digit or plain inability only",
                Boolean.valueOf(RelevanceGuard.doneSummaryGrounded(
                                "337 miles long")
                        && RelevanceGuard.doneSummaryGrounded(
                                "It is unable to be determined from the page")
                        && !RelevanceGuard.doneSummaryGrounded(
                                "no values are stated here")
                        && !RelevanceGuard.doneSummaryGrounded("")
                        && !RelevanceGuard.doneSummaryGrounded(null)),
                Boolean.TRUE);
        // Regression: the v1.9 milestone pass stays a clean pass —
        // the Clinch goal IS numeric ("how long") and its real
        // summary carries the digits.
        GhAction doneClinch = new GhAction("done");
        doneClinch.summary = "The Clinch River is 337 miles long and is "
                + "dammed by Norris Dam and Melton Hill Dam.";
        check("done guard: the Clinch River pass is not corrected",
                RelevanceGuard.doneGroundingReason(doneClinch, wikiGoal, 0),
                null);

        // -- v2.1: lookup-box discovery (Eyes.pickSearchTarget) -----------
        // weather.gov's shape: a plain text input named
        // "inputstring"; only its FORM says what it is for.
        ElementInfo nwsBox = el(3, "input", "inputstring");
        nwsBox.type = "text";
        nwsBox.formText = "Local forecast by \"City, St\" or ZIP code";
        java.util.List<ElementInfo> nwsOnly =
                new java.util.ArrayList<ElementInfo>();
        nwsOnly.add(nwsBox);
        check("lookup box: the weather.gov input is discovered via its form",
                Boolean.valueOf(Eyes.pickSearchTarget(nwsOnly) == nwsBox),
                Boolean.TRUE);
        ElementInfo realSearch = el(5, "input", "");
        realSearch.type = "search";
        java.util.List<ElementInfo> classicLater =
                new java.util.ArrayList<ElementInfo>();
        classicLater.add(nwsBox);
        classicLater.add(realSearch);
        check("lookup box: the classic search rule still wins",
                Boolean.valueOf(Eyes.pickSearchTarget(classicLater)
                        == realSearch),
                Boolean.TRUE);
        ElementInfo phSearchEl = el(6, "input", "");
        phSearchEl.type = "text";
        phSearchEl.placeholder = "Search the site";
        java.util.List<ElementInfo> classicPh =
                new java.util.ArrayList<ElementInfo>();
        classicPh.add(nwsBox);
        classicPh.add(phSearchEl);
        check("lookup box: a classic placeholder match wins too",
                Boolean.valueOf(Eyes.pickSearchTarget(classicPh) == phSearchEl),
                Boolean.TRUE);
        ElementInfo nwsBox2 = el(4, "input", "otherfield");
        nwsBox2.type = "text";
        nwsBox2.formText = "Enter your location";
        java.util.List<ElementInfo> ambiguous =
                new java.util.ArrayList<ElementInfo>();
        ambiguous.add(nwsBox);
        ambiguous.add(nwsBox2);
        check("lookup box: two candidates = ambiguity refused, no guess",
                Eyes.pickSearchTarget(ambiguous), null);
        ElementInfo plainBox = el(1, "input", "email");
        plainBox.type = "text";
        plainBox.formText = "Sign up for our newsletter";
        java.util.List<ElementInfo> plainOnly =
                new java.util.ArrayList<ElementInfo>();
        plainOnly.add(plainBox);
        check("lookup box: an unrelated form text does not qualify",
                Eyes.pickSearchTarget(plainOnly), null);
        ElementInfo zipCheck = el(2, "input", "agree");
        zipCheck.type = "checkbox";
        zipCheck.formText = "Enter your ZIP code";
        java.util.List<ElementInfo> checkOnly =
                new java.util.ArrayList<ElementInfo>();
        checkOnly.add(zipCheck);
        check("lookup box: a checkbox in a lookup form never qualifies",
                Eyes.pickSearchTarget(checkOnly), null);
        ElementInfo zipHidden = el(2, "input", "token");
        zipHidden.type = "hidden";
        zipHidden.formText = "Enter your ZIP code";
        java.util.List<ElementInfo> hiddenOnly =
                new java.util.ArrayList<ElementInfo>();
        hiddenOnly.add(zipHidden);
        check("lookup box: a hidden input never qualifies",
                Eyes.pickSearchTarget(hiddenOnly), null);
        ElementInfo locBox = el(7, "input", "place");
        locBox.type = "text";
        locBox.formText = "Find a location near you";
        java.util.List<ElementInfo> locOnly =
                new java.util.ArrayList<ElementInfo>();
        locOnly.add(locBox);
        check("lookup box: a 'location' form qualifies its input",
                Boolean.valueOf(Eyes.pickSearchTarget(locOnly) == locBox),
                Boolean.TRUE);
        check("lookup words: form text detection",
                Boolean.valueOf(Eyes.formSuggestsLookup("Enter ZIP code")
                        && Eyes.formSuggestsLookup("your postal address")
                        && Eyes.formSuggestsLookup("Choose a location")
                        && Eyes.formSuggestsLookup("Search")
                        && !Eyes.formSuggestsLookup("Contact us")
                        && !Eyes.formSuggestsLookup("")
                        && !Eyes.formSuggestsLookup(null)),
                Boolean.TRUE);
        check("text-like inputs: free-text types only",
                Boolean.valueOf(Eyes.isTextLikeInput(nwsBox)
                        && Eyes.isTextLikeInput(realSearch)
                        && !Eyes.isTextLikeInput(zipCheck)
                        && !Eyes.isTextLikeInput(zipHidden)
                        && !Eyes.isTextLikeInput(null)),
                Boolean.TRUE);
        ElementInfo taSearch = el(8, "textarea", "searchbox");
        check("classic rule: a textarea named for search still qualifies",
                Boolean.valueOf(Eyes.isSearchInput(taSearch)),
                Boolean.TRUE);
        check("map JS carries the enclosing form's visible text",
                Boolean.valueOf(Eyes.MAP_JS.contains("formText:ftxt(el.form)")
                        && Eyes.MAP_JS.contains("createTreeWalker")),
                Boolean.TRUE);

        // -- v2.2: explicit-label tier (Eyes.pickSearchTarget) ------------
        // The REAL weather.gov shape, modeled from the homepage HTML
        // the parent fetched and inspected: three text inputs.
        // (1) name="query", the usa.gov site search — its form's
        // ambient text says "Search For", so the v2.1 ambient rule
        // sees it, but it carries no explicit label in this map.
        // (2) id/name="inputstring" in form getForecast, with the
        // explicit <label for="inputstring">Local forecast by "City,
        // St" or ZIP code</label>. (3) the MyForecast widget's
        // location input, ambient location form text only. v2.1's
        // ambient rule refused this page (every input qualified);
        // the label tier must pick (2).

        ElementInfo usaQuery = el(1, "input", "query");
        usaQuery.type = "text";
        usaQuery.formText = "Search For";
        ElementInfo nwsInput = el(2, "input", "inputstring");
        nwsInput.type = "text";
        nwsInput.labelText = "Local forecast by \"City, St\" or ZIP code";
        nwsInput.formText = "Local forecast by \"City, St\" or ZIP code Go";
        ElementInfo myfcst = el(3, "input", "myfcst-location-input");
        myfcst.type = "text";
        myfcst.formText = "Customize Your Weather.gov Enter Your City, "
                + "ST or ZIP Code";
        java.util.List<ElementInfo> nwsReal =
                new java.util.ArrayList<ElementInfo>();
        nwsReal.add(usaQuery);
        nwsReal.add(nwsInput);
        nwsReal.add(myfcst);
        check("label tier: the real weather.gov shape picks the "
                        + "labelled forecast input",
                Boolean.valueOf(Eyes.pickSearchTarget(nwsReal) == nwsInput),
                Boolean.TRUE);
        ElementInfo nwsUnlabelled = el(2, "input", "inputstring");
        nwsUnlabelled.type = "text";
        nwsUnlabelled.formText = nwsInput.formText;
        java.util.List<ElementInfo> nwsNoLabel =
                new java.util.ArrayList<ElementInfo>();
        nwsNoLabel.add(usaQuery);
        nwsNoLabel.add(nwsUnlabelled);
        nwsNoLabel.add(myfcst);
        check("label tier: without the label the ambient rule refuses "
                        + "the same page",
                Eyes.pickSearchTarget(nwsNoLabel), null);
        ElementInfo labA = el(1, "input", "a");
        labA.type = "text";
        labA.labelText = "Enter your ZIP code";
        labA.formText = "Enter your ZIP code";
        ElementInfo labB = el(2, "input", "b");
        labB.type = "text";
        labB.labelText = "Choose a location";
        labB.formText = "Choose a location";
        java.util.List<ElementInfo> twoLabelled =
                new java.util.ArrayList<ElementInfo>();
        twoLabelled.add(labA);
        twoLabelled.add(labB);
        check("label tier: two labelled inputs fall through to refusal",
                Eyes.pickSearchTarget(twoLabelled), null);
        ElementInfo classicSearch = el(9, "input", "");
        classicSearch.type = "search";
        java.util.List<ElementInfo> classicVsLabel =
                new java.util.ArrayList<ElementInfo>();
        classicVsLabel.add(nwsInput);
        classicVsLabel.add(classicSearch);
        check("label tier: the classic rule still wins overall",
                Boolean.valueOf(Eyes.pickSearchTarget(classicVsLabel)
                        == classicSearch),
                Boolean.TRUE);
        ElementInfo ambOnly = el(2, "input", "other");
        ambOnly.type = "text";
        ambOnly.formText = "Enter your ZIP code";
        java.util.List<ElementInfo> labelVsAmbient =
                new java.util.ArrayList<ElementInfo>();
        labelVsAmbient.add(ambOnly);
        labelVsAmbient.add(labA);
        check("label tier: one labelled input beats an ambient-only one",
                Boolean.valueOf(Eyes.pickSearchTarget(labelVsAmbient)
                        == labA),
                Boolean.TRUE);
        check("label words: explicit label detection",
                Boolean.valueOf(Eyes.labelSuggestsLookup(
                                "Local forecast by \"City, St\" or ZIP code")
                        && Eyes.labelSuggestsLookup(
                                "Enter Your City, ST or ZIP Code")
                        && Eyes.labelSuggestsLookup("Search the site")
                        && Eyes.labelSuggestsLookup("Your postal code")
                        && !Eyes.labelSuggestsLookup("Email address")
                        && !Eyes.labelSuggestsLookup("")
                        && !Eyes.labelSuggestsLookup(null)),
                Boolean.TRUE);
        check("label tier: a labelled lookup box is recognised",
                Boolean.valueOf(Eyes.isLabelledLookupBox(nwsInput)
                        && !Eyes.isLabelledLookupBox(myfcst)
                        && !Eyes.isLabelledLookupBox(usaQuery)
                        && !Eyes.isLabelledLookupBox(null)),
                Boolean.TRUE);
        check("map JS carries the explicit label text",
                Boolean.valueOf(Eyes.MAP_JS.contains("labelText:labx(el)")
                        && Eyes.MAP_JS.contains("aria-labelledby")),
                Boolean.TRUE);
        check("heal scan JS carries the explicit label text too",
                Boolean.valueOf(Eyes.identityScanJs()
                        .contains("labelText:labx(e)")),
                Boolean.TRUE);
        java.util.List<ElementInfo> scanned = Eyes.parseIdentityScan(
                "{\"els\":[{\"tag\":\"input\",\"type\":\"text\","
                        + "\"name\":\"inputstring\",\"labelText\":"
                        + "\"Local forecast by ZIP\"}]}");
        check("identity scan parse carries labelText",
                scanned.size() == 1 ? scanned.get(0).labelText : "<none>",
                "Local forecast by ZIP");

        // -- v2.2: navigation-race predicate + retry cap (Agent) ----------
        // Field shape (both weather runs): step 1's search navigated
        // weather.gov -> DuckDuckGo during step 2's ~50 s decide;
        // step 2's tap on the departed page's SEARCH died stale.

        String staleTap = "error: element 1 not on page";
        String nwsHome = "https://www.weather.gov/";
        String raceDdgUrl = "https://html.duckduckgo.com/html/?q=Oakdale";
        check("race: a stale error across a navigation is a race",
                Boolean.valueOf(Agent.staleNavigationRace(
                        nwsHome, raceDdgUrl, staleTap)),
                Boolean.TRUE);
        check("race: a stale error on the same page is not a race",
                Boolean.valueOf(Agent.staleNavigationRace(
                        nwsHome, nwsHome, staleTap)),
                Boolean.FALSE);
        check("race: a different error across pages is not a race",
                Boolean.valueOf(Agent.staleNavigationRace(nwsHome, raceDdgUrl,
                        "error: no option \"x\" in select #2")),
                Boolean.FALSE);
        check("race: a success across pages is not a race",
                Boolean.valueOf(Agent.staleNavigationRace(
                        nwsHome, raceDdgUrl, "ok: tapped #1")),
                Boolean.FALSE);
        check("race: nulls are safe and not a race",
                Boolean.valueOf(!Agent.staleNavigationRace(null, null,
                                staleTap)
                        && !Agent.staleNavigationRace(nwsHome, raceDdgUrl,
                                null)),
                Boolean.TRUE);
        check("race retry: the first race in a step retries",
                Boolean.valueOf(Agent.shouldRaceRetry(
                        nwsHome, raceDdgUrl, staleTap, 0)),
                Boolean.TRUE);
        check("race retry: the cap is one per step",
                Boolean.valueOf(Agent.RACE_RETRY_CAP == 1
                        && !Agent.shouldRaceRetry(nwsHome, raceDdgUrl,
                                staleTap, 1)
                        && !Agent.shouldRaceRetry(nwsHome, raceDdgUrl,
                                staleTap, 2)),
                Boolean.TRUE);
        check("race retry: no race, no retry",
                Boolean.valueOf(Agent.shouldRaceRetry(
                        nwsHome, nwsHome, staleTap, 0)),
                Boolean.FALSE);

        // -- v2.7: stale-stash refresh retry (Agent) -------------------
        // Field case (trail-20261025-003150): the signature was
        // IDENTICAL at every step and the URL never moved, yet both
        // taps died "element N not on page" — not a race (the race
        // path owns URL changes), a stale execution-time view. The
        // predicate fires only on that exact shape.
        String fieldSig = "d2f2d2f4:1508";
        String fieldUrl = "https://html.duckduckgo.com/html/"
                + "?q=forecast+for+Oakdale+TN+chance+of+rain";
        String staleTap8 = "error: element 8 not on page";
        check("stale stash: the field case fires (same sig, same URL)",
                Boolean.valueOf(Agent.staleStashSuspect(
                        fieldSig, fieldSig, fieldUrl, fieldUrl,
                        staleTap8)),
                Boolean.TRUE);
        check("stale stash: the v2.2 race case does NOT fire "
                        + "(URL moved; the race path owns it)",
                Boolean.valueOf(Agent.staleStashSuspect(
                        fieldSig, fieldSig, nwsHome, raceDdgUrl,
                        staleTap)),
                Boolean.FALSE);
        check("stale stash: a changed signature does not fire "
                        + "(the page really changed)",
                Boolean.valueOf(Agent.staleStashSuspect(
                        fieldSig, "9abc1234:1508", fieldUrl, fieldUrl,
                        staleTap8)),
                Boolean.FALSE);
        check("stale stash: a non-stale result does not fire",
                Boolean.valueOf(Agent.staleStashSuspect(
                        fieldSig, fieldSig, fieldUrl, fieldUrl,
                        "ok: tapped #8")),
                Boolean.FALSE);
        check("stale stash: nulls are safe and do not fire",
                Boolean.valueOf(!Agent.staleStashSuspect(
                                null, fieldSig, fieldUrl, fieldUrl,
                                staleTap8)
                        && !Agent.staleStashSuspect(
                                fieldSig, fieldSig, fieldUrl, fieldUrl,
                                null)),
                Boolean.TRUE);
        check("stale retry: the first stale failure in a step retries",
                Boolean.valueOf(Agent.shouldStaleRetry(
                        fieldSig, fieldSig, fieldUrl, fieldUrl,
                        staleTap8, 0)),
                Boolean.TRUE);
        check("stale retry: the cap is one per step",
                Boolean.valueOf(Agent.STALE_RETRY_CAP == 1
                        && !Agent.shouldStaleRetry(
                                fieldSig, fieldSig, fieldUrl, fieldUrl,
                                staleTap8, 1)
                        && !Agent.shouldStaleRetry(
                                fieldSig, fieldSig, fieldUrl, fieldUrl,
                                staleTap8, 2)),
                Boolean.TRUE);
        check("stale retry: the race case never takes the stale path",
                Boolean.valueOf(Agent.shouldStaleRetry(
                        fieldSig, fieldSig, nwsHome, raceDdgUrl,
                        staleTap, 0)),
                Boolean.FALSE);

        // -- v2.2: redundant auto-read suppression (Agent) ----------------
        // The section is skipped once the page it came from has been
        // read to the end this run; the completion banner and the
        // arrival guard are computed elsewhere and unaffected.

        java.util.Map<String, Integer> totals =
                new java.util.LinkedHashMap<String, Integer>();
        totals.put(articleUrl, Integer.valueOf(11604));
        check("auto-read section shows on its page before completion",
                Agent.autoReadSectionFor(section, articleUrl, articleUrl,
                        new java.util.LinkedHashMap<String, Integer>()),
                section);
        check("auto-read section is suppressed once fully read",
                Agent.autoReadSectionFor(section, articleUrl, articleUrl,
                        totals),
                null);
        check("auto-read section stays hidden on other pages",
                Agent.autoReadSectionFor(section, articleUrl,
                        "https://en.wikipedia.org/wiki/Norris_Dam", totals),
                null);
        check("auto-read section: no section, nothing to show",
                Agent.autoReadSectionFor(null, articleUrl, articleUrl,
                        totals),
                null);

        // -- v2.2: density-aware token estimate (Agent) -------------------
        // The v2.1 field overflow: chars/4 said the weather.com
        // prompt fit; the URL-dense text tokenized far worse and the
        // real 2048 context overflowed. Lines carrying "http" now
        // count at chars/3; every other line costs exactly what it
        // cost before.

        String prose = "The Clinch River rises in Virginia and flows "
                + "337 miles.\nNorris Dam and Melton Hill Dam both "
                + "stand on it.\nThe tailwaters below Norris Dam are "
                + "stocked with trout.";
        check("density estimate: prose estimates exactly as before",
                Integer.valueOf(Agent.estTokens(prose)),
                Integer.valueOf((prose.length() + 3) / 4));
        String urlLine = "href=https://example.com/"
                + "abcdefghijklmnopqrstuvwxyz012345678";
        check("density estimate: the URL fixture line is 60 chars",
                Integer.valueOf(urlLine.length()),
                Integer.valueOf(60));
        check("density estimate: a URL line counts at chars/3",
                Integer.valueOf(Agent.estTokens(urlLine)),
                Integer.valueOf(20));
        String mixedUrlText = rep('w', 100) + "\n" + urlLine;
        check("density estimate: only the URL line is surcharged",
                Integer.valueOf(Agent.estTokens(mixedUrlText)),
                Integer.valueOf(46));
        Eyes.Perception prosePage = new Eyes.Perception();
        prosePage.url = "about:blank";
        prosePage.title = "Clinch River";
        prosePage.textPreview = "The Clinch River rises in Virginia.";
        java.util.List<String> proseHist =
                new java.util.ArrayList<String>();
        proseHist.add("step 1: {\"action\":\"read\"} -> ok: read chars "
                + "0-100 of 11604; read again for more:\nThe Clinch "
                + "River is dammed by Norris Dam.");
        String prosePrompt = Agent.buildPrompt(wikiGoal, prosePage,
                "[1] <a> \"Clinch River\"\n", proseHist);
        check("density estimate: a Clinch-shaped prompt (prose lines "
                        + "only) estimates the same as before",
                Integer.valueOf(Agent.estTokens(prosePrompt)),
                Integer.valueOf((prosePrompt.length() + 3) / 4));

        // The budget consequence: a URL-dense prompt the OLD
        // estimator waved through (the field shape) is now seen as
        // over budget and trimmed to fit.
        String wxGoal = "Find the forecast for Oakdale, "
                + "Tennessee, and tell me tonight's low temperature "
                + "and the chance of rain.";
        Eyes.Perception wxPage = new Eyes.Perception();
        wxPage.url = "https://weather.com/weather/tenday/l/Oakdale,TN";
        wxPage.title = "10-day weather forecast for Oakdale, Tennessee, "
                + "United States - The Weather Channel | weather.com";
        wxPage.textPreview = "";
        StringBuilder wxMap = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            wxMap.append("[").append(i)
                    .append("] <a> \"x\" href=https://weather.com/weather/"
                            + "tenday/l/Oakdale,TN?par=google\n");
        }
        java.util.List<String> noHist = new java.util.ArrayList<String>();
        java.util.List<String> noHistUrls =
                new java.util.ArrayList<String>();
        String wxRaw = Agent.buildPrompt(wxGoal, wxPage,
                wxMap.toString(), noHist, null, 0);
        check("density budget: the old estimate waved the prompt through",
                Boolean.valueOf((wxRaw.length() + 3) / 4
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("density budget: the density-aware estimate sees it over",
                Boolean.valueOf(Agent.estTokens(wxRaw)
                        > Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        String wxTrimmed = Agent.buildPromptWithinBudget(wxGoal,
                wxPage, wxMap.toString(), noHist, noHistUrls, null, null,
                0);
        check("density budget: the trimmed prompt fits the budget",
                Boolean.valueOf(Agent.estTokens(wxTrimmed)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("density budget: the map tail was cut to make it fit",
                Boolean.valueOf(wxTrimmed.contains(
                        "(element map truncated")),
                Boolean.TRUE);

        // -- v2.3: thin-page read retry decisions (Eyes) ------------------

        check("thin read: the threshold is 3000 chars",
                Integer.valueOf(Eyes.THIN_READ_THRESHOLD),
                Integer.valueOf(3000));
        check("thin read: totals below the threshold are thin",
                Boolean.valueOf(Eyes.isThinRead(2390)
                        && Eyes.isThinRead(2999)
                        && Eyes.isThinRead(0)
                        && !Eyes.isThinRead(3000)
                        && !Eyes.isThinRead(11604)),
                Boolean.TRUE);
        java.util.Set<String> thinRetried =
                new java.util.LinkedHashSet<String>();
        check("thin read: a fresh thin URL spends its retry",
                Boolean.valueOf(Eyes.shouldThinRetry(
                        "https://weather.com/x", thinRetried, 2390)),
                Boolean.TRUE);
        thinRetried.add("https://weather.com/x");
        check("thin read: a retried URL does not retry again",
                Boolean.valueOf(Eyes.shouldThinRetry(
                        "https://weather.com/x", thinRetried, 2390)),
                Boolean.FALSE);
        check("thin read: a thick page never retries",
                Boolean.valueOf(Eyes.shouldThinRetry(
                        "https://en.wikipedia.org/wiki/Clinch_River",
                        thinRetried, 11604)),
                Boolean.FALSE);
        check("thin read: null url never retries, null set is fresh",
                Boolean.valueOf(!Eyes.shouldThinRetry(null, thinRetried, 100)
                        && Eyes.shouldThinRetry("https://a.b/", null, 100)),
                Boolean.TRUE);
        Eyes.ReadChunk thinA = new Eyes.ReadChunk();
        thinA.total = 812;
        Eyes.ReadChunk thinB = new Eyes.ReadChunk();
        thinB.total = 2390;
        check("thin read: the larger extraction wins",
                Boolean.valueOf(Eyes.pickBestExtraction(thinA, thinB) == thinB
                        && Eyes.pickBestExtraction(thinB, thinA) == thinB),
                Boolean.TRUE);
        Eyes.ReadChunk thinC = new Eyes.ReadChunk();
        thinC.total = 2390;
        check("thin read: a tie keeps the later (more settled) extraction",
                Boolean.valueOf(Eyes.pickBestExtraction(thinB, thinC) == thinC
                        && Eyes.pickBestExtraction(thinA, null) == thinA
                        && Eyes.pickBestExtraction(null, thinA) == thinA),
                Boolean.TRUE);
        check("thin read: the note reports growth honestly",
                Eyes.thinRetryNote(812, 2390),
                "(page rendered thin at first: 812 chars, 2390 after "
                        + "settle+scroll)");
        check("thin read: the note admits a page that stayed thin",
                Eyes.thinRetryNote(2390, 2390),
                "(page still thin after retry: 2390 chars)");

        // -- v2.3: labels in the map + search result naming ---------------

        ElementInfo labelledInput = el(4, "input", "inputstring");
        labelledInput.type = "text";
        labelledInput.labelText =
                "Local forecast by \"City, St\" or ZIP code";
        check("map label: an input shows its associated label",
                Eyes.mapLabelSuffix(labelledInput),
                " [label: Local forecast by \"City, St\" or ZIP code]");
        ElementInfo unlabelledInput = el(5, "input", "q");
        unlabelledInput.type = "text";
        check("map label: an input without a label shows nothing",
                Eyes.mapLabelSuffix(unlabelledInput), "");
        ElementInfo labelledLink = el(6, "a", "Forecast");
        labelledLink.href = "https://www.weather.gov/";
        labelledLink.labelText = "Local forecast";
        check("map label: non-inputs never get the bracketed label",
                Eyes.mapLabelSuffix(labelledLink), "");
        check("map label: null element is safe",
                Eyes.mapLabelSuffix(null), "");
        ElementInfo bothLabels = el(7, "input", "inputstring");
        bothLabels.type = "text";
        bothLabels.label = "Local forecast by \"City, St\" or ZIP code";
        bothLabels.labelText = "Local forecast by \"City, St\" or ZIP code";
        Eyes.Perception labelPage = new Eyes.Perception();
        labelPage.url = "https://www.weather.gov/";
        labelPage.title = "National Weather Service";
        labelPage.elements.add(bothLabels);
        String labelRender = labelPage.render();
        check("map label: render carries the bracketed label once",
                Boolean.valueOf(labelRender.contains(
                                "[label: Local forecast by")
                        && !labelRender.contains(" label=Local")),
                Boolean.TRUE);
        ElementInfo plainLabelledLink = el(8, "a", "Home");
        plainLabelledLink.href = "https://example.com/";
        plainLabelledLink.label = "Homepage";
        Eyes.Perception linkPage = new Eyes.Perception();
        linkPage.elements.add(plainLabelledLink);
        check("map label: a link keeps the compact label= form",
                Boolean.valueOf(linkPage.render().contains(" label=Homepage")),
                Boolean.TRUE);
        check("search naming: the labelled tier classifies and names",
                Boolean.valueOf(Eyes.searchTargetTier(labelledInput)
                                == Eyes.SEARCH_TIER_LABELLED
                        && labelledInput.labelText.equals(
                                Eyes.searchBoxText(labelledInput))),
                Boolean.TRUE);
        ElementInfo ambientInput = el(9, "input", "other");
        ambientInput.type = "text";
        ambientInput.formText = "Enter your ZIP code";
        check("search naming: the ambient tier names the form text",
                Boolean.valueOf(Eyes.searchTargetTier(ambientInput)
                                == Eyes.SEARCH_TIER_AMBIENT
                        && "Enter your ZIP code".equals(
                                Eyes.searchBoxText(ambientInput))),
                Boolean.TRUE);
        ElementInfo classicInput = el(10, "input", "");
        classicInput.type = "search";
        check("search naming: the classic tier names nothing",
                Boolean.valueOf(Eyes.searchTargetTier(classicInput)
                                == Eyes.SEARCH_TIER_CLASSIC
                        && Eyes.searchBoxText(classicInput) == null),
                Boolean.TRUE);
        ElementInfo plainInput = el(11, "input", "email");
        plainInput.type = "text";
        check("search naming: an unsignalled input has no tier",
                Boolean.valueOf(Eyes.searchTargetTier(plainInput)
                                == Eyes.SEARCH_TIER_NONE
                        && Eyes.searchBoxText(plainInput) == null
                        && Eyes.searchTargetTier(null)
                                == Eyes.SEARCH_TIER_NONE),
                Boolean.TRUE);
        check("search naming: the via-box result string",
                Eyes.formatSearchedViaBox(
                        "Local forecast by \"City, St\" or ZIP code",
                        "Oakdale, Tennessee forecast"),
                "ok: searched via the page's box (\"Local forecast by "
                        + "\"City, St\" or ZIP code\") for \"Oakdale, "
                        + "Tennessee forecast\"");
        check("search naming: the picked weather.gov box names itself",
                Eyes.formatSearchedViaBox(
                        Eyes.searchBoxText(Eyes.pickSearchTarget(nwsReal)),
                        "Oakdale, Tennessee forecast"),
                "ok: searched via the page's box (\"Local forecast by "
                        + "\"City, St\" or ZIP code\") for \"Oakdale, "
                        + "Tennessee forecast\"");

        // -- v2.3: element-map dedupe (Eyes.shapeMap) ---------------------

        ElementInfo dupLinkA = rawEl(0, "a", "", "Home");
        dupLinkA.href = "https://www.weather.gov/";
        ElementInfo dupLinkB = rawEl(1, "a", "", "Home");
        dupLinkB.href = "https://www.weather.gov/";
        ElementInfo diffHref = rawEl(2, "a", "", "Home");
        diffHref.href = "https://www.weather.gov/other";
        check("dedupe: identical links are duplicates",
                Boolean.valueOf(Eyes.isDuplicateElement(dupLinkA, dupLinkB)
                        && !Eyes.isDuplicateElement(dupLinkA, diffHref)),
                Boolean.TRUE);
        ElementInfo goBtnA = rawEl(0, "button", "", "Go");
        ElementInfo goBtnB = rawEl(1, "button", "", "Go");
        ElementInfo stopBtn = rawEl(2, "button", "", "Stop");
        check("dedupe: identical buttons are duplicates, named ones not",
                Boolean.valueOf(Eyes.isDuplicateElement(goBtnA, goBtnB)
                        && !Eyes.isDuplicateElement(goBtnA, stopBtn)
                        && !Eyes.isDuplicateElement(goBtnA, dupLinkA)
                        && !Eyes.isDuplicateElement(goBtnA, null)),
                Boolean.TRUE);
        ElementInfo sameShapeA = rawEl(0, "input", "text", "q");
        sameShapeA.labelText = "Search the site";
        ElementInfo sameShapeB = rawEl(1, "input", "text", "q");
        sameShapeB.labelText = "Search the site";
        ElementInfo diffLabel = rawEl(2, "input", "text", "q");
        diffLabel.labelText = "Enter your ZIP code";
        ElementInfo diffType = rawEl(3, "input", "search", "q");
        diffType.labelText = "Search the site";
        check("dedupe: inputs need name, tag, type AND label to match",
                Boolean.valueOf(Eyes.isDuplicateElement(sameShapeA, sameShapeB)
                        && !Eyes.isDuplicateElement(sameShapeA, diffLabel)
                        && !Eyes.isDuplicateElement(sameShapeA, diffType)),
                Boolean.TRUE);

        // Header nav repeated in the footer: the repeats collapse,
        // the first occurrence (document order) survives, numbering
        // stays tight.
        java.util.List<ElementInfo> chrome =
                new java.util.ArrayList<ElementInfo>();
        ElementInfo navHome = rawEl(0, "a", "", "Home");
        navHome.href = "https://www.weather.gov/";
        chrome.add(navHome);
        ElementInfo navFcst = rawEl(1, "a", "", "Forecast");
        navFcst.href = "https://www.weather.gov/forecast";
        chrome.add(navFcst);
        ElementInfo chromeArticle = rawEl(2, "a", "", "Oakdale forecast");
        chromeArticle.href = "https://forecast.weather.gov/MapClick.php?x=1";
        chrome.add(chromeArticle);
        ElementInfo footHome = rawEl(3, "a", "", "Home");
        footHome.href = "https://www.weather.gov/";
        chrome.add(footHome);
        ElementInfo footFcst = rawEl(4, "a", "", "Forecast");
        footFcst.href = "https://www.weather.gov/forecast";
        chrome.add(footFcst);
        java.util.List<ElementInfo> chromeShaped = Eyes.shapeMap(chrome);
        check("dedupe: repeated chrome collapses to first occurrences",
                Boolean.valueOf(chromeShaped.size() == 3
                        && chromeShaped.get(0) == navHome
                        && chromeShaped.get(1) == navFcst
                        && chromeShaped.get(2) == chromeArticle
                        && chromeShaped.get(0).number == 1
                        && chromeShaped.get(2).number == 3),
                Boolean.TRUE);

        // Same words, different destinations: both survive.
        java.util.List<ElementInfo> readMores =
                new java.util.ArrayList<ElementInfo>();
        ElementInfo rm1 = rawEl(0, "a", "", "Read more");
        rm1.href = "https://example.com/a";
        readMores.add(rm1);
        ElementInfo rm2 = rawEl(1, "a", "", "Read more");
        rm2.href = "https://example.com/b";
        readMores.add(rm2);
        check("dedupe: same-name links with different hrefs survive",
                Integer.valueOf(Eyes.shapeMap(readMores).size()),
                Integer.valueOf(2));

        // Same-shaped inputs with different labels: both survive.
        java.util.List<ElementInfo> twoBoxes =
                new java.util.ArrayList<ElementInfo>();
        twoBoxes.add(sameShapeA);
        twoBoxes.add(diffLabel);
        check("dedupe: same-shaped inputs with different labels survive",
                Integer.valueOf(Eyes.shapeMap(twoBoxes).size()),
                Integer.valueOf(2));

        // -- v2.4 item 1+5: few-shot examples + the contract trim ---------
        // The examples ride at the END of the contract block, so the
        // built prompt STARTS with the whole block (head + rules +
        // examples) before any per-step section.

        String exGoal = "Search Wikipedia for the Clinch River, open "
                + "the article, and tell me how long it is";
        Eyes.Perception exPage = new Eyes.Perception();
        exPage.url = "https://en.wikipedia.org/wiki/Special:Search";
        exPage.title = "Clinch River - Search results";
        String exPrompt = Agent.buildPrompt(exGoal, exPage,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>());
        check("examples: the built prompt starts with the contract block",
                Boolean.valueOf(exPrompt.startsWith(Agent.CONTRACT_BLOCK)),
                Boolean.TRUE);
        check("examples: the contract block ends with the examples",
                Boolean.valueOf(Agent.CONTRACT_BLOCK.endsWith(
                        Agent.EXAMPLES_TEXT)),
                Boolean.TRUE);
        check("examples: they sit after the action contract, before "
                        + "any per-step section",
                Boolean.valueOf(Agent.CONTRACT_BLOCK.indexOf(
                                "\"action\":\"stuck\"")
                        < Agent.CONTRACT_BLOCK.indexOf("Examples:")
                        && exPrompt.indexOf("Examples:")
                        < exPrompt.indexOf("Page: ")),
                Boolean.TRUE);
        check("examples: the results-discipline example is present",
                Boolean.valueOf(Agent.EXAMPLES_TEXT.contains(
                        "\"action\":\"tap\",\"el\":4")
                        && Agent.EXAMPLES_TEXT.contains("Next page")),
                Boolean.TRUE);
        check("examples: the answer-from-text example carries the "
                        + "value in its summary",
                Boolean.valueOf(Agent.EXAMPLES_TEXT.contains(
                        "\"action\":\"done\",\"summary\":\"337 "
                                + "miles long.\"")),
                Boolean.TRUE);
        check("examples: both together stay within the 220-token "
                        + "budget (" + Agent.estTokens(Agent.EXAMPLES_TEXT)
                        + " est)",
                Boolean.valueOf(Agent.estTokens(Agent.EXAMPLES_TEXT)
                        <= 220),
                Boolean.TRUE);
        // Item 5's budget: v2.3's static prefix measured 292 est
        // tokens (1168 chars); the trim had to pay for the examples,
        // so the whole block may exceed it by at most 40.
        check("trim: the static prefix stays within v2.3 + 40 est "
                        + "tokens (" + Agent.estTokens(Agent.CONTRACT_BLOCK)
                        + " <= 292 + 40)",
                Boolean.valueOf(Agent.estTokens(Agent.CONTRACT_BLOCK)
                        <= 292 + 40),
                Boolean.TRUE);
        check("trim: the standing text itself shrank vs v2.3 (292)",
                Boolean.valueOf(Agent.estTokens(Agent.CONTRACT_TEXT)
                        < 292),
                Boolean.TRUE);
        String[] allActions = { "tap", "type", "search", "read",
                "goto", "scroll", "back", "wait", "done", "stuck" };
        boolean allPresent = true;
        for (int i = 0; i < allActions.length; i++) {
            if (!Agent.CONTRACT_BLOCK.contains(
                    "\"action\":\"" + allActions[i] + "\"")) {
                allPresent = false;
            }
        }
        check("trim: every action's syntax line survived",
                Boolean.valueOf(allPresent), Boolean.TRUE);
        // A max-size prompt still fits via the trimmer, and the
        // never-trimmed contract (examples included) survives it.
        Eyes.Perception maxPage = new Eyes.Perception();
        maxPage.url = "https://en.wikipedia.org/wiki/Clinch_River";
        maxPage.title = "Clinch River - Wikipedia";
        maxPage.textPreview = rep('p', 240);
        StringBuilder maxMap = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            maxMap.append("[").append(i).append("] <a> \"link ")
                    .append(i).append("\" href=https://example.org/")
                    .append(i).append("\n");
        }
        java.util.List<String> maxHist =
                new java.util.ArrayList<String>();
        java.util.List<String> maxHistUrls =
                new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) {
            maxHist.add("step " + i + ": {\"action\":\"read\"} -> "
                    + rep('r', 500));
            maxHistUrls.add(null);
        }
        String maxAuto = "Page text (auto-read, chars 0-2400 of "
                + "11604):\n" + rep('t', 2400);
        String maxTrimmed = Agent.buildPromptWithinBudget(exGoal,
                maxPage, maxMap.toString(), maxHist, maxHistUrls,
                maxAuto, "https://en.wikipedia.org/wiki/Clinch_River",
                0);
        check("examples: a max-size prompt still fits the budget",
                Boolean.valueOf(Agent.estTokens(maxTrimmed)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("examples: the trimmer never cuts the examples",
                Boolean.valueOf(maxTrimmed.contains("Examples:")),
                Boolean.TRUE);

        // -- v2.4 item 2: goal-match highlighting in the map --------------

        Eyes.Perception hlPage = new Eyes.Perception();
        hlPage.url = "https://en.wikipedia.org/wiki/Special:Search";
        hlPage.title = "Clinch River - Search results";
        ElementInfo hlMatch = el(1, "a", "Clinch River");
        hlMatch.href = "https://en.wikipedia.org/wiki/Clinch_River";
        hlPage.elements.add(hlMatch);
        ElementInfo hlPlain = el(2, "a", "Next page");
        hlPlain.href = "https://en.wikipedia.org/w/index.php?search=Clinch";
        hlPage.elements.add(hlPlain);
        ElementInfo hlInput = el(3, "input", "Clinch River");
        hlInput.type = "text";
        hlPage.elements.add(hlInput);
        String hlGoal = "Search Wikipedia for the Clinch River and "
                + "open the article";
        String hlRendered = hlPage.render(hlGoal);
        check("highlight: a goal-matching link is marked",
                Boolean.valueOf(hlRendered.contains(
                        "\"Clinch River\" href="
                                + "https://en.wikipedia.org/wiki/"
                                + "Clinch_River"
                                + Eyes.GOAL_MATCH_MARKER)),
                Boolean.TRUE);
        check("highlight: a non-matching link stays unmarked",
                Boolean.valueOf(!hlRendered.contains(
                        "\"Next page\" href=https://en.wikipedia.org/w/"
                                .concat("index.php?search=Clinch")
                                + Eyes.GOAL_MATCH_MARKER)),
                Boolean.TRUE);
        check("highlight: inputs are never marked, even on a name "
                        + "match",
                Boolean.valueOf(!hlRendered.contains(
                        "<input> \"Clinch River\" type=text"
                                + Eyes.GOAL_MATCH_MARKER)
                        && !hlRendered.substring(
                                hlRendered.indexOf("<input>"))
                                .contains(Eyes.GOAL_MATCH_MARKER)),
                Boolean.TRUE);
        check("highlight: no goal renders byte-identical to v2.3",
                hlPage.render(null), hlPage.render());
        Eyes.Perception hlNone = new Eyes.Perception();
        hlNone.url = "https://example.org/";
        ElementInfo hlOff = el(1, "a", "About us");
        hlOff.href = "https://example.org/about";
        hlNone.elements.add(hlOff);
        check("highlight: zero matches render byte-identical to v2.3",
                hlNone.render(hlGoal), hlNone.render());

        // -- v2.4 item 3: the grammar flag's Java surface -----------------
        // (The constraint itself is native and device-verified only;
        // what the host can pin is that the flag the shim reports is
        // read honestly: set only on an explicit "1" in slot 7.)

        String[] fullResult = { "{}", "100", "50", "3", "9", "40",
                "60", "1" };
        check("grammar flag: an 8-slot result with \"1\" is "
                        + "constrained",
                Boolean.valueOf(ModelEngine.parseStats(fullResult)
                        .lastGrammarConstrained),
                Boolean.TRUE);
        String[] offResult = { "{}", "100", "50", "3", "9", "40",
                "60", "0" };
        check("grammar flag: an explicit \"0\" is not constrained",
                Boolean.valueOf(ModelEngine.parseStats(offResult)
                        .lastGrammarConstrained),
                Boolean.FALSE);
        String[] oldResult = { "{}", "100", "50", "3", "9", "40",
                "60" };
        check("grammar flag: a pre-v2.4 7-slot result reads as not "
                        + "constrained, stats intact",
                Boolean.valueOf(!ModelEngine.parseStats(oldResult)
                        .lastGrammarConstrained
                        && ModelEngine.parseStats(oldResult)
                                .lastPromptTokens == 100),
                Boolean.TRUE);
        check("grammar flag: a null result reads as not constrained",
                Boolean.valueOf(!ModelEngine.parseStats(null)
                        .lastGrammarConstrained),
                Boolean.TRUE);
        check("grammar flag: the 6-arg Stats defaults to not "
                        + "constrained",
                Boolean.valueOf(!new ModelEngine.Stats(1, 2, 3, 4, 5, 6)
                        .lastGrammarConstrained),
                Boolean.TRUE);

        // -- v2.4 item 4: the Q5_K_M model pin ----------------------------
        // ModelStore's constants are compile-time constants, so
        // these read them inlined — no Android class is loaded.

        check("model: the pinned file is the Q5_K_M GGUF",
                com.ghosthands.app.ModelStore.MODEL_NAME,
                "qwen2.5-1.5b-instruct-q5_k_m.gguf");
        check("model: the URL is the official Qwen repo file",
                com.ghosthands.app.ModelStore.MODEL_URL,
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF"
                        + "/resolve/main/"
                        + "qwen2.5-1.5b-instruct-q5_k_m.gguf");
        check("model: the pinned size is the measured Q5_K_M size",
                Long.valueOf(com.ghosthands.app.ModelStore.MODEL_SIZE),
                Long.valueOf(1285494304L));
        check("model: the pinned sha256 is the measured Q5_K_M hash",
                com.ghosthands.app.ModelStore.MODEL_SHA256,
                "b46661073c18e5b56a41fa320975f866a00def1ff08feef4718e013258896f8c");
        String pinHash = com.ghosthands.app.ModelStore.MODEL_SHA256;
        boolean pinHex = pinHash.length() == 64;
        for (int i = 0; i < pinHash.length(); i++) {
            char c = pinHash.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                pinHex = false;
            }
        }
        check("model: the pinned sha256 is 64 lowercase hex chars",
                Boolean.valueOf(pinHex), Boolean.TRUE);

        // -- v2.5 item 1: the dead-result detour decision (Agent) ---------
        // Field shape (trail-20261024-234232): the planner tapped a
        // forecast result out of the DuckDuckGo results; the
        // destination died on load (chrome-error); the one reload
        // failed; the run stopped with a page of alternatives one
        // Back away. The detour decision is the loop's new answer.

        String ddgResults = "https://html.duckduckgo.com/html/?q=oakdale";
        String deadDest = "https://forecast.example.org/oakdale";
        check("detour: fires for a dead results link with detours left",
                Boolean.valueOf(Agent.shouldDetour(
                        deadDest, ddgResults, 0, true)),
                Boolean.TRUE);
        check("detour: the cap is two per run",
                Integer.valueOf(Agent.DETOUR_CAP), Integer.valueOf(2));
        check("detour: the last detour under the cap still fires",
                Boolean.valueOf(Agent.shouldDetour(deadDest, ddgResults,
                        Agent.DETOUR_CAP - 1, true)),
                Boolean.TRUE);
        check("detour: cap spent -> the honest stop returns",
                Boolean.valueOf(Agent.shouldDetour(deadDest, ddgResults,
                        Agent.DETOUR_CAP, true)),
                Boolean.FALSE);
        check("detour: no results origin on record -> no detour",
                Boolean.valueOf(Agent.shouldDetour(
                        deadDest, null, 0, true)),
                Boolean.FALSE);
        check("detour: an empty results URL is no origin",
                Boolean.valueOf(Agent.shouldDetour(
                        deadDest, "", 0, true)),
                Boolean.FALSE);
        check("detour: not arrived via a results link -> no detour",
                Boolean.valueOf(Agent.shouldDetour(
                        deadDest, ddgResults, 0, false)),
                Boolean.FALSE);
        check("detour: the results page itself failing is not detoured",
                Boolean.valueOf(Agent.shouldDetour(
                        ddgResults, ddgResults, 0, true)),
                Boolean.FALSE);
        check("detour: a null failed URL (the error page names none) "
                        + "still detours",
                Boolean.valueOf(Agent.shouldDetour(
                        null, ddgResults, 0, true)),
                Boolean.TRUE);

        // -- v2.5 item 2: prompt layout — the cache-geometry measurement --
        // A fixed 3-step same-page read scenario (the Clinch River
        // shape): the step-2 and step-3 prompts differ ONLY in the
        // history (one more entry). The native prefix cache reuses
        // the longest common prefix of consecutive prompts, so the
        // layout that puts the stable perception ahead of the
        // diverging history must reuse strictly more. Both layouts
        // are assembled here — the new one by Agent.buildPrompt,
        // the v2.4 one by the frozen test-local copy above.

        Eyes.Perception lcpPage = new Eyes.Perception();
        lcpPage.url = "https://en.wikipedia.org/wiki/Clinch_River";
        lcpPage.title = "Clinch River - Wikipedia";
        lcpPage.textPreview = "The Clinch River is a river that flows "
                + "through southwestern Virginia and Tennessee.";
        StringBuilder lcpMap = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            lcpMap.append("[").append(i).append("] <a> \"Section ")
                    .append(i).append("\" href=https://en.wikipedia.org/"
                            + "wiki/Clinch_River#s").append(i).append("\n");
        }
        String lcpSection = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('r', 600), 11604));
        java.util.List<String> histStep2 =
                new java.util.ArrayList<String>();
        histStep2.add("step 1: {\"action\":\"search\",\"query\":"
                + "\"Clinch River\"} -> ok: searched Wikipedia for "
                + "\"Clinch River\"");
        java.util.List<String> histStep3 =
                new java.util.ArrayList<String>(histStep2);
        histStep3.add("step 2: {\"action\":\"read\"} -> ok: read chars "
                + "2400-4800 of 11604; read again for more");
        String oldStep2 = buildPromptV24Layout(wikiGoal, lcpPage,
                lcpMap.toString(), histStep2, lcpSection, 0);
        String oldStep3 = buildPromptV24Layout(wikiGoal, lcpPage,
                lcpMap.toString(), histStep3, lcpSection, 0);
        String newStep2 = Agent.buildPrompt(wikiGoal, lcpPage,
                lcpMap.toString(), histStep2, lcpSection, 0);
        String newStep3 = Agent.buildPrompt(wikiGoal, lcpPage,
                lcpMap.toString(), histStep3, lcpSection, 0);
        int oldLcpTokens = Agent.estTokens(lcp(oldStep2, oldStep3));
        int newLcpTokens = Agent.estTokens(lcp(newStep2, newStep3));
        System.out.println("LCP-MEASUREMENT same-page read steps 2->3: "
                + "v2.4 layout reuses " + oldLcpTokens
                + " est tokens, v2.5 layout reuses " + newLcpTokens
                + " est tokens");
        check("layout: the new layout's reusable prefix is strictly "
                        + "larger (" + oldLcpTokens + " -> "
                        + newLcpTokens + ")",
                Boolean.valueOf(newLcpTokens > oldLcpTokens),
                Boolean.TRUE);
        check("layout: same information, only reordered (line "
                        + "multisets equal)",
                Boolean.valueOf(sortedLines(oldStep3)
                        .equals(sortedLines(newStep3))),
                Boolean.TRUE);
        check("layout: the map now precedes the history",
                Boolean.valueOf(newStep3.indexOf("Elements:")
                        < newStep3.indexOf("Recent steps:")),
                Boolean.TRUE);
        check("layout: the cue still ends the prompt",
                Boolean.valueOf(newStep3.endsWith("Next action JSON:")),
                Boolean.TRUE);

        // -- v2.5 item 3: the wider clickable net (Eyes shaping) ----------
        // The JS half of the net (the selector, the cursor pass) is
        // not host-executable and is pinned by shape below; what the
        // host pins for real is the shaping layer the script feeds:
        // harvest candidates (ElementInfo.pointer) enter the map,
        // rank in the link tier, are capped in document order, and
        // dedupe by the non-link rule (name+tag+type).

        check("net: the harvest cap is twelve",
                Integer.valueOf(Eyes.MAX_POINTER_HARVEST),
                Integer.valueOf(12));
        check("net: MAP_JS widens the selector and carries the harvest",
                Boolean.valueOf(Eyes.MAP_JS.contains("[role='link']")
                        && Eyes.MAP_JS.contains("ptrHarvest")
                        && Eyes.MAP_JS.contains("cursor")),
                Boolean.TRUE);
        check("net: the heal scan enumerates the same net",
                Boolean.valueOf(Eyes.identityScanJs()
                                .contains("[role='link']")
                        && Eyes.identityScanJs().contains("ptrHarvest")),
                Boolean.TRUE);
        ElementInfo ptrDiv = rawEl(0, "div", "", "Book a room");
        ptrDiv.pointer = true;
        check("net: a clickable div ranks in the link tier",
                Integer.valueOf(Eyes.tierOf(ptrDiv)), Integer.valueOf(4));
        ElementInfo ptrSearchDiv = rawEl(1, "div", "", "Search results");
        ptrSearchDiv.pointer = true;
        check("net: even a search-named clickable div stays in the "
                        + "link tier",
                Integer.valueOf(Eyes.tierOf(ptrSearchDiv)),
                Integer.valueOf(4));
        ElementInfo ptrRoleBtn = rawEl(2, "div", "", "Open menu");
        ptrRoleBtn.pointer = true;
        ptrRoleBtn.role = "button";
        check("net: a harvest element stays in the link tier even "
                        + "with a button role",
                Integer.valueOf(Eyes.tierOf(ptrRoleBtn)),
                Integer.valueOf(4));
        java.util.List<ElementInfo> netRaw =
                new java.util.ArrayList<ElementInfo>();
        netRaw.add(rawEl(0, "input", "text", "City"));
        ElementInfo cardDiv = rawEl(1, "div", "", "Sunnyvale Inn");
        cardDiv.pointer = true;
        netRaw.add(cardDiv);
        java.util.List<ElementInfo> netShaped = Eyes.shapeMap(netRaw);
        check("net: the clickable div enters the shaped map, numbered "
                        + "after the controls",
                Boolean.valueOf(netShaped.size() == 2
                        && netShaped.get(1).pointer
                        && netShaped.get(1).number == 2),
                Boolean.TRUE);
        java.util.List<ElementInfo> manyPtr =
                new java.util.ArrayList<ElementInfo>();
        for (int i = 0; i < 15; i++) {
            ElementInfo card = rawEl(i, "div", "", "Card " + i);
            card.pointer = true;
            manyPtr.add(card);
        }
        java.util.List<ElementInfo> cappedPtr = Eyes.shapeMap(manyPtr);
        boolean firstTwelve = cappedPtr.size() == 12;
        for (int i = 0; i < cappedPtr.size(); i++) {
            if (!("Card " + i).equals(cappedPtr.get(i).name)) {
                firstTwelve = false;
            }
        }
        check("net: the harvest cap keeps the first twelve in "
                        + "document order",
                Boolean.valueOf(firstTwelve), Boolean.TRUE);
        java.util.List<ElementInfo> twinDivs =
                new java.util.ArrayList<ElementInfo>();
        ElementInfo twin1 = rawEl(0, "div", "", "Read more");
        twin1.pointer = true;
        ElementInfo twin2 = rawEl(1, "div", "", "Read more");
        twin2.pointer = true;
        ElementInfo otherDiv = rawEl(2, "div", "", "Book now");
        otherDiv.pointer = true;
        twinDivs.add(twin1);
        twinDivs.add(twin2);
        twinDivs.add(otherDiv);
        check("net: identical clickable divs dedupe by name+tag+type; "
                        + "a distinct one survives",
                Integer.valueOf(Eyes.shapeMap(twinDivs).size()),
                Integer.valueOf(2));
        // Visibility lives in the script (vis()): a candidate it
        // filters never reaches the raw list at all. Modeled end to
        // end through parseMap — this is the JSON MAP_JS returns
        // for a page whose one harvest candidate was visible.
        String ptrInner = "{\"url\":\"https://example.org/hotels\","
                + "\"title\":\"Hotels\",\"text\":\"\",\"els\":[{"
                + "\"tag\":\"div\",\"role\":\"\",\"name\":"
                + "\"Sunnyvale Inn\",\"type\":\"\",\"href\":\"\","
                + "\"label\":\"\",\"formAction\":\"\",\"formText\":"
                + "\"\",\"labelText\":\"\",\"placeholder\":\"\","
                + "\"domId\":\"\",\"domName\":\"\",\"ptr\":1}]}";
        Eyes.Perception ptrParsed = Eyes.parseMap(
                com.ghosthands.app.Json.quote(ptrInner));
        check("net: parseMap carries the pointer flag into the map",
                Boolean.valueOf(ptrParsed.elements.size() == 1
                        && ptrParsed.elements.get(0).pointer),
                Boolean.TRUE);
        check("net: a candidate without the flag is not harvest",
                Boolean.valueOf(!parsed.elements.get(0).pointer),
                Boolean.TRUE);

        // -- v2.5 item 4: the stop-line run summary (Agent + Trail) -------

        check("summary: the closing line, in the field shape",
                Agent.runSummaryLine("done", 10, 512, 1000, 610),
                "— run: done in 10 steps, 512 s, 61% of prompt reused");
        check("summary: an error stop names its own outcome",
                Agent.runSummaryLine("error", 3, 41, 2000, 500),
                "— run: error in 3 steps, 41 s, 25% of prompt reused");
        check("summary: zero prompt tokens read as 0%, never a crash",
                Agent.runSummaryLine("stopped", 1, 0, 0, 0),
                "— run: stopped in 1 steps, 0 s, 0% of prompt reused");
        check("summary: the percent is a whole number (integer "
                        + "division)",
                Integer.valueOf(Agent.reusePercent(3, 1)),
                Integer.valueOf(33));
        check("summary: the percent is zero-safe",
                Integer.valueOf(Agent.reusePercent(0, 0)),
                Integer.valueOf(0));
        boolean typesOk = true;
        for (int i = 0; i < 2; i++) {
            String want = (i == 0) ? "detour" : "run_summary";
            boolean found = false;
            for (int j = 0;
                    j < com.ghosthands.app.Trail.EVENT_TYPES.length; j++) {
                if (com.ghosthands.app.Trail.EVENT_TYPES[j]
                        .equals(want)) {
                    found = true;
                }
            }
            if (!found) typesOk = false;
        }
        check("summary: the detour and run_summary trail types are "
                        + "registered",
                Boolean.valueOf(typesOk), Boolean.TRUE);
        com.ghosthands.app.Trail summaryTrail =
                new com.ghosthands.app.Trail(null);
        summaryTrail.record("detour", 3, com.ghosthands.app.Trail.fields(
                "failed_url", deadDest, "results_url", ddgResults));
        summaryTrail.record("run_summary", 10,
                com.ghosthands.app.Trail.fields(
                        "outcome", "done", "steps", Long.valueOf(10)));
        check("summary: detour and run_summary events record without "
                        + "throwing",
                Integer.valueOf(summaryTrail.events().size()),
                Integer.valueOf(2));

        // -- v2.6 item 1: the model registry (ModelStore) ----------------
        // Two quants of the same 1.5B, one registry. The Q4_K_M pin
        // is the v2.3-era one, recovered from the proof's
        // MainActivity (same repo, same URL shape); the Q5_K_M pin
        // is v2.4's and must agree with the legacy constants the
        // v2.4 checks above already pin.

        com.ghosthands.app.ModelStore.Spec q4 =
                com.ghosthands.app.ModelStore.specFor("q4_k_m");
        com.ghosthands.app.ModelStore.Spec q5 =
                com.ghosthands.app.ModelStore.specFor("q5_k_m");
        check("models: the registry knows both quants",
                Boolean.valueOf(q4 != null && q5 != null), Boolean.TRUE);
        check("models: Q4 id", q4.id, "q4_k_m");
        check("models: Q4 display name", q4.displayName,
                "Qwen2.5-1.5B Q4_K_M (faster, smaller)");
        check("models: Q4 file name", q4.fileName,
                "qwen2.5-1.5b-instruct-q4_k_m.gguf");
        check("models: Q4 URL is the recovered v2.3-era URL", q4.url,
                "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF"
                        + "/resolve/main/"
                        + "qwen2.5-1.5b-instruct-q4_k_m.gguf");
        check("models: Q4 size is the v2.3-era measured size",
                Long.valueOf(q4.size), Long.valueOf(1117320736L));
        check("models: Q4 sha256 is the v2.3-era measured hash", q4.sha256,
                "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e");
        check("models: Q5 display name", q5.displayName,
                "Qwen2.5-1.5B Q5_K_M (sharper, larger)");
        check("models: Q5 spec is the legacy pin, field for field",
                Boolean.valueOf(q5.fileName.equals(
                                com.ghosthands.app.ModelStore.MODEL_NAME)
                        && q5.url.equals(
                                com.ghosthands.app.ModelStore.MODEL_URL)
                        && q5.size
                                == com.ghosthands.app.ModelStore.MODEL_SIZE
                        && q5.sha256.equals(
                                com.ghosthands.app.ModelStore.MODEL_SHA256)),
                Boolean.TRUE);
        check("models: the default is Q5 (the behaviour since v2.4)",
                com.ghosthands.app.ModelStore.DEFAULT_ID, "q5_k_m");
        check("models: the default spec is the Q5 spec",
                Boolean.valueOf(
                        com.ghosthands.app.ModelStore.defaultSpec() == q5),
                Boolean.TRUE);
        check("models: the registry holds exactly the two quants",
                Integer.valueOf(com.ghosthands.app.ModelStore.MODELS.length),
                Integer.valueOf(2));
        check("models: specFor trims and matches case-insensitively",
                com.ghosthands.app.ModelStore.specFor(" Q4_K_M ").id,
                "q4_k_m");
        check("models: specFor rejects an unknown id",
                com.ghosthands.app.ModelStore.specFor("qwen3-4b"), null);
        check("models: specFor rejects null",
                com.ghosthands.app.ModelStore.specFor(null), null);
        check("models: resolveId keeps a known saved choice",
                com.ghosthands.app.ModelStore.resolveId("q4_k_m"), "q4_k_m");
        check("models: resolveId falls back to the default on null",
                com.ghosthands.app.ModelStore.resolveId(null), "q5_k_m");
        check("models: resolveId falls back to the default on an "
                        + "unknown saved choice",
                com.ghosthands.app.ModelStore.resolveId("gone.gguf"),
                "q5_k_m");
        check("models: the Q4 download reads 1.1 GB",
                com.ghosthands.app.ModelStore.gbLabel(q4), "1.1 GB");
        check("models: the Q5 download reads 1.3 GB",
                com.ghosthands.app.ModelStore.gbLabel(q5), "1.3 GB");
        check("models: picker label — active and on the phone",
                com.ghosthands.app.ModelStore.pickerLabel(q5, true, true),
                "Q5_K_M ✓ active");
        check("models: picker label — active but not downloaded",
                com.ghosthands.app.ModelStore.pickerLabel(q5, true, false),
                "Q5_K_M ✓ chosen");
        check("models: picker label — inactive and on the phone",
                com.ghosthands.app.ModelStore.pickerLabel(q4, false, true),
                "Q4_K_M · on phone");
        check("models: picker label — inactive, a download away",
                com.ghosthands.app.ModelStore.pickerLabel(q4, false, false),
                "Q4_K_M · 1.1 GB download");

        // The store's per-model file logic, against a scratch dir:
        // readiness is exact-size, partials are per-model, and one
        // model's presence says nothing about the other's.
        java.io.File scratch = new java.io.File(
                System.getProperty("java.io.tmpdir"), "gh-v26-modelstore");
        java.io.File[] stale = scratch.listFiles();
        if (stale != null) {
            for (int i = 0; i < stale.length; i++) stale[i].delete();
        }
        scratch.mkdirs();
        com.ghosthands.app.ModelStore ms =
                new com.ghosthands.app.ModelStore(scratch);
        check("models: fileFor names the model's own file",
                ms.fileFor(q4).getName(), q4.fileName);
        check("models: an absent model is not ready",
                Boolean.valueOf(ms.ready(q4)), Boolean.FALSE);
        check("models: no partial bytes for an absent model",
                Long.valueOf(ms.partialBytes(q4)), Long.valueOf(0));
        try {
            java.io.RandomAccessFile raf =
                    new java.io.RandomAccessFile(ms.fileFor(q4), "rw");
            raf.setLength(q4.size);
            raf.close();
            java.io.RandomAccessFile part =
                    new java.io.RandomAccessFile(
                            new java.io.File(scratch, q5.fileName + ".part"),
                            "rw");
            part.setLength(12345);
            part.close();
        } catch (java.io.IOException ioe) {
            throw new RuntimeException(ioe);
        }
        check("models: exact size on disk is ready",
                Boolean.valueOf(ms.ready(q4)), Boolean.TRUE);
        check("models: one model ready says nothing of the other",
                Boolean.valueOf(ms.ready(q5)), Boolean.FALSE);
        check("models: the partial belongs to its own model",
                Long.valueOf(ms.partialBytes(q5)), Long.valueOf(12345));
        check("models: the no-arg ready() reads the default model",
                Boolean.valueOf(ms.ready()), Boolean.FALSE);
        java.io.File[] junk = scratch.listFiles();
        if (junk != null) {
            for (int i = 0; i < junk.length; i++) junk[i].delete();
        }
        scratch.delete();

        // -- v2.6 item 1 (trail): the active model is on the records ----
        // A stub-bodied run against an unloaded engine: the first
        // decide is the engine-error record, and it — plus the stop
        // and the run_summary — must name the brain the Agent was
        // constructed with.
        final Eyes.Perception stubPage = Eyes.parseMap(
                com.ghosthands.app.Json.quote(
                        "{\"url\":\"https://example.org/\",\"title\":"
                        + "\"Example Domain\",\"text\":\"Example text.\","
                        + "\"els\":[]}"));
        Agent.Hands stubHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() { return stubPage; }
            @Override public String tap(int el) { return "ok"; }
            @Override public String type(int el, String text) { return "ok"; }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String query) { return "ok"; }
            @Override public String read(int chunkChars) {
                return "error: no readable text found on this page";
            }
            @Override public String reload(String failedUrl) { return "ok"; }
        };
        Agent.Ui stubUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                return false;
            }
            @Override public boolean stopRequested() { return false; }
            // v3.3 fixture: the co-pilot exists but this screen
            // is "backgrounded" and declines every pause, so
            // pre-v3.3 scenarios behave exactly as before.
            @Override public boolean foreground() { return false; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) { return null; }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) { return false; }
        };
        com.ghosthands.app.Trail modelTrail =
                new com.ghosthands.app.Trail(null);
        Agent modelAgent = new Agent(stubHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), modelTrail, stubUi,
                "q4_k_m");
        modelAgent.run("Count the zebra stripes in the quantum ledger");
        String decideModel = null;
        String stopModel = null;
        String summaryModel = null;
        for (java.util.Map<String, Object> ev : modelTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            Object mv = ev.get("model");
            if ("decide".equals(t)) decideModel = String.valueOf(mv);
            if ("stop".equals(t)) stopModel = String.valueOf(mv);
            if ("run_summary".equals(t)) summaryModel = String.valueOf(mv);
        }
        check("models: the decide records name the active model",
                decideModel, "q4_k_m");
        check("models: the stop record names the active model",
                stopModel, "q4_k_m");
        check("models: the run_summary names the active model",
                summaryModel, "q4_k_m");

        // -- v2.6 item 4: the unclean-shutdown marker (RunMarker) --------

        com.ghosthands.app.RunMarker marker =
                new com.ghosthands.app.RunMarker(
                        "Find the forecast for Oakdale, Tennessee",
                        1792900000000L, "q5_k_m");
        com.ghosthands.app.RunMarker markerBack =
                com.ghosthands.app.RunMarker.parse(marker.serialize());
        check("marker: a written marker parses back",
                Boolean.valueOf(markerBack != null), Boolean.TRUE);
        check("marker: the goal survives the round trip",
                markerBack.goal,
                "Find the forecast for Oakdale, Tennessee");
        check("marker: the model survives the round trip",
                markerBack.modelId, "q5_k_m");
        check("marker: the start time survives the round trip",
                Long.valueOf(markerBack.startedAtMs),
                Long.valueOf(1792900000000L));
        com.ghosthands.app.RunMarker hairy =
                new com.ghosthands.app.RunMarker(
                        "Goal with \"quotes\", a \\ backslash, and élan",
                        42L, "q4_k_m");
        check("marker: quotes, backslashes and unicode survive",
                com.ghosthands.app.RunMarker.parse(hairy.serialize()).goal,
                hairy.goal);
        check("marker: null text is no marker",
                com.ghosthands.app.RunMarker.parse(null), null);
        check("marker: empty text is no marker",
                com.ghosthands.app.RunMarker.parse(""), null);
        check("marker: non-JSON is no marker",
                com.ghosthands.app.RunMarker.parse("not json"), null);
        check("marker: a JSON array is no marker",
                com.ghosthands.app.RunMarker.parse("[1,2]"), null);
        check("marker: an object without a goal is no marker",
                com.ghosthands.app.RunMarker.parse("{}"), null);
        check("marker: a non-string goal is no marker",
                com.ghosthands.app.RunMarker.parse("{\"goal\":5}"), null);
        check("marker: an empty goal is no marker",
                com.ghosthands.app.RunMarker.parse("{\"goal\":\"\"}"), null);
        com.ghosthands.app.RunMarker loose =
                com.ghosthands.app.RunMarker.parse(
                        "{\"goal\":\"g\",\"started_at_ms\":123.0,"
                        + "\"model\":\"q4_k_m\"}");
        check("marker: a fractional start time still parses (any "
                        + "JSON number is a Number)",
                Long.valueOf(loose.startedAtMs), Long.valueOf(123));
        check("marker: a missing model reads as empty, not a crash",
                com.ghosthands.app.RunMarker.parse(
                        "{\"goal\":\"g\"}").modelId, "");
        check("marker: the launch line names the abrupt end and "
                        + "the goal",
                com.ghosthands.app.RunMarker.abruptLine(marker),
                "Last run ended abruptly (app or phone died) — goal: "
                        + "Find the forecast for Oakdale, Tennessee");

        // -- v2.6.1: the grammar kill-switch (ModelStore plumbing) -------
        // The setting defaults OFF for every install; only an
        // explicit persisted "on" resolves to on, and the persisted
        // form round-trips through the resolver.

        check("grammar switch: the default is off",
                Boolean.valueOf(com.ghosthands.app.ModelStore.GRAMMAR_DEFAULT_ON),
                Boolean.FALSE);
        check("grammar switch: nothing saved resolves off",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn(null)), Boolean.FALSE);
        check("grammar switch: empty saved value resolves off",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("")), Boolean.FALSE);
        check("grammar switch: saved 'on' resolves on",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("on")), Boolean.TRUE);
        check("grammar switch: saved 'ON' resolves on (any case)",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("ON")), Boolean.TRUE);
        check("grammar switch: saved ' on ' resolves on (trimmed)",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn(" on ")), Boolean.TRUE);
        check("grammar switch: saved 'off' resolves off",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("off")), Boolean.FALSE);
        check("grammar switch: saved 'true' is not 'on' — resolves off",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("true")), Boolean.FALSE);
        check("grammar switch: an unrecognised value resolves off",
                Boolean.valueOf(com.ghosthands.app.ModelStore
                        .resolveGrammarOn("garbage")), Boolean.FALSE);
        check("grammar switch: the persisted form of on is 'on'",
                com.ghosthands.app.ModelStore.grammarPrefValue(true), "on");
        check("grammar switch: the persisted form of off is 'off'",
                com.ghosthands.app.ModelStore.grammarPrefValue(false), "off");
        check("grammar switch: on survives the persist round trip",
                Boolean.valueOf(com.ghosthands.app.ModelStore.resolveGrammarOn(
                        com.ghosthands.app.ModelStore.grammarPrefValue(true))),
                Boolean.TRUE);
        check("grammar switch: off survives the persist round trip",
                Boolean.valueOf(com.ghosthands.app.ModelStore.resolveGrammarOn(
                        com.ghosthands.app.ModelStore.grammarPrefValue(false))),
                Boolean.FALSE);
        check("grammar switch: the settings label is the brief's, verbatim",
                com.ghosthands.app.ModelStore.GRAMMAR_LABEL,
                "Output grammar lock (experimental — off is the stable setting)");
        check("grammar switch: the toggle chip names the on state",
                com.ghosthands.app.ModelStore.grammarToggleLabel(true),
                "Grammar lock: On");
        check("grammar switch: the toggle chip names the off state",
                com.ghosthands.app.ModelStore.grammarToggleLabel(false),
                "Grammar lock: Off");

        // -- v2.6.1: the flag inside ModelEngine --------------------------
        // A fresh engine is off (the stable configuration before
        // anyone sets anything); the setter is what generate()
        // hands the shim.

        ModelEngine grammarEngine = new ModelEngine();
        check("grammar switch: a fresh engine defaults to off",
                Boolean.valueOf(grammarEngine.grammarOn()), Boolean.FALSE);
        grammarEngine.setGrammarOn(true);
        check("grammar switch: setGrammarOn(true) is what generate reads",
                Boolean.valueOf(grammarEngine.grammarOn()), Boolean.TRUE);
        grammarEngine.setGrammarOn(false);
        check("grammar switch: setGrammarOn(false) turns it back off",
                Boolean.valueOf(grammarEngine.grammarOn()), Boolean.FALSE);

        // -- v2.6.1 (trail): the setting is on the run's records ---------
        // Same stub-bodied shape as the v2.6 model test: the first
        // decide is the engine-error record, and it — plus the stop
        // and the run_summary — must carry the switch state the
        // Agent was constructed with, beside the model id.

        com.ghosthands.app.Trail grammarTrail =
                new com.ghosthands.app.Trail(null);
        Agent grammarAgent = new Agent(stubHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), grammarTrail, stubUi,
                "q5_k_m", true);
        grammarAgent.run("Count the zebra stripes in the quantum ledger");
        Object decideSetting = null;
        Object stopSetting = null;
        Object summarySetting = null;
        for (java.util.Map<String, Object> ev : grammarTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            Object sv = ev.get("grammar_setting");
            if ("decide".equals(t)) decideSetting = sv;
            if ("stop".equals(t)) stopSetting = sv;
            if ("run_summary".equals(t)) summarySetting = sv;
        }
        check("grammar switch: the decide records name the setting (on)",
                decideSetting, Boolean.TRUE);
        check("grammar switch: the stop record names the setting (on)",
                stopSetting, Boolean.TRUE);
        check("grammar switch: the run_summary names the setting (on)",
                summarySetting, Boolean.TRUE);

        com.ghosthands.app.Trail grammarOffTrail =
                new com.ghosthands.app.Trail(null);
        Agent grammarOffAgent = new Agent(stubHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), grammarOffTrail, stubUi,
                "q5_k_m", false);
        grammarOffAgent.run("Count the zebra stripes in the quantum ledger");
        Object offDecideSetting = null;
        Object offSummarySetting = null;
        for (java.util.Map<String, Object> ev : grammarOffTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            Object sv = ev.get("grammar_setting");
            if ("decide".equals(t)) offDecideSetting = sv;
            if ("run_summary".equals(t)) offSummarySetting = sv;
        }
        check("grammar switch: the decide records name the setting (off)",
                offDecideSetting, Boolean.FALSE);
        check("grammar switch: the run_summary names the setting (off)",
                offSummarySetting, Boolean.FALSE);

        // -- v2.8: main-content bar, score and pick -----------------------
        // Fixtures are the collector metrics measured on the real
        // pages (read-lab runs of the shipped scripts): the DDG
        // lite mirror, the Wikipedia Clinch article, the saved
        // weather.gov homepage shell, and the synthetic
        // weather.com shape. The decisions are pure; the scripts
        // that collect the metrics are shape-checked below.

        check("main bar: a null candidate never clears",
                Boolean.valueOf(Eyes.clearsMainBar(null)), Boolean.FALSE);
        check("main bar: chrome never clears, however prose-like",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 5000, 4900, 10, true))),
                Boolean.FALSE);
        check("main bar: under the size floor fails",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 599, 590, 0, false))),
                Boolean.FALSE);
        check("main bar: paragraph share of exactly half clears",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 1000, 500, 0, false))),
                Boolean.TRUE);
        check("main bar: paragraph share under half fails",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 1000, 499, 0, false))),
                Boolean.FALSE);
        check("main bar: link density of exactly 2/5 clears",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 1000, 600, 400, false))),
                Boolean.TRUE);
        check("main bar: link density over 2/5 fails",
                Boolean.valueOf(Eyes.clearsMainBar(
                        readCand("div", 1000, 600, 401, false))),
                Boolean.FALSE);
        check("main score: prose mass discounted by link share",
                Integer.valueOf(Eyes.mainContentScore(
                        readCand("div", 200, 100, 50, false))),
                Integer.valueOf(75));

        // DDG lite results (measured): every candidate is a link
        // wall or a region list — nothing clears, the full-page
        // tier serves, byte-identical there (no chrome elements).
        java.util.List<Eyes.ReadCandidate> ddgCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        ddgCands.add(readCand("div", 3952, 573, 3039, false));
        ddgCands.add(readCand("div", 704, 0, 0, false));
        ddgCands.add(readCand("div", 3242, 573, 3039, false));
        check("main pick: DDG results clear nothing -> fallback",
                Integer.valueOf(Eyes.pickMainContent(ddgCands, -1)),
                Integer.valueOf(-1));

        // Clinch article (measured): the legacy root <main> is
        // in the list and clears; a wider wrapper scores higher,
        // but rule 1 keeps the legacy root — its text is the
        // pre-v2.8 extraction, byte-for-byte.
        java.util.List<Eyes.ReadCandidate> clinchCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        clinchCands.add(readCand("div", 13853, 12903, 4688, false));
        clinchCands.add(readCand("main", 13083, 12220, 4305, false));
        clinchCands.add(readCand("div", 11440, 11026, 3026, false));
        check("main pick: a strong legacy root wins over a higher score",
                Integer.valueOf(Eyes.pickMainContent(clinchCands, 1)),
                Integer.valueOf(1));
        check("main pick: without the legacy rule, the tightest "
                        + "near-best clearer is the content core",
                Integer.valueOf(Eyes.pickMainContent(clinchCands, -1)),
                Integer.valueOf(2));

        // Weather.com shape (synthetic fixture, measured): the
        // page wrapper dilutes below the paragraph bar, the
        // header/nav blocks are chrome, the forecast section is
        // the only clearer.
        java.util.List<Eyes.ReadCandidate> wxCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        wxCands.add(readCand("div", 4804, 1531, 490, false));
        wxCands.add(readCand("div", 418, 0, 0, true));
        wxCands.add(readCand("div", 486, 0, 0, true));
        wxCands.add(readCand("section", 1542, 1531, 0, false));
        check("main pick: the weather shape picks the forecast section",
                Integer.valueOf(Eyes.pickMainContent(wxCands, -1)),
                Integer.valueOf(3));

        // weather.gov homepage shell (measured): the nav-wrapper
        // div (link density 0.48) is rejected by the tightened
        // bar; the content column (0.38) is the pick.
        java.util.List<Eyes.ReadCandidate> govCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        govCands.add(readCand("div", 3047, 2090, 1450, false));
        govCands.add(readCand("div", 2204, 1264, 832, false));
        check("main pick: a link-dense wrapper loses to the content block",
                Integer.valueOf(Eyes.pickMainContent(govCands, -1)),
                Integer.valueOf(1));

        // Tightness tie-break: within 9/10 of the best score the
        // smaller container wins; outside the band it does not.
        java.util.List<Eyes.ReadCandidate> tieCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        tieCands.add(readCand("div", 4000, 3000, 400, false));
        tieCands.add(readCand("div", 2500, 2100, 250, false));
        tieCands.add(readCand("div", 3900, 2950, 390, false));
        check("main pick: tightest within 9/10 of the best score wins",
                Integer.valueOf(Eyes.pickMainContent(tieCands, -1)),
                Integer.valueOf(2));
        tieCands.remove(2);
        check("main pick: outside the band the best score stands",
                Integer.valueOf(Eyes.pickMainContent(tieCands, -1)),
                Integer.valueOf(0));
        java.util.List<Eyes.ReadCandidate> weakLegacyCands =
                new java.util.ArrayList<Eyes.ReadCandidate>();
        weakLegacyCands.add(readCand("div", 4000, 3000, 400, false));
        weakLegacyCands.add(readCand("main", 900, 100, 50, false));
        check("main pick: a weak legacy root does not veto a strong div",
                Integer.valueOf(Eyes.pickMainContent(weakLegacyCands, 1)),
                Integer.valueOf(0));
        check("main pick: no candidates -> fallback",
                Integer.valueOf(Eyes.pickMainContent(
                        new java.util.ArrayList<Eyes.ReadCandidate>(), -1)),
                Integer.valueOf(-1));
        check("main pick: null list -> fallback",
                Integer.valueOf(Eyes.pickMainContent(null, -1)),
                Integer.valueOf(-1));

        // -- v2.8: collector payload parsing ------------------------------

        Eyes.ReadCandidates parsedCands = Eyes.parseReadCandidates(
                "{\"cands\":[{\"tag\":\"main\",\"len\":13083,"
                + "\"para\":12220,\"link\":4305,\"chrome\":0},"
                + "{\"tag\":\"nav\",\"len\":900,\"para\":100,"
                + "\"link\":800,\"chrome\":1}],\"legacy\":0}");
        check("candidate parse: list size",
                Integer.valueOf(parsedCands.list.size()),
                Integer.valueOf(2));
        check("candidate parse: legacy index",
                Integer.valueOf(parsedCands.legacyIndex),
                Integer.valueOf(0));
        check("candidate parse: metrics land in the fields",
                Boolean.valueOf(parsedCands.list.get(0).textLen == 13083
                        && parsedCands.list.get(0).paraLen == 12220
                        && parsedCands.list.get(0).linkLen == 4305
                        && !parsedCands.list.get(0).chrome
                        && parsedCands.list.get(1).chrome),
                Boolean.TRUE);
        Eyes.ReadCandidates garbageCands =
                Eyes.parseReadCandidates("not json");
        check("candidate parse: garbage is an empty collection",
                Boolean.valueOf(garbageCands.list.isEmpty()
                        && garbageCands.legacyIndex == -1),
                Boolean.TRUE);
        check("candidate parse: null is an empty collection",
                Boolean.valueOf(Eyes.parseReadCandidates(null)
                        .list.isEmpty()),
                Boolean.TRUE);

        // parseMap carries the light pass (and tolerates its absence).
        String mapWithCands = "{\"url\":\"https://x.local/\","
                + "\"title\":\"T\",\"text\":\"fallback preview\","
                + "\"els\":[],\"cands\":[{\"tag\":\"section\","
                + "\"len\":1542,\"para\":1531,\"link\":0,\"chrome\":0}],"
                + "\"legacy\":-1}";
        Eyes.Perception percCands = Eyes.parseMap(
                com.ghosthands.app.Json.quote(mapWithCands));
        check("parseMap: candidates ride along",
                Boolean.valueOf(percCands.readCandidates.size() == 1
                        && percCands.legacyCandidateIndex == -1
                        && percCands.readCandidates.get(0).paraLen == 1531),
                Boolean.TRUE);
        Eyes.Perception percBare = Eyes.parseMap(
                com.ghosthands.app.Json.quote(
                        "{\"url\":\"https://x.local/\",\"title\":\"T\","
                        + "\"text\":\"p\",\"els\":[]}"));
        check("parseMap: a pre-v2.8 payload has no candidates",
                Boolean.valueOf(percBare.readCandidates.isEmpty()
                        && percBare.legacyCandidateIndex == -1),
                Boolean.TRUE);

        // -- v2.8: density settle decision --------------------------------

        check("settle: no probes yet waits",
                Integer.valueOf(Eyes.settleDecision(new int[] {})),
                Integer.valueOf(Eyes.SETTLE_WAIT));
        check("settle: one probe never settles",
                Integer.valueOf(Eyes.settleDecision(new int[] {500})),
                Integer.valueOf(Eyes.SETTLE_WAIT));
        check("settle: a near-flat pair settles",
                Integer.valueOf(Eyes.settleDecision(new int[] {500, 510})),
                Integer.valueOf(Eyes.SETTLE_FLAT));
        check("settle: shrinkage within the delta also settles",
                Integer.valueOf(Eyes.settleDecision(new int[] {300, 268})),
                Integer.valueOf(Eyes.SETTLE_FLAT));
        check("settle: growth past the delta keeps waiting",
                Integer.valueOf(Eyes.settleDecision(new int[] {500, 900})),
                Integer.valueOf(Eyes.SETTLE_WAIT));
        check("settle: a late flattening settles on the third probe",
                Integer.valueOf(Eyes.settleDecision(
                        new int[] {500, 900, 905})),
                Integer.valueOf(Eyes.SETTLE_FLAT));
        check("settle: still growing at the budget proceeds by budget",
                Integer.valueOf(Eyes.settleDecision(
                        new int[] {500, 900, 2000})),
                Integer.valueOf(Eyes.SETTLE_BUDGET));
        check("settle: an empty page settles flat",
                Integer.valueOf(Eyes.settleDecision(new int[] {0, 0})),
                Integer.valueOf(Eyes.SETTLE_FLAT));
        check("settle: probe parse reads the length",
                Integer.valueOf(Eyes.parseTextLength("{\"len\":12345}")),
                Integer.valueOf(12345));
        check("settle: probe parse failure is -1 (proceed unsettled)",
                Integer.valueOf(Eyes.parseTextLength("null")),
                Integer.valueOf(-1));

        // -- v2.8: tiered read strings, and the Agent parsers on them -----

        String mainTierRead = Eyes.formatReadChunk(0, "abc", 8412,
                Eyes.READ_TIER_MAIN);
        check("tiered read: the main-content string, exact",
                mainTierRead,
                "ok: read chars 0-3 of 8412 (main content); "
                        + "read again for more:\nabc");
        String fullTierEnd = Eyes.formatReadChunk(8410, "ab", 8412,
                Eyes.READ_TIER_FULL);
        check("tiered read: the full-page end string, exact",
                fullTierEnd,
                "ok: read chars 8410-8412 of 8412 (full page); "
                        + "end of page text:\nab");
        check("tiered read: the total still parses",
                Integer.valueOf(Agent.readTotalChars(mainTierRead)),
                Integer.valueOf(8412));
        check("tiered read: the end still registers",
                Boolean.valueOf(Agent.readReachedEnd(fullTierEnd)),
                Boolean.TRUE);
        check("tiered read: a mid read is not the end",
                Boolean.valueOf(Agent.readReachedEnd(mainTierRead)),
                Boolean.FALSE);
        check("tiered read: the auto-read section keeps the range + tier",
                Agent.autoReadSection(mainTierRead),
                "Page text (auto-read, chars 0-3 of 8412 "
                        + "(main content)):\nabc");

        // Cross-tier dedupe: the history stubbing keys on the
        // read's URL (unchanged), so a main-content chunk and a
        // full-page chunk stub identically when the auto-read
        // section shows the same page.
        String readUrl = "https://weather.example/tenday";
        java.util.List<String> histMain = new java.util.ArrayList<String>();
        histMain.add("step 2: read -> " + mainTierRead);
        java.util.List<String> histMainUrls =
                new java.util.ArrayList<String>();
        histMainUrls.add(readUrl);
        check("dedupe: a main-content history chunk stubs like any read",
                Agent.dedupeReadHistory(histMain, histMainUrls, readUrl)
                        .get(0),
                "step 2: read -> (page text shown above in the "
                        + "auto-read section; not repeated here)");
        java.util.List<String> histFull = new java.util.ArrayList<String>();
        histFull.add("step 2: read -> " + Eyes.formatReadChunk(0, "abc",
                8412, Eyes.READ_TIER_FULL));
        check("dedupe: a full-page history chunk stubs identically",
                Agent.dedupeReadHistory(histFull, histMainUrls, readUrl)
                        .get(0),
                "step 2: read -> (page text shown above in the "
                        + "auto-read section; not repeated here)");
        java.util.List<String> tierHistOther = new java.util.ArrayList<String>();
        String otherEntry = "step 2: read -> " + mainTierRead;
        tierHistOther.add(otherEntry);
        java.util.List<String> tierHistOtherUrls =
                new java.util.ArrayList<String>();
        tierHistOtherUrls.add("https://other.example/page");
        check("dedupe: another page's chunk is never stubbed",
                Agent.dedupeReadHistory(tierHistOther, tierHistOtherUrls, readUrl)
                        .get(0),
                otherEntry);

        // -- v2.8: preview preference, recognition untouched ----------------

        check("preview: a winner's opening text leads",
                Eyes.preferredPreview("region wall", "forecast text"),
                "forecast text");
        check("preview: an empty winner keeps the fallback",
                Eyes.preferredPreview("region wall", ""),
                "region wall");
        check("preview: a missing winner keeps the fallback",
                Eyes.preferredPreview("region wall", null),
                "region wall");
        check("preview: previewAt parse reads the text",
                Eyes.parsePreviewText("{\"text\":\"hello\"}"), "hello");
        check("preview: previewAt parse failure is empty",
                Eyes.parsePreviewText("junk"), "");

        // The recognition rules read title + URL only: swapping
        // the preview (region wall vs content-led) must not move
        // any verdict, on a results page or on an article.
        String v28DdgTitle = "forecast for Oakdale TN chance of rain "
                + "at DuckDuckGo";
        String v28DdgUrl = "https://html.duckduckgo.com/html/"
                + "?q=forecast+for+Oakdale+TN+chance+of+rain";
        Eyes.Perception ddgWall = new Eyes.Perception();
        ddgWall.url = v28DdgUrl;
        ddgWall.title = v28DdgTitle;
        ddgWall.textPreview = "All Regions Argentina Australia Austria";
        Eyes.Perception ddgContent = new Eyes.Perception();
        ddgContent.url = v28DdgUrl;
        ddgContent.title = v28DdgTitle;
        ddgContent.textPreview = "10-day weather forecast for Oakdale, TN";
        java.util.Set<String> seenNone = new java.util.HashSet<String>();
        check("preview: results recognition ignores the preview (wall)",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        ddgWall.title, ddgWall.url)),
                Boolean.TRUE);
        check("preview: results recognition ignores the preview (content)",
                Boolean.valueOf(RelevanceGuard.isSearchResultsPage(
                        ddgContent.title, ddgContent.url)),
                Boolean.TRUE);
        check("preview: auto-read verdict identical across previews",
                Boolean.valueOf(RelevanceGuard.shouldAutoRead(wikiGoal,
                        ddgWall.title, ddgWall.url, seenNone)
                        == RelevanceGuard.shouldAutoRead(wikiGoal,
                                ddgContent.title, ddgContent.url, seenNone)),
                Boolean.TRUE);
        String promptWall = Agent.buildPrompt(wikiGoal, ddgWall,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>(),
                null, 0);
        String promptContent = Agent.buildPrompt(wikiGoal, ddgContent,
                "[1] <a> \"x\"\n", new java.util.ArrayList<String>(),
                null, 0);
        check("preview: the planner's page-text line follows the preview",
                Boolean.valueOf(promptWall.contains("All Regions")
                        && promptContent.contains("10-day weather")
                        && !promptWall.equals(promptContent)),
                Boolean.TRUE);

        // -- v2.8: the new scripts are structurally valid ------------------

        check("js validity: the candidate collector script",
                jsStructureError(Eyes.readCandidatesJs()), null);
        check("js validity: the settle probe script",
                jsStructureError(Eyes.pageTextLengthJs()), null);
        check("js validity: the rooted read script (candidate)",
                jsStructureError(Eyes.readTextAtJs(2, 0,
                        Eyes.READ_CHUNK_CHARS)), null);
        check("js validity: the rooted read script (full page)",
                jsStructureError(Eyes.readTextAtJs(-1, 0,
                        Eyes.READ_CHUNK_CHARS)), null);
        check("js validity: the candidate preview script",
                jsStructureError(Eyes.previewAtJs(2)), null);
        check("read JS: the collector uses the read limit",
                Boolean.valueOf(Eyes.readCandidatesJs()
                        .contains("ghCollectCands(48)")),
                Boolean.TRUE);
        check("read JS: MAP carries the light candidate pass",
                Boolean.valueOf(Eyes.MAP_JS.contains("cands:rc.list")
                        && Eyes.MAP_JS.contains("ghCollectCands(8)")),
                Boolean.TRUE);
        check("read JS: a stale candidate reports total -1",
                Boolean.valueOf(Eyes.readTextAtJs(2, 0, 100)
                        .contains("total:-1")),
                Boolean.TRUE);
        check("read JS: the full-page tier suppresses chrome roles",
                Boolean.valueOf(Eyes.readTextAtJs(-1, 0, 100)
                        .contains("navigation")
                        && Eyes.readTextAtJs(-1, 0, 100)
                                .contains("querySelector('main')")),
                Boolean.TRUE);
        check("read JS: the probe measures with the metrics walk",
                Boolean.valueOf(Eyes.pageTextLengthJs()
                        .contains("ghMetrics")),
                Boolean.TRUE);

        // -- v2.9 item 2: digit-dense lines (Agent.estTokens) ------------
        // The v2.8 field death's measurement half: hourly forecast
        // text — fragment lines of temperatures, percents, times —
        // tokenizes at ~a token per 2 chars, and the v2.2 estimator
        // charged it as prose. The frozen v2.2 estimator above is
        // the audit's baseline.

        check("digit estimate: a temperature fragment costs 2",
                Integer.valueOf(Agent.estTokens("54°")),
                Integer.valueOf(2));
        check("digit estimate: a percent fragment costs 2",
                Integer.valueOf(Agent.estTokens("10%")),
                Integer.valueOf(2));
        check("digit estimate: a digit-free fragment is prose-priced",
                Integer.valueOf(Agent.estTokens("Clear")),
                Integer.valueOf((5 + 3) / 4));
        check("digit estimate: an hourly data row counts at chars/2",
                Integer.valueOf(Agent.estTokens("2 AM 54° Clear 10%")),
                Integer.valueOf(9));
        String readHeader = "ok: read chars 0-2400 of 9074 (main "
                + "content); read again for more";
        check("digit estimate: a read header line (12-27% digits) "
                        + "is NOT surcharged",
                Integer.valueOf(Agent.estTokens(readHeader)),
                Integer.valueOf((readHeader.length() + 3) / 4));
        String digitUrl = "https://example.com/0123456789/0123456789/"
                + "0123456789";
        check("digit estimate: a line both URL- and digit-dense "
                        + "pays its worst rate, not both",
                Integer.valueOf(Agent.estTokens(digitUrl)),
                Integer.valueOf((digitUrl.length() + 1) / 2));
        String wxChunk = hourlyChunk(2, 2400);
        check("digit estimate: the frozen estimator prices the "
                        + "hourly chunk as prose (600)",
                Integer.valueOf(estTokensV22(wxChunk)),
                Integer.valueOf(600));
        check("digit estimate: the tightened estimator sees the "
                        + "chunk's true density (700)",
                Integer.valueOf(Agent.estTokens(wxChunk)),
                Integer.valueOf(700));

        // -- v2.9: the estimator regression pair --------------------------
        // The trail-20261025-015133 step-5 composition, rebuilt
        // part for part: the field goal, the AccuWeather-class
        // perception, steps 1-3 in history, the step-4 read (a
        // 2,400-char main-content chunk) in history, and the
        // auto-read section carrying the next chunk. The old
        // estimator under-measured this composition by 207 tokens;
        // the new one rates it over budget BEFORE any degradation
        // and the ladder brings it under (item 1, below).

        String rainGoal = "find the forecast for Oakdale TN and if "
                + "we are expecting it to rain today";
        Eyes.Perception wxField = accuPage(
                hourlyChunk(2, 2400).substring(0, 240));
        String wxRendered = wxField.render(rainGoal);
        java.util.List<String> fieldHist =
                new java.util.ArrayList<String>();
        java.util.List<String> fieldUrls =
                new java.util.ArrayList<String>();
        fieldHist.add("step 1: {\"action\":\"tap\",\"el\":12} -> "
                + "error: element 12 not on page");
        fieldUrls.add(null);
        fieldHist.add("step 2: {\"action\":\"search\",\"query\":"
                + "\"Oakdale TN weather forecast\"} -> ok: no search "
                + "box on this page; searched DuckDuckGo for \"Oakdale "
                + "TN weather forecast\" [You searched for \"Oakdale TN "
                + "weather forecast\" and are on the results page. Next: "
                + "tap the result link that matches the goal, or use "
                + "{\"action\":\"read\"} to read this page. Do not "
                + "search the same query again.]");
        fieldUrls.add(null);
        fieldHist.add("step 3: {\"action\":\"tap\",\"el\":8} -> "
                + "ok: tapped #8");
        fieldUrls.add(null);
        fieldHist.add("step 4: {\"action\":\"read\"} -> "
                + Eyes.formatReadChunk(0, wxChunk, 9074, "main content"));
        fieldUrls.add(wxField.url);
        String fieldSection = Agent.autoReadSection(
                Eyes.formatReadChunk(2400, hourlyChunk(3, 2400), 9074,
                        "main content"));
        String fieldRaw = Agent.buildPrompt(rainGoal, wxField,
                wxRendered, fieldHist, fieldSection, 0);
        System.out.println("FIT-MEASUREMENT field composition: frozen "
                + "est " + estTokensV22(fieldRaw) + ", tightened est "
                + Agent.estTokens(fieldRaw) + ", budget "
                + Agent.PROMPT_TOKEN_BUDGET);
        check("fit pair: the frozen estimator's blind spot on the "
                        + "field composition is 207 tokens",
                Integer.valueOf(Agent.estTokens(fieldRaw)
                        - estTokensV22(fieldRaw)),
                Integer.valueOf(207));
        check("fit pair: the tightened estimator rates the raw "
                        + "composition OVER budget",
                Boolean.valueOf(Agent.estTokens(fieldRaw)
                        > Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        Agent.FitResult fieldFit = Agent.fitPrompt(rainGoal, wxField,
                wxRendered, fieldHist, fieldUrls, fieldSection, 0);
        check("fit pair: the ladder fits the field composition",
                Boolean.valueOf(fieldFit.fits), Boolean.TRUE);
        check("fit pair: the fitted prompt is under budget",
                Boolean.valueOf(Agent.estTokens(fieldFit.prompt)
                        <= Agent.PROMPT_TOKEN_BUDGET),
                Boolean.TRUE);
        check("fit pair: the history read was stubbed, the section "
                        + "kept",
                Boolean.valueOf(fieldFit.prompt.contains(
                                Agent.READ_STUB_TAIL)
                        && fieldFit.prompt.contains(
                                "auto-read, chars 2400-4800")),
                Boolean.TRUE);
        check("fit pair: the stubbed chunk's text is gone (the "
                        + "section's next chunk remains)",
                Boolean.valueOf(!fieldFit.prompt.contains(
                                wxChunk.substring(2000, 2060))
                        && fieldFit.prompt.contains(
                                "Chevron right\n3 AM")),
                Boolean.TRUE);

        // -- v2.9 item 1: the ladder's pieces ------------------------------

        String readEntry = "step 4: {\"action\":\"read\"} -> "
                + Eyes.formatReadChunk(0, "CHUNKTEXT", 9074,
                        "main content");
        String stubbedEntry = Agent.stubReadChunk(readEntry);
        check("stub: the step line and accounting survive, the "
                        + "chunk does not",
                Boolean.valueOf(stubbedEntry.startsWith(
                                "step 4: {\"action\":\"read\"} -> ok: "
                                        + "read chars 0-9 of 9074 "
                                        + "(main content); read again "
                                        + "for more: ")
                        && stubbedEntry.endsWith(Agent.READ_STUB_TAIL)
                        && !stubbedEntry.contains("CHUNKTEXT")),
                Boolean.TRUE);
        check("stub: a non-read entry is untouched",
                Agent.stubReadChunk(
                        "step 1: {\"action\":\"tap\",\"el\":12} -> "
                                + "error: element 12 not on page"),
                "step 1: {\"action\":\"tap\",\"el\":12} -> error: "
                        + "element 12 not on page");
        check("stub: an errored read (no chunk framing) is untouched",
                Agent.stubReadChunk("step 2: {\"action\":\"read\"} -> "
                        + "error: no readable text found on this page"),
                "step 2: {\"action\":\"read\"} -> error: no readable "
                        + "text found on this page");
        check("stub: null stays null",
                Agent.stubReadChunk(null), null);
        java.util.List<String> mixHist =
                new java.util.ArrayList<String>();
        mixHist.add("step 1: {\"action\":\"tap\",\"el\":1} -> ok");
        mixHist.add(readEntry);
        java.util.List<String> mixUrls =
                new java.util.ArrayList<String>();
        mixUrls.add(null);
        mixUrls.add("https://example.org/a");
        java.util.List<String> mixStubbed =
                Agent.stubReadChunksInHistory(mixHist, mixUrls);
        check("stub: only the read entry is stubbed, inputs unmutated",
                Boolean.valueOf(mixStubbed.get(0).equals(mixHist.get(0))
                        && mixStubbed.get(1).endsWith(
                                Agent.READ_STUB_TAIL)
                        && mixHist.get(1).contains("CHUNKTEXT")),
                Boolean.TRUE);
        check("map header: the reduced set states itself",
                Agent.mapFitHeader(24, 40),
                "map: 24 of 40 elements (page dense)\n");
        check("counts note: the range is read from the section",
                Agent.countsOnlyNote(fieldSection),
                "note: this page was auto-read (chars 2400-4800 of "
                        + "9074 (main content)) but its text is "
                        + "omitted from this prompt to fit the budget; "
                        + "use read to see it.");
        check("counts note: a rangeless section gets the generic note",
                Agent.countsOnlyNote("Page text (auto-read):\nxyz"),
                "note: this page was auto-read but its text is "
                        + "omitted from this prompt to fit the budget; "
                        + "use read to see it.");
        check("dense summary: the honest stop's wording",
                Agent.densePageSummary(2574),
                "page too dense for the model's context (prompt still "
                        + "2574 est tokens after every trim; budget "
                        + "1700)");

        // renderElements at the standing caps is render(), byte for
        // byte (the refactor's safety pin), and topElements keeps
        // the controls + goal matches, in map order, unrenumbered.
        check("fit map: renderElements at standing caps is render()",
                Eyes.renderElements(wxField.elements, rainGoal,
                        Integer.MAX_VALUE, 80, Eyes.MAP_BUDGET_CHARS),
                wxRendered);
        java.util.List<ElementInfo> top24 =
                Eyes.topElements(wxField.elements, rainGoal, 24);
        boolean ascending = top24.size() == 24;
        for (int i = 1; i < top24.size(); i++) {
            if (top24.get(i).number <= top24.get(i - 1).number) {
                ascending = false;
            }
        }
        boolean hasSearch = false;
        boolean hasForecast = false;
        for (ElementInfo e : top24) {
            if (e.number == 1) hasSearch = true;
            if (e.number == 3) hasForecast = true;
        }
        check("fit map: topElements keeps 24, ascending, with the "
                        + "search box and the goal-matching link",
                Boolean.valueOf(ascending && hasSearch && hasForecast),
                Boolean.TRUE);
        check("fit map: a keep >= the map size returns the whole map",
                Integer.valueOf(Eyes.topElements(wxField.elements,
                        rainGoal, 40).size()),
                Integer.valueOf(40));

        // -- v2.9 item 1: rung isolation -----------------------------------
        // Each fixture is over budget in a way only one rung can
        // close (the rungs are cumulative; the reported rung is
        // the one that closed it).

        // Rung 1: three fat read entries carry the overage.
        Eyes.Perception fitPage1 = new Eyes.Perception();
        fitPage1.url = "https://en.wikipedia.org/wiki/Clinch_River";
        fitPage1.title = "Clinch River - Wikipedia";
        fitPage1.textPreview = rep('p', 240);
        fitPage1.elements.add(elx(1, "a", "x",
                "https://en.wikipedia.org/wiki/X"));
        java.util.List<String> histR1 =
                new java.util.ArrayList<String>();
        java.util.List<String> urlsR1 =
                new java.util.ArrayList<String>();
        for (int i = 1; i <= 3; i++) {
            histR1.add("step " + i + ": {\"action\":\"read\"} -> "
                    + Eyes.formatReadChunk(0, rep('r', 2400), 11604));
            urlsR1.add(fitPage1.url);
        }
        Agent.FitResult fitR1 = Agent.fitPrompt(wikiGoal, fitPage1,
                "[1] <a> \"x\"\n", histR1, urlsR1, null, 0);
        check("rung 1: stubbing the history reads closes it",
                Boolean.valueOf(fitR1.fits && fitR1.rungs == 1),
                Boolean.TRUE);
        check("rung 1: the preview survived whole (rung 2 never ran)",
                Boolean.valueOf(fitR1.prompt.contains(rep('p', 200))),
                Boolean.TRUE);

        // Rung 2: no reads to stub; the preview's truncation closes
        // it (the map and section survive).
        Eyes.Perception fitPage2 = new Eyes.Perception();
        fitPage2.url = "https://en.wikipedia.org/wiki/Clinch_River";
        fitPage2.title = "Clinch River - Wikipedia";
        fitPage2.textPreview = hourlyChunk(2, 2400).substring(0, 240);
        fitPage2.elements.addAll(wxField.elements);
        String rendered2 = fitPage2.render(wikiGoal);
        java.util.List<String> histR2 =
                new java.util.ArrayList<String>();
        java.util.List<String> urlsR2 =
                new java.util.ArrayList<String>();
        for (int i = 1; i <= 5; i++) {
            histR2.add("step " + i + ": {\"action\":\"tap\",\"el\":16}"
                    + " -> ok: " + rep('h', i == 5 ? 476 : 380));
            urlsR2.add(null);
        }
        String sectionR2 = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('t', 700), 9074));
        String rawR2 = Agent.buildPrompt(wikiGoal, fitPage2,
                rendered2, histR2, sectionR2, 0);
        Agent.FitResult fitR2 = Agent.fitPrompt(wikiGoal, fitPage2,
                rendered2, histR2, urlsR2, sectionR2, 0);
        System.out.println("FIT-MEASUREMENT rung-2 fixture: raw est "
                + Agent.estTokens(rawR2) + ", fit rungs " + fitR2.rungs
                + ", fit est " + fitR2.estTokens);
        check("rung 2: the raw prompt is over, the fit closes at "
                        + "rung 2",
                Boolean.valueOf(Agent.estTokens(rawR2)
                                > Agent.PROMPT_TOKEN_BUDGET
                        && fitR2.fits && fitR2.rungs == 2),
                Boolean.TRUE);
        check("rung 2: the preview was truncated, the map not reduced",
                Boolean.valueOf(!fitR2.prompt.contains(
                                hourlyChunk(2, 2400).substring(180, 240))
                        && !fitR2.prompt.contains("(page dense)")),
                Boolean.TRUE);

        // Rung 3: the dense map carries the overage; only the
        // reduced set closes it.
        Eyes.Perception fitPage3 = accuPage("");
        String rendered3 = fitPage3.render(rainGoal);
        java.util.List<String> histR3 =
                new java.util.ArrayList<String>();
        java.util.List<String> urlsR3 =
                new java.util.ArrayList<String>();
        for (int i = 1; i <= 5; i++) {
            histR3.add("step " + i + ": {\"action\":\"tap\",\"el\":16}"
                    + " -> ok: " + rep('h', 380));
            urlsR3.add(null);
        }
        String sectionR3 = Agent.autoReadSection(
                Eyes.formatReadChunk(0, rep('t', 1500), 9074));
        Agent.FitResult fitR3 = Agent.fitPrompt(rainGoal, fitPage3,
                rendered3, histR3, urlsR3, sectionR3, 0);
        System.out.println("FIT-MEASUREMENT rung-3 fixture: fit rungs "
                + fitR3.rungs + ", fit est " + fitR3.estTokens);
        check("rung 3: the reduced map closes it",
                Boolean.valueOf(fitR3.fits && fitR3.rungs == 3),
                Boolean.TRUE);
        check("rung 3: the header states the reduction; the search "
                        + "box and goal match survive, unrenumbered; "
                        + "a tail element is gone",
                Boolean.valueOf(fitR3.prompt.contains(
                                "map: 24 of 40 elements (page dense)")
                        && fitR3.prompt.contains("[1] <input>")
                        && fitR3.prompt.contains("[3] <a>")
                        && !fitR3.prompt.contains(
                                "Nearby city weather 9")),
                Boolean.TRUE);
        check("rung 3: names were truncated harder",
                Boolean.valueOf(!fitR3.prompt.contains(
                        "Forecast for Oakdale")),
                Boolean.TRUE);

        // Rung 4: only the auto-read section is heavy; its text
        // leaves, its accounting stays as a note, and the arrival
        // banner (which would claim the text is above) goes with it.
        Eyes.Perception fitPage4 = new Eyes.Perception();
        fitPage4.url = "https://en.wikipedia.org/wiki/Clinch_River";
        fitPage4.title = "Clinch River - Wikipedia";
        fitPage4.textPreview = "";
        fitPage4.elements.add(elx(1, "a", "x",
                "https://en.wikipedia.org/wiki/X"));
        java.util.List<String> histR4 =
                new java.util.ArrayList<String>();
        histR4.add("step 1: {\"action\":\"search\",\"query\":\"Clinch "
                + "River\"} -> ok: searched Wikipedia");
        java.util.List<String> urlsR4 =
                new java.util.ArrayList<String>();
        urlsR4.add(null);
        String sectionR4 = Agent.autoReadSection(
                Eyes.formatReadChunk(2400, rep('t', 5188), 9074));
        Agent.FitResult fitR4 = Agent.fitPrompt(wikiGoal, fitPage4,
                "[1] <a> \"x\"\n", histR4, urlsR4, sectionR4, 0);
        check("rung 4: the section's counts alone close it",
                Boolean.valueOf(fitR4.fits && fitR4.rungs == 4),
                Boolean.TRUE);
        check("rung 4: the text is gone, the accounting remains, "
                        + "the banner does not lie",
                Boolean.valueOf(!fitR4.prompt.contains(rep('t', 100))
                        && fitR4.prompt.contains("note: this page was "
                                + "auto-read (chars 2400-7588 of 9074)")
                        && !fitR4.prompt.contains(
                                "You are ON the target page")),
                Boolean.TRUE);

        // The ladder's end: an irreducible core (a giant goal —
        // never degradable) does not fit, and says so.
        Agent.FitResult fitEnd = Agent.fitPrompt(rep('g', 4000),
                fitPage4, "[1] <a> \"x\"\n", histR4, urlsR4, null, 0);
        check("ladder end: a non-fit reports itself, prompt null",
                Boolean.valueOf(!fitEnd.fits && fitEnd.prompt == null
                        && fitEnd.estTokens > Agent.PROMPT_TOKEN_BUDGET
                        && fitEnd.rungs == 4),
                Boolean.TRUE);

        // -- v2.9 item 3: adaptive read chunks ------------------------------

        check("dense: the threshold is 600 est tokens",
                Integer.valueOf(Agent.DENSE_PERCEPTION_TOKENS),
                Integer.valueOf(600));
        check("dense: the field perception weighs 614 — dense",
                Integer.valueOf(Agent.perceptionWeight(wxField,
                        wxRendered)),
                Integer.valueOf(614));
        Eyes.Perception wiki40 = new Eyes.Perception();
        wiki40.url = "https://en.wikipedia.org/wiki/Clinch_River";
        wiki40.title = "Clinch River - Wikipedia";
        wiki40.textPreview = "The Clinch River is a river that "
                + "flows through southwestern Virginia and Tennessee.";
        for (int i = 1; i <= 40; i++) {
            wiki40.elements.add(elx(i, "a", "Section " + i,
                    "https://en.wikipedia.org/wiki/Clinch_River#s" + i));
        }
        check("dense: a saturated Wikipedia perception weighs 546 "
                        + "— not dense",
                Integer.valueOf(Agent.perceptionWeight(wiki40,
                        wiki40.render(rainGoal))),
                Integer.valueOf(546));
        check("dense: the boundary is the threshold itself",
                Boolean.valueOf(Agent.densePerception(600)
                        && !Agent.densePerception(599)),
                Boolean.TRUE);
        check("dense: chunk sizes follow the weight",
                Boolean.valueOf(Agent.readChunkChars(614)
                                == Agent.DENSE_READ_CHUNK_CHARS
                        && Agent.readChunkChars(546)
                                == Eyes.READ_CHUNK_CHARS
                        && Agent.DENSE_READ_CHUNK_CHARS == 1200),
                Boolean.TRUE);
        check("dense: the read result's note is the shipped wording",
                Agent.DENSE_READ_NOTE,
                "(read smaller chunks on this dense page)");

        // -- v2.9: trail types and loop behaviour ---------------------------

        com.ghosthands.app.Trail v29Trail =
                new com.ghosthands.app.Trail(null);
        boolean v29TypesOk = true;
        try {
            v29Trail.record("fit", 1, com.ghosthands.app.Trail.fields(
                    "rungs", Long.valueOf(2),
                    "est_tokens", Long.valueOf(1600)));
            v29Trail.record("stale_stash", 1,
                    com.ghosthands.app.Trail.fields(
                            "result", "error: element 1 not on page"));
        } catch (IllegalArgumentException e) {
            v29TypesOk = false;
        }
        check("trail: the fit and stale_stash types are registered",
                Boolean.valueOf(v29TypesOk), Boolean.TRUE);

        // The stale-stash retry, end to end (a latent v2.7 defect
        // this registration fixes: the loop recorded "stale_stash"
        // into a registry that refused it, so the retry would have
        // thrown on device instead of re-deciding).
        final Eyes.Perception stalePage = new Eyes.Perception();
        stalePage.url = "https://example.org/";
        stalePage.title = "Example Domain";
        stalePage.textPreview = "Example text.";
        stalePage.elements.add(elx(1, "a", "Example page",
                "https://example.org/page"));
        Agent.Hands staleHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return stalePage;
            }
            @Override public String tap(int el) {
                return "error: element 1 not on page";
            }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String q) { return "ok"; }
            @Override public String read(int chunkChars) {
                return "error: no readable text found on this page";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] staleCalls = {0};
        ModelEngine staleEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                staleCalls[0]++;
                if (staleCalls[0] == 1) {
                    return "{\"action\":\"tap\",\"el\":1}";
                }
                return "{\"action\":\"done\",\"summary\":\"Opened "
                        + "the example page.\"}";
            }
        };
        com.ghosthands.app.Trail staleTrail =
                new com.ghosthands.app.Trail(null);
        Agent staleAgent = new Agent(staleHands, staleEngine,
                PolicyPacks.loadPack("standard"), staleTrail, stubUi,
                "q5_k_m");
        staleAgent.run("Open the example page");
        boolean sawStale = false;
        String staleStop = null;
        for (java.util.Map<String, Object> ev : staleTrail.events()) {
            if ("stale_stash".equals(String.valueOf(ev.get("type")))) {
                sawStale = true;
            }
            if ("stop".equals(String.valueOf(ev.get("type")))) {
                staleStop = String.valueOf(ev.get("reason"));
            }
        }
        check("stale retry: the run records the retry and finishes "
                        + "done",
                Boolean.valueOf(sawStale && "done".equals(staleStop)),
                Boolean.TRUE);

        // The honest dense stop: an irreducible goal never reaches
        // the engine — the stop is the fit's, not an engine error.
        com.ghosthands.app.Trail denseTrail =
                new com.ghosthands.app.Trail(null);
        Agent denseAgent = new Agent(stubHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), denseTrail, stubUi,
                "q5_k_m");
        denseAgent.run(rep('g', 4000));
        String denseReason = null;
        String denseSummary = null;
        boolean denseDecide = false;
        boolean denseSummaryEvent = false;
        for (java.util.Map<String, Object> ev : denseTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("decide".equals(t)) denseDecide = true;
            if ("stop".equals(t)) {
                denseReason = String.valueOf(ev.get("reason"));
                denseSummary = String.valueOf(ev.get("summary"));
            }
            if ("run_summary".equals(t)) denseSummaryEvent = true;
        }
        check("dense stop: reason budget, the fit's summary, a run "
                        + "summary, and no decide ever happened",
                Boolean.valueOf("budget".equals(denseReason)
                        && denseSummary != null
                        && denseSummary.contains(
                                "page too dense for the model's context")
                        && denseSummaryEvent && !denseDecide),
                Boolean.TRUE);

        // Adaptive chunks, end to end: the dense page's read is
        // served at 1200, the light page's at the standing 2400.
        final int[] denseChunkSeen = {-1};
        Agent.Hands denseHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return wxField;
            }
            @Override public String tap(int el) { return "ok"; }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String q) { return "ok"; }
            @Override public String read(int chunkChars) {
                denseChunkSeen[0] = chunkChars;
                return "ok: read chars 0-31 of 31; end of page text:\n"
                        + "Rain chance 10 percent tonight.";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] denseCalls = {0};
        ModelEngine denseEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                denseCalls[0]++;
                if (denseCalls[0] == 1) {
                    return "{\"action\":\"read\"}";
                }
                return "{\"action\":\"done\",\"summary\":\"Rain is "
                        + "expected today, about a 10% chance.\"}";
            }
        };
        Agent denseRunAgent = new Agent(denseHands, denseEngine,
                PolicyPacks.loadPack("standard"),
                new com.ghosthands.app.Trail(null), stubUi, "q5_k_m");
        denseRunAgent.run(rainGoal);
        check("dense run: the read was served at the dense chunk",
                Integer.valueOf(denseChunkSeen[0]),
                Integer.valueOf(Agent.DENSE_READ_CHUNK_CHARS));

        final int[] lightChunkSeen = {-1};
        Agent.Hands lightHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return stalePage;
            }
            @Override public String tap(int el) { return "ok"; }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String q) { return "ok"; }
            @Override public String read(int chunkChars) {
                lightChunkSeen[0] = chunkChars;
                return "ok: read chars 0-31 of 31; end of page text:\n"
                        + "The Clinch River is 337 miles long.";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] lightCalls = {0};
        ModelEngine lightEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                lightCalls[0]++;
                if (lightCalls[0] == 1) {
                    return "{\"action\":\"read\"}";
                }
                return "{\"action\":\"done\",\"summary\":\"The "
                        + "Clinch River is 337 miles long.\"}";
            }
        };
        Agent lightRunAgent = new Agent(lightHands, lightEngine,
                PolicyPacks.loadPack("standard"),
                new com.ghosthands.app.Trail(null), stubUi, "q5_k_m");
        lightRunAgent.run(wikiGoal);
        check("light run: the read was served at the standing chunk",
                Integer.valueOf(lightChunkSeen[0]),
                Integer.valueOf(Eyes.READ_CHUNK_CHARS));

        // =============================================================
        // v3.0 — the feature release: scratchpad, saved goals +
        // checks, voice goals, answer cards, finish notification.
        // =============================================================

        String rainGoal30 = "Find the forecast for Oakdale, Tennessee, "
                + "and tell me tonight's low temperature and the "
                + "chance of rain.";

        // -- v3.0 item 1: the Scratchpad --------------------------------

        Scratchpad pad = new Scratchpad();
        check("pad: an empty notebook renders no block",
                pad.render(), "");
        check("pad: an empty notebook stubs to nothing",
                pad.renderStubbed(), "");
        check("pad: blank text is refused as empty",
                pad.statusFor("   "), Scratchpad.EMPTY);
        check("pad: the first fact is added",
                pad.add("Tonight's low is 43 degrees",
                        "https://wx.example/hourly", "Hourly Weather"),
                Scratchpad.ADDED);
        check("pad: the same fact again is a duplicate",
                pad.statusFor("Tonight's low is 43 degrees"),
                Scratchpad.DUPLICATE);
        check("pad: a duplicate add changes nothing",
                pad.add("Tonight's low is 43 degrees",
                        "https://other.example/", "Other"),
                Scratchpad.DUPLICATE);
        check("pad: the notebook still holds one fact",
                Integer.valueOf(pad.size()), Integer.valueOf(1));
        check("pad: surrounding whitespace is the same fact",
                pad.statusFor("  Tonight's low is 43 degrees  "),
                Scratchpad.DUPLICATE);
        check("pad: a second fact lands",
                pad.add("Rain chance is 10 percent",
                        "https://wx.example/hourly", "Hourly Weather"),
                Scratchpad.ADDED);
        check("pad: the block names the count and the facts",
                Boolean.valueOf(pad.render().contains("Facts noted:")
                        && pad.render().contains(
                                "- Tonight's low is 43 degrees")
                        && pad.render().contains("(from:")),
                Boolean.TRUE);
        check("pad: the stub keeps the count, drops the texts",
                Boolean.valueOf(pad.renderStubbed().contains(
                                "Facts noted: 2")
                        && !pad.renderStubbed().contains("43 degrees")),
                Boolean.TRUE);
        check("pad: factsText is the bare evidence (no sources)",
                pad.factsText(),
                "Tonight's low is 43 degrees\nRain chance is 10 percent");

        Scratchpad capPad = new Scratchpad();
        boolean capOk = true;
        for (int i = 1; i <= Scratchpad.MAX_FACTS; i++) {
            if (!Scratchpad.ADDED.equals(capPad.add("fact number " + i,
                    "https://x.example/", "X"))) capOk = false;
        }
        check("pad: twelve facts fit", Boolean.valueOf(capOk),
                Boolean.TRUE);
        check("pad: the thirteenth is refused as full",
                capPad.statusFor("one fact too many"), Scratchpad.FULL);
        check("pad: a full notebook still renders its count",
                Boolean.valueOf(capPad.renderStubbed().contains(
                        "Facts noted: 12")),
                Boolean.TRUE);

        Scratchpad longPad = new Scratchpad();
        StringBuilder longFact = new StringBuilder();
        for (int i = 0; i < 25; i++) longFact.append("0123456789");
        longPad.add(longFact.toString(), "https://x.example/", "X");
        check("pad: an over-long fact is truncated to the cap",
                Integer.valueOf(longPad.facts().get(0).text.length()),
                Integer.valueOf(Scratchpad.MAX_FACT_CHARS));
        check("pad: the truncation carries the ellipsis marker",
                Boolean.valueOf(longPad.facts().get(0).text
                        .endsWith(Scratchpad.TRUNC_MARKER)),
                Boolean.TRUE);

        // -- v3.0 item 1: the note action, end to end through the
        //    parser and every policy pack -------------------------------

        GhAction noteAction = PlannerParse.extract(
                "{\"action\":\"note\",\"fact\":\"Low is 43 degrees\"}");
        check("note: the parser keeps a note with a fact",
                Boolean.valueOf(noteAction != null
                        && "note".equals(noteAction.action)
                        && "Low is 43 degrees".equals(noteAction.fact)),
                Boolean.TRUE);
        check("note: a note without a fact is rejected",
                PlannerParse.extract("{\"action\":\"note\"}"), null);
        check("note: a note with a blank fact is rejected",
                PlannerParse.extract(
                        "{\"action\":\"note\",\"fact\":\"  \"}"), null);
        String[] packIds = {"readonly", "standard", "strict", "seatbelt"};
        for (int i = 0; i < packIds.length; i++) {
            Governor g = new Governor(
                    PolicyPacks.loadPack(packIds[i]).policy);
            check("note: classified readonly under " + packIds[i],
                    g.classify(noteAction, null, ""), "readonly");
            check("note: allowed under " + packIds[i],
                    g.evaluate(noteAction, null, "").outcome, "allow");
        }

        // -- v3.0 item 1: done grounding against evidence --------------

        GhAction doneInvented = new GhAction("done");
        doneInvented.summary = "Tonight's low is 99 degrees";
        GhAction doneNoted = new GhAction("done");
        doneNoted.summary = "Tonight's low is 43 degrees";
        GhAction doneHopeless = new GhAction("done");
        doneHopeless.summary = "I could not find the forecast";
        String notedEvidence = "Facts noted:\n"
                + "- Tonight's low is 43 degrees\n"
                + "- Rain chance is 10 percent";
        String otherEvidence = "the page mentioned 51 degrees at noon";
        check("grounding: a number from the notes grounds the done",
                RelevanceGuard.doneGroundingReason(
                        doneNoted, rainGoal30, 0, notedEvidence), null);
        check("grounding: a number from read text grounds the done",
                RelevanceGuard.doneGroundingReason(doneNoted, rainGoal30,
                        0, "step 2 read: the low was 43 degrees"),
                null);
        check("grounding: an invented number is corrected",
                Boolean.valueOf(RelevanceGuard.doneGroundingReason(
                        doneInvented, rainGoal30, 0, notedEvidence) != null),
                Boolean.TRUE);
        check("grounding: evidence without the number corrects too",
                Boolean.valueOf(RelevanceGuard.doneGroundingReason(
                        doneNoted, rainGoal30, 0, otherEvidence) != null),
                Boolean.TRUE);
        check("grounding: saying so plainly needs no digits",
                RelevanceGuard.doneGroundingReason(
                        doneHopeless, rainGoal30, 0, otherEvidence), null);
        check("grounding: null evidence keeps the v2.1 shape rule",
                RelevanceGuard.doneGroundingReason(
                        doneInvented, rainGoal30, 0, null), null);
        check("grounding: the cap still silences the guard",
                RelevanceGuard.doneGroundingReason(doneInvented,
                        rainGoal30, 2, notedEvidence), null);
        check("grounding: grouped and decimal numbers tokenize whole",
                Boolean.valueOf(RelevanceGuard.numberTokens(
                                "1,117 files, 43.5 degrees, 7 days")
                        .contains("1117")
                        && RelevanceGuard.numberTokens(
                                "1,117 files, 43.5 degrees, 7 days")
                        .contains("43.5")),
                Boolean.TRUE);
        java.util.List<String> evHist = new java.util.ArrayList<String>();
        evHist.add("step 1: {\"action\":\"read\"} -> the low was 43");
        Scratchpad evPad = new Scratchpad();
        evPad.add("Rain chance is 10 percent",
                "https://wx.example/", "Wx");
        String evidence = Agent.groundingEvidence(evHist, null, evPad);
        check("grounding: the loop's evidence is history + notes",
                Boolean.valueOf(evidence.contains("the low was 43")
                        && evidence.contains("Rain chance is 10 percent")),
                Boolean.TRUE);

        // -- v3.0 item 1: the FACTS block in the prompt ------------------

        Eyes.Perception notePage = new Eyes.Perception();
        notePage.url = "https://wx.example/hourly";
        notePage.title = "Hourly Weather";
        java.util.List<String> noHist30 = new java.util.ArrayList<String>();
        String promptNoFacts = Agent.buildPrompt(rainGoal30, notePage,
                "[1] <a> \"x\"\n", noHist30, null, 0, "");
        check("prompt: an empty notebook is byte-identical to v2.9",
                promptNoFacts, Agent.buildPrompt(rainGoal30, notePage,
                        "[1] <a> \"x\"\n", noHist30));
        Scratchpad onePad = new Scratchpad();
        onePad.add("Tonight's low is 43 degrees",
                "https://wx.example/hourly", "Hourly Weather");
        String promptFacts = Agent.buildPrompt(rainGoal30, notePage,
                "[1] <a> \"x\"\n", noHist30, null, 0, onePad.render());
        check("prompt: the FACTS block rides with the facts",
                Boolean.valueOf(promptFacts.contains("Facts noted:")
                        && promptFacts.contains(
                                "Tonight's low is 43 degrees")),
                Boolean.TRUE);

        // -- v3.0 item 1: the ladder stubs facts only at the last rung ---
        // The overage lives in the history (fat tap entries no rung
        // but the facts stub can shrink past: rung 1 finds no reads,
        // the preview is rung-2-sized already, the map is one
        // element, there is no section for rung 4).

        Eyes.Perception densePage = new Eyes.Perception();
        densePage.url = "https://wx.example/hourly";
        densePage.title = "Hourly Weather";
        densePage.textPreview = rep('p', 240);
        densePage.elements.add(elx(1, "a", "x", "https://wx.example/x"));
        java.util.List<String> tapHist = new java.util.ArrayList<String>();
        java.util.List<String> tapUrls = new java.util.ArrayList<String>();
        for (int i = 1; i <= 8; i++) {
            tapHist.add("step " + i + ": {\"action\":\"tap\",\"el\":1}"
                    + " -> ok: " + rep('h', 380));
            tapUrls.add(null);
        }
        Scratchpad fatPad = new Scratchpad();
        for (int i = 1; i <= Scratchpad.MAX_FACTS; i++) {
            StringBuilder fb = new StringBuilder();
            fb.append("fact ").append(i).append(' ');
            while (fb.length() < 196) fb.append("detail ");
            fatPad.add(fb.toString().trim(),
                    "https://wx.example/hourly",
                    "Hourly Weather Forecast for Oakdale Tennessee");
        }
        Agent.FitResult fitR5 = Agent.fitPrompt(rainGoal30, densePage,
                "[1] <a> \"x\"\n", tapHist, tapUrls, null, 0,
                fatPad.render(), fatPad.renderStubbed());
        System.out.println("FIT-MEASUREMENT rung-5 fixture: fit rungs "
                + fitR5.rungs + ", fit est " + fitR5.estTokens
                + ", factsStubbed " + fitR5.factsStubbed);
        check("rung 5: stubbing the facts closes what nothing else "
                        + "could",
                Boolean.valueOf(fitR5.fits && fitR5.rungs == 5
                        && fitR5.factsStubbed),
                Boolean.TRUE);
        check("rung 5: the fitted prompt carries the stub, not the "
                        + "fact texts",
                Boolean.valueOf(fitR5.prompt.contains(
                                "texts omitted to fit the prompt budget")
                        && !fitR5.prompt.contains("fact 7")),
                Boolean.TRUE);

        // The same facts on a rung-1 composition survive intact.
        java.util.List<String> readHist = new java.util.ArrayList<String>();
        java.util.List<String> readUrls = new java.util.ArrayList<String>();
        for (int i = 1; i <= 3; i++) {
            readHist.add("step " + i + ": {\"action\":\"read\"} -> "
                    + Eyes.formatReadChunk(0, rep('r', 2400), 11604));
            readUrls.add(densePage.url);
        }
        Agent.FitResult fitEarly = Agent.fitPrompt(rainGoal30, densePage,
                "[1] <a> \"x\"\n", readHist, readUrls, null, 0,
                onePad.render(), onePad.renderStubbed());
        check("ladder: facts survive whole when an early rung closes",
                Boolean.valueOf(fitEarly.fits && fitEarly.rungs == 1
                        && !fitEarly.factsStubbed
                        && fitEarly.prompt.contains(
                                "Tonight's low is 43 degrees")),
                Boolean.TRUE);

        // Nothing fits: with facts aboard the ladder reports rung 5.
        Agent.FitResult fitNever = Agent.fitPrompt(rep('g', 6000),
                densePage, "[1] <a> \"x\"\n", tapHist, tapUrls, null, 0,
                onePad.render(), onePad.renderStubbed());
        check("ladder: a hopeless prompt with facts stops at rung 5",
                Boolean.valueOf(!fitNever.fits && fitNever.rungs == 5
                        && fitNever.factsStubbed
                        && fitNever.prompt == null),
                Boolean.TRUE);

        // -- v3.0 item 1: a loop run that notes, overclaims, corrects ----
        // The scripted planner notes the low, then tries to answer
        // with an invented 99 — the evidence guard must correct it
        // (under the v2.1 shape rule 99 would have passed) — then
        // answers with the noted 43 and the run completes.

        final int[] noteCalls = {0};
        ModelEngine noteEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                noteCalls[0]++;
                if (noteCalls[0] == 1) {
                    return "{\"action\":\"note\",\"fact\":\"Tonight's "
                            + "low is 43 degrees, rain chance 10 percent\"}";
                }
                if (noteCalls[0] == 2) {
                    return "{\"action\":\"done\",\"summary\":\"Tonight's "
                            + "low is 99 degrees\"}";
                }
                return "{\"action\":\"done\",\"summary\":\"Tonight's "
                        + "low is 43 degrees\"}";
            }
        };
        com.ghosthands.app.Trail noteTrail =
                new com.ghosthands.app.Trail(null);
        Agent noteAgent = new Agent(stubHands, noteEngine,
                PolicyPacks.loadPack("standard"), noteTrail, stubUi,
                "q5_k_m");
        noteAgent.run(rainGoal30);
        boolean sawRun = false;
        boolean sawNote = false;
        String noteStop = null;
        for (java.util.Map<String, Object> ev : noteTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("run".equals(t)) {
                sawRun = rainGoal30.equals(String.valueOf(ev.get("goal")));
            }
            if ("note".equals(t)) {
                sawNote = "added".equals(String.valueOf(ev.get("status")));
            }
            if ("stop".equals(t)) {
                noteStop = String.valueOf(ev.get("reason"));
            }
        }
        check("loop: the run event opens the trail with the goal",
                Boolean.valueOf(sawRun), Boolean.TRUE);
        check("loop: the note landed in the trail as added",
                Boolean.valueOf(sawNote), Boolean.TRUE);
        check("loop: the invented number was corrected, the noted "
                        + "one completed the run",
                Boolean.valueOf(noteCalls[0] == 3
                        && "done".equals(noteStop)),
                Boolean.TRUE);
        check("loop: the notebook holds the noted fact",
                Integer.valueOf(noteAgent.scratchpad().size()),
                Integer.valueOf(1));
        AnswerCard loopCard = AnswerCard.fromEvents(
                noteTrail.events(), rainGoal30);
        check("loop: the run's own trail rebuilds its answer card",
                Boolean.valueOf(loopCard != null
                        && "done".equals(loopCard.outcome)
                        && loopCard.facts.size() == 1
                        && loopCard.outcomeLine.contains("43 degrees")),
                Boolean.TRUE);

        // The registry sweep: every registered type records without
        // throwing — the v2.9 latent defect was a loop emitting an
        // unregistered type; this pins the whole registry, run and
        // note included.
        com.ghosthands.app.Trail regTrail =
                new com.ghosthands.app.Trail(null);
        boolean registryOk = true;
        for (int i = 0; i < com.ghosthands.app.Trail.EVENT_TYPES.length;
                i++) {
            try {
                regTrail.record(com.ghosthands.app.Trail.EVENT_TYPES[i],
                        0, com.ghosthands.app.Trail.fields("probe", "v3.0"));
            } catch (Exception e) {
                registryOk = false;
            }
        }
        check("trail: every registered event type records",
                Boolean.valueOf(registryOk), Boolean.TRUE);
        boolean registryHasNew = false;
        for (int i = 0; i < com.ghosthands.app.Trail.EVENT_TYPES.length;
                i++) {
            if ("note".equals(com.ghosthands.app.Trail.EVENT_TYPES[i])) {
                registryHasNew = true;
            }
        }
        check("trail: the note type is registered",
                Boolean.valueOf(registryHasNew), Boolean.TRUE);

        // -- v3.0 item 2: SavedGoals -------------------------------------

        SavedGoals store30 = new SavedGoals();
        check("saved: junk parses to an empty store",
                Integer.valueOf(SavedGoals.parse("not json").size()),
                Integer.valueOf(0));
        check("saved: null parses to an empty store",
                Integer.valueOf(SavedGoals.parse(null).size()),
                Integer.valueOf(0));
        store30.put(new SavedGoals.SavedGoal("Weather check",
                rainGoal30, "standard", SavedGoals.minutesOfDay(7, 30)));
        store30.put(new SavedGoals.SavedGoal("River facts",
                wikiGoal, "readonly", SavedGoals.NO_SCHEDULE));
        check("saved: two goals stored",
                Integer.valueOf(store30.size()), Integer.valueOf(2));
        SavedGoals roundTrip = SavedGoals.parse(store30.serialize());
        check("saved: the round trip keeps both goals",
                Integer.valueOf(roundTrip.size()), Integer.valueOf(2));
        check("saved: the schedule survives the round trip",
                Integer.valueOf(roundTrip.find("Weather check").schedMinutes),
                Integer.valueOf(450));
        check("saved: the pack survives the round trip",
                roundTrip.find("River facts").packId, "readonly");
        store30.put(new SavedGoals.SavedGoal("Weather check",
                "a shorter goal", "strict", SavedGoals.NO_SCHEDULE));
        check("saved: saving the same name replaces, never duplicates",
                Integer.valueOf(store30.size()), Integer.valueOf(2));
        check("saved: the replacement carries the new text",
                store30.find("Weather check").goal, "a shorter goal");
        check("saved: removing a goal works",
                Boolean.valueOf(store30.remove("River facts")),
                Boolean.TRUE);
        check("saved: removing a ghost fails cleanly",
                Boolean.valueOf(store30.remove("River facts")),
                Boolean.FALSE);

        java.util.TimeZone gmt = java.util.TimeZone.getTimeZone("GMT");
        java.util.Calendar base = java.util.Calendar.getInstance(gmt);
        base.set(2026, 9, 25, 6, 30, 0);
        base.set(java.util.Calendar.MILLISECOND, 0);
        long nowMs = base.getTimeInMillis();
        java.util.Calendar expect = java.util.Calendar.getInstance(gmt);
        expect.set(2026, 9, 25, 7, 0, 0);
        expect.set(java.util.Calendar.MILLISECOND, 0);
        check("schedule: a time still ahead fires today",
                Long.valueOf(SavedGoals.nextFireMillis(nowMs, 420, gmt)),
                Long.valueOf(expect.getTimeInMillis()));
        expect.set(2026, 9, 26, 6, 0, 0);
        check("schedule: a time already past fires tomorrow",
                Long.valueOf(SavedGoals.nextFireMillis(nowMs, 360, gmt)),
                Long.valueOf(expect.getTimeInMillis()));
        expect.set(2026, 9, 26, 6, 30, 0);
        check("schedule: arming at the exact minute means tomorrow",
                Long.valueOf(SavedGoals.nextFireMillis(nowMs, 390, gmt)),
                Long.valueOf(expect.getTimeInMillis()));
        check("schedule: the labels read as a user expects",
                Boolean.valueOf("07:05".equals(SavedGoals.timeLabel(425))
                        && "daily 07:30".equals(SavedGoals.scheduleLabel(
                                new SavedGoals.SavedGoal("n", "g",
                                        "standard", 450)))
                        && "".equals(SavedGoals.scheduleLabel(
                                new SavedGoals.SavedGoal("n", "g",
                                        "standard",
                                        SavedGoals.NO_SCHEDULE)))),
                Boolean.TRUE);

        // -- v3.0 item 3: VoiceGoal ---------------------------------------

        check("voice: nothing heard is no goal",
                VoiceGoal.pickHeard(null), null);
        java.util.List<String> heardNone = new java.util.ArrayList<String>();
        heardNone.add("");
        heardNone.add("   ");
        check("voice: blank candidates are no goal",
                VoiceGoal.pickHeard(heardNone), null);
        java.util.List<String> heardSome = new java.util.ArrayList<String>();
        heardSome.add("  ");
        heardSome.add("  find the weather in Oakdale  ");
        heardSome.add("find something else");
        check("voice: the first usable candidate wins, trimmed",
                VoiceGoal.pickHeard(heardSome),
                "find the weather in Oakdale");
        check("voice: the labels cover unavailable/listening/ready",
                Boolean.valueOf("Voice n/a".equals(
                                VoiceGoal.buttonLabel(false, false))
                        && "Listening…".equals(
                                VoiceGoal.buttonLabel(true, true))
                        && "🎤 Voice".equals(
                                VoiceGoal.buttonLabel(true, false))),
                Boolean.TRUE);
        check("voice: the button enables only when ready",
                Boolean.valueOf(VoiceGoal.buttonEnabled(true, false)
                        && !VoiceGoal.buttonEnabled(true, true)
                        && !VoiceGoal.buttonEnabled(false, false)),
                Boolean.TRUE);

        // -- v3.0 item 4: AnswerCard against the week's real shapes -------

        java.util.List<java.util.Map<String, Object>> clinchEv =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        clinchEv.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", wikiGoal));
        clinchEv.add(com.ghosthands.app.Trail.fields(
                "type", "perceive",
                "url", "https://en.wikipedia.org/wiki/Clinch_River",
                "title", "Clinch River - Wikipedia"));
        clinchEv.add(com.ghosthands.app.Trail.fields(
                "type", "note", "status", "added",
                "fact", "The Clinch River is 337 miles long",
                "url", "https://en.wikipedia.org/wiki/Clinch_River",
                "title", "Clinch River - Wikipedia"));
        clinchEv.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "done",
                "summary", "The Clinch River is 337 miles long and is "
                        + "dammed by Norris Dam and Melton Hill Dam."));
        clinchEv.add(com.ghosthands.app.Trail.fields(
                "type", "run_summary", "steps", Long.valueOf(10),
                "seconds", Long.valueOf(95),
                "reuse_percent", Long.valueOf(78)));
        AnswerCard clinchCard = AnswerCard.fromEvents(clinchEv, null);
        check("card: the Clinch run rebuilds as a done card",
                Boolean.valueOf(clinchCard != null
                        && "done".equals(clinchCard.outcome)
                        && clinchCard.outcomeLine.equals(
                                "The Clinch River is 337 miles long "
                                + "and is dammed by Norris Dam and "
                                + "Melton Hill Dam.")
                        && "Clinch River - Wikipedia".equals(
                                clinchCard.sourceTitle)
                        && clinchCard.steps == 10
                        && clinchCard.seconds == 95
                        && clinchCard.reusePercent == 78
                        && clinchCard.facts.size() == 1),
                Boolean.TRUE);
        check("card: the card text carries goal, answer, source, facts",
                Boolean.valueOf(clinchCard.cardText().contains(wikiGoal)
                        && clinchCard.cardText().contains("337 miles")
                        && clinchCard.cardText().contains(
                                "https://en.wikipedia.org/wiki/Clinch_River")
                        && clinchCard.cardText().contains("Facts noted:")
                        && clinchCard.cardText().contains(
                                "- The Clinch River is 337 miles long")),
                Boolean.TRUE);
        check("card: the share text is the plain version with a date",
                Boolean.valueOf(clinchCard.shareText("2026-10-25 02:30")
                        .startsWith("Ghost Hands — 2026-10-25 02:30")
                        && clinchCard.shareText("2026-10-25 02:30")
                        .contains("Answer: The Clinch River is 337")
                        && clinchCard.shareText("2026-10-25 02:30")
                        .contains("Norris Dam")),
                Boolean.TRUE);
        check("card: the notification line is the outcome line itself",
                clinchCard.notificationLine(),
                "The Clinch River is 337 miles long and is dammed by "
                        + "Norris Dam and Melton Hill Dam.");

        java.util.List<java.util.Map<String, Object>> wxErr =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        wxErr.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", rainGoal30));
        wxErr.add(com.ghosthands.app.Trail.fields(
                "type", "perceive",
                "url", "https://html.duckduckgo.com/html/?q=oakdale",
                "title", "DuckDuckGo"));
        wxErr.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "error",
                "summary", "model engine error: ERROR: prompt too "
                        + "long for context"));
        wxErr.add(com.ghosthands.app.Trail.fields(
                "type", "run_summary", "steps", Long.valueOf(5),
                "seconds", Long.valueOf(61),
                "reuse_percent", Long.valueOf(40)));
        AnswerCard wxCard = AnswerCard.fromEvents(wxErr, null);
        check("card: the v2.8 weather error stop reads as an error",
                Boolean.valueOf(wxCard != null
                        && "error".equals(wxCard.outcome)
                        && wxCard.outcomeLine.equals(
                                "Error — model engine error: ERROR: "
                                + "prompt too long for context")
                        && wxCard.facts.isEmpty()),
                Boolean.TRUE);

        java.util.List<java.util.Map<String, Object>> stuckEv =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        stuckEv.add(com.ghosthands.app.Trail.fields(
                "type", "perceive", "url", "https://html.duckduckgo.com/",
                "title", "DuckDuckGo"));
        stuckEv.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "stuck",
                "summary", "element map unchanged for 3 consecutive "
                        + "steps"));
        AnswerCard stuckCard = AnswerCard.fromEvents(stuckEv,
                "the fallback goal");
        check("card: a stuck stop reads plainly, goal falls back",
                Boolean.valueOf(stuckCard != null
                        && stuckCard.outcomeLine.equals(
                                "Got stuck — element map unchanged for "
                                + "3 consecutive steps")
                        && "the fallback goal".equals(stuckCard.goal)
                        && !stuckCard.hasStats),
                Boolean.TRUE);

        java.util.List<java.util.Map<String, Object>> noStop =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        noStop.add(com.ghosthands.app.Trail.fields(
                "type", "perceive", "url", "https://x.example/",
                "title", "X"));
        check("card: a trail with no stop makes no card",
                AnswerCard.fromEvents(noStop, "g"), null);

        // The past-runs path: the same events as trail text rebuild
        // the same card.
        com.ghosthands.app.Trail pastTrail =
                new com.ghosthands.app.Trail(null);
        for (int i = 0; i < clinchEv.size(); i++) {
            java.util.Map<String, Object> src = clinchEv.get(i);
            java.util.Map<String, Object> copy =
                    new java.util.LinkedHashMap<String, Object>(src);
            String type = String.valueOf(copy.remove("type"));
            pastTrail.record(type, 0, copy);
        }
        AnswerCard pastCard = AnswerCard.fromTrailText(
                pastTrail.toJsonLines(), null);
        check("card: trail text rebuilds the same card as the events",
                Boolean.valueOf(pastCard != null
                        && pastCard.cardText().equals(
                                AnswerCard.fromEvents(
                                        pastTrail.events(), null)
                                        .cardText())),
                Boolean.TRUE);

        // =============================================================
        // v3.1 — break the loop: the repeat-action guard, the
        // answer card's findings fallback, the scratchpad nudge.
        // Field evidence: trail-20261025-025203 (the Hawaii fares
        // run) — tap "Google Search", read the fares, the same
        // tap proposed again, and a card whose answer line was
        // the bare error.
        // =============================================================

        // -- v3.1 item 1: repeat-action keys (pure) ---------------------

        ElementInfo gsBtn = elx(1, "button", "Google Search", null);
        gsBtn.type = "submit";
        GhAction tapGs = new GhAction("tap");
        tapGs.el = 1;
        String gsKey = RelevanceGuard.repeatActionKey(tapGs, gsBtn);
        check("repeat: a submit button keys on name+tag+type",
                Boolean.valueOf(gsKey != null
                        && gsKey.startsWith("tap|")), Boolean.TRUE);
        ElementInfo gsBtnTwin = elx(9, "button", "Google Search", null);
        gsBtnTwin.type = "submit";
        check("repeat: the same identity on another instance is "
                        + "the same key (numbers do not count)",
                RelevanceGuard.repeatActionKey(tapGs, gsBtnTwin), gsKey);
        ElementInfo gsRenamed = elx(1, "button", "Search Google", null);
        gsRenamed.type = "submit";
        check("repeat: an element whose identity genuinely changed "
                        + "keys differently",
                Boolean.valueOf(!gsKey.equals(
                        RelevanceGuard.repeatActionKey(tapGs,
                                gsRenamed))),
                Boolean.TRUE);
        GhAction typeGs = new GhAction("type");
        typeGs.el = 1;
        typeGs.text = "Oakdale TN";
        check("repeat: tap and type into the same element are "
                        + "different keys",
                Boolean.valueOf(!gsKey.equals(
                        RelevanceGuard.repeatActionKey(typeGs, gsBtn))),
                Boolean.TRUE);
        ElementInfo flightA = elx(3, "a", "Flights",
                "https://a.example/flights");
        ElementInfo flightB = elx(4, "a", "Flights",
                "https://b.example/flights");
        GhAction tapFlight = new GhAction("tap");
        check("repeat: same visible name, different hrefs — "
                        + "different keys",
                Boolean.valueOf(!RelevanceGuard.repeatActionKey(
                                tapFlight, flightA)
                        .equals(RelevanceGuard.repeatActionKey(
                                tapFlight, flightB))),
                Boolean.TRUE);
        ElementInfo flightSpan = elx(7, "span", "Flights",
                "https://a.example/flights");
        check("repeat: name+href is the identity when an href "
                        + "exists (tag does not count)",
                RelevanceGuard.repeatActionKey(tapFlight, flightSpan),
                RelevanceGuard.repeatActionKey(tapFlight, flightA));
        GhAction gotoA = new GhAction("goto");
        gotoA.url = "https://a.example/flights";
        check("repeat: a goto keys on its URL",
                RelevanceGuard.repeatActionKey(gotoA, null),
                "goto|https://a.example/flights");
        check("repeat: an unresolvable element has no key",
                RelevanceGuard.repeatActionKey(tapGs, null), null);
        ElementInfo anonEl = elx(5, "div", "", null);
        check("repeat: an element with no name, href or type has "
                        + "no provable identity",
                RelevanceGuard.repeatActionKey(tapGs, anonEl), null);
        String[] exemptKinds = {"search", "read", "scroll", "back",
                "wait", "note", "done", "stuck"};
        boolean exemptOk = true;
        for (int i = 0; i < exemptKinds.length; i++) {
            GhAction ex = new GhAction(exemptKinds[i]);
            ex.el = 1;
            if (RelevanceGuard.repeatActionKey(ex, gsBtn) != null) {
                exemptOk = false;
            }
        }
        check("repeat: search/read/scroll/back/wait/note/done/"
                        + "stuck are exempt (null keys)",
                Boolean.valueOf(exemptOk), Boolean.TRUE);

        // -- v3.1 item 1: the correction predicate (pure) ---------------

        java.util.Set<String> ledger =
                new java.util.LinkedHashSet<String>();
        check("repeat: an action never executed is not corrected",
                RelevanceGuard.repeatActionReason(tapGs, gsBtn,
                        ledger, 0), null);
        ledger.add(gsKey);
        String gsReason = RelevanceGuard.repeatActionReason(
                tapGs, gsBtn, ledger, 0);
        check("repeat: an executed tap proposed again is corrected, "
                        + "naming the tap",
                Boolean.valueOf(gsReason != null
                        && gsReason.contains("already tapped")
                        && gsReason.contains("\"Google Search\"")
                        && gsReason.contains("note")),
                Boolean.TRUE);
        check("repeat: the second correction still fires",
                Boolean.valueOf(RelevanceGuard.repeatActionReason(
                        tapGs, gsBtn, ledger, 1) != null),
                Boolean.TRUE);
        check("repeat: the cap silences the guard for that key",
                RelevanceGuard.repeatActionReason(tapGs, gsBtn,
                        ledger, RelevanceGuard
                                .REPEAT_ACTION_CORRECTION_CAP),
                null);
        check("repeat: the other-href twin is not corrected by the "
                        + "first link's execution",
                RelevanceGuard.repeatActionReason(tapFlight, flightB,
                        ledger, 0), null);
        java.util.Set<String> gotoLedger =
                new java.util.LinkedHashSet<String>();
        gotoLedger.add(RelevanceGuard.repeatActionKey(gotoA, null));
        check("repeat: a repeated goto is corrected, naming the URL",
                Boolean.valueOf(RelevanceGuard.repeatActionReason(
                        gotoA, null, gotoLedger, 0) != null
                        && RelevanceGuard.repeatActionReason(
                                gotoA, null, gotoLedger, 0)
                                .contains("already went to "
                                        + "https://a.example/flights")),
                Boolean.TRUE);
        check("repeat: a type into an executed field is corrected",
                Boolean.valueOf(RelevanceGuard.repeatActionReason(
                        typeGs, gsBtn,
                        new java.util.LinkedHashSet<String>(
                                java.util.Collections.singleton(
                                        RelevanceGuard.repeatActionKey(
                                                typeGs, gsBtn))), 0)
                        .contains("already typed into")),
                Boolean.TRUE);

        // -- v3.1 items 1+3: the field pattern, end to end --------------
        // The Hawaii run's shape on stub machinery: tap the search
        // button (ask -> approved -> ok), read the fares, propose
        // the SAME tap again (the guard must correct it — the tap
        // must NOT execute a second time), then finish with a done
        // whose numbers come from the read evidence. The read's
        // history entry must carry the one-time note hint; prompts
        // before the read must not.

        final Eyes.Perception faresPage = new Eyes.Perception();
        faresPage.url = "https://www.google.com/search?q=plane+"
                + "tickets+to+Hawaii";
        faresPage.title = "plane tickets to Hawaii - Google Search";
        faresPage.textPreview = "Search Results Flights to Hawaii "
                + "Honolulu $734 Kahului $698";
        ElementInfo faresBtn = elx(1, "button", "Google Search", null);
        faresBtn.type = "submit";
        faresPage.elements.add(faresBtn);
        faresPage.elements.add(elx(2, "a", "Honolulu flights",
                "https://www.google.com/travel/flights"));
        final int[] faresTapCalls = {0};
        Agent.Hands faresHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return faresPage;
            }
            @Override public String tap(int el) {
                faresTapCalls[0]++;
                return "ok: tapped #" + el;
            }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String query) {
                return "ok";
            }
            @Override public String read(int chunkChars) {
                return Eyes.formatReadChunk(0,
                        "Search Results\n\nFlights to Hawaii\n\n"
                        + "Honolulu\n$734\n\nKahului\n$698\n\n"
                        + "Kailua-Kona\n$641",
                        4029, "full page");
            }
            @Override public String reload(String u) { return "ok"; }
        };
        Agent.Ui approvingUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                return true;
            }
            @Override public boolean stopRequested() { return false; }
            // v3.3 fixture: as stubUi — no co-pilot on this
            // "screen", so loop scenarios keep their old endings.
            @Override public boolean foreground() { return false; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) { return null; }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) { return false; }
        };
        final java.util.List<String> faresPrompts =
                new java.util.ArrayList<String>();
        final int[] faresCalls = {0};
        ModelEngine faresEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                faresPrompts.add(prompt);
                faresCalls[0]++;
                if (faresCalls[0] == 1) {
                    return "{\"action\":\"tap\",\"el\":1}";
                }
                if (faresCalls[0] == 2) {
                    return "{\"action\":\"read\"}";
                }
                if (faresCalls[0] == 3) {
                    return "{\"action\":\"tap\",\"el\":1}";
                }
                return "{\"action\":\"done\",\"summary\":\"Flights "
                        + "to Hawaii: Honolulu $734, Kahului $698.\"}";
            }
        };
        com.ghosthands.app.Trail faresTrail =
                new com.ghosthands.app.Trail(null);
        Agent faresAgent = new Agent(faresHands, faresEngine,
                PolicyPacks.loadPack("standard"), faresTrail,
                approvingUi, "q5_k_m");
        String faresGoal = "Search Google for plane tickets to "
                + "Hawaii and tell me the prices";
        faresAgent.run(faresGoal);
        boolean sawRepeatGuard = false;
        String faresStop = null;
        for (java.util.Map<String, Object> ev : faresTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("guard".equals(t) && String.valueOf(ev.get("reason"))
                    .contains("already tapped")) {
                sawRepeatGuard = true;
            }
            if ("stop".equals(t)) {
                faresStop = String.valueOf(ev.get("reason"));
            }
        }
        check("loop: the repeated tap was corrected, not executed "
                        + "(the button was tapped exactly once)",
                Boolean.valueOf(faresTapCalls[0] == 1
                        && sawRepeatGuard),
                Boolean.TRUE);
        check("loop: after the correction the planner finished with "
                        + "the grounded fares done",
                Boolean.valueOf(faresCalls[0] == 4
                        && "done".equals(faresStop)),
                Boolean.TRUE);
        check("nudge: no prompt before the first read carries the "
                        + "hint",
                Boolean.valueOf(!faresPrompts.get(0)
                                .contains("tip: when you find a fact")
                        && !faresPrompts.get(1)
                                .contains("tip: when you find a fact")),
                Boolean.TRUE);
        check("nudge: the first prompt after the read carries the "
                        + "hint, exactly once",
                Boolean.valueOf(faresPrompts.get(2)
                                .contains("tip: when you find a fact")
                        && faresPrompts.get(2).indexOf(
                                "tip: when you find a fact")
                        == faresPrompts.get(2).lastIndexOf(
                                "tip: when you find a fact")),
                Boolean.TRUE);
        check("nudge: later reads never re-append it (still one "
                        + "hint in the final prompt)",
                Boolean.valueOf(faresPrompts.get(3)
                                .indexOf("tip: when you find a fact")
                        >= 0
                        && faresPrompts.get(3).indexOf(
                                "tip: when you find a fact")
                        == faresPrompts.get(3).lastIndexOf(
                                "tip: when you find a fact")),
                Boolean.TRUE);

        // The cap, end to end: two corrections for the key, then
        // the same proposal executes again (the stuck machinery
        // owns it from there).
        final int[] capTapCalls = {0};
        Agent.Hands capHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return faresPage;
            }
            @Override public String tap(int el) {
                capTapCalls[0]++;
                return "ok: tapped #" + el;
            }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String query) {
                return "ok";
            }
            @Override public String read(int chunkChars) {
                return "error: no readable text found on this page";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] capCalls = {0};
        ModelEngine capEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                capCalls[0]++;
                if (capCalls[0] <= 4) {
                    return "{\"action\":\"tap\",\"el\":1}";
                }
                return "{\"action\":\"done\",\"summary\":\"Opened "
                        + "the search results.\"}";
            }
        };
        com.ghosthands.app.Trail capTrail =
                new com.ghosthands.app.Trail(null);
        Agent capAgent = new Agent(capHands, capEngine,
                PolicyPacks.loadPack("standard"), capTrail,
                approvingUi, "q5_k_m");
        capAgent.run("search Google for plane tickets to Hawaii");
        int capGuards = 0;
        String capStop = null;
        for (java.util.Map<String, Object> ev : capTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("guard".equals(t) && String.valueOf(ev.get("reason"))
                    .contains("repeat action corrected")) {
                capGuards++;
            }
            if ("stop".equals(t)) {
                capStop = String.valueOf(ev.get("reason"));
            }
        }
        check("loop: two corrections per key, then the repeat "
                        + "executes (tapped twice in all)",
                Boolean.valueOf(capGuards == 2
                        && capTapCalls[0] == 2
                        && "done".equals(capStop)),
                Boolean.TRUE);

        // A run that never reads never sees the hint.
        final java.util.List<String> noReadPrompts =
                new java.util.ArrayList<String>();
        final int[] noReadCalls = {0};
        ModelEngine noReadEngine = new ModelEngine() {
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                noReadPrompts.add(prompt);
                noReadCalls[0]++;
                if (noReadCalls[0] == 1) {
                    return "{\"action\":\"tap\",\"el\":2}";
                }
                return "{\"action\":\"done\",\"summary\":\"Opened "
                        + "the Honolulu flights page.\"}";
            }
        };
        Agent noReadAgent = new Agent(capHands, noReadEngine,
                PolicyPacks.loadPack("standard"),
                new com.ghosthands.app.Trail(null), approvingUi,
                "q5_k_m");
        noReadAgent.run("search Google for plane tickets to Hawaii");
        boolean noHint = true;
        for (int i = 0; i < noReadPrompts.size(); i++) {
            if (noReadPrompts.get(i)
                    .contains("tip: when you find a fact")) {
                noHint = false;
            }
        }
        check("nudge: a run with no successful read never carries "
                        + "the hint",
                Boolean.valueOf(noHint && noReadCalls[0] == 2),
                Boolean.TRUE);
        check("nudge: the hint names the note action and the "
                        + "evidence check",
                Boolean.valueOf(Agent.NOTE_HINT.contains("note action")
                        && Agent.NOTE_HINT.contains(
                                "checked against your notes and "
                                + "reads")),
                Boolean.TRUE);

        // -- v3.1 item 2: the card's findings fallback ------------------
        // The field run's recorded shape: a fares read, then an
        // error stop, no notes — the card must show the fares.

        String faresGoal31 = "search Google for plane tickets to "
                + "Hawaii";
        String faresText = "Search Results\n\nSearch flights\n\n"
                + "Flights to Hawaii \n\nHonolulu\n$734\n\n"
                + "Kahului\n$698\n\nKailua-Kona\n$641\n\nMore "
                + "airlines, times and fares follow in the full "
                + "listing for other islands and travel dates.";
        String faresRaw = Eyes.formatReadChunk(0, faresText, 4029,
                "full page");
        // The loop records read results truncated to 300 chars.
        String faresRecorded = faresRaw.length() > 300
                ? faresRaw.substring(0, 300) + "..." : faresRaw;
        java.util.List<java.util.Map<String, Object>> faresEv =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", faresGoal31));
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "perceive",
                "url", "https://www.google.com/search?q=plane+"
                        + "tickets+to+Hawaii",
                "title", "plane tickets to Hawaii - Google Search"));
        GhAction readAct31 = new GhAction("read");
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", readAct31.toMap()));
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.TRUE,
                "result", faresRecorded));
        GhAction tapAct = new GhAction("tap");
        tapAct.el = 1;
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", tapAct.toMap()));
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.FALSE,
                "result", "error: element 1 not on page"));
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "error",
                "summary", "element 1 not on page"));
        faresEv.add(com.ghosthands.app.Trail.fields(
                "type", "run_summary", "steps", Long.valueOf(5),
                "seconds", Long.valueOf(745),
                "reuse_percent", Long.valueOf(37)));
        AnswerCard faresCard = AnswerCard.fromEvents(faresEv, null);
        check("card: the error-ended fares run gains a findings "
                        + "excerpt with the fares it read",
                Boolean.valueOf(faresCard != null
                        && "error".equals(faresCard.outcome)
                        && faresCard.findings.contains("Honolulu")
                        && faresCard.findings.contains("$734")
                        && faresCard.findings.contains("Kahului")
                        && faresCard.findings.contains("$698")
                        && !faresCard.findings.contains("read chars")
                        && faresCard.facts.isEmpty()),
                Boolean.TRUE);
        check("card: the outcome is labelled, not posed as an "
                        + "answer",
                Boolean.valueOf(faresCard.cardText().contains(
                                "Outcome: Error — element 1 not on "
                                + "page")
                        && !faresCard.cardText().contains(
                                "Answer: Error")),
                Boolean.TRUE);
        check("card: the share text carries the labelled outcome "
                        + "and the findings section",
                Boolean.valueOf(faresCard.shareText("2026-10-25 03:04")
                        .contains("Outcome: Error — element 1 not "
                                + "on page")
                        && faresCard.shareText("2026-10-25 03:04")
                        .contains("What it found (excerpt from the "
                                + "source page):")
                        && faresCard.shareText("2026-10-25 03:04")
                        .contains("$734")),
                Boolean.TRUE);

        // A done with reads but no notes keeps its summary as the
        // answer — no findings section.
        java.util.List<java.util.Map<String, Object>> doneReadEv =
                new java.util.ArrayList<java.util.Map<String, Object>>(
                        faresEv);
        doneReadEv.set(doneReadEv.size() - 2,
                com.ghosthands.app.Trail.fields(
                        "type", "stop", "reason", "done",
                        "summary", "Honolulu $734, Kahului $698."));
        AnswerCard doneReadCard = AnswerCard.fromEvents(doneReadEv,
                null);
        check("card: a done summary stays the answer (no findings "
                        + "fallback)",
                Boolean.valueOf(doneReadCard != null
                        && doneReadCard.findings.length() == 0
                        && doneReadCard.cardText().contains(
                                "Answer: Honolulu $734")),
                Boolean.TRUE);

        // Notes present: the facts section stands, no findings.
        java.util.List<java.util.Map<String, Object>> notedErrEv =
                new java.util.ArrayList<java.util.Map<String, Object>>(
                        faresEv);
        notedErrEv.add(3, com.ghosthands.app.Trail.fields(
                "type", "note", "status", "added",
                "fact", "Kahului fare is $698",
                "url", "https://www.google.com/search?q=plane+"
                        + "tickets+to+Hawaii",
                "title", "plane tickets to Hawaii - Google Search"));
        AnswerCard notedErrCard = AnswerCard.fromEvents(notedErrEv,
                null);
        check("card: noted facts stand instead of the findings "
                        + "fallback",
                Boolean.valueOf(notedErrCard != null
                        && notedErrCard.facts.size() == 1
                        && notedErrCard.findings.length() == 0
                        && notedErrCard.cardText().contains(
                                "Facts noted:")),
                Boolean.TRUE);

        // The LAST successful read wins; a failed read never
        // supplies the excerpt.
        java.util.List<java.util.Map<String, Object>> twoReadEv =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", readAct31.toMap()));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.TRUE,
                "result", Eyes.formatReadChunk(0,
                        "First page text about apples", 900,
                        "full page")));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", readAct31.toMap()));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.TRUE,
                "result", Eyes.formatReadChunk(0,
                        "Second page text about oranges", 800,
                        "full page")));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", readAct31.toMap()));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.FALSE,
                "result", "error: no readable text found on this "
                        + "page"));
        twoReadEv.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "stuck",
                "summary", "element map unchanged for 3 consecutive "
                        + "steps"));
        AnswerCard twoReadCard = AnswerCard.fromEvents(twoReadEv,
                "the fallback goal");
        check("card: the findings excerpt is the LAST successful "
                        + "read's opening text",
                Boolean.valueOf(twoReadCard != null
                        && twoReadCard.findings.contains("oranges")
                        && !twoReadCard.findings.contains("apples")),
                Boolean.TRUE);

        // The excerpt helper itself: framing stripped, cap kept,
        // dense note dropped.
        check("card: the excerpt strips the read framing",
                AnswerCard.readExcerpt(Eyes.formatReadChunk(0,
                        "Hello page", 500, "main content")),
                "Hello page");
        StringBuilder bigText = new StringBuilder();
        while (bigText.length() < 900) bigText.append("0123456789");
        String bigExcerpt = AnswerCard.readExcerpt(
                Eyes.formatReadChunk(0, bigText.toString(), 5000,
                        "full page"));
        check("card: the excerpt is capped at 400 chars plus the "
                        + "ellipsis",
                Integer.valueOf(bigExcerpt.length()),
                Integer.valueOf(AnswerCard.EXCERPT_CHARS + 1));
        check("card: the excerpt drops the dense-read note",
                Boolean.valueOf(!AnswerCard.readExcerpt(
                        Eyes.formatReadChunk(0, "Short dense read",
                                3000, "full page")
                        + "\n" + Agent.DENSE_READ_NOTE)
                        .contains("smaller chunks")),
                Boolean.TRUE);

        // =============================================================
        // v3.2 — watch mode: the evidence comparator, the baseline
        // store, the verdict matrix, the card banner. The design
        // constraint under test: the 1.5B rewords every answer, so
        // the verdict runs on numbers, never on phrasing.
        // =============================================================

        // -- v3.2: signature extraction --------------------------------

        java.util.List<String> fareFacts =
                new java.util.ArrayList<String>();
        fareFacts.add("Kahului fare $698");
        WatchCompare.Signature sigA = WatchCompare.signature(
                fareFacts, "Kahului $698, Honolulu $734");
        check("watch: the signature takes facts first, then the "
                        + "summary, one entry per value",
                Boolean.valueOf(sigA.entries.size() == 2
                        && "kahului fare".equals(
                                sigA.entries.get(0).label)
                        && "698".equals(sigA.entries.get(0).value)
                        && "$698".equals(sigA.entries.get(0).raw)
                        && "honolulu".equals(sigA.entries.get(1).label)
                        && "734".equals(sigA.entries.get(1).value)),
                Boolean.TRUE);
        WatchCompare.Signature normSig = WatchCompare.signature(
                new java.util.ArrayList<String>(),
                "KAHULUI Fare: $698");
        check("watch: labels normalize case and punctuation away",
                normSig.entries.get(0).label, "kahului fare");
        WatchCompare.Signature tokSig = WatchCompare.signature(
                new java.util.ArrayList<String>(),
                "It is 1,117 miles and 43.5 degrees");
        check("watch: values tokenize like the grounding guard "
                        + "(grouping commas dropped, decimals kept)",
                Boolean.valueOf(tokSig.entries.size() == 2
                        && "1117".equals(tokSig.entries.get(0).value)
                        && "1,117".equals(tokSig.entries.get(0).raw)
                        && "43.5".equals(tokSig.entries.get(1).value)),
                Boolean.TRUE);
        check("watch: the baseline line lists label and raw value",
                WatchCompare.entriesLine(sigA),
                "Kahului fare $698, Honolulu $734");

        // -- v3.2: the comparator --------------------------------------

        // THE PIN: two differently-worded summaries carrying the
        // same numbers are NO CHANGE — even when one run noted
        // its facts and the other did not.
        WatchCompare.Signature sigB = WatchCompare.signature(
                new java.util.ArrayList<String>(),
                "The cheapest flight is Kahului at $698, and "
                        + "Honolulu comes in at $734.");
        WatchCompare.Verdict reworded = WatchCompare.compare(
                sigA, "Kahului $698, Honolulu $734",
                sigB, "The cheapest flight is Kahului at $698, and "
                        + "Honolulu comes in at $734.");
        check("watch: reworded, same numbers = no change",
                Boolean.valueOf(WatchCompare.NO_CHANGE.equals(
                                reworded.state)
                        && reworded.diffLines.isEmpty()),
                Boolean.TRUE);

        // A paired change under a stable label reads as one line.
        java.util.List<String> dropFacts =
                new java.util.ArrayList<String>();
        dropFacts.add("Kahului fare $617");
        WatchCompare.Signature sigC = WatchCompare.signature(
                dropFacts, "Kahului now $617, Honolulu still $734");
        WatchCompare.Verdict dropped = WatchCompare.compare(
                sigA, "Kahului $698, Honolulu $734",
                sigC, "Kahului now $617, Honolulu still $734");
        check("watch: a fare drop pairs by label into one diff line",
                Boolean.valueOf(WatchCompare.CHANGED.equals(
                                dropped.state)
                        && dropped.diffLines.size() == 1
                        && "kahului fare: $698 -> $617".equals(
                                dropped.diffLines.get(0))),
                Boolean.TRUE);

        // Appeared and vanished numbers.
        java.util.List<String> oneFact =
                new java.util.ArrayList<String>();
        oneFact.add("Kahului $698");
        WatchCompare.Signature sigOne = WatchCompare.signature(
                oneFact, "Kahului $698");
        WatchCompare.Verdict appeared = WatchCompare.compare(
                sigOne, "Kahului $698", sigA,
                "Kahului $698, Honolulu $734");
        check("watch: an appeared number is a new: line",
                Boolean.valueOf(WatchCompare.CHANGED.equals(
                                appeared.state)
                        && appeared.diffLines.size() == 1
                        && "new: honolulu $734".equals(
                                appeared.diffLines.get(0))),
                Boolean.TRUE);
        WatchCompare.Verdict vanished = WatchCompare.compare(
                sigA, "Kahului $698, Honolulu $734",
                sigOne, "Kahului $698");
        check("watch: a vanished number is a gone: line",
                Boolean.valueOf(WatchCompare.CHANGED.equals(
                                vanished.state)
                        && vanished.diffLines.size() == 1
                        && "gone: honolulu $734".equals(
                                vanished.diffLines.get(0))),
                Boolean.TRUE);

        // The numberless fallback: normalized-summary equality.
        WatchCompare.Signature sigNone = WatchCompare.signature(
                new java.util.ArrayList<String>(),
                "The library is open today.");
        WatchCompare.Verdict numlessSame = WatchCompare.compare(
                sigNone, "The library is open today.",
                WatchCompare.signature(
                        new java.util.ArrayList<String>(),
                        "The library is open today!"),
                "The library is open today!");
        check("watch: numberless runs compare normalized summaries "
                        + "(same answer, new punctuation = no change)",
                numlessSame.state, WatchCompare.NO_CHANGE);
        WatchCompare.Verdict numlessDiff = WatchCompare.compare(
                sigNone, "The library is open today.",
                WatchCompare.signature(
                        new java.util.ArrayList<String>(),
                        "The library is closed today."),
                "The library is closed today.");
        check("watch: numberless runs with different answers change",
                Boolean.valueOf(WatchCompare.CHANGED.equals(
                                numlessDiff.state)
                        && numlessDiff.diffLines.size() == 1
                        && numlessDiff.diffLines.get(0).startsWith(
                                "answer changed:")
                        && numlessDiff.diffLines.get(0).contains(
                                "closed")),
                Boolean.TRUE);
        check("watch: a null baseline is a baseline-set verdict",
                WatchCompare.compare(null, "", sigA, "s").state,
                WatchCompare.BASELINE);

        // -- v3.2: signatures from trail events -------------------------

        WatchCompare.Signature evSig = WatchCompare.signatureFromEvents(
                finishedRunEvents(new String[]{"Kahului fare $698"},
                        "done", "Kahului $698, Honolulu $734"));
        check("watch: a done run's events yield its signature",
                Boolean.valueOf(evSig != null
                        && evSig.entries.size() == 2
                        && "kahului fare".equals(
                                evSig.entries.get(0).label)),
                Boolean.TRUE);
        check("watch: a stuck run has no signature",
                WatchCompare.signatureFromEvents(finishedRunEvents(
                        new String[]{"Kahului fare $698"},
                        "stuck", "map unchanged")),
                null);
        check("watch: an error run has no signature",
                WatchCompare.signatureFromEvents(finishedRunEvents(
                        new String[0], "error", "engine error")),
                null);
        java.util.List<java.util.Map<String, Object>> dupNotes =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        dupNotes.add(com.ghosthands.app.Trail.fields(
                "type", "note", "status", "duplicate",
                "fact", "Kahului fare $698",
                "url", "https://example.test/", "title", "Example"));
        dupNotes.add(com.ghosthands.app.Trail.fields(
                "type", "note",
                "fact", "Kahului fare $698",
                "url", "https://example.test/", "title", "Example"));
        dupNotes.add(com.ghosthands.app.Trail.fields(
                "type", "note", "status", "added",
                "fact", "Kahului fare $698",
                "url", "https://example.test/", "title", "Example"));
        dupNotes.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "done",
                "summary", "Kahului $698"));
        WatchCompare.Signature dupSig =
                WatchCompare.signatureFromEvents(dupNotes);
        check("watch: unadded notes do not count and a fact text "
                        + "counts once, as the notebook holds it",
                Boolean.valueOf(dupSig != null
                        && dupSig.entries.size() == 1
                        && "kahului fare".equals(
                                dupSig.entries.get(0).label)),
                Boolean.TRUE);

        // -- v3.2: the baseline store -----------------------------------

        SavedGoals watchStore = new SavedGoals();
        SavedGoals.SavedGoal wg = new SavedGoals.SavedGoal(
                "Fares", "find fares", "readonly", 420);
        wg.watched = true;
        wg.setBaseline(sigA, "Kahului $698, Honolulu $734",
                1761433200000L);
        watchStore.put(wg);
        SavedGoals.SavedGoal wgBack =
                SavedGoals.parse(watchStore.serialize()).find("Fares");
        check("watch store: the watch flag and baseline round-trip",
                Boolean.valueOf(wgBack != null && wgBack.watched
                        && wgBack.hasBaseline()
                        && wgBack.baseline.entries.size() == 2
                        && "kahului fare".equals(wgBack.baseline
                                .entries.get(0).label)
                        && "$734".equals(wgBack.baseline
                                .entries.get(1).raw)
                        && "Kahului $698, Honolulu $734".equals(
                                wgBack.baselineSummary)
                        && wgBack.baselineTsMs == 1761433200000L),
                Boolean.TRUE);
        SavedGoals legacyStore = SavedGoals.parse(
                "[{\"name\":\"Old\",\"goal\":\"g\",\"pack\":"
                        + "\"standard\",\"sched\":-1}]");
        check("watch store: a pre-v3.2 record parses unwatched, "
                        + "with no baseline",
                Boolean.valueOf(legacyStore.size() == 1
                        && !legacyStore.find("Old").watched
                        && !legacyStore.find("Old").hasBaseline()),
                Boolean.TRUE);
        SavedGoals junkStore = SavedGoals.parse(
                "[{\"name\":\"J\",\"goal\":\"g\",\"pack\":\"standard\","
                        + "\"sched\":-1,\"watch\":true,\"base\":"
                        + "{\"sig\":\"junk\"}},"
                        + "{\"name\":\"K\",\"goal\":\"g\",\"pack\":"
                        + "\"standard\",\"sched\":-1,\"watch\":true,"
                        + "\"base\":\"junk\"},"
                        + "{\"name\":\"L\",\"goal\":\"g\",\"pack\":"
                        + "\"standard\",\"sched\":-1,\"watch\":true,"
                        + "\"base\":{\"ts\":5,\"summary\":\"s\","
                        + "\"sig\":[{\"v\":42},{\"l\":\"x\","
                        + "\"v\":\"9\",\"r\":\"9\"},\"junk\"]}}]");
        check("watch store: a malformed baseline costs the "
                        + "baseline, never the goal or its watch",
                Boolean.valueOf(junkStore.find("J").watched
                        && !junkStore.find("J").hasBaseline()
                        && junkStore.find("K").watched
                        && !junkStore.find("K").hasBaseline()
                        && junkStore.find("L").hasBaseline()
                        && junkStore.find("L").baseline.entries
                                .size() == 1
                        && "9".equals(junkStore.find("L").baseline
                                .entries.get(0).value)),
                Boolean.TRUE);
        SavedGoals emptyBaseStore = new SavedGoals();
        SavedGoals.SavedGoal eg = new SavedGoals.SavedGoal(
                "Lib", "is the library open", "readonly",
                SavedGoals.NO_SCHEDULE);
        eg.watched = true;
        eg.setBaseline(new WatchCompare.Signature(),
                "The library is open today.", 7L);
        emptyBaseStore.put(eg);
        SavedGoals.SavedGoal egBack = SavedGoals.parse(
                emptyBaseStore.serialize()).find("Lib");
        check("watch store: a numberless baseline is still a "
                        + "baseline after the round-trip",
                Boolean.valueOf(egBack != null && egBack.hasBaseline()
                        && egBack.baseline.isEmpty()),
                Boolean.TRUE);
        SavedGoals.SavedGoal resaved = new SavedGoals.SavedGoal(
                "Fares", "find fares", "readonly", 420);
        resaved.copyWatchFrom(wg);
        boolean carriedWatch = resaved.watched
                && resaved.hasBaseline();
        resaved.watched = false;
        resaved.clearBaseline();
        SavedGoals resaveStore = new SavedGoals();
        resaveStore.put(resaved);
        SavedGoals.SavedGoal resavedBack =
                SavedGoals.parse(resaveStore.serialize()).find("Fares");
        check("watch store: a re-save carries the watch over, and "
                        + "unwatching clears the baseline for good",
                Boolean.valueOf(carriedWatch
                        && resavedBack != null
                        && !resavedBack.watched
                        && !resavedBack.hasBaseline()),
                Boolean.TRUE);

        // -- v3.2: the verdict matrix (the Activity's flow, pure) -------

        SavedGoals matrix = new SavedGoals();
        SavedGoals.SavedGoal mg = new SavedGoals.SavedGoal(
                "Fares", "find fares", "readonly",
                SavedGoals.NO_SCHEDULE);
        mg.watched = true;
        matrix.put(mg);
        java.util.List<java.util.Map<String, Object>> run1 =
                finishedRunEvents(new String[]{"Kahului fare $698"},
                        "done", "Kahului $698, Honolulu $734");
        WatchCompare.Signature msig1 =
                WatchCompare.signatureFromEvents(run1);
        WatchCompare.Verdict mv1 = WatchCompare.compare(
                mg.baseline, mg.baselineSummary, msig1,
                WatchCompare.doneSummaryFromEvents(run1));
        check("watch matrix: the first watched done run sets the "
                        + "baseline and reports no change",
                mv1.state, WatchCompare.BASELINE);
        mg.setBaseline(msig1,
                WatchCompare.doneSummaryFromEvents(run1), 1000L);
        java.util.List<java.util.Map<String, Object>> run2 =
                finishedRunEvents(new String[0], "done",
                        "The cheapest flight is Kahului at $698, "
                                + "and Honolulu comes in at $734.");
        WatchCompare.Signature msig2 =
                WatchCompare.signatureFromEvents(run2);
        WatchCompare.Verdict mv2 = WatchCompare.compare(
                mg.baseline, mg.baselineSummary, msig2,
                WatchCompare.doneSummaryFromEvents(run2));
        check("watch matrix: a reworded repeat check is quiet",
                Boolean.valueOf(WatchCompare.NO_CHANGE.equals(
                                mv2.state)
                        && mv2.diffLines.isEmpty()),
                Boolean.TRUE);
        mg.setBaseline(msig2,
                WatchCompare.doneSummaryFromEvents(run2), 2000L);
        java.util.List<java.util.Map<String, Object>> run3 =
                finishedRunEvents(new String[]{"Kahului fare $617"},
                        "done", "Kahului now $617, Honolulu "
                                + "still $734");
        WatchCompare.Signature msig3 =
                WatchCompare.signatureFromEvents(run3);
        WatchCompare.Verdict mv3 = WatchCompare.compare(
                mg.baseline, mg.baselineSummary, msig3,
                WatchCompare.doneSummaryFromEvents(run3));
        check("watch matrix: a changed check diffs against the "
                        + "previous check, in one line",
                Boolean.valueOf(WatchCompare.CHANGED.equals(mv3.state)
                        && mv3.diffLines.size() == 1
                        && mv3.diffLines.get(0).endsWith(
                                "$698 -> $617")),
                Boolean.TRUE);
        mg.setBaseline(msig3,
                WatchCompare.doneSummaryFromEvents(run3), 3000L);
        WatchCompare.Signature msig4 =
                WatchCompare.signatureFromEvents(finishedRunEvents(
                        new String[0], "stuck", "map unchanged"));
        check("watch matrix: a failed check produces no signature "
                        + "and the baseline stays at the last done run",
                Boolean.valueOf(msig4 == null
                        && mg.baselineTsMs == 3000L
                        && WatchCompare.compare(mg.baseline,
                                mg.baselineSummary, msig3,
                                "Kahului now $617, Honolulu "
                                        + "still $734").state
                                .equals(WatchCompare.NO_CHANGE)),
                Boolean.TRUE);

        // -- v3.2: the card banner ---------------------------------------

        java.util.List<java.util.Map<String, Object>> quietEv =
                finishedRunEvents(new String[]{"Kahului fare $698"},
                        "done", "Kahului $698, Honolulu $734");
        quietEv.add(com.ghosthands.app.Trail.fields(
                "type", "watch", "goal_name", "Fares",
                "verdict", "no_change",
                "since_ms", Long.valueOf(1761433200000L)));
        AnswerCard quietCard = AnswerCard.fromEvents(quietEv, null);
        check("watch card: a quiet run's banner names the baseline "
                        + "check it compared against",
                Boolean.valueOf(quietCard != null
                        && quietCard.watchBanner().equals(
                                "Watch: no change since "
                                + AnswerCard.watchTimeLabel(
                                        1761433200000L) + ".")
                        && quietCard.cardText().contains(
                                "Watch: no change since")),
                Boolean.TRUE);
        java.util.List<Object> diffList =
                new java.util.ArrayList<Object>();
        diffList.add("kahului fare: $698 -> $617");
        java.util.List<java.util.Map<String, Object>> changedEv =
                finishedRunEvents(new String[]{"Kahului fare $617"},
                        "done", "Kahului now $617");
        changedEv.add(com.ghosthands.app.Trail.fields(
                "type", "watch", "goal_name", "Fares",
                "verdict", "changed",
                "since_ms", Long.valueOf(1761433200000L),
                "diff", diffList));
        AnswerCard changedCard = AnswerCard.fromEvents(changedEv, null);
        check("watch card: a changed run's banner carries its diff, "
                        + "on the card and in the share text",
                Boolean.valueOf(changedCard != null
                        && changedCard.watchBanner().startsWith(
                                "Watch: changed since ")
                        && changedCard.watchBanner().contains(
                                "- kahului fare: $698 -> $617")
                        && changedCard.shareText("2026-10-26 07:00")
                                .contains("kahului fare: $698 -> $617")),
                Boolean.TRUE);
        java.util.List<java.util.Map<String, Object>> failedEv =
                finishedRunEvents(new String[0], "stuck",
                        "element map unchanged");
        failedEv.add(com.ghosthands.app.Trail.fields(
                "type", "watch", "goal_name", "Fares",
                "verdict", "not_done"));
        AnswerCard failedCard = AnswerCard.fromEvents(failedEv, null);
        check("watch card: a failed check says it could not check, "
                        + "and that the baseline stands",
                Boolean.valueOf(failedCard != null
                        && failedCard.watchBanner().equals(
                                "Watch: couldn't check this time "
                                + "— the baseline is unchanged.")),
                Boolean.TRUE);
        java.util.List<java.util.Map<String, Object>> firstEv =
                finishedRunEvents(new String[0], "done", "Kahului $698");
        firstEv.add(com.ghosthands.app.Trail.fields(
                "type", "watch", "goal_name", "Fares",
                "verdict", "baseline",
                "since_ms", Long.valueOf(0)));
        AnswerCard firstCard = AnswerCard.fromEvents(firstEv, null);
        check("watch card: the first watched run reports its "
                        + "baseline being set",
                Boolean.valueOf(firstCard != null
                        && firstCard.watchBanner().equals(
                                "Watch: baseline set — later checks "
                                + "compare against this run.")),
                Boolean.TRUE);
        check("watch card: an unwatched run has no banner",
                Boolean.valueOf(AnswerCard.fromEvents(
                                finishedRunEvents(new String[0],
                                        "done", "Kahului $698"),
                                null).watchBanner().length() == 0),
                Boolean.TRUE);

        // The past-runs path: a trail carrying a watch event (the
        // record itself loop-tests the v3.2 registration) rebuilds
        // the same bannered card from its text.
        com.ghosthands.app.Trail watchTrail =
                new com.ghosthands.app.Trail(null);
        watchTrail.record("run", 0, com.ghosthands.app.Trail.fields(
                "goal", "find fares"));
        for (int i = 0; i < changedEv.size(); i++) {
            java.util.Map<String, Object> src = changedEv.get(i);
            java.util.Map<String, Object> copy =
                    new java.util.LinkedHashMap<String, Object>(src);
            String type = String.valueOf(copy.remove("type"));
            watchTrail.record(type, 1, copy);
        }
        AnswerCard rebuiltWatch = AnswerCard.fromTrailText(
                watchTrail.toJsonLines(), null);
        check("watch card: trail text rebuilds the bannered card",
                Boolean.valueOf(rebuiltWatch != null
                        && rebuiltWatch.cardText().equals(
                                AnswerCard.fromEvents(
                                        watchTrail.events(), null)
                                        .cardText())
                        && rebuiltWatch.watchBanner().contains(
                                "kahului fare: $698 -> $617")),
                Boolean.TRUE);

        // ================= v3.3: the remaining four =================

        // -- registry: the three new trail types --------------------
        boolean reg33 = true;
        boolean hasRoute = false, hasShot = false, hasAssist = false;
        for (int i = 0; i < com.ghosthands.app.Trail.EVENT_TYPES.length;
                i++) {
            String t = com.ghosthands.app.Trail.EVENT_TYPES[i];
            if ("route".equals(t)) hasRoute = true;
            if ("screenshot".equals(t)) hasShot = true;
            if ("assist".equals(t)) hasAssist = true;
        }
        com.ghosthands.app.Trail regTrail33 =
                new com.ghosthands.app.Trail(null);
        try {
            regTrail33.record("route", 0,
                    com.ghosthands.app.Trail.fields("probe", "v3.3"));
            regTrail33.record("screenshot", 0,
                    com.ghosthands.app.Trail.fields("file", "x.jpg"));
            regTrail33.record("assist", 0,
                    com.ghosthands.app.Trail.fields("phase", "offered"));
        } catch (Exception e) {
            reg33 = false;
        }
        check("trail: route, screenshot and assist types are "
                        + "registered and record",
                Boolean.valueOf(reg33 && hasRoute && hasShot
                        && hasAssist),
                Boolean.TRUE);

        // -- JS structure: the two new scripts (v2.7's rule) --------
        check("eyes: pointIdentityJs is structurally sound",
                jsStructureError(Eyes.pointIdentityJs(10, 20)), null);
        check("eyes: valueScanJs is structurally sound",
                jsStructureError(Eyes.valueScanJs(2)), null);

        // -- Eyes.parseFieldValue: the password belt ----------------
        Eyes.FieldValue fvPw = Eyes.parseFieldValue(
                "{\"found\":true,\"type\":\"password\","
                + "\"value\":\"hunter2\"}");
        check("field value: a password payload parses redacted "
                        + "with NO value, whatever it claims",
                Boolean.valueOf(fvPw.found && fvPw.redacted
                        && fvPw.value.length() == 0),
                Boolean.TRUE);
        Eyes.FieldValue fvRed = Eyes.parseFieldValue(
                "{\"found\":true,\"redacted\":true,"
                + "\"type\":\"password\",\"value\":\"hunter2\"}");
        check("field value: the redacted flag alone empties the "
                        + "value too",
                Boolean.valueOf(fvRed.found && fvRed.redacted
                        && fvRed.value.length() == 0),
                Boolean.TRUE);
        Eyes.FieldValue fvText = Eyes.parseFieldValue(
                "{\"found\":true,\"type\":\"text\","
                + "\"value\":\"oakdale\"}");
        check("field value: an ordinary field's value reads back",
                Boolean.valueOf(fvText.found && !fvText.redacted
                        && "oakdale".equals(fvText.value)),
                Boolean.TRUE);
        check("field value: not-found and junk parse as not found",
                Boolean.valueOf(!Eyes.parseFieldValue(
                                "{\"found\":false}").found
                        && !Eyes.parseFieldValue("junk").found
                        && !Eyes.parseFieldValue(null).found),
                Boolean.TRUE);

        // -- Routes: identity keying --------------------------------
        check("routes: the key is name + href when an href exists",
                Routes.identityKey("Sign in", "https://x.test/in",
                        "a", ""),
                "Sign in\nhttps://x.test/in");
        check("routes: without an href the key is name + tag + type",
                Routes.identityKey("Email", "", "input", "text"),
                "Email\ninput\ntext");
        check("routes: an element with nothing to identify it by "
                        + "keys null",
                Routes.identityKey("", "", "div", ""), null);
        check("routes: the password predicate",
                Boolean.valueOf(Routes.isPasswordType("password")
                        && Routes.isPasswordType("PASSWORD")
                        && !Routes.isPasswordType("text")
                        && !Routes.isPasswordType(null)),
                Boolean.TRUE);
        ElementInfo emailField = elx(1, "input", "Email", null);
        emailField.type = "text";
        ElementInfo pwField = elx(2, "input", "Password", null);
        pwField.type = "password";
        ElementInfo checkbox = elx(3, "input", "Agree", null);
        checkbox.type = "checkbox";
        check("routes: typable fields are text-like inputs, "
                        + "passwords and textareas — not checkboxes",
                Boolean.valueOf(Routes.isTypableField(emailField)
                        && Routes.isTypableField(pwField)
                        && !Routes.isTypableField(checkbox)
                        && !Routes.isTypableField(
                                elx(4, "a", "Link", "https://x.test/"))),
                Boolean.TRUE);

        // -- Routes: the redaction line ------------------------------
        Routes.Step pwStep = Routes.typeStep(pwField, "password",
                "hunter2");
        check("routes: a password type step is redacted at "
                        + "construction, its value dropped",
                Boolean.valueOf(pwStep.redacted && pwStep.text == null),
                Boolean.TRUE);
        Routes store33 = new Routes();
        Routes.Route login33 = new Routes.Route();
        login33.name = "Login";
        login33.startUrl = "https://example.test/login";
        login33.steps.add(Routes.tapStep(
                elx(1, "a", "Sign in", "https://example.test/login")));
        login33.steps.add(Routes.typeStep(emailField, "text",
                "a@b.c"));
        login33.steps.add(pwStep);
        store33.put(login33);
        String loginJson = store33.serialize();
        check("routes: the serialized store never contains the "
                        + "password text",
                Boolean.valueOf(!loginJson.contains("hunter2")),
                Boolean.TRUE);
        Routes loginBack = Routes.parse(loginJson);
        check("routes: the round trip keeps the route and steps",
                Boolean.valueOf(loginBack.size() == 1
                        && loginBack.find("Login") != null
                        && loginBack.find("Login").steps.size() == 3
                        && "https://example.test/login".equals(
                                loginBack.find("Login").startUrl)),
                Boolean.TRUE);
        check("routes: the round trip keeps the type text and "
                        + "the redaction",
                Boolean.valueOf("a@b.c".equals(loginBack.find("Login")
                                .steps.get(1).text)
                        && loginBack.find("Login").steps.get(2).redacted
                        && loginBack.find("Login").steps.get(2).text
                                == null),
                Boolean.TRUE);
        check("routes: junk and half-records parse tolerantly",
                Boolean.valueOf(Routes.parse("not json").size() == 0
                        && Routes.parse(null).size() == 0
                        && Routes.parse("[{\"name\":\"x\"}]").size()
                                == 0),
                Boolean.TRUE);
        Routes sneaky = Routes.parse("[{\"name\":\"S\","
                + "\"start\":\"https://x.test/\",\"steps\":[{"
                + "\"kind\":\"type\",\"name\":\"Password\","
                + "\"tag\":\"input\",\"type\":\"password\","
                + "\"redacted\":true,\"text\":\"hunter2\"}]}]");
        check("routes: a stored redacted step parses redacted "
                        + "even if its record smuggles a text key",
                Boolean.valueOf(sneaky.find("S") != null
                        && sneaky.find("S").steps.get(0).redacted
                        && sneaky.find("S").steps.get(0).text == null),
                Boolean.TRUE);
        check("routes: step descriptions name the step, and a "
                        + "redacted step says it was never recorded",
                Boolean.valueOf("tap \"Sign in\"".equals(
                                login33.steps.get(0).describe())
                        && pwStep.describe().contains("Password")
                        && pwStep.describe().contains(
                                "never recorded")),
                Boolean.TRUE);

        // -- Routes: live resolution ---------------------------------
        java.util.List<ElementInfo> livePage =
                new java.util.ArrayList<ElementInfo>();
        livePage.add(elx(7, "a", "Sign in", "https://example.test/login"));
        livePage.add(elx(9, "input", "Email", null));
        livePage.get(1).type = "text";
        ElementInfo resolved33 = Routes.resolve(login33.steps.get(0),
                livePage);
        check("routes: resolve finds the live element by identity "
                        + "(its own number, not the recorded one)",
                Boolean.valueOf(resolved33 != null
                        && resolved33.number == 7),
                Boolean.TRUE);
        Routes.Step ghostStep = Routes.tapStep(
                elx(1, "a", "Dashboard", "https://example.test/dash"));
        check("routes: an absent identity resolves null",
                Routes.resolve(ghostStep, livePage), null);
        java.util.List<ElementInfo> dupPage =
                new java.util.ArrayList<ElementInfo>(livePage);
        dupPage.add(elx(11, "a", "Sign in",
                "https://example.test/login"));
        check("routes: an ambiguous identity resolves null — "
                        + "never a guess",
                Routes.resolve(login33.steps.get(0), dupPage), null);

        // -- Copilot: eligibility + the pause machine ----------------
        check("copilot: a stuck stop, foreground, assists left — "
                        + "eligible",
                Boolean.valueOf(Copilot.eligible("stuck", 0, true)
                        && Copilot.eligible("stuck", 1, true)),
                Boolean.TRUE);
        check("copilot: the cap is two assists per run",
                Boolean.valueOf(!Copilot.eligible("stuck", 2, true)),
                Boolean.TRUE);
        check("copilot: only the stuck stop is eligible — never "
                        + "error, budget or denied",
                Boolean.valueOf(!Copilot.eligible("error", 0, true)
                        && !Copilot.eligible("budget", 0, true)
                        && !Copilot.eligible("denied", 0, true)
                        && !Copilot.eligible(null, 0, true)),
                Boolean.TRUE);
        check("copilot: backgrounded means no pause is offered",
                Boolean.valueOf(!Copilot.eligible("stuck", 0, false)),
                Boolean.TRUE);
        Copilot.Pause pause33 = new Copilot.Pause();
        boolean pauseSeq = pause33.state() == Copilot.State.IDLE
                && !pause33.resume()
                && pause33.offer()
                && pause33.state() == Copilot.State.WAITING
                && !pause33.resume()
                && pause33.state() == Copilot.State.WAITING
                && pause33.tapIdentified()
                && pause33.state() == Copilot.State.TAPPED
                && pause33.tapIdentified()
                && pause33.resume()
                && pause33.resumed()
                && !pause33.decline();
        check("copilot: the pause machine — Resume needs an "
                        + "identified tap; terminal states hold",
                Boolean.valueOf(pauseSeq), Boolean.TRUE);
        Copilot.Pause pause33b = new Copilot.Pause();
        check("copilot: a pause can be declined from waiting",
                Boolean.valueOf(pause33b.offer() && pause33b.decline()
                        && pause33b.state() == Copilot.State.DECLINED
                        && !pause33b.tapIdentified()),
                Boolean.TRUE);

        // -- Copilot: the scripted stuck run that gets rescued -------
        final Eyes.Perception animalsPage = new Eyes.Perception();
        animalsPage.url = "https://example.org/animals";
        animalsPage.title = "Animals — Example";
        animalsPage.textPreview = "Animal pages";
        animalsPage.elements.add(elx(1, "a", "Red panda",
                "https://example.org/red-panda"));
        animalsPage.elements.add(elx(2, "a", "Blue whale",
                "https://example.org/blue-whale"));
        animalsPage.elements.add(elx(3, "a", "Green frog",
                "https://example.org/green-frog"));
        animalsPage.elements.add(elx(4, "a", "Zebra",
                "https://example.org/zebra"));
        Agent.Hands animalsHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return animalsPage;
            }
            @Override public String tap(int el) {
                return "ok: tapped #" + el;
            }
            @Override public String type(int el, String text) {
                return "ok";
            }
            @Override public String gotoUrl(String url) { return "ok"; }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String query) { return "ok"; }
            @Override public String read(int chunkChars) {
                return "error: no readable text found on this page";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] assistCalls = {0};
        final java.util.List<String> assistPrompts =
                new java.util.ArrayList<String>();
        Agent.Ui rescueUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                return true;
            }
            @Override public boolean stopRequested() { return false; }
            @Override public boolean foreground() { return true; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) {
                assistCalls[0]++;
                ElementInfo z = elx(4, "a", "Zebra",
                        "https://example.org/zebra");
                return z;
            }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) { return false; }
        };
        ModelEngine animalsEngine = new ModelEngine() {
            private int calls = 0;
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                assistPrompts.add(prompt);
                calls++;
                if (calls == 1) return "{\"action\":\"tap\",\"el\":1}";
                if (calls == 2) return "{\"action\":\"tap\",\"el\":2}";
                if (calls == 3) return "{\"action\":\"tap\",\"el\":3}";
                return "{\"action\":\"done\",\"summary\":\"I found "
                        + "the red panda, blue whale and green frog "
                        + "pages.\"}";
            }
        };
        com.ghosthands.app.Trail rescueTrail =
                new com.ghosthands.app.Trail(null);
        Agent rescueAgent = new Agent(animalsHands, animalsEngine,
                PolicyPacks.loadPack("standard"), rescueTrail,
                rescueUi, "q5_k_m");
        rescueAgent.run("Open the animal pages and tell me which "
                + "animals live there");
        String rescueStop = null;
        boolean assistOffered = false, assistTappedUser = false;
        int rescueExecutes = 0;
        for (java.util.Map<String, Object> ev : rescueTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("stop".equals(t)) {
                rescueStop = String.valueOf(ev.get("reason"));
            }
            if ("assist".equals(t)
                    && "offered".equals(String.valueOf(
                            ev.get("phase")))) {
                assistOffered = true;
            }
            if ("assist".equals(t)
                    && "tapped".equals(String.valueOf(ev.get("phase")))
                    && "user".equals(String.valueOf(
                            ev.get("provenance")))) {
                assistTappedUser = true;
            }
            if ("execute".equals(t)) rescueExecutes++;
        }
        check("copilot loop: the unchanged-map stuck run was "
                        + "offered a pause and finished done after "
                        + "the user's tap",
                Boolean.valueOf("done".equals(rescueStop)
                        && assistOffered && assistCalls[0] == 1),
                Boolean.TRUE);
        check("copilot loop: the assist is on the trail with USER "
                        + "provenance, and the user's tap was never "
                        + "an agent execute (3 executes: the "
                        + "planner's own taps)",
                Boolean.valueOf(assistTappedUser
                        && rescueExecutes == 3),
                Boolean.TRUE);
        boolean noteInPrompt = false;
        for (int i = 0; i < assistPrompts.size(); i++) {
            if (assistPrompts.get(i).contains("the user tapped "
                    + "\"Zebra\" to get past a stuck point")) {
                noteInPrompt = true;
            }
        }
        check("copilot loop: the planner's next prompt carries "
                        + "the assist history note",
                Boolean.valueOf(noteInPrompt), Boolean.TRUE);

        // The declined rescue: same stuck run, the user lets it die.
        Agent.Ui declineUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                return true;
            }
            @Override public boolean stopRequested() { return false; }
            @Override public boolean foreground() { return true; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) { return null; }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) { return false; }
        };
        ModelEngine declineEngine = new ModelEngine() {
            private int calls = 0;
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                calls++;
                if (calls == 1) return "{\"action\":\"tap\",\"el\":1}";
                if (calls == 2) return "{\"action\":\"tap\",\"el\":2}";
                if (calls == 3) return "{\"action\":\"tap\",\"el\":3}";
                return "{\"action\":\"done\",\"summary\":\"done\"}";
            }
        };
        com.ghosthands.app.Trail declineTrail =
                new com.ghosthands.app.Trail(null);
        new Agent(animalsHands, declineEngine,
                PolicyPacks.loadPack("standard"), declineTrail,
                declineUi, "q5_k_m").run(
                "Open the animal pages and tell me which animals "
                + "live there");
        String declineStop = null;
        boolean assistDeclined = false;
        for (java.util.Map<String, Object> ev : declineTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("stop".equals(t)) {
                declineStop = String.valueOf(ev.get("reason"));
            }
            if ("assist".equals(t) && "declined".equals(
                    String.valueOf(ev.get("phase")))) {
                assistDeclined = true;
            }
        }
        check("copilot loop: a declined pause leaves the run "
                        + "stopped stuck, the decline on the trail",
                Boolean.valueOf("stuck".equals(declineStop)
                        && assistDeclined),
                Boolean.TRUE);

        // Backgrounded: no pause exists to decline.
        Agent.Ui bgUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                return true;
            }
            @Override public boolean stopRequested() { return false; }
            @Override public boolean foreground() { return false; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) {
                throw new AssertionError("backgrounded: no pause "
                        + "may be offered");
            }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) { return false; }
        };
        ModelEngine bgEngine = new ModelEngine() {
            private int calls = 0;
            @Override public synchronized String generate(
                    String prompt, int maxTokens) {
                calls++;
                if (calls == 1) return "{\"action\":\"tap\",\"el\":1}";
                if (calls == 2) return "{\"action\":\"tap\",\"el\":2}";
                if (calls == 3) return "{\"action\":\"tap\",\"el\":3}";
                return "{\"action\":\"done\",\"summary\":\"done\"}";
            }
        };
        com.ghosthands.app.Trail bgTrail =
                new com.ghosthands.app.Trail(null);
        new Agent(animalsHands, bgEngine,
                PolicyPacks.loadPack("standard"), bgTrail, bgUi,
                "q5_k_m").run("Open the animal pages and tell me "
                + "which animals live there");
        String bgStop = null;
        boolean bgAssist = false;
        for (java.util.Map<String, Object> ev : bgTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("stop".equals(t)) {
                bgStop = String.valueOf(ev.get("reason"));
            }
            if ("assist".equals(t)) bgAssist = true;
        }
        check("copilot loop: backgrounded, the same run stops "
                        + "stuck with no assist event at all",
                Boolean.valueOf("stuck".equals(bgStop) && !bgAssist),
                Boolean.TRUE);

        // -- Route replay (loop) --------------------------------------
        final Eyes.Perception loginPage = new Eyes.Perception();
        loginPage.url = "https://example.test/login";
        loginPage.title = "Sign in — Example";
        loginPage.textPreview = "Sign in";
        ElementInfo loginEmail = elx(1, "input", "Email", null);
        loginEmail.type = "text";
        ElementInfo loginPw = elx(2, "input", "Password", null);
        loginPw.type = "password";
        ElementInfo loginBtn = elx(3, "button", "Sign in", null);
        loginBtn.type = "submit";
        loginPage.elements.add(loginEmail);
        loginPage.elements.add(loginPw);
        loginPage.elements.add(loginBtn);
        final int[] typedInto = {-1};
        Agent.Hands loginHands = new Agent.Hands() {
            @Override public Eyes.Perception perceive() {
                return loginPage;
            }
            @Override public String tap(int el) {
                return "ok: tapped #" + el;
            }
            @Override public String type(int el, String text) {
                typedInto[0] = el;
                return "ok: typed into #" + el;
            }
            @Override public String gotoUrl(String url) {
                return "ok: navigated to " + url;
            }
            @Override public String scroll(String dir) { return "ok"; }
            @Override public String back() { return "ok"; }
            @Override public String search(String query) { return "ok"; }
            @Override public String read(int chunkChars) {
                return "error: no readable text found on this page";
            }
            @Override public String reload(String u) { return "ok"; }
        };
        final int[] replayApprovals = {0};
        final boolean[] resumeAnswer = {true};
        Agent.Ui replayUi = new Agent.Ui() {
            @Override public void feed(String line) { }
            @Override public void status(String s) { }
            @Override public boolean approve(GhAction action,
                    Governor.Decision decision, String describe) {
                replayApprovals[0]++;
                return true;
            }
            @Override public boolean stopRequested() { return false; }
            @Override public boolean foreground() { return true; }
            @Override public ElementInfo awaitUserTap(int step,
                    long timeoutMs) { return null; }
            @Override public boolean awaitUserResume(int step,
                    String message, long timeoutMs) {
                return resumeAnswer[0];
            }
        };
        Routes.Route loginRoute = new Routes.Route();
        loginRoute.name = "Login";
        loginRoute.startUrl = "https://example.test/login";
        loginRoute.steps.add(Routes.typeStep(loginEmail, "text",
                "a@b.c"));
        loginRoute.steps.add(Routes.tapStep(loginBtn));
        com.ghosthands.app.Trail replayTrail =
                new com.ghosthands.app.Trail(null);
        new Agent(loginHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), replayTrail,
                replayUi, "q5_k_m").runRoute(loginRoute);
        String replayStop = null, replaySummary = null;
        int replayGoverns = 0, replayExecutes = 0;
        boolean replayProvenance = true, replayApproval = false;
        boolean replayRouteStart = false;
        for (java.util.Map<String, Object> ev : replayTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("stop".equals(t)) {
                replayStop = String.valueOf(ev.get("reason"));
                replaySummary = String.valueOf(ev.get("summary"));
            }
            if ("govern".equals(t)) {
                replayGoverns++;
                if (!"route_replay".equals(String.valueOf(
                        ev.get("provenance")))) {
                    replayProvenance = false;
                }
            }
            if ("execute".equals(t)) {
                replayExecutes++;
                if (!"route_replay".equals(String.valueOf(
                        ev.get("provenance")))) {
                    replayProvenance = false;
                }
            }
            if ("approval".equals(t)) replayApproval = true;
            if ("route".equals(t) && "start".equals(
                    String.valueOf(ev.get("phase")))) {
                replayRouteStart = true;
            }
        }
        check("replay loop: the route completes done, its start "
                        + "on the trail",
                Boolean.valueOf("done".equals(replayStop)
                        && replaySummary != null
                        && replaySummary.contains("completed")
                        && replayRouteStart),
                Boolean.TRUE);
        check("replay loop: every govern and execute carries the "
                        + "route_replay provenance (3 governs: the "
                        + "start goto + 2 steps; 3 executes)",
                Boolean.valueOf(replayProvenance && replayGoverns == 3
                        && replayExecutes == 3),
                Boolean.TRUE);
        check("replay loop: the consequential submit ASKED at "
                        + "replay time — a recording is not a "
                        + "blank check",
                Boolean.valueOf(replayApprovals[0] == 1
                        && replayApproval && typedInto[0] == 1),
                Boolean.TRUE);

        // Step 2 cannot resolve: the replay stops, naming it.
        Routes.Route brokenRoute = new Routes.Route();
        brokenRoute.name = "Broken";
        brokenRoute.startUrl = "https://example.test/login";
        brokenRoute.steps.add(Routes.typeStep(loginEmail, "text",
                "a@b.c"));
        brokenRoute.steps.add(Routes.tapStep(elx(1, "a", "Dashboard",
                "https://example.test/dash")));
        com.ghosthands.app.Trail brokenTrail =
                new com.ghosthands.app.Trail(null);
        new Agent(loginHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), brokenTrail,
                replayUi, "q5_k_m").runRoute(brokenRoute);
        String brokenStop = null, brokenSummary = null;
        for (java.util.Map<String, Object> ev : brokenTrail.events()) {
            if ("stop".equals(String.valueOf(ev.get("type")))) {
                brokenStop = String.valueOf(ev.get("reason"));
                brokenSummary = String.valueOf(ev.get("summary"));
            }
        }
        check("replay loop: an unresolvable second step stops "
                        + "the replay honestly, naming the step",
                Boolean.valueOf("error".equals(brokenStop)
                        && brokenSummary != null
                        && brokenSummary.contains("step 2")
                        && brokenSummary.contains("Dashboard")),
                Boolean.TRUE);

        // The redacted step: replay halts for the user's fill.
        Routes.Route pwRoute = new Routes.Route();
        pwRoute.name = "Password login";
        pwRoute.startUrl = "https://example.test/login";
        pwRoute.steps.add(Routes.typeStep(loginPw, "password",
                "hunter2"));
        pwRoute.steps.add(Routes.tapStep(loginBtn));
        resumeAnswer[0] = true;
        com.ghosthands.app.Trail pwTrail =
                new com.ghosthands.app.Trail(null);
        new Agent(loginHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), pwTrail, replayUi,
                "q5_k_m").runRoute(pwRoute);
        String pwStop = null;
        boolean sawUserFill = false, sawUserFilled = false;
        int pwExecutes = 0;
        for (java.util.Map<String, Object> ev : pwTrail.events()) {
            String t = String.valueOf(ev.get("type"));
            if ("stop".equals(t)) {
                pwStop = String.valueOf(ev.get("reason"));
            }
            if ("route".equals(t) && "user_fill".equals(
                    String.valueOf(ev.get("phase")))) {
                sawUserFill = true;
            }
            if ("route".equals(t) && "user_filled".equals(
                    String.valueOf(ev.get("phase")))
                    && "user".equals(String.valueOf(
                            ev.get("provenance")))) {
                sawUserFilled = true;
            }
            if ("execute".equals(t)) pwExecutes++;
        }
        check("replay loop: the redacted password step halts the "
                        + "replay for the user, who fills it; the "
                        + "route then completes — and the step was "
                        + "never executed (2 executes: goto + tap)",
                Boolean.valueOf("done".equals(pwStop) && sawUserFill
                        && sawUserFilled && pwExecutes == 2),
                Boolean.TRUE);
        check("replay loop: no password text anywhere in the "
                        + "replay's trail",
                Boolean.valueOf(!pwTrail.toJsonLines()
                        .contains("hunter2")),
                Boolean.TRUE);
        resumeAnswer[0] = false;
        com.ghosthands.app.Trail pwTrail2 =
                new com.ghosthands.app.Trail(null);
        new Agent(loginHands, new ModelEngine(),
                PolicyPacks.loadPack("standard"), pwTrail2, replayUi,
                "q5_k_m").runRoute(pwRoute);
        String pwStop2 = null, pwSummary2 = null;
        for (java.util.Map<String, Object> ev : pwTrail2.events()) {
            if ("stop".equals(String.valueOf(ev.get("type")))) {
                pwStop2 = String.valueOf(ev.get("reason"));
                pwSummary2 = String.valueOf(ev.get("summary"));
            }
        }
        check("replay loop: stopping at the password step ends "
                        + "the replay as stopped, naming the step",
                Boolean.valueOf("stopped".equals(pwStop2)
                        && pwSummary2 != null
                        && pwSummary2.contains("step 1")),
                Boolean.TRUE);

        // -- Shots: naming + retention --------------------------------
        check("shots: a trail's photo carries the trail's name",
                Shots.fileNameForTrail("trail-20261025-123456.jsonl"),
                "trail-20261025-123456.jpg");
        check("shots: a path prefix drops; null and empty name "
                        + "nothing",
                Boolean.valueOf("trail-a.jpg".equals(
                                Shots.fileNameForTrail(
                                        "/x/y/trail-a.jsonl"))
                        && Shots.fileNameForTrail(null) == null
                        && Shots.fileNameForTrail("") == null),
                Boolean.TRUE);
        java.util.List<String> shotNames =
                new java.util.ArrayList<String>();
        for (int i = 1; i <= 25; i++) {
            shotNames.add("trail-20261025-0000"
                    + (i < 10 ? "0" : "") + i + ".jpg");
        }
        java.util.List<String> excess25 = Shots.excess(shotNames, 20);
        check("shots: 25 photos keep the newest 20 — the excess "
                        + "is the 5 oldest, in order",
                Boolean.valueOf(excess25.size() == 5
                        && excess25.get(0).endsWith("01.jpg")
                        && excess25.get(4).endsWith("05.jpg")),
                Boolean.TRUE);
        check("shots: within the cap nothing is excess",
                Boolean.valueOf(Shots.excess(shotNames.subList(0, 3),
                        Shots.MAX_KEPT).isEmpty()),
                Boolean.TRUE);

        // -- AnswerCard: the screenshot is card data ------------------
        com.ghosthands.app.Trail shotTrail =
                new com.ghosthands.app.Trail(null);
        shotTrail.record("run", 0, com.ghosthands.app.Trail.fields(
                "goal", "find fares"));
        shotTrail.record("perceive", 1,
                com.ghosthands.app.Trail.fields(
                        "url", "https://example.test/",
                        "title", "Example"));
        shotTrail.record("stop", 2, com.ghosthands.app.Trail.fields(
                "reason", "done", "summary", "Fares found"));
        shotTrail.record("run_summary", 2,
                com.ghosthands.app.Trail.fields(
                        "steps", Long.valueOf(2),
                        "seconds", Long.valueOf(9),
                        "reuse_percent", Long.valueOf(50)));
        shotTrail.record("screenshot", 2,
                com.ghosthands.app.Trail.fields(
                        "file", "trail-20261025-123456.jpg"));
        AnswerCard shotCard = AnswerCard.fromEvents(
                shotTrail.events(), null);
        check("card: the screenshot event becomes the card's photo",
                Boolean.valueOf(shotCard != null
                        && "trail-20261025-123456.jpg".equals(
                                shotCard.screenshotFile)),
                Boolean.TRUE);
        AnswerCard shotCardPast = AnswerCard.fromTrailText(
                shotTrail.toJsonLines(), null);
        check("card: a Past rebuild names the same photo",
                Boolean.valueOf(shotCardPast != null
                        && "trail-20261025-123456.jpg".equals(
                                shotCardPast.screenshotFile)),
                Boolean.TRUE);
        check("card: the share TEXT stays text-only — no photo "
                        + "file name in it or on the card text",
                Boolean.valueOf(!shotCard.shareText("today")
                                .contains(".jpg")
                        && !shotCard.cardText().contains(".jpg")),
                Boolean.TRUE);
        check("card: a run without a screenshot event has no photo",
                Boolean.valueOf(AnswerCard.fromEvents(
                                finishedRunEvents(new String[0],
                                        "done", "ok"), null)
                                .screenshotFile.length() == 0),
                Boolean.TRUE);

        // -- SpeakText: the spoken form --------------------------------
        java.util.List<java.util.Map<String, Object>> speakEvs =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        speakEvs.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", "Find the forecast"));
        speakEvs.add(com.ghosthands.app.Trail.fields(
                "type", "perceive",
                "url", "https://www.weather.gov/forecast",
                "title", "Oakdale Forecast"));
        speakEvs.add(com.ghosthands.app.Trail.fields(
                "type", "note", "status", "added",
                "fact", "Tonight's low is 43 degrees",
                "url", "https://www.weather.gov/forecast",
                "title", "Oakdale Forecast"));
        speakEvs.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "done",
                "summary", "Tonight's low is 43 degrees"));
        AnswerCard speakCard = AnswerCard.fromEvents(speakEvs, null);
        String spoken = SpeakText.of(speakCard);
        check("speak: goal, answer and facts are spoken; the "
                        + "source is named by its TITLE",
                Boolean.valueOf(spoken.contains("Goal: Find the "
                                + "forecast")
                        && spoken.contains("Answer: Tonight's low "
                                + "is 43 degrees")
                        && spoken.contains("Facts noted: Tonight's "
                                + "low is 43 degrees")
                        && spoken.contains("Source: Oakdale "
                                + "Forecast")),
                Boolean.TRUE);
        check("speak: no raw URL is ever spoken",
                Boolean.valueOf(!spoken.contains("http")
                        && !spoken.contains("weather.gov")),
                Boolean.TRUE);
        java.util.List<java.util.Map<String, Object>> speakEvs2 =
                new java.util.ArrayList<java.util.Map<String, Object>>(
                        speakEvs);
        speakEvs2.set(1, com.ghosthands.app.Trail.fields(
                "type", "perceive",
                "url", "https://www.weather.gov/forecast",
                "title", ""));
        String spoken2 = SpeakText.of(
                AnswerCard.fromEvents(speakEvs2, null));
        check("speak: without a title the source is the host's "
                        + "name, www dropped — still no URL",
                Boolean.valueOf(spoken2.contains(
                                "Source: weather.gov")
                        && !spoken2.contains("http")),
                Boolean.TRUE);
        check("speak: a URL inside free text is scrubbed to its "
                        + "host, and the diff arrow becomes a word",
                Boolean.valueOf(SpeakText.scrub(
                                "See https://example.test/page for "
                                + "it").equals("See example.test "
                                + "for it")
                        && SpeakText.scrub("fares $698 -> $617")
                                .equals("fares $698 to $617")),
                Boolean.TRUE);
        java.util.List<java.util.Map<String, Object>> findEvs =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        findEvs.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", "Find the fares"));
        findEvs.add(com.ghosthands.app.Trail.fields(
                "type", "perceive", "url", "https://example.test/",
                "title", "Example"));
        GhAction readAct33 = new GhAction("read");
        findEvs.add(com.ghosthands.app.Trail.fields(
                "type", "execute", "action", readAct33.toMap()));
        findEvs.add(com.ghosthands.app.Trail.fields(
                "type", "result", "ok", Boolean.TRUE,
                "result", "ok: read chars 0-20 of 400 (full page):\n"
                        + "Honolulu $734 and Kahului $698"));
        findEvs.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "stuck",
                "summary", "action repeated 3 times"));
        String spokenFindings = SpeakText.of(
                AnswerCard.fromEvents(findEvs, null));
        check("speak: the findings excerpt case — a stuck run "
                        + "speaks what it found",
                Boolean.valueOf(spokenFindings.contains(
                                "What it found: Honolulu $734 and "
                                + "Kahului $698")
                        && spokenFindings.contains("Outcome:")),
                Boolean.TRUE);
        java.util.List<java.util.Map<String, Object>> watchEvs =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        watchEvs.add(com.ghosthands.app.Trail.fields(
                "type", "run", "goal", "find fares"));
        watchEvs.add(com.ghosthands.app.Trail.fields(
                "type", "stop", "reason", "done",
                "summary", "Kahului $617"));
        java.util.List<Object> diffList33 =
                new java.util.ArrayList<Object>();
        diffList33.add("kahului fare: $698 -> $617");
        watchEvs.add(com.ghosthands.app.Trail.fields(
                "type", "watch", "verdict", "changed",
                "goal_name", "Fares", "since_ms", Long.valueOf(0),
                "diff", diffList33));
        String spokenWatch = SpeakText.of(
                AnswerCard.fromEvents(watchEvs, null));
        check("speak: the watch banner rides in the spoken form",
                Boolean.valueOf(spokenWatch.contains("Watch: "
                                + "changed")
                        && spokenWatch.contains(
                                "kahului fare: $698 to $617")),
                Boolean.TRUE);
        check("speak: the Listen control's labels and enabled "
                        + "rule",
                Boolean.valueOf("🔊 Listen".equals(SpeakText
                                .buttonLabel(true, false))
                        && "■ Stop".equals(SpeakText.buttonLabel(
                                true, true))
                        && "Listen n/a".equals(SpeakText.buttonLabel(
                                false, false))
                        && SpeakText.buttonEnabled(true)
                        && !SpeakText.buttonEnabled(false)),
                Boolean.TRUE);

        System.out.println();
        System.out.println("GovernorTest: " + passed + " passed, " + failed + " failed");
    }
}
