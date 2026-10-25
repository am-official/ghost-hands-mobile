package com.ghosthands.app;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The Governor: a faithful Java port of ghost_hands/governor.py.
 * Every action is classified and judged before it runs.
 *
 *   readonly     - perceives or moves without changing anything
 *                  (goto a plain URL, tapping a plain link, scroll,
 *                  back, wait, done).
 *   write        - changes state in a reversible, ordinary way
 *                  (typing into a field, clicking a plain button).
 *   consequential- form submits, and any tap/type/goto whose target or
 *                  URL smells like money, messaging, destruction, or
 *                  publishing (pay, purchase, checkout, place order,
 *                  send, delete, publish, transfer, buy now...).
 *
 * A Policy maps each class to allow / ask / deny and carries a domain
 * allowlist (empty = all allowed), blocked domains, and deny_patterns
 * (v0.8): substrings that deny an action outright when found in the
 * target's name/label or the page/action URL - the seatbelt pack's
 * hook against prompt-injection-shaped targets.
 */
public class Governor {

    public static final String READONLY = "readonly";
    public static final String WRITE = "write";
    public static final String CONSEQUENTIAL = "consequential";
    // v3.12 (R4): ask is its OWN class, deliberately outside the
    // policy matrix. Asking the user a question changes nothing on
    // any page, so it is always allowed under every pack —
    // including readonly and seatbelt — and is never upgraded to
    // consequential, however the question or the page is worded.
    // It is not a write. (Named ASK_CLASS, not ASK: ASK above is
    // the approval OUTCOME — the two "ask"s are different ideas
    // that happen to share a word.)
    public static final String ASK_CLASS = "ask";

    public static final String ALLOW = "allow";
    public static final String ASK = "ask";
    public static final String DENY = "deny";

    public static final String[] CONSEQUENTIAL_KEYWORDS = {
            "pay",
            "purchase",
            "buy now",
            "checkout",
            "place order",
            "submit order",
            "confirm payment",
            "transfer",
            "send",
            "delete",
            "remove account",
            "publish",
    };

    // ---------------- Policy (governor.py Policy) ----------------

    public static class Policy {
        public String readonly = ALLOW;
        public String write = ALLOW;
        public String consequential = ASK;
        public List<String> allowedDomains = new ArrayList<String>();
        public List<String> blockedDomains = new ArrayList<String>();
        public List<String> denyPatterns = new ArrayList<String>();

        public Policy() { }

        public Policy(String readonly, String write, String consequential,
                      List<String> allowed, List<String> blocked, List<String> deny) {
            checkOutcome("readonly", readonly);
            checkOutcome("write", write);
            checkOutcome("consequential", consequential);
            this.readonly = readonly;
            this.write = write;
            this.consequential = consequential;
            if (allowed != null) {
                for (String d : allowed) this.allowedDomains.add(d.toLowerCase());
            }
            if (blocked != null) {
                for (String d : blocked) this.blockedDomains.add(d.toLowerCase());
            }
            if (deny != null) {
                for (String p : deny) {
                    if (p != null && p.trim().length() > 0) {
                        this.denyPatterns.add(p.toLowerCase());
                    }
                }
            }
        }

        private static void checkOutcome(String name, String val) {
            if (!ALLOW.equals(val) && !ASK.equals(val) && !DENY.equals(val)) {
                throw new IllegalArgumentException(
                        "policy " + name + " must be one of allow/ask/deny, got " + val);
            }
        }

        public String forClass(String classification) {
            if (READONLY.equals(classification)) return readonly;
            if (WRITE.equals(classification)) return write;
            if (CONSEQUENTIAL.equals(classification)) return consequential;
            return DENY;
        }
    }

    // ---------------- Decision (governor.py Decision) ----------------

    public static class Decision {
        public final String classification;
        public final String outcome; // allow | ask | deny
        public final String reason;

        public Decision(String classification, String outcome, String reason) {
            this.classification = classification;
            this.outcome = outcome;
            this.reason = reason;
        }

        public boolean allowed() { return ALLOW.equals(outcome); }
        public boolean needsApproval() { return ASK.equals(outcome); }
    }

    // ---------------- Governor ----------------

    private final Policy policy;

    public Governor(Policy policy) {
        this.policy = (policy != null) ? policy : new Policy();
    }

    public Policy policy() { return policy; }

    private static String hostname(String url) {
        if (url == null || url.length() == 0) return "";
        try {
            String host = new URL(url).getHost();
            return (host == null) ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static String urlJoin(String base, String rel) {
        if (rel == null) return null;
        try {
            if (base != null && base.length() > 0) {
                return new URL(new URL(base), rel).toString();
            }
            return rel;
        } catch (Exception e) {
            return rel;
        }
    }

    private static String matchKeyword(String haystack) {
        for (int i = 0; i < CONSEQUENTIAL_KEYWORDS.length; i++) {
            if (haystack.contains(CONSEQUENTIAL_KEYWORDS[i])) {
                return CONSEQUENTIAL_KEYWORDS[i];
            }
        }
        return null;
    }

    /**
     * classify() from governor.py, mapped onto the mobile vocabulary:
     * Python click -> tap, navigate -> goto; scroll/wait (and the
     * mobile back/done/stuck) are readonly like their Python kin.
     */
    public String classify(GhAction action, ElementInfo element, String pageUrl) {
        String kind = action.action;
        if ("ask".equals(kind)) {
            // v3.12 (R4): ask is its OWN class, returned before any
            // element or keyword logic can see the action — the
            // question text and the element haystack can never make
            // asking read as a write or a consequential move.
            return ASK_CLASS;
        }
        if ("done".equals(kind) || "stuck".equals(kind) || "wait".equals(kind)
                || "scroll".equals(kind) || "back".equals(kind)
                || "search".equals(kind) || "read".equals(kind)
                || "note".equals(kind)
                || "open_tab".equals(kind) || "switch_tab".equals(kind)
                || "close_tab".equals(kind)
                || "read_file".equals(kind)) {
            // search (v1.1) performs a read-only web search: it perceives
            // the world, it does not change account/site state.
            // read (v1.2) extracts page text: pure perception.
            // note (v3.0) records a fact in the run's own scratchpad:
            // it writes only to the agent's notebook on this phone —
            // nothing on any page, account, or site changes — so it
            // is readonly and allowed under every pack, the readonly
            // pack (which schedules launch under) included.
            // open_tab / switch_tab / close_tab (v3.15, R7) are
            // NAVIGATION: they rearrange the browser's own tabs and
            // change no state on any site — opening a tab loads a
            // page exactly as goto does (goto is readonly), switching
            // moves no page at all, and closing discards only local
            // browsing state. They return here, before the keyword
            // pass, so an open_tab url that merely smells (a path
            // containing "pay") can never upgrade them: the url is
            // still judged by the domain rules in evaluate(), like
            // goto's.
            // read_file (v3.16, R8) reads a document already in
            // the app's own downloads store: it perceives a local
            // file and changes nothing anywhere — not the file,
            // not a page, not an account. It returns here too, so
            // a document NAMED like money or destruction
            // ("delete-taxes.csv") can never upgrade it: the
            // name is a label, not a target.
            return READONLY;
        }

        List<String> bits = new ArrayList<String>();
        if (element != null) {
            if (element.name != null) bits.add(element.name);
            if (element.href != null) bits.add(element.href);
            if (element.label != null) bits.add(element.label);
            if (element.placeholder != null) bits.add(element.placeholder);
        }
        if (action.url != null) bits.add(action.url);
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < bits.size(); i++) {
            if (i > 0) hb.append(' ');
            hb.append(bits.get(i));
        }
        String haystack = hb.toString().toLowerCase();

        if ("goto".equals(kind)) {
            return (matchKeyword(haystack) != null) ? CONSEQUENTIAL : READONLY;
        }

        if ("tap".equals(kind)) {
            if (element == null) return WRITE;
            if (matchKeyword(haystack) != null) return CONSEQUENTIAL;
            // Form submits are consequential by definition.
            if ("submit".equals(element.type)
                    || (element.formAction != null && element.formAction.length() > 0)) {
                return CONSEQUENTIAL;
            }
            if ("a".equals(element.tag)) return READONLY;
            return WRITE;
        }

        if ("type".equals(kind)) {
            return (matchKeyword(haystack) != null) ? CONSEQUENTIAL : WRITE;
        }

        // v3.9 (R1): choose (pick a select's option) and toggle
        // (flip a checkbox, select a radio) are writes, judged
        // exactly like type: they change form state in a
        // reversible, ordinary way — allowed silently under
        // standard, asked under strict, denied under readonly —
        // and the same keyword smell (a target named like money
        // or destruction) upgrades them to consequential, as it
        // does a type. They never submit anything: a form's
        // submit stays a tap on the submit control, consequential
        // as always.
        if ("choose".equals(kind) || "toggle".equals(kind)) {
            return (matchKeyword(haystack) != null) ? CONSEQUENTIAL : WRITE;
        }

        return WRITE;
    }

    /** evaluate() from governor.py: deny-patterns first, then domain
     *  rules over every host the action can actually reach, then the
     *  class outcome. */
    public Decision evaluate(GhAction action, ElementInfo element, String pageUrl) {
        String classification = classify(action, element, pageUrl);
        Policy policy = this.policy;

        if (ASK_CLASS.equals(classification)) {
            // v3.12 (R4): structural always-allow, decided BEFORE
            // the deny-pattern pass and the domain rules can see
            // the action — an ask has no target, no URL of its own
            // and no page effect, so page state, allowlists and
            // question wording can never gate asking. The reason
            // string is pinned by the harness.
            return new Decision(ASK_CLASS, ALLOW, "ask: always allowed");
        }

        // Content deny-patterns first.
        if (!policy.denyPatterns.isEmpty()) {
            List<String> haystacks = new ArrayList<String>();
            if (element != null) {
                if (element.name != null) haystacks.add(element.name);
                if (element.label != null) haystacks.add(element.label);
            }
            if (pageUrl != null) haystacks.add(pageUrl);
            if (action.url != null) haystacks.add(action.url);
            List<String> lowered = new ArrayList<String>();
            for (String h : haystacks) {
                String low = h.toLowerCase();
                lowered.add(low);
                String folded = low.replace("%20", " ");
                folded = folded.replace('-', ' ').replace('_', ' ').replace('+', ' ');
                if (!folded.equals(low)) lowered.add(folded);
            }
            for (String pattern : policy.denyPatterns) {
                for (String h : lowered) {
                    if (h.contains(pattern)) {
                        return new Decision(classification, DENY,
                                "seatbelt: injection-shaped target (matched '"
                                        + pattern + "')");
                    }
                }
            }
        }

        // Domain rules apply to every host this action can reach.
        // v3.15 (R7): open_tab reaches its url exactly as goto does,
        // so the url is the effective one for domain purposes.
        boolean urlBearing = "goto".equals(action.action)
                || "open_tab".equals(action.action);
        String effectiveUrl = urlBearing ? action.url : pageUrl;
        List<String> candidates = new ArrayList<String>();
        if (effectiveUrl != null) candidates.add(effectiveUrl);
        if (action.url != null && urlBearing) {
            candidates.add(action.url);
        }
        if (element != null && "tap".equals(action.action)) {
            if (element.href != null) candidates.add(urlJoin(pageUrl, element.href));
            if (element.formAction != null) {
                candidates.add(urlJoin(pageUrl, element.formAction));
            }
        }
        LinkedHashSet<String> seenHosts = new LinkedHashSet<String>();
        for (String candidate : candidates) {
            String host = hostname(candidate);
            if (host.length() == 0 || seenHosts.contains(host)) continue;
            seenHosts.add(host);
            if (policy.blockedDomains.contains(host)) {
                return new Decision(classification, DENY,
                        "domain " + host + " is blocked by policy");
            }
            if (!policy.allowedDomains.isEmpty() && !policy.allowedDomains.contains(host)) {
                return new Decision(classification, DENY,
                        "domain " + host + " is not in the policy allowlist");
            }
        }

        String outcome = policy.forClass(classification);
        String reason;
        if (ALLOW.equals(outcome)) {
            reason = classification + " actions are allowed by policy";
        } else if (ASK.equals(outcome)) {
            reason = classification + " actions require approval by policy";
        } else {
            reason = classification + " actions are denied by policy";
        }
        return new Decision(classification, outcome, reason);
    }
}
