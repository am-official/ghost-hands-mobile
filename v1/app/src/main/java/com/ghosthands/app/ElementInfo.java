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
    // v3.9: a checkbox/radio input's value attribute, as
    // extracted ("" for everything else — the extractor emits it
    // for checkables only, so a text field's typed content never
    // rides the perception payload). shapeMap reads it when it
    // builds a radio group's choices (the label fallback).
    public String domValue;

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

    // v3.9 (R1 — forms frontier): the form association and form
    // state the map now carries. formIndex is the element's
    // enclosing form's position in document.forms (-1 when the
    // element sits in no form); the map renders it as a compact
    // "form=N" tag (N = formIndex + 1) so the planner can see a
    // form's fields together without reordering the tiered map.
    public int formIndex = -1;

    // v3.9: for checkbox/radio inputs, whether the control is
    // currently checked (the extractor reports it; everything
    // else leaves this false and the renderer consults it only
    // for checkables). For a checkbox the map renders the state;
    // for a radio it rides the choice below instead.
    public boolean checked;

    // v3.9: for a <select>, the option list as extracted —
    // parallel label/value lists and the index of the selected
    // option (-1 when the payload named none). The map renders
    // the labels (capped, with a "+N more" accounting) so the
    // planner can `choose` by a text it has actually seen; the
    // values ride for the choose action's fallback match.
    public java.util.List<String> optionLabels;
    public java.util.List<String> optionValues;
    public int selectedOption = -1;

    // v3.9: for a shaped RADIO GROUP (Eyes.shapeMap collapses
    // radio inputs sharing a form and a name attribute into one
    // entry — an HTML radio group IS one choice, and one map
    // entry per group is how the planner should see it), the
    // group's choices in document order. radioGroup marks the
    // entry as a collapsed group (a lone radio stays a plain
    // entry whose single choice is itself, choices == null).
    // The entry's own fields (name, label, labelText, domIndex)
    // are its FIRST member's — the group anchors its identity on
    // the first member, so the heal path compares and re-finds
    // individual radios exactly as before.
    public boolean radioGroup;
    public java.util.List<Choice> choices;

    /** One choice of a radio group (v3.9): its visible label
     *  (the member's associated label text, else its value), the
     *  member's value attribute, whether it is the checked one,
     *  and the member's own domIndex — the member stays
     *  individually addressable in window.__gh.all / the identity
     *  scan even though the map shows the group once. */
    public static class Choice {
        public String label = "";
        public String value = "";
        public boolean checked;
        public int domIndex = -1;
    }

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
        // v3.9 (R1): the form association and form state ride the
        // descriptor when they exist, so a trail replays what the
        // planner saw — which form a field belonged to, a
        // checkbox's state, a select's options and selection, a
        // radio group's choices. Additive keys only; pre-v3.9
        // descriptors are unchanged in shape.
        if (formIndex >= 0) m.put("form", Long.valueOf(formIndex + 1));
        if (Eyes.isCheckable(this)) m.put("checked", Boolean.valueOf(checked));
        if (optionLabels != null && !optionLabels.isEmpty()) {
            java.util.List<Object> opts = new java.util.ArrayList<Object>();
            for (int i = 0; i < optionLabels.size(); i++) {
                Map<String, Object> o = new LinkedHashMap<String, Object>();
                o.put("label", optionLabels.get(i));
                if (optionValues != null && i < optionValues.size()
                        && optionValues.get(i) != null
                        && optionValues.get(i).length() > 0) {
                    o.put("value", optionValues.get(i));
                }
                if (i == selectedOption) o.put("selected", Boolean.TRUE);
                opts.add(o);
            }
            m.put("options", opts);
        }
        if (radioGroup && choices != null) {
            java.util.List<Object> chs = new java.util.ArrayList<Object>();
            for (int i = 0; i < choices.size(); i++) {
                Choice c = choices.get(i);
                Map<String, Object> o = new LinkedHashMap<String, Object>();
                o.put("label", c.label);
                if (c.value != null && c.value.length() > 0) {
                    o.put("value", c.value);
                }
                if (c.checked) o.put("checked", Boolean.TRUE);
                chs.add(o);
            }
            m.put("choices", chs);
        }
        return m;
    }
}
