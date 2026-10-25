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
        if ("done".equals(kind) || "stuck".equals(kind) || "wait".equals(kind)
                || "scroll".equals(kind) || "back".equals(kind)
                || "search".equals(kind) || "read".equals(kind)
                || "note".equals(kind)) {
            // search (v1.1) performs a read-only web search: it perceives
            // the world, it does not change account/site state.
            // read (v1.2) extracts page text: pure perception.
            // note (v3.0) records a fact in the run's own scratchpad:
            // it writes only to the agent's notebook on this phone —
            // nothing on any page, account, or site changes — so it
            // is readonly and allowed under every pack, the readonly
            // pack (which schedules launch under) included.
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

        return WRITE;
    }

    /** evaluate() from governor.py: deny-patterns first, then domain
     *  rules over every host the action can actually reach, then the
     *  class outcome. */
    public Decision evaluate(GhAction action, ElementInfo element, String pageUrl) {
        String classification = classify(action, element, pageUrl);
        Policy policy = this.policy;

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
        String effectiveUrl = "goto".equals(action.action) ? action.url : pageUrl;
        List<String> candidates = new ArrayList<String>();
        if (effectiveUrl != null) candidates.add(effectiveUrl);
        if (action.url != null && "goto".equals(action.action)) {
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
