package com.ghosthands.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eyes: perceive the WebView page as a numbered element map, in the
 * spirit of ghost_hands/eyes.py — deliberately cheap and text-only
 * (no screenshots, no vision model). One perception serves the
 * planner (prompt), the governor (target), and the trail (record).
 *
 * The injected script enumerates interactive elements, keeps only
 * visible ones, resolves a human name for each (text, aria-label,
 * placeholder, value, name, title), and stashes the live nodes on
 * window.__gh.els so hands actions can address them by the same
 * numbers the planner sees.
 *
 * v1.1 ordering: candidates are tiered BEFORE numbering (DOM order
 * preserved within a tier) so homepage links cannot bury the controls
 * the planner actually needs — tier 0: search inputs/controls,
 * tier 1: other inputs/textareas/selects, tier 2: buttons/submit
 * controls, tier 3: links and other clickables. The stash order is
 * the numbering: window.__gh.els[i] is element i+1, always.
 *
 * v1.2 changes, driven by the v1.1 field run (a Wikipedia search
 * repeated itself to a stuck stop on Special:Search):
 * - Shaping lives in Java now (shapeMap), not in the injected script:
 *   MAP_JS returns raw candidates in DOM order, Java tiers, caps and
 *   numbers them, and the Activity re-stashes window.__gh.els from
 *   the shaped list's DOM indices. That makes the caps host-testable.
 * - Checkbox/radio inputs are DEMOTED below buttons (tier 3, links
 *   are tier 4) and capped at MAX_CHECKABLES: a search page's dozens
 *   of namespace/filter checkboxes can no longer crowd the result
 *   links out of the map.
 * - At most MAX_CONTROLS_BEFORE_LINK non-link elements may precede
 *   the first link, so links always get room in the map.
 * - Perception carries a short page-text preview (textPreview) and a
 *   read sense (readTextJs / parseRead / formatReadChunk) so the
 *   planner can actually read the page it is on.
 *
 * v2.0: the identity sense for self-healing hands (identityJs /
 * identityScanJs / decideHeal below). A 40-100 s model generation
 * sits between perception and execution, and the live DOM churns in
 * that window, so the stashed node at a perceived index may be gone
 * or replaced by tap time (v1.9 field run: "element 11 not on page"
 * twice). Execution now verifies the stash slot still holds the
 * perceived element's identity and, when it does not, re-finds the
 * element by identity in a fresh scan — acting only on a unique
 * match.
 *
 * v2.5: the candidate net widens past the classic selector
 * (anchors, buttons, form fields, role=button/link, onclick
 * carriers) to a bounded cursor-pointer harvest (ptrHarvest,
 * shared with the heal scan): visible, text-carrying elements
 * whose computed cursor is "pointer" — the clickable divs and
 * spans modern pages use instead of real controls. Candidates
 * carry ElementInfo.pointer; shapeMap caps the harvest and
 * tierOf ranks it with the links.
 *
 * v2.8: main-content reading (the read-sense section below).
 * The v1.8 walk reads a page in document order from the legacy
 * root (main || article || body), so on JS-heavy real sites the
 * front of every read is header/nav/aside chrome and the content
 * sits past the first chunks (the v2.1 weather.com run extracted
 * 2,390 chars of navigation skeleton and died of prompt overflow
 * before reaching the forecast). The read path now scores
 * candidate containers on deterministic metrics (cleaned text
 * length, paragraph-ish share, link density, chrome ancestry),
 * extracts the winning subtree when one clears a clear quality
 * bar, and otherwise falls back to the legacy root walk with
 * chrome subtrees suppressed. The same pick feeds the
 * perception's text preview, so the planner's first glimpse of
 * a page is its content, not its region wall.
 *
 * Prompt economy matters on this hardware (~20 tok/s prompt
 * processing): the map is capped at 40 elements, names at 48 chars,
 * hrefs at 80, and the rendered map at ~1500 chars.
 */
public final class Eyes {

    private Eyes() { }

    public static final int MAX_ELEMENTS = 40;
    public static final int MAP_BUDGET_CHARS = 1500;
    /** v2.4: the marker appended to a goal-matching link's map
     *  line by Perception.render(String) — see there. */
    public static final String GOAL_MATCH_MARKER = " [goal match]";
    /** v1.2: at most this many checkbox/radio inputs survive shaping. */
    public static final int MAX_CHECKABLES = 6;
    /** v1.2: at most this many non-link elements may precede the
     *  first link in the shaped map. */
    public static final int MAX_CONTROLS_BEFORE_LINK = 18;
    /** v1.2: chars of page text one `read` action returns. */
    public static final int READ_CHUNK_CHARS = 2400;
    /** v1.8: cap on the total extracted page text. A long article's
     *  full readable text (all sections, references included) runs
     *  to tens of thousands of chars; the cap keeps one extraction
     *  bounded while the chunk cursor pages through what is kept. */
    public static final int READ_TOTAL_CAP_CHARS = 60000;
    /** v1.2: page-text preview length carried in every perception. */
    public static final int TEXT_PREVIEW_CHARS = 240;
    /** Bound on raw candidates MAP_JS collects (DOM order). */
    public static final int MAX_RAW_CANDIDATES = 600;
    /** v2.5: bound on the cursor-pointer harvest (clickable divs
     *  and spans the classic selector does not name), in document
     *  order. MAP_JS stops its own pass at this many; shapeMap
     *  enforces it again on whatever it is handed, so the cap is
     *  a property of the shaping layer, not of the script. */
    public static final int MAX_POINTER_HARVEST = 12;
    /** v2.8: a container enters the main-content candidate list
     *  only when its raw text is at least this long (a cheap
     *  textContent pre-filter in the collector script; the scored
     *  metrics come from the same visibility-blind walk the
     *  extractor uses, so the two agree about what text is). */
    public static final int READ_CANDIDATE_MIN_RAW = 400;
    /** v2.8: bound on scored read candidates per collection —
     *  the read path's collector (48) and the perception's
     *  lighter pass inside MAP_JS (8, preview duty only). */
    public static final int READ_CANDIDATE_LIMIT = 48;
    public static final int MAP_CANDIDATE_LIMIT = 8;
    /** v2.8: the quality bar's floor — a main-content winner
     *  must carry at least this many cleaned chars (the bar's
     *  share/density terms are in clearsMainBar). */
    public static final int MAIN_CONTENT_MIN_CHARS = 600;
    /** v2.8: the tier names a read result reports (see the
     *  tiered formatReadChunk overload). */
    public static final String READ_TIER_MAIN = "main content";
    public static final String READ_TIER_FULL = "full page";
    /** v2.8: the density-settle probe budget (see the settle
     *  section below): at most this many probes, about this far
     *  apart, and growth of at most this many chars between the
     *  last two probes reads as flat. */
    public static final int SETTLE_MAX_PROBES = 3;
    public static final int SETTLE_INTERVAL_MS = 600;
    public static final int SETTLE_FLAT_DELTA_CHARS = 32;

    /** A perception of the current page. */
    public static class Perception {
        public String url = "";
        public String title = "";
        /** v1.2: short cleaned page-text preview (~240 chars). */
        public String textPreview = "";
        public List<ElementInfo> elements = new ArrayList<ElementInfo>();
        /** v2.8: the page's scored main-content candidates, as
         *  MAP_JS's light collector pass measured them (metrics
         *  only — no text). The read path collects its own fuller
         *  list per read session; this one exists so the preview
         *  preference (pickMainContent over these, then
         *  previewAtJs for the winner's opening text) can run at
         *  perception time. Empty when the script predates v2.8
         *  or found nothing big enough to score. */
        public List<ReadCandidate> readCandidates =
                new ArrayList<ReadCandidate>();
        /** v2.8: index into readCandidates of the legacy read
         *  root (the first <main>, else first <article>) when the
         *  collector admitted it, else -1. */
        public int legacyCandidateIndex = -1;

        public ElementInfo get(int number) {
            for (ElementInfo e : elements) {
                if (e.number == number) return e;
            }
            return null;
        }

        /** Renders like eyes.py: [n] <tag> "name" type=.. href=..
         *  No goal context: no goal-match markers (this is also the
         *  rendering signature() keys on, so stuck detection is
         *  untouched by v2.4's highlighting). */
        public String render() {
            return render(null);
        }

        /**
         * v2.4 (1.5B Max, item 2 — goal-match highlighting): as
         * render(), plus a " [goal match]" marker appended to the
         * line of every LINK whose name matches a significant goal
         * token — the guard family's own matching
         * (RelevanceGuard.significantTokens + nameMatches), shared,
         * not re-implemented. A 1.5B planner scans forty lines
         * badly; the marker points its attention at the handful of
         * links the goal is actually about, using the exact rule
         * the guards will judge its taps by. Links only; inputs
         * and buttons are never marked. The marker is rendering
         * only: it is appended here, downstream of shapeMap (whose
         * dedupe compares ElementInfo fields) and of the identity
         * the heal path compares (also ElementInfo fields), so
         * neither can see it. A null/empty goal, or a page with no
         * matching link, renders byte-identical to render().
         */
        public String render(String goal) {
            return renderElements(elements, goal, Integer.MAX_VALUE,
                    80, MAP_BUDGET_CHARS);
        }

        /** Identity for stuck detection (map text + url). */
        public String signature() {
            String material = render() + "|" + url;
            int h = material.hashCode();
                    return Integer.toHexString(h) + ":" + material.length();
        }
    }

    /**
     * v2.9: the map renderer, parameterized — Perception.render is
     * this with the standing caps (names untruncated — extraction
     * already capped them at 48; hrefs at 80; MAP_BUDGET_CHARS of
     * map text), and its output at those caps is byte-identical to
     * every earlier version. The prompt fitter's map rung renders
     * through here with harder caps (shorter names and hrefs, a
     * smaller char budget) so a dense page's map can shrink
     * without changing what a map line SAYS — the same element,
     * the same number (numbers are the page's own; the hands
     * address the stash by them, so a reduced map must never
     * renumber), only less text per line.
     */
    public static String renderElements(List<ElementInfo> els,
                                        String goal, int nameCap,
                                        int hrefCap, int charBudget) {
        java.util.Set<String> tokens = (goal == null)
                ? null : RelevanceGuard.significantTokens(goal);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < els.size(); i++) {
            ElementInfo el = els.get(i);
            StringBuilder line = new StringBuilder();
            line.append('[').append(el.number).append("] <").append(el.tag)
                    .append("> \"")
                    .append(nameCap == Integer.MAX_VALUE
                            ? el.name : truncate(el.name, nameCap))
                    .append('"');
            if (el.type != null && el.type.length() > 0) {
                line.append(" type=").append(el.type);
            }
            // Compact identifying context when it adds information
            // the name doesn't already carry (labelled fields,
            // placeholder-only controls) — kept short for budget.
            // v2.3: an input's explicitly associated label renders
            // in the bracketed form instead (mapLabelSuffix) — it
            // is the fuller text (120-char cap, not 24), and
            // showing both would carry the same words twice.
            String labelSuffix = mapLabelSuffix(el);
            if (labelSuffix.length() == 0
                    && el.label != null && el.label.length() > 0
                    && !el.label.equals(el.name)
                    && (el.name == null || !el.name.contains(el.label))) {
                line.append(" label=").append(truncate(el.label, 24));
            }
            if (el.placeholder != null && el.placeholder.length() > 0
                    && (el.name == null || !el.name.contains(el.placeholder))
                    && (el.label == null || !el.label.contains(el.placeholder))) {
                line.append(" ph=").append(truncate(el.placeholder, 24));
            }
            line.append(labelSuffix);
            if (el.href != null && el.href.length() > 0) {
                line.append(" href=").append(truncate(el.href, hrefCap));
            }
            // v2.4: the goal-match marker goes last on the line
            // (see Perception.render(String)); it is part of the
            // line's length for the budget accounting, like every
            // other suffix.
            if (tokens != null && isLink(el)
                    && RelevanceGuard.nameMatches(el.name, tokens)) {
                line.append(GOAL_MATCH_MARKER);
            }
            if (used + line.length() + 1 > charBudget) {
                int remaining = els.size() - i;
                sb.append("... truncated: ").append(remaining)
                        .append(" more element(s) not shown\n");
                break;
            }
            sb.append(line).append('\n');
            used += line.length() + 1;
        }
        return sb.toString();
    }

    /**
     * v2.9: the fitter's reduced map set — the top `keep` elements
     * of a shaped map, chosen goal-first: interactive controls
     * (the shaping tiers 0-2 — search boxes, inputs, buttons) and
     * goal-matching links are kept first, in map order; the
     * remaining slots fill in map order. The returned list keeps
     * the map's own order and the elements' own numbers (see
     * renderElements: a reduced map never renumbers). A list no
     * larger than `keep` is returned whole.
     */
    public static List<ElementInfo> topElements(List<ElementInfo> els,
                                                String goal, int keep) {
        List<ElementInfo> out = new ArrayList<ElementInfo>();
        if (els == null) return out;
        if (keep < 0) keep = 0;
        if (els.size() <= keep) {
            out.addAll(els);
            return out;
        }
        java.util.Set<String> tokens = (goal == null)
                ? null : RelevanceGuard.significantTokens(goal);
        boolean[] chosen = new boolean[els.size()];
        int n = 0;
        for (int i = 0; i < els.size() && n < keep; i++) {
            ElementInfo e = els.get(i);
            boolean priority = tierOf(e) <= 2
                    || (tokens != null && isLink(e)
                            && RelevanceGuard.nameMatches(e.name, tokens));
            if (priority) {
                chosen[i] = true;
                n++;
            }
        }
        for (int i = 0; i < els.size() && n < keep; i++) {
            if (!chosen[i]) {
                chosen[i] = true;
                n++;
            }
        }
        for (int i = 0; i < els.size(); i++) {
            if (chosen[i]) out.add(els.get(i));
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /**
     * v2.3 (labels the planner can see): the map-line suffix that
     * shows an INPUT element's explicitly associated label —
     * " [label: Local forecast by \"City, St\" or ZIP code]".
     * Inputs only: v2.2 extracted labelText for the search-target
     * decision, but the rendered map never showed it, so the
     * planner could not see what a bare text box was FOR and had
     * to guess which box a goal wanted. Other tags keep the
     * existing compact label= form; prose pages carry no inputs
     * and their prompts do not grow. Empty when the input has no
     * associated label.
     */
    public static String mapLabelSuffix(ElementInfo e) {
        if (e == null || !"input".equals(e.tag)) return "";
        if (e.labelText == null || e.labelText.length() == 0) return "";
        return " [label: " + e.labelText + "]";
    }

    // ---------------- v1.2 map shaping (Java, host-testable) ---------

    /** Checkbox/radio inputs: the crowd-out culprits of defect A. */
    public static boolean isCheckable(ElementInfo e) {
        if (e == null || !"input".equals(e.tag)) return false;
        return "checkbox".equals(e.type) || "radio".equals(e.type);
    }

    /** A plain link element (the thing controls must not bury). */
    public static boolean isLink(ElementInfo e) {
        return e != null && "a".equals(e.tag);
    }

    /**
     * The tier an element sorts into (lower = earlier in the map):
     *   0 search inputs/controls (type=search, or "search" in the
     *     name/label/placeholder/name-attribute/id haystack)
     *   1 other text-ish inputs, textareas, selects
     *   2 buttons and submit-ish controls
     *   3 checkbox/radio inputs (demoted in v1.2: they sorted as
     *     tier 1 in v1.1 and ate the map on search pages)
     *   4 links and other clickables
     * Mirrors the v1.1 injected-script heuristic, plus the demotion.
     */
    public static int tierOf(ElementInfo e) {
        // v2.5: a cursor-pointer harvest element ranks in the link
        // tier (4), whatever its name or role suggests — it is a
        // clickable surface, not a control, and a "search"-named
        // clickable div must not jump the queue ahead of the real
        // search input.
        if (e.pointer) return 4;
        String type = (e.type == null) ? "" : e.type.toLowerCase();
        StringBuilder hay = new StringBuilder();
        String[] fields = { e.name, e.label, e.placeholder, e.domName, e.domId };
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) {
                if (hay.length() > 0) hay.append(' ');
                hay.append(fields[i]);
            }
        }
        if ("search".equals(type)
                || hay.toString().toLowerCase().contains("search")) {
            return 0;
        }
        if (isCheckable(e)) return 3;
        if ("input".equals(e.tag) || "textarea".equals(e.tag)
                || "select".equals(e.tag)) {
            if ("submit".equals(type) || "button".equals(type)
                    || "reset".equals(type) || "image".equals(type)) {
                return 2;
            }
            return 1;
        }
        if ("button".equals(e.tag) || "button".equals(e.role)) return 2;
        return 4;
    }

    /**
     * Shape raw DOM-order candidates into the numbered map:
     * stable tier sort (DOM order within a tier), checkbox/radio cap,
     * pre-link control cap, total cap, then numbering 1..N. The
     * returned elements keep their domIndex so the Activity can stash
     * exactly this list as window.__gh.els.
     */
    public static List<ElementInfo> shapeMap(List<ElementInfo> raw) {
        // v2.5: the cursor-pointer harvest cap, applied on the raw
        // list in its given (document) order before any sorting:
        // at most MAX_POINTER_HARVEST pointer-marked candidates
        // survive. MAP_JS already stops its pass at the cap; this
        // enforcement makes the bound a shaping property the host
        // harness can pin, whatever produced the candidate list.
        List<ElementInfo> harvestCapped = new ArrayList<ElementInfo>();
        int ptrSeen = 0;
        for (ElementInfo e : raw) {
            if (e.pointer) {
                ptrSeen++;
                if (ptrSeen > MAX_POINTER_HARVEST) continue;
            }
            harvestCapped.add(e);
        }

        List<ElementInfo> sorted = new ArrayList<ElementInfo>(harvestCapped);
        Collections.sort(sorted, new Comparator<ElementInfo>() {
            @Override public int compare(ElementInfo x, ElementInfo y) {
                int t = tierOf(x) - tierOf(y);
                if (t != 0) return t;
                return x.domIndex - y.domIndex;
            }
        });

        // v2.3 dedupe: pages repeat identical chrome (header and
        // footer nav, mirrored menus), and every repeat eats one of
        // the map's 40 slots — weather.gov's map was 40 elements
        // dominated by nav while its real controls were few. Drop
        // later duplicates (isDuplicateElement) before the caps, so
        // the caps budget distinct elements; the FIRST occurrence
        // in shaped (tier/DOM) order survives.
        List<ElementInfo> deduped = new ArrayList<ElementInfo>();
        for (ElementInfo e : sorted) {
            boolean dup = false;
            for (ElementInfo kept : deduped) {
                if (isDuplicateElement(kept, e)) { dup = true; break; }
            }
            if (!dup) deduped.add(e);
        }
        sorted = deduped;

        // Checkbox/radio cap: keep only the first few (in tier/DOM
        // order); the rest are dropped from the map entirely.
        List<ElementInfo> capped = new ArrayList<ElementInfo>();
        int checkables = 0;
        for (ElementInfo e : sorted) {
            if (isCheckable(e)) {
                checkables++;
                if (checkables > MAX_CHECKABLES) continue;
            }
            capped.add(e);
        }

        // Pre-link control cap: everything before the first link is a
        // non-link by definition; if there are more than the cap, drop
        // the excess (the lowest-priority ones, nearest the link) so
        // links are guaranteed room. No link on the page -> no cap.
        int firstLink = -1;
        for (int i = 0; i < capped.size(); i++) {
            if (isLink(capped.get(i))) { firstLink = i; break; }
        }
        if (firstLink > MAX_CONTROLS_BEFORE_LINK) {
            List<ElementInfo> trimmed = new ArrayList<ElementInfo>();
            for (int i = 0; i < capped.size(); i++) {
                if (i >= MAX_CONTROLS_BEFORE_LINK && i < firstLink) continue;
                trimmed.add(capped.get(i));
            }
            capped = trimmed;
        }

        // Total cap + numbering.
        List<ElementInfo> out = new ArrayList<ElementInfo>();
        for (ElementInfo e : capped) {
            if (out.size() >= MAX_ELEMENTS) break;
            e.number = out.size() + 1;
            out.add(e);
        }
        return out;
    }

    /**
     * v2.3: are two shaped candidates the same element for map
     * purposes? Exact match only (the fields as extracted — names
     * and hrefs arrive already trimmed by MAP_JS):
     * - two LINKS are duplicates when name AND href both match:
     *   the same destination offered under the same words is one
     *   choice, however often the chrome repeats it;
     * - two NON-LINKS are duplicates when name, tag and type all
     *   match — with one exception: INPUTS whose explicitly
     *   associated labels (labelText) differ are distinct
     *   controls that merely share a shape (two "Search" boxes
     *   labelled for different forms), so inputs must also agree
     *   on labelText.
     * Near-duplicates survive by design: an href differing by a
     * tracking parameter is a different string, and fuzzy matching
     * could merge elements the planner must tell apart.
     */
    public static boolean isDuplicateElement(ElementInfo a, ElementInfo b) {
        if (a == null || b == null) return false;
        if (!eq(a.tag, b.tag)) return false;
        if (isLink(a)) {
            return eq(a.name, b.name) && eq(a.href, b.href);
        }
        if (!eq(a.name, b.name) || !eq(a.type, b.type)) return false;
        if ("input".equals(a.tag)) {
            return eq(a.labelText, b.labelText);
        }
        return true;
    }

    /**
     * v2.5: the cursor-pointer harvest, shared verbatim by MAP_JS
     * and the heal scan's collectNodes (the two enumerations must
     * agree or a perceived harvest element could never be re-found).
     * ptrHarvest(main) walks the document in order and collects up
     * to MAX_POINTER_HARVEST elements the classic selector does not
     * name: computed cursor "pointer", visible (the same vis() the
     * main pass uses), carrying visible text. Skipped: the main
     * candidates themselves and anything INSIDE one (a span inside
     * a link computes cursor:pointer too, and is not a separate
     * choice), anything inside an already-harvested element (the
     * outermost clickable surface is the one a tap would hit), and
     * the body/document element (a page-level handler is not an
     * element choice). Whether the web's clickable divs deserve the
     * planner's attention is a shaping question; this pass only
     * finds them, in document order.
     */
    private static final String POINTER_HARVEST_JS =
            "function ptrHarvest(main){"
            + "var extra=[];"
            + "var all=document.querySelectorAll('*');"
            + "for(var i=0;i<all.length&&extra.length<" + MAX_POINTER_HARVEST + ";i++){"
            + "var el=all[i];"
            + "if(el===document.body||el===document.documentElement)continue;"
            + "var skip=false;"
            + "for(var j=0;j<main.length;j++){"
            + "if(main[j]===el||main[j].contains(el)){skip=true;break;}}"
            + "if(skip)continue;"
            + "for(var j=0;j<extra.length;j++){"
            + "if(extra[j].contains(el)){skip=true;break;}}"
            + "if(skip)continue;"
            + "if(!vis(el))continue;"
            + "var s=window.getComputedStyle(el);"
            + "if(!s||s.cursor!=='pointer')continue;"
            + "if(!txt(el))continue;"
            + "extra.push(el);}"
            + "return extra;}";

    // ---------------- v2.8 read sense: shared script snippets -------
    //
    // The collector half (READ_SENSE_JS) measures candidate
    // containers; the walk half (READ_WALK_JS) extracts text from
    // a chosen root. Both are shared verbatim by the scripts that
    // use them (MAP_JS's light pass, readCandidatesJs,
    // pageTextLengthJs, readTextAtJs, previewAtJs) so every
    // consumer measures and extracts the same way. The metrics
    // definition: a text node's contribution is its length with
    // whitespace runs collapsed to one space — the same cleaning
    // the extractor's join applies, summed per node so a score
    // never needs the string built. (Boundary slack vs the exact
    // cleaned length is at most one char per node junction; the
    // scores are ratios and floors, never exact totals — the
    // exact total of a served read still comes from the
    // extraction itself.)

    /** JS: candidate metrics + collection. ghMetrics walks a
     *  subtree once counting cleaned text length (len), the
     *  length inside paragraph-ish elements (para: p, li, td/th,
     *  blockquote, dd/dt, pre, h1-h6 — where prose lives), and
     *  the length inside anchors (link). ghChrome marks an
     *  element that is, or sits inside, a header/nav/footer/
     *  aside element or a navigation/banner/contentinfo role.
     *  ghCollectCands enumerates article/main/section/div in
     *  document order, pre-filters on raw text length, scores
     *  the survivors, stashes their nodes on
     *  window.__gh.readCands (list index == stash index), and
     *  reports which index the legacy read root (first main,
     *  else first article) landed at, if it was admitted. */
    private static final String READ_SENSE_JS =
            "var GH_SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};"
            + "var GH_PARA={P:1,LI:1,TD:1,TH:1,BLOCKQUOTE:1,DD:1,DT:1,"
            + "PRE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1};"
            + "function ghMetrics(root){"
            + "var len=0,para=0,link=0;"
            + "function walk(n,inA,inP){"
            + "var kids=n.childNodes;"
            + "for(var i=0;i<kids.length;i++){"
            + "var k=kids[i];"
            + "if(k.nodeType===3){"
            + "var L=k.data.replace(/\\s+/g,' ').length;"
            + "len+=L;if(inA)link+=L;if(inP)para+=L;}"
            + "else if(k.nodeType===1){"
            + "var t=k.tagName;"
            + "if(GH_SKIP[t])continue;"
            + "walk(k,inA||t==='A',inP||!!GH_PARA[t]);"
            + "}}}"
            + "walk(root,false,false);"
            + "return {len:len,para:para,link:link};}"
            + "function ghChrome(el){"
            + "var n=el;"
            + "while(n&&n.nodeType===1){"
            + "var t=n.tagName;"
            + "if(t==='HEADER'||t==='NAV'||t==='FOOTER'||t==='ASIDE')"
            + "return 1;"
            + "var r=n.getAttribute?n.getAttribute('role'):null;"
            + "if(r==='navigation'||r==='banner'||r==='contentinfo')"
            + "return 1;"
            + "n=n.parentElement;}"
            + "return 0;}"
            + "function ghCollectCands(limit){"
            + "var nodes=document.querySelectorAll("
            + "'article,main,section,div');"
            + "var list=[],stash=[];"
            + "var legEl=document.querySelector('main')"
            + "||document.querySelector('article');"
            + "var legacy=-1;"
            + "for(var i=0;i<nodes.length&&list.length<limit;i++){"
            + "var el=nodes[i];"
            + "if(((el.textContent||'').length)<"
            + READ_CANDIDATE_MIN_RAW + ")continue;"
            + "var m=ghMetrics(el);"
            + "if(el===legEl)legacy=list.length;"
            + "list.push({tag:el.tagName.toLowerCase(),len:m.len,"
            + "para:m.para,link:m.link,chrome:ghChrome(el)});"
            + "stash.push(el);}"
            + "window.__gh=window.__gh||{};"
            + "window.__gh.readCands=stash;"
            + "return {list:list,legacy:legacy};}";

    /** JS: the extraction walk (READ_WALK_JS) — the v1.8
     *  visibility-blind DOM walk with the same skip set, block
     *  breaks and cleaning as readTextJs, parameterised by root
     *  and by chrome suppression. ghWalkText(root, supp) extracts
     *  a subtree's full cleaned text (capped like readTextJs);
     *  with supp set, header/nav/footer/aside subtrees and
     *  navigation/banner/contentinfo roles are skipped whole —
     *  used ONLY by the full-page fallback tier (a strong
     *  main-content read is never suppressed). ghPreviewText is
     *  the same walk with an early stop, returning the cleaned
     *  opening TEXT_PREVIEW_CHARS chars. */
    private static final String READ_WALK_JS =
            "var GH_SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};"
            + "var GH_BLOCK={ADDRESS:1,ARTICLE:1,ASIDE:1,BLOCKQUOTE:1,"
            + "DD:1,DIV:1,DL:1,DT:1,FIELDSET:1,FIGCAPTION:1,FIGURE:1,"
            + "FOOTER:1,FORM:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,HEADER:1,"
            + "HR:1,LI:1,MAIN:1,NAV:1,OL:1,P:1,PRE:1,SECTION:1,TABLE:1,"
            + "TBODY:1,TD:1,TFOOT:1,TH:1,THEAD:1,TR:1,UL:1};"
            + "var GH_CHROME={HEADER:1,NAV:1,FOOTER:1,ASIDE:1};"
            + "function ghIsChromeEl(k){"
            + "if(GH_CHROME[k.tagName])return true;"
            + "var r=k.getAttribute?k.getAttribute('role'):null;"
            + "return r==='navigation'||r==='banner'"
            + "||r==='contentinfo';}"
            + "function ghClean(t){"
            + "return t.replace(/[ \\t]+/g,' ')"
            + ".replace(/\\n[ \\t]+/g,'\\n')"
            + ".replace(/\\n{3,}/g,'\\n\\n').trim();}"
            + "function ghWalkText(root,supp){"
            + "var parts=[];"
            + "function walk(n){"
            + "var kids=n.childNodes;"
            + "for(var i=0;i<kids.length;i++){"
            + "var k=kids[i];"
            + "if(k.nodeType===3){parts.push(k.data);}"
            + "else if(k.nodeType===1){"
            + "var tag=k.tagName;"
            + "if(GH_SKIP[tag])continue;"
            + "if(supp&&ghIsChromeEl(k))continue;"
            + "if(tag==='BR'){parts.push('\\n');continue;}"
            + "walk(k);"
            + "if(GH_BLOCK[tag])parts.push('\\n');"
            + "}}}"
            + "if(root)walk(root);"
            + "var t=ghClean(parts.join(''));"
            + "if(t.length>" + READ_TOTAL_CAP_CHARS + ")"
            + "t=t.slice(0," + READ_TOTAL_CAP_CHARS + ");"
            + "return t;}"
            + "function ghPreviewText(root){"
            + "var parts=[],size=0;"
            + "function walk(n){"
            + "var kids=n.childNodes;"
            + "for(var i=0;i<kids.length;i++){"
            + "if(size>800)return;"
            + "var k=kids[i];"
            + "if(k.nodeType===3){parts.push(k.data);size+=k.data.length;}"
            + "else if(k.nodeType===1){"
            + "var tag=k.tagName;"
            + "if(GH_SKIP[tag])continue;"
            + "if(tag==='BR'){parts.push('\\n');size+=1;continue;}"
            + "walk(k);"
            + "if(GH_BLOCK[tag]){parts.push('\\n');size+=1;}"
            + "}}}"
            + "if(root)walk(root);"
            + "return ghClean(parts.join('')).slice(0,"
            + TEXT_PREVIEW_CHARS + ");}";

    /** JS: enumerate interactives in DOM order, stash the nodes on
     *  window.__gh.all, return a JSON string. v1.2: NO tiering, NO
     *  capping in the script — the raw candidate list goes to Java
     *  (shapeMap), which numbers it; the Activity then re-stashes
     *  window.__gh.els from the shaped list (stashJs). Also returns a
     *  short page-text preview (text). */
    public static final String MAP_JS =
            "(function(){"
            + "function vis(e){var r=e.getClientRects();if(!r||r.length===0)return false;"
            + "var s=window.getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden';}"
            + "function txt(e){return ((e.innerText||e.textContent||'').trim().replace(/\\s+/g,' '));}"
            + "function esc(s){return (window.CSS&&CSS.escape)?CSS.escape(s):s.replace(/\"/g,'\\\\\"');}"
            + "function nm(e){var n=txt(e);if(n)return n.slice(0,48);"
            + "var a=e.getAttribute('aria-label');if(a)return a.slice(0,48);"
            + "if(e.placeholder)return e.placeholder.slice(0,48);"
            + "var t=(e.type||'').toLowerCase();"
            + "if(e.value&&(t==='submit'||t==='button'||t==='reset'||t==='image'))return (''+e.value).slice(0,48);"
            + "if(e.tagName==='SELECT'&&e.selectedIndex>=0)return txt(e.options[e.selectedIndex]).slice(0,48);"
            + "if(e.name)return e.name.slice(0,48);"
            + "if(e.title)return e.title.slice(0,48);return '';}"
            + "function lab(e){var id=e.id;if(id){var l=document.querySelector('label[for=\"'+esc(id)+'\"]');"
            + "if(l)return txt(l).slice(0,48);}"
            + "if(e.closest){var w=e.closest('label');if(w)return txt(w).slice(0,48);}return '';}"
            + "function labx(e){var id=e.id;if(id){var l=document.querySelector('label[for=\"'+esc(id)+'\"]');"
            + "if(l)return txt(l).slice(0,120);}"
            + "if(e.closest){var w=e.closest('label');if(w)return txt(w).slice(0,120);}"
            + "var lb=e.getAttribute?e.getAttribute('aria-labelledby'):null;"
            + "if(lb){var ids=lb.split(/\\s+/);var parts=[];"
            + "for(var i=0;i<ids.length;i++){var n=document.getElementById(ids[i]);"
            + "if(n){var s=txt(n);if(s)parts.push(s);}}"
            + "return parts.join(' ').slice(0,120);}return '';}"
            + "function role(e){var r=e.getAttribute('role');if(r)return r;var tag=e.tagName.toLowerCase();"
            + "if(tag==='a')return 'link';if(tag==='button')return 'button';"
            + "if(tag==='select')return 'combobox';if(tag==='textarea')return 'textbox';"
            + "var t=(e.type||'').toLowerCase();"
            + "if(t==='submit'||t==='button'||t==='reset'||t==='image')return 'button';"
            + "if(t==='checkbox')return 'checkbox';if(t==='radio')return 'radio';return 'textbox';}"
            + "function ftxt(f){if(!f)return '';"
            + "var parts=[];"
            + "var lb=f.querySelectorAll('label,legend');"
            + "for(var i=0;i<lb.length;i++){var s=txt(lb[i]);if(s)parts.push(s);}"
            + "var w=document.createTreeWalker(f,4,null);var n;"
            + "while(n=w.nextNode()){"
            + "var p=n.parentElement,ok=true;"
            + "while(p&&p!==f){var tg=p.tagName;"
            + "if(tg==='INPUT'||tg==='SELECT'||tg==='TEXTAREA'||tg==='BUTTON'"
            + "||tg==='LABEL'||tg==='LEGEND'||tg==='SCRIPT'||tg==='STYLE'){ok=false;break;}"
            + "p=p.parentElement;}"
            + "if(!ok)continue;"
            + "var s=(n.nodeValue||'').trim();if(s)parts.push(s);}"
            + "return parts.join(' ').replace(/\\s+/g,' ').slice(0,200);}"
            + POINTER_HARVEST_JS
            + READ_SENSE_JS
            + "function ptxt(){var r=document.querySelector('main')||document.querySelector('article')||document.body;"
            + "if(!r)return '';var t=(r.innerText||'').replace(/\\s+/g,' ').trim();"
            + "return t.slice(0," + TEXT_PREVIEW_CHARS + ");}"
            + "var nodes=document.querySelectorAll(\"a,button,input,select,textarea,[role='button'],[role='link'],[onclick]\");"
            + "var out=[];window.__gh={all:[],els:[]};"
            + "for(var i=0;i<nodes.length&&out.length<" + MAX_RAW_CANDIDATES + ";i++){var el=nodes[i];"
            + "if(!vis(el))continue;"
            + "window.__gh.all.push(el);"
            + "out.push({tag:el.tagName.toLowerCase(),role:role(el),name:nm(el),"
            + "type:(el.type||''),href:(el.href||''),label:lab(el),"
            + "formAction:(el.form&&el.form.action)?el.form.action:'',"
            + "formText:ftxt(el.form),"
            + "labelText:labx(el),"
            + "placeholder:(el.placeholder||''),domId:(el.id||''),"
            + "domName:(el.getAttribute('name')||''),ptr:0});}"
            + "var extra=ptrHarvest(window.__gh.all);"
            + "for(var j=0;j<extra.length&&out.length<" + MAX_RAW_CANDIDATES + ";j++){var el=extra[j];"
            + "window.__gh.all.push(el);"
            + "out.push({tag:el.tagName.toLowerCase(),role:role(el),name:nm(el),"
            + "type:(el.type||''),href:(el.href||''),label:lab(el),"
            + "formAction:(el.form&&el.form.action)?el.form.action:'',"
            + "formText:ftxt(el.form),"
            + "labelText:labx(el),"
            + "placeholder:(el.placeholder||''),domId:(el.id||''),"
            + "domName:(el.getAttribute('name')||''),ptr:1});}"
            // v2.8: a light candidate pass (metrics only) rides
            // along so parseMap can offer the preview preference;
            // it stashes window.__gh.readCands, which previewAtJs
            // addresses by the same indices. The read path runs
            // its own fuller collection per read session.
            + "var rc=ghCollectCands(" + MAP_CANDIDATE_LIMIT + ");"
            + "return JSON.stringify({url:location.href,title:document.title,text:ptxt(),els:out,"
            + "cands:rc.list,legacy:rc.legacy});"
            + "})()";

    /** JS: make window.__gh.els exactly the shaped map's nodes, in
     *  numbering order, addressed by their DOM indices. Called by the
     *  Activity right after parseMap so hands actions address the
     *  same numbers the planner saw. */
    public static String stashJs(List<ElementInfo> elements) {
        StringBuilder sb = new StringBuilder();
        sb.append("(function(){var all=(window.__gh&&window.__gh.all)||[];var idx=[");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(elements.get(i).domIndex);
        }
        sb.append("];var out=[];for(var i=0;i<idx.length;i++){"
                + "var e=all[idx[i]];if(e)out.push(e);}"
                + "window.__gh.els=out;"
                + "return 'ok: stashed '+out.length+' elements';})()");
        return sb.toString();
    }

    /** Parse the MAP_JS result (evaluateJavascript hands back a
     *  JSON-encoded string containing our JSON string), then shape
     *  the raw candidates into the numbered map. */
    public static Perception parseMap(String evaluateResult) {
        Perception p = new Perception();
        if (evaluateResult == null) return p;
        Object outer;
        try {
            outer = Json.parse(evaluateResult);
        } catch (Exception e) {
            return p;
        }
        if (!(outer instanceof String)) return p;
        Object inner;
        try {
            inner = Json.parse((String) outer);
        } catch (Exception e) {
            return p;
        }
        if (!(inner instanceof Map)) return p;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) inner;
        p.url = str(m.get("url"));
        p.title = str(m.get("title"));
        p.textPreview = str(m.get("text"));
        // v2.8: the candidate metrics MAP_JS measured (absent in
        // pre-v2.8 payloads — the list then stays empty and the
        // preview preference simply never fires).
        Object cands = m.get("cands");
        if (cands instanceof List) {
            for (Object o : (List<?>) cands) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = (Map<String, Object>) o;
                p.readCandidates.add(candidateFromMap(cm));
            }
        }
        Object leg = m.get("legacy");
        if (leg instanceof Number) {
            p.legacyCandidateIndex = ((Number) leg).intValue();
        }
        Object els = m.get("els");
        List<ElementInfo> raw = new ArrayList<ElementInfo>();
        if (els instanceof List) {
            for (Object o : (List<?>) els) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> em = (Map<String, Object>) o;
                ElementInfo el = elementFromMap(em);
                el.domIndex = raw.size();
                raw.add(el);
            }
        }
        p.elements = shapeMap(raw);
        return p;
    }

    /** One MAP_JS-shaped identity object -> ElementInfo (domIndex is
     *  the caller's to set: parseMap uses the raw candidate order,
     *  the v2.0 heal scan uses the fresh enumeration order). */
    private static ElementInfo elementFromMap(Map<String, Object> em) {
        ElementInfo el = new ElementInfo();
        el.tag = str(em.get("tag"));
        el.role = str(em.get("role"));
        el.name = str(em.get("name"));
        el.type = str(em.get("type"));
        el.href = str(em.get("href"));
        el.label = str(em.get("label"));
        el.formAction = str(em.get("formAction"));
        el.formText = str(em.get("formText"));
        el.labelText = str(em.get("labelText"));
        Object ptr = em.get("ptr");
        el.pointer = (ptr instanceof Number)
                && ((Number) ptr).intValue() != 0;
        el.placeholder = str(em.get("placeholder"));
        el.domId = str(em.get("domId"));
        el.domName = str(em.get("domName"));
        return el;
    }

    // ---------------- v2.0 self-healing hands ----------------
    //
    // The stale-element defect (v1.9 field run, steps 2 and 4):
    // between perception and execution sits a 40-100 s model
    // generation. The hands address the node stashed at the
    // perceived index (window.__gh.els[n-1]), but the live DOM
    // churns in that window — lazy renders, rotating chrome — so
    // by tap time the stash slot can be empty or hold a different
    // node, and the tap dies with "element N not on page" (or,
    // worse, would have acted on the wrong node). The heal flow:
    // before acting, re-read the identity of the node NOW at the
    // stash slot (identityJs) and compare it in Java against the
    // perceived element's identity (identityMatches). On a match,
    // act exactly as before. On a mismatch, scan the CURRENT DOM
    // (identityScanJs: the same selector + visibility filter +
    // identity extraction MAP_JS uses, no stash side effects) and
    // decide in Java (decideHeal): exactly one identity match ->
    // act on it via the scan-act JS and report the re-find; zero
    // or several -> the original "not on page" error, unchanged.

    /** JS helpers shared by the heal scripts: the visibility test
     *  and identity extraction are MAP_JS's own functions, verbatim
     *  in behaviour, so an identity read at execution time is
     *  comparable to the one perception recorded. collectNodes() is
     *  MAP_JS's raw enumeration (same selector, same visibility
     *  filter, same bound) WITHOUT the window.__gh side effects.
     *  v2.7: through v2.6.1 the collectNodes builder below carried
     *  one closing brace too many (the ptrHarvest append line closed
     *  the function early, leaving `return out;` outside it and the
     *  IIFE closed a brace early), so every script built on these
     *  helpers — identityJs, identityScanJs, tapScanJs, typeScanJs —
     *  was syntactically invalid and could not execute at all. The
     *  host harness now structurally validates every JS string this
     *  class emits, so a malformed builder fails the build's tests
     *  instead of shipping silently. */
    private static final String HEAL_FUNCS_JS =
            "function vis(e){var r=e.getClientRects();if(!r||r.length===0)return false;"
            + "var s=window.getComputedStyle(e);return s.display!=='none'&&s.visibility!=='hidden';}"
            + "function txt(e){return ((e.innerText||e.textContent||'').trim().replace(/\\s+/g,' '));}"
            + "function esc(s){return (window.CSS&&CSS.escape)?CSS.escape(s):s.replace(/\"/g,'\\\\\"');}"
            + "function nm(e){var n=txt(e);if(n)return n.slice(0,48);"
            + "var a=e.getAttribute('aria-label');if(a)return a.slice(0,48);"
            + "if(e.placeholder)return e.placeholder.slice(0,48);"
            + "var t=(e.type||'').toLowerCase();"
            + "if(e.value&&(t==='submit'||t==='button'||t==='reset'||t==='image'))return (''+e.value).slice(0,48);"
            + "if(e.tagName==='SELECT'&&e.selectedIndex>=0)return txt(e.options[e.selectedIndex]).slice(0,48);"
            + "if(e.name)return e.name.slice(0,48);"
            + "if(e.title)return e.title.slice(0,48);return '';}"
            + "function lab(e){var id=e.id;if(id){var l=document.querySelector('label[for=\"'+esc(id)+'\"]');"
            + "if(l)return txt(l).slice(0,48);}"
            + "if(e.closest){var w=e.closest('label');if(w)return txt(w).slice(0,48);}return '';}"
            + "function labx(e){var id=e.id;if(id){var l=document.querySelector('label[for=\"'+esc(id)+'\"]');"
            + "if(l)return txt(l).slice(0,120);}"
            + "if(e.closest){var w=e.closest('label');if(w)return txt(w).slice(0,120);}"
            + "var lb=e.getAttribute?e.getAttribute('aria-labelledby'):null;"
            + "if(lb){var ids=lb.split(/\\s+/);var parts=[];"
            + "for(var i=0;i<ids.length;i++){var n=document.getElementById(ids[i]);"
            + "if(n){var s=txt(n);if(s)parts.push(s);}}"
            + "return parts.join(' ').slice(0,120);}return '';}"
            + "function role(e){var r=e.getAttribute('role');if(r)return r;var tag=e.tagName.toLowerCase();"
            + "if(tag==='a')return 'link';if(tag==='button')return 'button';"
            + "if(tag==='select')return 'combobox';if(tag==='textarea')return 'textbox';"
            + "var t=(e.type||'').toLowerCase();"
            + "if(t==='submit'||t==='button'||t==='reset'||t==='image')return 'button';"
            + "if(t==='checkbox')return 'checkbox';if(t==='radio')return 'radio';return 'textbox';}"
            + "function ftxt(f){if(!f)return '';"
            + "var parts=[];"
            + "var lb=f.querySelectorAll('label,legend');"
            + "for(var i=0;i<lb.length;i++){var s=txt(lb[i]);if(s)parts.push(s);}"
            + "var w=document.createTreeWalker(f,4,null);var n;"
            + "while(n=w.nextNode()){"
            + "var p=n.parentElement,ok=true;"
            + "while(p&&p!==f){var tg=p.tagName;"
            + "if(tg==='INPUT'||tg==='SELECT'||tg==='TEXTAREA'||tg==='BUTTON'"
            + "||tg==='LABEL'||tg==='LEGEND'||tg==='SCRIPT'||tg==='STYLE'){ok=false;break;}"
            + "p=p.parentElement;}"
            + "if(!ok)continue;"
            + "var s=(n.nodeValue||'').trim();if(s)parts.push(s);}"
            + "return parts.join(' ').replace(/\\s+/g,' ').slice(0,200);}"
            + "function identOf(e){return {tag:e.tagName.toLowerCase(),role:role(e),name:nm(e),"
            + "type:(e.type||''),href:(e.href||''),label:lab(e),"
            + "formAction:(e.form&&e.form.action)?e.form.action:'',"
            + "formText:ftxt(e.form),"
            + "labelText:labx(e),"
            + "placeholder:(e.placeholder||''),domId:(e.id||''),"
            + "domName:(e.getAttribute('name')||'')}}"
            + POINTER_HARVEST_JS
            + "function collectNodes(){"
            + "var nodes=document.querySelectorAll(\"a,button,input,select,textarea,[role='button'],[role='link'],[onclick]\");"
            + "var out=[];"
            + "for(var i=0;i<nodes.length&&out.length<" + MAX_RAW_CANDIDATES + ";i++){"
            + "if(!vis(nodes[i]))continue;out.push(nodes[i]);}"
            + "var ex=ptrHarvest(out);"
            + "for(var j=0;j<ex.length;j++)out.push(ex[j]);"
            + "return out;}";

    /** JS: the identity of the node NOW stashed at slot n (1-based,
     *  the planner's number), as {found, el} — the execution-time
     *  counterpart of the perceived element. */
    public static String identityJs(int n) {
        return "(function(){" + HEAL_FUNCS_JS
                + "var e=window.__gh&&window.__gh.els[" + (n - 1) + "];"
                // v2.7: a stashed node the page has since REPLACED
                // (framework re-render) is detached: its identity
                // still reads perfectly, but tapping it taps
                // nothing. Report it as not found so the scan can
                // re-find the live node instead.
                + "if(!e||e.isConnected===false)"
                + "return JSON.stringify({found:false});"
                + "return JSON.stringify({found:true,el:identOf(e)});})()";
    }

    /** JS: a fresh identity scan of the live DOM — every visible
     *  interactive's identity object, in MAP_JS's raw enumeration
     *  order. Read-only: window.__gh is not touched. */
    public static String identityScanJs() {
        return "(function(){" + HEAL_FUNCS_JS
                + "var ns=collectNodes();var out=[];"
                + "for(var i=0;i<ns.length;i++)out.push(identOf(ns[i]));"
                + "return JSON.stringify({els:out});})()";
    }

    /** Parse an identityJs result (the decoded JSON text, as
     *  parseRead takes its input): the ElementInfo now at the
     *  stash slot, or null when the slot is empty. */
    public static ElementInfo parseIdentity(String jsonText) {
        if (jsonText == null) return null;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        if (!Boolean.TRUE.equals(m.get("found"))) return null;
        Object el = m.get("el");
        if (!(el instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Map<String, Object> em = (Map<String, Object>) el;
        return elementFromMap(em);
    }

    /** Parse an identityScanJs result: the raw identity list in
     *  enumeration order (domIndex = scan position). */
    public static List<ElementInfo> parseIdentityScan(String jsonText) {
        List<ElementInfo> out = new ArrayList<ElementInfo>();
        if (jsonText == null) return out;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return out;
        }
        if (!(parsed instanceof Map)) return out;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object els = m.get("els");
        if (els instanceof List) {
            for (Object o : (List<?>) els) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> em = (Map<String, Object>) o;
                ElementInfo el = elementFromMap(em);
                el.domIndex = out.size();
                out.add(el);
            }
        }
        return out;
    }

    // ---------------- v2.1 search-target choice ----------------
    //
    // The `search` action's path (b) picks its target from the
    // perceived map. Through v2.0 the hands accepted only inputs
    // that look like search boxes (type=search, or "search" in the
    // input's own name/label/placeholder) — which missed
    // weather.gov's forecast lookup: its input is a plain text
    // field named "inputstring", and only the FORM around it says
    // what it is for ('Local forecast by "City, St" or ZIP code').
    // The run fell back to DuckDuckGo (field trail-20261024-
    // 230228, step 1). The decisions below are factored purely so
    // the host harness can pin them; the hands call
    // pickSearchTarget and fall through to the DuckDuckGo
    // fallback on null, exactly as before.

    /** Lookup words: a form whose visible text carries one of
     *  these is a lookup form (search / location / city / ZIP). */
    private static final String[] FORM_LOOKUP_WORDS = {
            "search", "location", "city", "zip", "postal",
    };

    /** The pre-v2.1 acceptance rule, unchanged: an input/textarea
     *  of type=search, or one whose own name/label/placeholder
     *  mentions "search". */
    public static boolean isSearchInput(ElementInfo e) {
        if (e == null) return false;
        if (!"input".equals(e.tag) && !"textarea".equals(e.tag)) {
            return false;
        }
        if ("search".equals(e.type)) return true;
        return mentionsSearch(e.name) || mentionsSearch(e.label)
                || mentionsSearch(e.placeholder);
    }

    private static boolean mentionsSearch(String s) {
        return s != null && s.toLowerCase().contains("search");
    }

    /** A visible text-like input: an <input> whose type takes free
     *  text (text, search, or no type — the DOM reports a typeless
     *  input as "text"). Visibility is the map's own guarantee:
     *  MAP_JS only admits visible candidates. */
    public static boolean isTextLikeInput(ElementInfo e) {
        if (e == null || !"input".equals(e.tag)) return false;
        String t = (e.type == null) ? "" : e.type.toLowerCase();
        return t.length() == 0 || "text".equals(t) || "search".equals(t);
    }

    /** True when a form's visible text (ElementInfo.formText —
     *  labels, legend and plain text within the form) mentions a
     *  lookup word. */
    public static boolean formSuggestsLookup(String formText) {
        if (formText == null) return false;
        String f = formText.toLowerCase();
        for (int i = 0; i < FORM_LOOKUP_WORDS.length; i++) {
            if (f.contains(FORM_LOOKUP_WORDS[i])) return true;
        }
        return false;
    }

    /** v2.2: true when an element's EXPLICITLY associated label
     *  text (ElementInfo.labelText — a <label for>, a wrapping
     *  <label>, or an aria-labelledby target) mentions a lookup
     *  word: the same vocabulary as formSuggestsLookup, but the
     *  signal is one page author pointing at THIS input, not the
     *  ambient text of whatever form surrounds it. */
    public static boolean labelSuggestsLookup(String labelText) {
        if (labelText == null) return false;
        String f = labelText.toLowerCase();
        for (int i = 0; i < FORM_LOOKUP_WORDS.length; i++) {
            if (f.contains(FORM_LOOKUP_WORDS[i])) return true;
        }
        return false;
    }

    /** The v2.1 broader rule: a text-like input whose enclosing
     *  form's visible text marks the form as a lookup form. */
    public static boolean isLookupBox(ElementInfo e) {
        return isTextLikeInput(e) && formSuggestsLookup(e.formText);
    }

    /** The v2.2 explicit-label rule: a text-like input whose own
     *  associated label marks it as a lookup box. */
    public static boolean isLabelledLookupBox(ElementInfo e) {
        return isTextLikeInput(e) && labelSuggestsLookup(e.labelText);
    }

    /**
     * Choose the element the `search` action types into, or null
     * when there is no safe choice (the caller then uses the
     * DuckDuckGo fallback, as it always has). Tiers, in order:
     *   1. the first element the classic rule accepts
     *      (isSearchInput) — v2.0 behaviour, untouched;
     *   2. v2.2, the explicit-label rule: if EXACTLY ONE text-like
     *      input's associated label (isLabelledLookupBox) marks
     *      it as a lookup box, it — an explicit label association
     *      beats ANY number of ambient form-text candidates,
     *      because the page author named this input's purpose.
     *      Two or more labelled candidates fall through without
     *      picking from this tier (the ambient rule below then
     *      sees them too, and its ambiguity refusal applies);
     *   3. the v2.1 ambient rule: if EXACTLY ONE element's form
     *      text qualifies it (isLookupBox), it; if two or more
     *      qualify, null — ambiguity is refused, never guessed.
     *
     * Why tier 2 exists (v2.1 field miss, diagnosed against
     * weather.gov's real HTML): the homepage carries TWO
     * location-shaped text inputs — "inputstring" in the forecast
     * form and the MyForecast widget's — so the ambient rule saw
     * both qualify and refused, and the run fell back to
     * DuckDuckGo. The missed signal: "inputstring" has an
     * explicit <label for="inputstring">Local forecast by "City,
     * St" or ZIP code</label>. Tier 2 keys on exactly that.
     */
    public static ElementInfo pickSearchTarget(List<ElementInfo> elements) {
        if (elements == null) return null;
        for (ElementInfo e : elements) {
            if (isSearchInput(e)) return e;
        }
        ElementInfo labelled = null;
        int labelledCount = 0;
        for (ElementInfo e : elements) {
            if (isLabelledLookupBox(e)) {
                labelledCount++;
                labelled = e;
            }
        }
        if (labelledCount == 1) return labelled;
        ElementInfo only = null;
        for (ElementInfo e : elements) {
            if (isLookupBox(e)) {
                if (only != null) return null;
                only = e;
            }
        }
        return only;
    }

    // ---------------- v2.3 search result naming ----------------
    //
    // When the search executed through a lookup box (the v2.2
    // explicit-label tier or the v2.1 ambient tier), the result
    // string names the box it used, so the planner learns what
    // kind of box it just typed into — a location box that answers
    // with a list of matching places reads very differently from a
    // general search box, and the follow-up page's behaviour only
    // makes sense with that knowledge. The classic tier's result
    // string is unchanged from v2.0.

    /** searchTargetTier results. */
    public static final int SEARCH_TIER_NONE = -1;
    public static final int SEARCH_TIER_CLASSIC = 0;
    public static final int SEARCH_TIER_LABELLED = 1;
    public static final int SEARCH_TIER_AMBIENT = 2;

    /**
     * Which pickSearchTarget tier an element satisfies, judged on
     * the element itself (the chooser's count conditions — exactly
     * one labelled / ambient candidate — are what picked it; this
     * only classifies the winner): classic when the pre-v2.1 rule
     * accepts it, else labelled when its own associated label
     * marks it a lookup box, else ambient when its form's text
     * does, else NONE.
     */
    public static int searchTargetTier(ElementInfo e) {
        if (e == null) return SEARCH_TIER_NONE;
        if (isSearchInput(e)) return SEARCH_TIER_CLASSIC;
        if (isLabelledLookupBox(e)) return SEARCH_TIER_LABELLED;
        if (isLookupBox(e)) return SEARCH_TIER_AMBIENT;
        return SEARCH_TIER_NONE;
    }

    /** The text that names a search target in the result string:
     *  its associated label for the labelled tier, its form's text
     *  for the ambient tier, null for classic / no tier (the
     *  classic result string names nothing and stays unchanged). */
    public static String searchBoxText(ElementInfo e) {
        int tier = searchTargetTier(e);
        if (tier == SEARCH_TIER_LABELLED) return e.labelText;
        if (tier == SEARCH_TIER_AMBIENT) return e.formText;
        return null;
    }

    /** The labelled/ambient search result string, in substance:
     *  ok: searched via the page's box ("Local forecast by "City,
     *  St" or ZIP code") for "Oakdale, Tennessee forecast". */
    public static String formatSearchedViaBox(String boxText, String query) {
        return "ok: searched via the page's box (\"" + boxText
                + "\") for \"" + query + "\"";
    }

    // ---------------- v1.2 read sense ----------------

    /** Per-page read position: repeated `read` actions walk the page
     *  text chunk by chunk; a new URL restarts at the top. */
    public static class ReadCursor {
        public String url = "";
        public int offset = 0;

        /** v2.3: URLs whose thin read already spent its one
         *  settle/scroll retry this run (see the thin-read section
         *  below). Lives with the read state; WebHands is created
         *  per run, so the set is per run. */
        public final Set<String> thinRetriedUrls =
                new LinkedHashSet<String>();

        public void resetFor(String u) {
            if (u == null) u = "";
            if (!u.equals(url)) {
                url = u;
                offset = 0;
            }
        }
    }

    /** One chunk of extracted page text, as returned by readTextJs. */
    public static class ReadChunk {
        public int total = 0;
        public int start = 0;
        public String text = "";
    }

    /** JS: extract readable page text and return one slice of it.
     *  Prefers <main>, then <article>, then the body; horizontal
     *  whitespace is collapsed and blank-line runs are squeezed, so
     *  the text is dense enough for a small planner. Returns JSON
     *  {total, start, text} where total is the full cleaned length
     *  (capped at READ_TOTAL_CAP_CHARS).
     *
     *  v1.8 EXTRACTION FIX. Through v1.7 this read
     *  `root.innerText || root.textContent`. That was the v1.7
     *  field run's silent truncation: the Clinch River article's
     *  readable text totalled 1,473 chars — the title chrome, the
     *  hatnote and the lead paragraph, nothing more. The cause is
     *  innerText's spec semantics: it returns only RENDERED text,
     *  so a subtree styled display:none contributes nothing. An
     *  Android WebView receives Wikipedia's mobile presentation,
     *  whose article sections past the lead live in collapsed
     *  (display:none) containers until the reader expands them —
     *  the full article is in the DOM, invisible to innerText, and
     *  the textContent fallback never engaged because innerText
     *  was non-empty. The extraction below therefore walks the DOM
     *  itself, collecting text nodes regardless of visibility
     *  (skipping script/style/noscript/iframe/svg subtrees and
     *  restoring block-level line breaks), so collapsed sections
     *  are read like any other. The MAP_JS preview keeps innerText:
     *  a 240-char "what is on screen" glimpse is its whole job. */
    public static String readTextJs(int start, int len) {
        return "(function(){"
                + "var root=document.querySelector('main')"
                + "||document.querySelector('article')||document.body;"
                + "var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,SVG:1};"
                + "var BLOCK={ADDRESS:1,ARTICLE:1,ASIDE:1,BLOCKQUOTE:1,"
                + "DD:1,DIV:1,DL:1,DT:1,FIELDSET:1,FIGCAPTION:1,FIGURE:1,"
                + "FOOTER:1,FORM:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,HEADER:1,"
                + "HR:1,LI:1,MAIN:1,NAV:1,OL:1,P:1,PRE:1,SECTION:1,TABLE:1,"
                + "TBODY:1,TD:1,TFOOT:1,TH:1,THEAD:1,TR:1,UL:1};"
                + "var parts=[];"
                + "function walk(n){"
                + "var kids=n.childNodes;"
                + "for(var i=0;i<kids.length;i++){"
                + "var k=kids[i];"
                + "if(k.nodeType===3){parts.push(k.data);}"
                + "else if(k.nodeType===1){"
                + "var tag=k.tagName;"
                + "if(SKIP[tag])continue;"
                + "if(tag==='BR'){parts.push('\\n');continue;}"
                + "walk(k);"
                + "if(BLOCK[tag])parts.push('\\n');"
                + "}}}"
                + "if(root)walk(root);"
                + "var t=parts.join('');"
                + "t=t.replace(/[ \\t]+/g,' ').replace(/\\n[ \\t]+/g,'\\n')"
                + ".replace(/\\n{3,}/g,'\\n\\n').trim();"
                + "if(t.length>" + READ_TOTAL_CAP_CHARS + ")"
                + "t=t.slice(0," + READ_TOTAL_CAP_CHARS + ");"
                + "var s=Math.max(0," + start + ");"
                + "return JSON.stringify({total:t.length,start:s,"
                + "text:t.slice(s,s+" + len + ")});"
                + "})()";
    }

    /** Parse a readTextJs result (the decoded JSON text). */
    public static ReadChunk parseRead(String jsonText) {
        ReadChunk c = new ReadChunk();
        if (jsonText == null) return c;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return c;
        }
        if (!(parsed instanceof Map)) return c;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object total = m.get("total");
        Object start = m.get("start");
        if (total instanceof Number) c.total = ((Number) total).intValue();
        if (start instanceof Number) c.start = ((Number) start).intValue();
        c.text = str(m.get("text"));
        return c;
    }

    /** The result string a `read` execution reports (and whose text
     *  the agent history carries into the next prompt). */
    public static String formatReadChunk(int start, String chunk, int total) {
        return formatReadChunk(start, chunk, total, null);
    }

    /**
     * v2.8: as above, naming the tier that served the read —
     * "ok: read chars 0-2400 of 8412 (main content); ..." or
     * "(full page)". The tier sits between the total and the
     * semicolon on purpose: Agent's parsers key on this framing
     * (readTotalChars reads the digits after " of ",
     * readReachedEnd looks for "; end of page text",
     * autoReadSection takes the range up to the ';'), and all
     * three read a tiered string exactly as they read the
     * untiered one. A null/empty tier renders the pre-v2.8
     * string byte-for-byte.
     */
    public static String formatReadChunk(int start, String chunk, int total,
                                         String tier) {
        int end = start + (chunk == null ? 0 : chunk.length());
        StringBuilder sb = new StringBuilder();
        sb.append("ok: read chars ").append(start).append('-').append(end)
                .append(" of ").append(total);
        if (tier != null && tier.length() > 0) {
            sb.append(" (").append(tier).append(')');
        }
        sb.append(end < total ? "; read again for more" : "; end of page text");
        sb.append(":\n").append(chunk == null ? "" : chunk);
        return sb.toString();
    }

    // ---------------- v2.3 thin-page read retry ----------------
    //
    // Field evidence (v2.1/v2.2 weather runs): weather.com's 10-day
    // page extracted only 2,390 chars, all navigation skeleton —
    // script-driven pages hydrate late, or only when scrolled into
    // view, so a single extraction taken the moment the agent asks
    // can see a fraction of the text the page will shortly carry.
    // When a read's total comes back thin, the hands now give the
    // page one chance to finish rendering before believing it:
    // wait, re-extract, and (if still thin) scroll through it once
    // to trip lazy rendering, then extract again, keeping the best
    // attempt. One retry per URL per run. The decisions below are
    // pure; the waiting/scrolling/extraction lives in WebHands.

    /** A read whose extracted total is below this many chars is
     *  "thin": likelier a half-rendered page than a genuinely
     *  small one (a real article or forecast runs far past it;
     *  weather.com's skeleton was 2,390). */
    public static final int THIN_READ_THRESHOLD = 3000;

    /** Is this extracted total thin? */
    public static boolean isThinRead(int total) {
        return total < THIN_READ_THRESHOLD;
    }

    /** Should this read spend the URL's one thin retry? Only for a
     *  thin total on a URL not yet retried this run. */
    public static boolean shouldThinRetry(String url,
                                          Set<String> retriedUrls,
                                          int total) {
        if (url == null || !isThinRead(total)) return false;
        return retriedUrls == null || !retriedUrls.contains(url);
    }

    /** The better of two extractions of the same page at the same
     *  offset: the one whose total is larger; on a tie, the LATER
     *  one (b) — it read the more settled DOM. Null-safe: a null
     *  yields the other. */
    public static ReadChunk pickBestExtraction(ReadChunk a, ReadChunk b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.total >= a.total ? b : a;
    }

    /** The read result's honest account of a spent retry: the page
     *  GREW (it really was still rendering), or it stayed thin (the
     *  text simply is not there — the retry cannot conjure what a
     *  page never puts in the DOM). */
    public static String thinRetryNote(int firstTotal, int finalTotal) {
        if (finalTotal > firstTotal) {
            return "(page rendered thin at first: " + firstTotal
                    + " chars, " + finalTotal + " after settle+scroll)";
        }
        return "(page still thin after retry: " + finalTotal + " chars)";
    }

    // ---------------- v2.8 main-content reading ----------------
    //
    // The decision layer for the read-sense scripts above. All
    // pure: the scripts collect faithful metrics, these methods
    // judge them, and the host harness pins the judgments against
    // fixtures measured on real pages (the DuckDuckGo lite
    // results mirror, the Wikipedia Clinch article, the
    // weather.gov homepage, and a synthetic weather.com shape).
    //
    // The bar (clearsMainBar), all integer ratios so the verdict
    // is identical on every JVM and in every mirror of the rule:
    //   - not chrome (the candidate is not, and does not sit
    //     inside, a header/nav/footer/aside element or a
    //     navigation/banner/contentinfo role);
    //   - at least MAIN_CONTENT_MIN_CHARS of cleaned text;
    //   - paragraph-ish share at least 1/2 (paraLen * 2 >=
    //     textLen): at least half the text lives where prose
    //     lives. This is the separator that matters in practice:
    //     measured paragraph share is ~0.9 for an article body
    //     and ~0.1 for a search-results block, whose snippets
    //     live in link and layout markup instead;
    //   - link density at most 2/5 (linkLen * 5 <= textLen * 2):
    //     past two fifths link text a block is an index, not
    //     prose — the measured nav-wrapper that motivated the
    //     cap sat at 0.48 while Wikipedia's <main> (wikilinks
    //     and all) sits at ~0.33.
    // The score rewards prose mass discounted by link density:
    // paraLen * (textLen - linkLen) / textLen.
    //
    // The pick (pickMainContent):
    //   1. The LEGACY ROOT WINS WHEN IT IS STRONG: when the
    //      collector admitted the element readTextJs would root
    //      at (first main, else first article) and it clears the
    //      bar, it is picked — its extraction is then the very
    //      text the pre-v2.8 extractor produced, byte-for-byte,
    //      so pages that already read well cannot regress.
    //   2. Otherwise the best score wins, with a tightness
    //      tie-break: any clearer scoring within 9/10 of the
    //      best and carrying less text beats it (nested wrappers
    //      around the same content differ mainly by the chrome
    //      they dilute it with; the tightest near-best container
    //      is the content core). Remaining ties go to document
    //      order.
    //   3. No clearer -> -1: the full-page tier serves (legacy
    //      root, chrome subtrees suppressed) — never worse in
    //      kind than the pre-v2.8 walk, and on pages with no
    //      semantic chrome elements (the DDG mirror has none)
    //      byte-identical to it.

    /** One scored container: metrics only, as the collector
     *  measured them (see READ_SENSE_JS for definitions). */
    public static class ReadCandidate {
        public String tag = "";
        public int textLen = 0;
        public int paraLen = 0;
        public int linkLen = 0;
        public boolean chrome = false;
    }

    /** A collection: the candidates in document order plus the
     *  index of the legacy read root among them (-1 when the
     *  root was not admitted — no main/article, or it fell under
     *  the collector's size floor). */
    public static class ReadCandidates {
        public final List<ReadCandidate> list =
                new ArrayList<ReadCandidate>();
        public int legacyIndex = -1;
    }

    /** One collector-shaped candidate object -> ReadCandidate. */
    private static ReadCandidate candidateFromMap(Map<String, Object> cm) {
        ReadCandidate c = new ReadCandidate();
        c.tag = str(cm.get("tag"));
        c.textLen = num(cm.get("len"));
        c.paraLen = num(cm.get("para"));
        c.linkLen = num(cm.get("link"));
        Object ch = cm.get("chrome");
        c.chrome = (ch instanceof Number)
                && ((Number) ch).intValue() != 0;
        return c;
    }

    private static int num(Object o) {
        return (o instanceof Number) ? ((Number) o).intValue() : 0;
    }

    /** Parse a readCandidatesJs result (the decoded JSON text):
     *  the candidate list and the legacy root's index. A failed
     *  or foreign payload parses as an empty collection with
     *  legacyIndex -1 — the read then falls back, as it may. */
    public static ReadCandidates parseReadCandidates(String jsonText) {
        ReadCandidates out = new ReadCandidates();
        if (jsonText == null) return out;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return out;
        }
        if (!(parsed instanceof Map)) return out;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object leg = m.get("legacy");
        if (leg instanceof Number) {
            out.legacyIndex = ((Number) leg).intValue();
        }
        Object cands = m.get("cands");
        if (cands instanceof List) {
            for (Object o : (List<?>) cands) {
                if (!(o instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = (Map<String, Object>) o;
                out.list.add(candidateFromMap(cm));
            }
        }
        return out;
    }

    /** JS: collect the read candidates (the fuller pass the read
     *  path runs per session; MAP_JS carries the light one). Also
     *  stashes the candidate nodes on window.__gh.readCands —
     *  readTextAtJs / previewAtJs address them by list index. */
    public static String readCandidatesJs() {
        return "(function(){" + READ_SENSE_JS
                + "var rc=ghCollectCands(" + READ_CANDIDATE_LIMIT + ");"
                + "return JSON.stringify({cands:rc.list,legacy:rc.legacy});"
                + "})()";
    }

    /** JS: extract one slice of text from a chosen root.
     *  candIndex >= 0: the stashed candidate node — the
     *  main-content tier (verbatim walk, no suppression; a strong
     *  read never shrinks). A candidate node that is gone or was
     *  replaced (the stash dies with every MAP_JS, and pages
     *  re-render) reports total -1, the caller's signal to
     *  re-collect and retry once. candIndex < 0: the full-page
     *  tier — the legacy root with chrome subtrees suppressed. */
    public static String readTextAtJs(int candIndex, int start, int len) {
        return "(function(){" + READ_WALK_JS
                + "var idx=" + candIndex + ";"
                + "var root=null,supp=false;"
                + "if(idx>=0){"
                + "var cands=(window.__gh&&window.__gh.readCands)||[];"
                + "root=cands[idx];"
                + "if(!root||root.isConnected===false)"
                + "return JSON.stringify({total:-1,start:0,text:''});"
                + "}else{"
                + "root=document.querySelector('main')"
                + "||document.querySelector('article')||document.body;"
                + "supp=true;}"
                + "var t=ghWalkText(root,supp);"
                + "var s=Math.max(0," + start + ");"
                + "return JSON.stringify({total:t.length,start:s,"
                + "text:t.slice(s,s+" + len + ")});"
                + "})()";
    }

    /** JS: the stashed candidate's opening text (the walk's own
     *  cleaned opening, TEXT_PREVIEW_CHARS) for the perception
     *  preview preference. An empty string when the node is gone
     *  or carries no text — the fallback preview then stands. */
    public static String previewAtJs(int candIndex) {
        return "(function(){" + READ_WALK_JS
                + "var cands=(window.__gh&&window.__gh.readCands)||[];"
                + "var root=cands[" + candIndex + "];"
                + "if(!root||root.isConnected===false)"
                + "return JSON.stringify({text:''});"
                + "return JSON.stringify({text:ghPreviewText(root)});"
                + "})()";
    }

    /** Parse a previewAtJs result (the decoded JSON text). */
    public static String parsePreviewText(String jsonText) {
        if (jsonText == null) return "";
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return "";
        }
        if (!(parsed instanceof Map)) return "";
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        return str(m.get("text"));
    }

    /** The preview the planner sees: the winning candidate's
     *  opening text when it exists, else the page's standing
     *  preview. (Item 3 of v2.8: the preview prefers content.
     *  Title, URL and the element map — everything the
     *  recognition rules read — are untouched by this choice.) */
    public static String preferredPreview(String fallback,
                                          String winnerText) {
        if (winnerText != null && winnerText.length() > 0) {
            return winnerText;
        }
        return fallback == null ? "" : fallback;
    }

    /** The quality bar (see the section comment). */
    public static boolean clearsMainBar(ReadCandidate c) {
        if (c == null || c.chrome) return false;
        if (c.textLen < MAIN_CONTENT_MIN_CHARS) return false;
        if (c.paraLen * 2 < c.textLen) return false;
        if (c.linkLen * 5 > c.textLen * 2) return false;
        return true;
    }

    /** The ranking score: paragraph-ish mass discounted by the
     *  non-link share of the text. Integer arithmetic, floored. */
    public static int mainContentScore(ReadCandidate c) {
        if (c == null || c.textLen <= 0) return 0;
        return (int) ((long) c.paraLen * (c.textLen - c.linkLen)
                / c.textLen);
    }

    /** Pick the main-content winner (see the section comment):
     *  the legacy root when it is strong, else the tightest
     *  near-best clearer, else -1 for the full-page tier. */
    public static int pickMainContent(List<ReadCandidate> cands,
                                      int legacyIndex) {
        if (cands == null) return -1;
        if (legacyIndex >= 0 && legacyIndex < cands.size()
                && clearsMainBar(cands.get(legacyIndex))) {
            return legacyIndex;
        }
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < cands.size(); i++) {
            if (!clearsMainBar(cands.get(i))) continue;
            int s = mainContentScore(cands.get(i));
            if (s > bestScore) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) return -1;
        int pick = best;
        for (int i = 0; i < cands.size(); i++) {
            if (!clearsMainBar(cands.get(i))) continue;
            ReadCandidate c = cands.get(i);
            if (mainContentScore(c) * 10 >= bestScore * 9
                    && c.textLen < cands.get(pick).textLen) {
                pick = i;
            }
        }
        return pick;
    }

    // ---------------- v2.8 density settle ----------------
    //
    // Script-driven pages hydrate in waves: the text the first
    // extraction sees can be a fraction of what the page carries
    // a second later (the v2.1 weather.com read). Before a read
    // session's first extraction, the hands now poll a quick
    // text-length probe (pageTextLengthJs — one counting walk
    // over the body, no string built) up to SETTLE_MAX_PROBES
    // times, SETTLE_INTERVAL_MS apart, and proceed when the
    // growth flattens or the budget expires: a stability signal
    // instead of a guessed fixed wait. The v2.3 thin-read retry
    // remains the second line for a read that still comes back
    // thin. The decision over a probe series is pure (below);
    // the polling and sleeping live in the hands.

    /** settleDecision outcomes. */
    public static final int SETTLE_WAIT = 0;
    public static final int SETTLE_FLAT = 1;
    public static final int SETTLE_BUDGET = 2;

    /** JS: the settle probe — the body's cleaned text length
     *  under the metrics definition (the same walk the candidate
     *  collector scores with), as {"len": N}. */
    public static String pageTextLengthJs() {
        return "(function(){" + READ_SENSE_JS
                + "var b=document.body;"
                + "if(!b)return JSON.stringify({len:0});"
                + "return JSON.stringify({len:ghMetrics(b).len});})()";
    }

    /** Parse a pageTextLengthJs result: the probed length, or -1
     *  when the probe produced nothing parseable (the caller
     *  treats a failed probe as "proceed unsettled" — probing is
     *  best-effort polish, never a gate). */
    public static int parseTextLength(String jsonText) {
        if (jsonText == null) return -1;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return -1;
        }
        if (!(parsed instanceof Map)) return -1;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object len = m.get("len");
        return (len instanceof Number) ? ((Number) len).intValue() : -1;
    }

    /**
     * Decide from the probe lengths gathered so far: FLAT when
     * the last two probes differ by at most
     * SETTLE_FLAT_DELTA_CHARS (growth or shrinkage alike — the
     * page has stopped moving), BUDGET when the probe budget is
     * spent, WAIT otherwise. One probe never settles: a single
     * reading says nothing about motion.
     */
    public static int settleDecision(int[] probes) {
        if (probes == null || probes.length == 0) return SETTLE_WAIT;
        int n = probes.length;
        if (n >= 2) {
            int d = probes[n - 1] - probes[n - 2];
            if (d < 0) d = -d;
            if (d <= SETTLE_FLAT_DELTA_CHARS) return SETTLE_FLAT;
        }
        if (n >= SETTLE_MAX_PROBES) return SETTLE_BUDGET;
        return SETTLE_WAIT;
    }

    private static String str(Object o) {
        return (o instanceof String) ? (String) o : "";
    }

    // ---------------- hands ----------------

    /** JS: tap element n (1-based, matching the map). */
    public static String tapJs(int n) {
        return "(function(){var e=window.__gh&&window.__gh.els[" + (n - 1) + "];"
                + "if(!e)return 'error: element " + n + " not on page';"
                + "e.scrollIntoView({block:'center'});"
                + "if(e.tagName==='SELECT'||e.tagName==='INPUT'||e.tagName==='TEXTAREA'){e.focus();"
                + "if(e.tagName==='SELECT')return 'ok: focused select #" + n + "';}"
                + "e.click();return 'ok: tapped #" + n + "';})()";
    }

    /** JS: type text into element n, React-friendly, firing input/change. */
    public static String typeJs(int n, String text) {
        return "(function(){var e=window.__gh&&window.__gh.els[" + (n - 1) + "];"
                + "if(!e)return 'error: element " + n + " not on page';"
                + "var text=" + Json.quote(text) + ";"
                + "e.scrollIntoView({block:'center'});e.focus();"
                + "if(e.tagName==='SELECT'){for(var i=0;i<e.options.length;i++){"
                + "if(e.options[i].text===text||e.options[i].value===text){e.selectedIndex=i;"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'ok: selected \"'+text+'\" in #" + n + "';}}"
                + "return 'error: no option \"'+text+'\" in select #" + n + "';}"
                + "if(e.isContentEditable){e.textContent=text;"
                + "e.dispatchEvent(new InputEvent('input',{bubbles:true}));"
                + "return 'ok: typed into #" + n + "';}"
                + "var proto=e.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(setter&&setter.set){setter.set.call(e,text);}else{e.value=text;}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'ok: typed into #" + n + "';})()";
    }

    /** JS: submit from search element n (v1.1 search action path b).
     *  Prefers the enclosing form's own submit control; otherwise
     *  fires Enter and submits the form. Bounded: touches only the
     *  search element's own form. */
    public static String submitSearchJs(int n) {
        return "(function(){var e=window.__gh&&window.__gh.els[" + (n - 1) + "];"
                + "if(!e)return 'error: element " + n + " not on page';"
                + "e.scrollIntoView({block:'center'});e.focus();"
                + "function enter(){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                + "e.dispatchEvent(new KeyboardEvent('keydown',o));"
                + "e.dispatchEvent(new KeyboardEvent('keypress',o));"
                + "e.dispatchEvent(new KeyboardEvent('keyup',o));}"
                + "if(e.form){"
                + "var btn=e.form.querySelector(\"input[type=submit],button[type=submit],button:not([type])\");"
                + "if(btn){btn.click();return 'ok: clicked the form submit control for #" + n + "';}"
                + "enter();"
                + "if(e.form.requestSubmit){e.form.requestSubmit();}else{e.form.submit();}"
                + "return 'ok: submitted the enclosing form for #" + n + "';}"
                + "enter();"
                + "return 'ok: pressed Enter in #" + n + " (no enclosing form)';"
                + "})()";
    }

    /** JS: scroll the page. */
    public static String scrollJs(String dir) {
        int sign = "up".equals(dir) ? -1 : 1;
        return "(function(){var d=Math.floor(window.innerHeight*0.85)*" + sign + ";"
                + "window.scrollBy(0,d);return 'ok: scrolled " + dir
                + " to y='+Math.floor(window.scrollY);})()";
    }

    // ---------------- v2.0 heal: act on a re-found element ---------

    /** JS: tap the node at scanIndex in a fresh identity-scan
     *  enumeration (the same collectNodes order identityScanJs
     *  reported), with tapJs's act logic. n is the planner's
     *  element number, used only in the not-on-page error so the
     *  failure string stays byte-identical to tapJs's. Any success
     *  returns 'ok: acted'; the caller composes the planner-visible
     *  healed result string. */
    public static String tapScanJs(int scanIndex, int n) {
        return "(function(){" + HEAL_FUNCS_JS
                + "var e=collectNodes()[" + scanIndex + "];"
                + "if(!e)return 'error: element " + n + " not on page';"
                + "e.scrollIntoView({block:'center'});"
                + "if(e.tagName==='SELECT'||e.tagName==='INPUT'||e.tagName==='TEXTAREA'){e.focus();"
                + "if(e.tagName==='SELECT')return 'ok: acted';}"
                + "e.click();return 'ok: acted';})()";
    }

    /** JS: type into the node at scanIndex in a fresh identity-scan
     *  enumeration — typeJs's logic addressed by scan position
     *  instead of stash slot. Error strings keep the planner's
     *  number n and typeJs's exact formats; successes return
     *  'ok: acted' and the caller composes the healed string. */
    public static String typeScanJs(int scanIndex, int n, String text) {
        return "(function(){" + HEAL_FUNCS_JS
                + "var e=collectNodes()[" + scanIndex + "];"
                + "if(!e)return 'error: element " + n + " not on page';"
                + "var text=" + Json.quote(text) + ";"
                + "e.scrollIntoView({block:'center'});e.focus();"
                + "if(e.tagName==='SELECT'){for(var i=0;i<e.options.length;i++){"
                + "if(e.options[i].text===text||e.options[i].value===text){e.selectedIndex=i;"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'ok: acted';}}"
                + "return 'error: no option \"'+text+'\" in select #" + n + "';}"
                + "if(e.isContentEditable){e.textContent=text;"
                + "e.dispatchEvent(new InputEvent('input',{bubbles:true}));"
                + "return 'ok: acted';}"
                + "var proto=e.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value');"
                + "if(setter&&setter.set){setter.set.call(e,text);}else{e.value=text;}"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));"
                + "e.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return 'ok: acted';})()";
    }

    // ---------------- v3.3: the user's own hand ------------------
    //
    // Teach mode and the co-pilot tap both start from the same
    // question: the user just tapped the page THERE — what
    // element was it? These two scripts answer with the heal's
    // own extraction, so an identity read from a fingertip is
    // comparable to the identities perception records and the
    // heal resolves: same vis(), same identOf(), same candidate
    // set (collectNodes' selector, plus the pointer-cursor
    // harvest's rule for the point script's ancestor walk).

    /** JS: the identity of the interactive element at viewport
     *  point (x, y) in CSS pixels — document.elementFromPoint,
     *  walked up to the first ancestor the map's collector would
     *  admit (its selector, or a cursor-pointer element carrying
     *  text, the harvest's rule). Result shape is identityJs's:
     *  {found, el} — parseIdentity reads it. */
    public static String pointIdentityJs(double x, double y) {
        return "(function(){" + HEAL_FUNCS_JS
                + "var e=document.elementFromPoint("
                + Double.toString(x) + "," + Double.toString(y) + ");"
                + "while(e){"
                + "if(e.matches&&e.matches(\"a,button,input,select,textarea,[role='button'],[role='link'],[onclick]\"))break;"
                + "var cs=window.getComputedStyle(e);"
                + "if(cs&&cs.cursor==='pointer'&&txt(e))break;"
                + "e=e.parentElement;}"
                + "if(!e||!vis(e))return JSON.stringify({found:false});"
                + "return JSON.stringify({found:true,el:identOf(e)});})()";
    }

    /** One field reading (valueScanJs's result): whether the
     *  field was found, whether it is a password field (whose
     *  value was NOT read — see valueScanJs), its input type,
     *  and its current value ("" for a password field, always). */
    public static class FieldValue {
        public boolean found = false;
        public boolean redacted = false;
        public String type = "";
        public String value = "";
    }

    /** JS: the current value of the node at scanIndex in a fresh
     *  identity-scan enumeration — teach mode's field flush.
     *  THE SECURITY LINE lives here too: for a password input
     *  the script reports the field redacted WITHOUT touching
     *  its .value — the password is never read, so it can never
     *  be recorded, logged or leaked. parseFieldValue applies
     *  the same refusal a second time on the Java side. */
    public static String valueScanJs(int scanIndex) {
        return "(function(){" + HEAL_FUNCS_JS
                + "var e=collectNodes()[" + scanIndex + "];"
                + "if(!e)return JSON.stringify({found:false});"
                + "var t=(e.type||'').toLowerCase();"
                + "if(t==='password')return JSON.stringify({found:true,"
                + "redacted:true,type:'password'});"
                + "var v=('value' in e)?(''+e.value)"
                + ":(e.isContentEditable?txt(e):'');"
                + "return JSON.stringify({found:true,type:t,value:v});})()";
    }

    /** Parse a valueScanJs result. A field reported (or typed)
     *  as a password parses with an EMPTY value whatever the
     *  payload claims to carry — the Java-side belt to the
     *  script's braces. */
    public static FieldValue parseFieldValue(String jsonText) {
        FieldValue out = new FieldValue();
        if (jsonText == null) return out;
        Object parsed;
        try {
            parsed = Json.parse(jsonText);
        } catch (Exception e) {
            return out;
        }
        if (!(parsed instanceof Map)) return out;
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) parsed;
        if (!Boolean.TRUE.equals(m.get("found"))) return out;
        out.found = true;
        Object t = m.get("type");
        out.type = (t instanceof String) ? (String) t : "";
        if (Boolean.TRUE.equals(m.get("redacted"))
                || Routes.isPasswordType(out.type)) {
            out.redacted = true;
            out.value = "";
            return out;
        }
        Object v = m.get("value");
        out.value = (v instanceof String) ? (String) v : "";
        return out;
    }

    // ---------------- v2.0 heal: the pure decision ----------------

    /**
     * Element identity, for the heal decision: the fields that say
     * "this is the element the planner meant". For a link: its name
     * and its href. For a control: its name, tag and input type.
     * Two elements are the same element for healing purposes when
     * their tags agree and those fields are all equal (null reads
     * as ""). Deliberately strict: a heal that acts on the wrong
     * element is worse than the honest "not on page" failure.
     */
    public static boolean identityMatches(ElementInfo perceived,
                                          ElementInfo current) {
        if (perceived == null || current == null) return false;
        if (!eqIgnoreCase(perceived.tag, current.tag)) return false;
        if (isLink(perceived)) {
            return eq(perceived.name, current.name)
                    && eq(perceived.href, current.href);
        }
        return eq(perceived.name, current.name)
                && eq(perceived.type, current.type);
    }

    private static boolean eq(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private static boolean eqIgnoreCase(String a, String b) {
        return (a == null ? "" : a).equalsIgnoreCase(b == null ? "" : b);
    }

    /** The heal decision for one tap/type (decideHeal's result). */
    public static class Heal {
        /** The stash slot still holds the perceived element: act
         *  exactly as before. */
        public static final int MATCH = 0;
        /** The stash slot lost it, but exactly one element in the
         *  fresh scan carries the identity: act on that one. */
        public static final int REFOUND = 1;
        /** No element in the scan carries the identity. */
        public static final int NONE = 2;
        /** Several elements carry the identity — acting on any of
         *  them would be a guess, so healing refuses. */
        public static final int MULTIPLE = 3;

        public int kind = NONE;
        /** For REFOUND: the match's index in the scanned list
         *  (its position in the fresh enumeration). */
        public int scanIndex = -1;
    }

    /**
     * Decide how a tap/type on `perceived` should be executed,
     * given the element NOW at its stash slot (atStash, null when
     * the slot is empty) and — consulted only when the slot does
     * not match — the fresh DOM identity scan. Pure: the host
     * harness pins this decision; WebHands supplies the live
     * readings and carries out the verdict.
     */
    public static Heal decideHeal(ElementInfo perceived,
                                  ElementInfo atStash,
                                  List<ElementInfo> scan) {
        Heal h = new Heal();
        if (perceived == null) return h;
        if (identityMatches(perceived, atStash)) {
            h.kind = Heal.MATCH;
            return h;
        }
        // v2.7: NO EVIDENCE either way — the stash-slot identity
        // could not be read (atStash null: the slot read failed or
        // the slot is empty) AND the fresh scan produced nothing
        // (scan null, or empty: parseIdentityScan reports a failed
        // scan as an empty list, and a page that holds a perceived
        // element cannot honestly scan to zero candidates). The
        // pre-v2.7 code fell through to NONE here, which made the
        // hands refuse the tap WITHOUT ever trying the stash — on
        // the v2.6.1 field run (trail-20261025-003150) that veto,
        // not any map/stash divergence, failed both taps on an
        // unchanged page. With no evidence the element moved, act
        // on the stash exactly as perceived; if the slot is truly
        // empty the plain tap reports "not on page" itself.
        if (atStash == null && (scan == null || scan.isEmpty())) {
            h.kind = Heal.MATCH;
            return h;
        }
        int count = 0;
        int found = -1;
        if (scan != null) {
            for (int i = 0; i < scan.size(); i++) {
                if (identityMatches(perceived, scan.get(i))) {
                    count++;
                    found = i;
                    if (count > 1) break;
                }
            }
        }
        if (count == 1) {
            h.kind = Heal.REFOUND;
            h.scanIndex = found;
        } else if (count > 1) {
            h.kind = Heal.MULTIPLE;
        }
        return h;
    }
}
