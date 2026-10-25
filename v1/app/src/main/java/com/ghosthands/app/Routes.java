package com.ghosthands.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.3 (item 1 — teach a route): routes — macros the user teaches
 * the app by doing, then replays under governance.
 *
 * A route is a start URL plus a list of steps. Each step is a tap
 * or a type, anchored by ELEMENT IDENTITY — the heal's identity
 * (Eyes.identityMatches): a link by its name + href, a control by
 * its name + tag + type. Never raw coordinates, never the map's
 * numbers: numbers belong to one perception of one page load, and
 * a route must survive the page being re-perceived. Identity
 * keying here (identityKey) is RelevanceGuard.repeatActionKey's
 * rule, shared in spirit: the href when the element carries one,
 * else name + tag + type.
 *
 * THE SECURITY LINE: text typed into a password-type input is
 * NEVER recorded. A type step for a password field is redacted
 * at construction (typeStep drops the value even when a caller
 * hands one in): the step records only that a field was filled.
 * At replay the loop stops at a redacted step and the user fills
 * the field themselves — a recording is not a blank check, and a
 * password is not the app's to keep. Deliberate corollary: a
 * tapped password field becomes a redacted step whether or not
 * anything was typed into it, because the app refuses to even
 * measure a password field's contents.
 *
 * The store mirrors SavedGoals' discipline: one JSON string
 * (Json.write), tolerant parsing (a corrupt value parses to an
 * empty store; a malformed record costs the record, never the
 * store), replace-by-name keeping list position.
 *
 * Pure: no Android — the host harness pins serialization, the
 * identity keying, the redaction predicate and step resolution.
 */
public class Routes {

    /** One recorded step: a tap, or a type into a field. The
     *  identity fields (name / href / tag / type) are the
     *  element's identity as the heal's extraction reported it
     *  when the user acted; text is a type step's recorded value
     *  (null when redacted). */
    public static final class Step {
        public String kind = "";       // "tap" | "type"
        public String name = "";
        public String href = "";
        public String tag = "";
        public String type = "";       // the element's input type
        public String text = null;
        public boolean redacted = false;

        public boolean isType() {
            return "type".equals(kind);
        }

        /** This step's identity key (identityKey over its own
         *  fields): equal keys mean the same element identity. */
        public String identityKey() {
            return Routes.identityKey(name, href, tag, type);
        }

        /** The step's target as an ElementInfo carrying only the
         *  identity — what Eyes.identityMatches judges a live
         *  element against, and what the governor classifies. */
        public ElementInfo targetElement() {
            ElementInfo e = new ElementInfo();
            e.tag = tag;
            e.name = name;
            e.href = href;
            e.type = type;
            e.role = "";
            return e;
        }

        /** The step in plain words, for the teach panel, the
         *  routes list and the replay's honest stops — a stop
         *  that names its step names it with this. */
        public String describe() {
            String who = displayName();
            if (isType()) {
                return redacted
                        ? "type into \"" + who + "\" (password — "
                                + "never recorded; you fill it at "
                                + "replay)"
                        : "type into \"" + who + "\"";
            }
            return "tap \"" + who + "\"";
        }

        /** The name a human would call this step's element: its
         *  name, else its href, else its tag (+type). */
        public String displayName() {
            if (name != null && name.length() > 0) return name;
            if (href != null && href.length() > 0) return href;
            String t = (tag == null) ? "" : tag;
            if (type != null && type.length() > 0) {
                t += " (" + type + ")";
            }
            return t.length() > 0 ? t : "element";
        }
    }

    /** One taught route: a name, where it starts, and its steps
     *  in the order the user performed them. */
    public static final class Route {
        public String name = "";
        public String startUrl = "";
        public final List<Step> steps = new ArrayList<Step>();
    }

    private final List<Route> routes = new ArrayList<Route>();

    public int size() {
        return routes.size();
    }

    /** All routes, in saved order (a copy of the list; the
     *  records themselves are shared). */
    public List<Route> all() {
        return new ArrayList<Route>(routes);
    }

    /** The route with this exact name, or null. */
    public Route find(String name) {
        if (name == null) return null;
        for (int i = 0; i < routes.size(); i++) {
            if (name.equals(routes.get(i).name)) return routes.get(i);
        }
        return null;
    }

    /** Add a route, replacing any existing route of the same
     *  name (the replacement keeps the original's position). */
    public void put(Route r) {
        if (r == null || r.name == null) return;
        for (int i = 0; i < routes.size(); i++) {
            if (r.name.equals(routes.get(i).name)) {
                routes.set(i, r);
                return;
            }
        }
        routes.add(r);
    }

    /** Remove by name; true when something was removed. */
    public boolean remove(String name) {
        if (name == null) return false;
        for (int i = 0; i < routes.size(); i++) {
            if (name.equals(routes.get(i).name)) {
                routes.remove(i);
                return true;
            }
        }
        return false;
    }

    // ---------------- step construction (the teach side) ---------

    /**
     * The identity key for an element: the name + href when the
     * element carries an href, else name + tag + type — the
     * heal's identity as RelevanceGuard.repeatActionKey keys it.
     * Null when the element carries nothing to identify it by
     * (no name, no href, no type): such a step could never be
     * re-found honestly, so teach does not record one.
     */
    public static String identityKey(String name, String href,
                                     String tag, String type) {
        String n = (name == null) ? "" : name.trim();
        String h = (href == null) ? "" : href.trim();
        String tg = (tag == null) ? "" : tag.trim();
        String tp = (type == null) ? "" : type.trim();
        if (n.length() == 0 && h.length() == 0 && tp.length() == 0) {
            return null;
        }
        return (h.length() > 0) ? n + "\n" + h : n + "\n" + tg + "\n" + tp;
    }

    /** True for a field the user types free text into: a text-like
     *  input (Eyes.isTextLikeInput's set), a password input, the
     *  other free-text input types, or a textarea. Teach treats a
     *  tap on one of these as the start of a type step, not a tap
     *  step; every other element is a tap step. */
    public static boolean isTypableField(ElementInfo e) {
        if (e == null) return false;
        if ("textarea".equals(e.tag)) return true;
        if (!"input".equals(e.tag)) return false;
        if (Eyes.isTextLikeInput(e)) return true;
        String t = (e.type == null) ? "" : e.type.toLowerCase();
        return "password".equals(t) || "email".equals(t)
                || "tel".equals(t) || "url".equals(t)
                || "number".equals(t);
    }

    /** The redaction predicate: an input type of "password". */
    public static boolean isPasswordType(String inputType) {
        return inputType != null
                && "password".equalsIgnoreCase(inputType.trim());
    }

    /** A tap step for the element the user tapped. */
    public static Step tapStep(ElementInfo el) {
        Step s = new Step();
        s.kind = "tap";
        if (el != null) {
            s.name = nz(el.name);
            s.href = nz(el.href);
            s.tag = nz(el.tag);
            s.type = nz(el.type);
        }
        return s;
    }

    /**
     * A type step for a field, carrying the value the user typed
     * — UNLESS the field is a password field, in which case the
     * value is dropped here, at construction, whatever the
     * caller passed: the step comes out redacted with no text.
     * This is the one place type steps are minted, so the
     * security line holds by construction, not by caller care.
     */
    public static Step typeStep(ElementInfo el, String inputType,
                                String value) {
        Step s = new Step();
        s.kind = "type";
        if (el != null) {
            s.name = nz(el.name);
            s.href = nz(el.href);
            s.tag = nz(el.tag);
            s.type = nz(el.type);
        }
        if (isPasswordType(inputType)
                || (el != null && isPasswordType(el.type))) {
            s.redacted = true;
            s.text = null;
        } else {
            s.redacted = false;
            s.text = value;
        }
        return s;
    }

    // ---------------- resolution (the replay side) ---------------

    /**
     * Resolve a step against a live perception: the ONE element
     * the heal's identity rules match to the step's recorded
     * identity, or null when there is none — or several, since
     * acting on a guess is worse than stopping honestly and
     * naming the step.
     */
    public static ElementInfo resolve(Step step,
                                      List<ElementInfo> elements) {
        if (step == null || elements == null) return null;
        ElementInfo target = step.targetElement();
        ElementInfo found = null;
        for (int i = 0; i < elements.size(); i++) {
            if (Eyes.identityMatches(target, elements.get(i))) {
                if (found != null) return null;
                found = elements.get(i);
            }
        }
        return found;
    }

    /** The host part of a URL, for list labels ("example.com"
     *  from "https://example.com/path"); "" when there is none. */
    public static String hostOf(String url) {
        if (url == null) return "";
        String u = url.trim();
        int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        int end = u.length();
        int slash = u.indexOf('/');
        if (slash >= 0 && slash < end) end = slash;
        int q = u.indexOf('?');
        if (q >= 0 && q < end) end = q;
        int h = u.indexOf('#');
        if (h >= 0 && h < end) end = h;
        return u.substring(0, end);
    }

    private static String nz(String s) {
        return (s == null) ? "" : s;
    }

    // ---------------- serialization ----------------

    /** The whole store as one JSON string (an array of records;
     *  an empty store serializes as "[]"). A redacted step's
     *  record carries no text key at all — what was never
     *  recorded cannot leak through the file. */
    public String serialize() {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < routes.size(); i++) {
            Route r = routes.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", r.name);
            m.put("start", r.startUrl);
            List<Object> steps = new ArrayList<Object>();
            for (int j = 0; j < r.steps.size(); j++) {
                Step s = r.steps.get(j);
                Map<String, Object> sm =
                        new LinkedHashMap<String, Object>();
                sm.put("kind", s.kind);
                sm.put("name", s.name);
                sm.put("href", s.href);
                sm.put("tag", s.tag);
                sm.put("type", s.type);
                if (s.redacted) {
                    sm.put("redacted", Boolean.TRUE);
                } else if (s.text != null) {
                    sm.put("text", s.text);
                }
                steps.add(sm);
            }
            m.put("steps", steps);
            out.add(m);
        }
        return Json.write(out);
    }

    /** Parse a store from its serialized form. Anything that is
     *  not a JSON array of records with a non-empty name and
     *  start URL yields an empty (or partial) store — never an
     *  exception. A step whose kind is not tap/type is skipped;
     *  a step marked redacted parses redacted with no text,
     *  whatever else its record claims to carry. */
    public static Routes parse(String json) {
        Routes store = new Routes();
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
            Object start = m.get("start");
            if (!(name instanceof String) || !(start instanceof String)) {
                continue;
            }
            if (((String) name).trim().length() == 0
                    || ((String) start).trim().length() == 0) {
                continue;
            }
            Route r = new Route();
            r.name = (String) name;
            r.startUrl = (String) start;
            Object stepsObj = m.get("steps");
            if (stepsObj instanceof List) {
                List<?> steps = (List<?>) stepsObj;
                for (int j = 0; j < steps.size(); j++) {
                    Object so = steps.get(j);
                    if (!(so instanceof Map)) continue;
                    Map<?, ?> sm = (Map<?, ?>) so;
                    Object kind = sm.get("kind");
                    if (!("tap".equals(kind) || "type".equals(kind))) {
                        continue;
                    }
                    Step s = new Step();
                    s.kind = (String) kind;
                    s.name = str(sm.get("name"));
                    s.href = str(sm.get("href"));
                    s.tag = str(sm.get("tag"));
                    s.type = str(sm.get("type"));
                    if (Boolean.TRUE.equals(sm.get("redacted"))) {
                        s.redacted = true;
                        s.text = null;
                    } else {
                        Object text = sm.get("text");
                        s.text = (text instanceof String)
                                ? (String) text : null;
                    }
                    r.steps.add(s);
                }
            }
            store.put(r);
        }
        return store;
    }

    private static String str(Object o) {
        return (o instanceof String) ? (String) o : "";
    }
}
