package com.ghosthands.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One interactive element from the WebView eyes — the mobile analogue
 * of eyes.py's Element: a number, a tag/role, a human name, and the
 * destinations the governor needs to reason about (href, form action).
 */
public class ElementInfo {

    public int number;
    public String tag = "";
    public String role = "";
    public String name = "";
    public String type;         // input type / button type, "" when n/a
    public String href;         // resolved absolute href for links
    public String label;        // associated <label> text
    public String placeholder;
    public String formAction;   // enclosing form's action URL

    // v1.2 map shaping bookkeeping (not rendered, not in the trail
    // descriptor): the element's position in the raw DOM-order
    // candidate list (its index into window.__gh.all), plus the raw
    // id / name-attribute the tier heuristic looks at.
    public int domIndex = -1;
    public String domId;
    public String domName;

    // v2.1 lookup-box bookkeeping (also not rendered, not in the
    // trail descriptor): the enclosing form's visible text —
    // its labels, legend and plain text — capped at 200 chars by
    // the extractor. Eyes.pickSearchTarget reads it: a form that
    // talks about a location / city / ZIP lookup identifies its
    // text input as a lookup box even when the input itself is
    // not named like a search box (weather.gov's forecast form).
    public String formText;

    // v2.2 lookup-box bookkeeping (likewise not rendered, not in
    // the trail descriptor): the text of the label EXPLICITLY
    // associated with this element — a <label for="its id">, a
    // wrapping <label>, or an aria-labelledby target — capped at
    // 120 chars by the extractor. Distinct from `label` above
    // (the 48-char planner-facing field the map renders and the
    // classic search rule reads): pickSearchTarget's explicit-
    // label tier reads this one. An explicit association is a far
    // stronger signal than the ambient form text of formText:
    // weather.gov's forecast input (id "inputstring") has
    // <label for="inputstring">Local forecast by "City, St" or
    // ZIP code</label>, and v2.1's ambient tier refused the page
    // because a second location-shaped input (the MyForecast
    // widget's) qualified alongside it.
    public String labelText;

    // v2.5 clickable-net bookkeeping (not rendered, not in the
    // trail descriptor): true when this candidate came from
    // MAP_JS's cursor-pointer harvest — an element the classic
    // selector set does not name (no anchor/button/input/select/
    // textarea tag, no role=button/link, no onclick attribute)
    // whose computed cursor is "pointer" and which carries visible
    // text: the clickable divs and spans modern pages use instead
    // of real controls. Eyes.shapeMap caps the harvest
    // (MAX_POINTER_HARVEST, document order) and tierOf ranks it in
    // the link tier, whatever its name or role suggests.
    public boolean pointer;

    /** The subset recorded in the trail so a run can be replayed. */
    public Map<String, Object> descriptor() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("number", Long.valueOf(number));
        m.put("tag", tag);
        m.put("role", role);
        m.put("name", name);
        if (type != null && type.length() > 0) m.put("type", type);
        if (href != null && href.length() > 0) m.put("href", href);
        if (label != null && label.length() > 0) m.put("label", label);
        if (formAction != null && formAction.length() > 0) m.put("formAction", formAction);
        return m;
    }
}
