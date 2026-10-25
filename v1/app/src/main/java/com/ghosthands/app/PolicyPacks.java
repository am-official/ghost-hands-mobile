package com.ghosthands.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Policy packs — a port of ghost_hands/policies.py. A pack is data,
 * not code: class outcomes for the Governor plus an approval timeout.
 *
 *   readonly — perception only; writes and consequential denied.
 *   standard — the default: readonly + write allowed, consequential asks.
 *   strict   — writes ask too; approvals on a 120s leash.
 *   seatbelt — standard plus curated prompt-injection deny patterns.
 *
 * Unknown pack names are a loud error listing the known packs — never
 * a silent fallback to a permissive policy.
 */
public final class PolicyPacks {

    private PolicyPacks() { }

    public static final double DEFAULT_APPROVAL_TIMEOUT = 600.0;

    /** The seatbelt pack's curated prompt-injection patterns (v0.8). */
    public static final String[] INJECTION_PATTERNS = {
            "ignore previous instructions",
            "ignore all instructions",
            "disregard your instructions",
            "you are now",
            "system prompt",
            "click here to claim",
            "verify your password to continue",
    };

    public static class Pack {
        public String name;
        public String description;
        public Governor.Policy policy;
        public double approvalTimeout;
    }

    public static final String[] NAMES = {"readonly", "standard", "strict", "seatbelt"};

    public static Pack loadPack(String name) {
        String key = (name == null ? "" : name.trim().toLowerCase());
        Pack p = new Pack();
        p.name = key;
        if ("readonly".equals(key)) {
            p.description = "Perception only: readonly actions allowed; "
                    + "writes and consequential actions denied outright.";
            p.policy = new Governor.Policy(Governor.ALLOW, Governor.DENY, Governor.DENY,
                    null, null, null);
            p.approvalTimeout = 0.0;
        } else if ("standard".equals(key)) {
            p.description = "The default Ghost Hands posture: readonly and "
                    + "write actions allowed; consequential actions ask a "
                    + "human (600s to answer).";
            p.policy = new Governor.Policy(Governor.ALLOW, Governor.ALLOW, Governor.ASK,
                    null, null, null);
            p.approvalTimeout = DEFAULT_APPROVAL_TIMEOUT;
        } else if ("strict".equals(key)) {
            p.description = "Writes ask too, and approvals run on a short "
                    + "leash: consequential actions ask with a 120s timeout.";
            p.policy = new Governor.Policy(Governor.ALLOW, Governor.ASK, Governor.ASK,
                    null, null, null);
            p.approvalTimeout = 120.0;
        } else if ("seatbelt".equals(key)) {
            p.description = "Standard's posture plus the seatbelt rule: a "
                    + "target whose text (or URL) matches a curated "
                    + "prompt-injection pattern is denied outright, "
                    + "whatever its class; consequential actions ask on "
                    + "a 300s leash.";
            List<String> deny = new ArrayList<String>(Arrays.asList(INJECTION_PATTERNS));
            p.policy = new Governor.Policy(Governor.ALLOW, Governor.ALLOW, Governor.ASK,
                    null, null, deny);
            p.approvalTimeout = 300.0;
        } else {
            StringBuilder known = new StringBuilder();
            String[] sorted = NAMES.clone();
            Arrays.sort(sorted);
            for (int i = 0; i < sorted.length; i++) {
                if (i > 0) known.append(", ");
                known.append(sorted[i]);
            }
            throw new IllegalArgumentException(
                    "unknown policy pack '" + name + "'; known packs: " + known);
        }
        return p;
    }
}
