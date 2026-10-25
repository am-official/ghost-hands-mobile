package com.ghosthands.app;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v3.13 (Everything Train R5 — site grants): per-site
 * remembered consequential approvals, as a pure store.
 *
 * A grant is the user's standing answer to one repeated
 * question: "on THIS site, for THIS kind of action, you do not
 * have to ask me again." It is created only from the approval
 * panel, only when the user ticks "Remember for this site" on
 * an approval they are granting anyway, and it carries its
 * provenance (the trail of the run in which it was granted).
 *
 * WHAT A GRANT CAN AND CANNOT DO — the precedence is the
 * feature, and it is structural, not a promise in a comment:
 * the agent loop consults this store ONLY after the Governor
 * has already decided, and only when that decision is ASK with
 * classification CONSEQUENTIAL. So a grant can answer an ask;
 * it can never produce one, and it can never outrank:
 *  - the seatbelt / injection deny pass (a denied action
 *    never reaches the ask step at all);
 *  - the domain allow/block rules (same — they decide before
 *    the class outcome);
 *  - the readonly pack (consequential is DENIED there, never
 *    asked, so there is no ask for a grant to answer);
 *  - scheduled / watch runs (readonly-forced, same reason);
 *  - a STOP (the user's stop is not a governor outcome and is
 *    never consulted here).
 * Matching is exact on (host, action kind): a grant for taps
 * on a host says nothing about types on the same host — the
 * panel asks again, exactly as it did before grants existed.
 *
 * Storage discipline matches SavedGoals: one JSON preferences
 * value owned by the Activity, bridged in and out verbatim.
 * Parsing is tolerant — malformed text reads as an empty
 * store, and a malformed entry is dropped, never fatal: a
 * corrupt grant store must fail toward ASKING, the safe side.
 * Hosts are normalized (trimmed, lowercased) on every boundary
 * so a stored host and a perceived host meet.
 */
public class SiteGrants {

    /** One remembered approval. */
    public static class Grant {
        public final String id;
        public final String host;
        public final String kind;
        public final long createdAtMs;
        /** The trail file name of the run that granted it —
         *  provenance, "" when unknown. */
        public final String trailName;

        public Grant(String id, String host, String kind,
                     long createdAtMs, String trailName) {
            this.id = id == null ? "" : id;
            this.host = normalizeHost(host);
            this.kind = kind == null ? "" : kind;
            this.createdAtMs = createdAtMs;
            this.trailName = trailName == null ? "" : trailName;
        }

        /** The govern-record stamp a grant's use leaves:
         *  exactly which grant answered, and for which host —
         *  the audit trail's whole account of why no human was
         *  asked. */
        public Map<String, Object> stamp() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("host", host);
            return m;
        }
    }

    private final List<Grant> grants = new ArrayList<Grant>();

    public SiteGrants() { }

    public int size() {
        return grants.size();
    }

    /** Every grant, oldest first (a copy — the store is the
     *  only writer). */
    public List<Grant> all() {
        return new ArrayList<Grant>(grants);
    }

    /**
     * Remember an approval: one grant per (host, kind) — re-
     * remembering the same pair replaces the old grant (the
     * newer answer, with the newer provenance, is the standing
     * one). A blank host or kind is refused (null): a grant
     * that cannot name its site and action must never exist.
     * The id is derived from the triple (host, kind, creation
     * time), so the same act always mints the same id.
     */
    public Grant add(String host, String kind, long nowMs,
                     String trailName) {
        String h = normalizeHost(host);
        if (h.length() == 0 || kind == null || kind.length() == 0) {
            return null;
        }
        for (int i = grants.size() - 1; i >= 0; i--) {
            Grant g = grants.get(i);
            if (g.host.equals(h) && g.kind.equals(kind)) {
                grants.remove(i);
            }
        }
        Grant g = new Grant(h + "#" + kind + "#" + nowMs, h, kind,
                nowMs, trailName);
        grants.add(g);
        return g;
    }

    /** The grant covering exactly this host and action kind,
     *  or null. Host comparison is on the normalized form. */
    public Grant match(String host, String kind) {
        String h = normalizeHost(host);
        if (h.length() == 0 || kind == null) return null;
        for (int i = 0; i < grants.size(); i++) {
            Grant g = grants.get(i);
            if (g.host.equals(h) && g.kind.equals(kind)) return g;
        }
        return null;
    }

    /** Revoke one grant by id; true when one was removed.
     *  Takes effect for the next governor decision — the loop
     *  reads this store live, never a snapshot. */
    public boolean revoke(String id) {
        if (id == null) return false;
        for (int i = 0; i < grants.size(); i++) {
            if (grants.get(i).id.equals(id)) {
                grants.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Revoke everything; returns how many grants fell. */
    public int revokeAll() {
        int n = grants.size();
        grants.clear();
        return n;
    }

    // ---------------- persistence ----------------

    public String serialize() {
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < grants.size(); i++) {
            Grant g = grants.get(i);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", g.id);
            m.put("host", g.host);
            m.put("kind", g.kind);
            m.put("created_at_ms", Long.valueOf(g.createdAtMs));
            m.put("trail", g.trailName);
            list.add(m);
        }
        return Json.write(list);
    }

    /**
     * Parse a stored store. Anything that is not a JSON array
     * (including null) reads as an empty store; an entry that
     * lacks a usable host or kind is dropped. A stored id is
     * kept verbatim — revocation names grants by the id the
     * user was shown, so an upgrade must not re-mint ids under
     * existing grants; an entry with no id gets the derived one.
     */
    public static SiteGrants parse(String text) {
        SiteGrants store = new SiteGrants();
        if (text == null) return store;
        Object o;
        try {
            o = Json.parse(text.trim());
        } catch (Exception e) {
            return store;
        }
        if (!(o instanceof List)) return store;
        for (Object item : (List<?>) o) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            Object h = m.get("host");
            Object k = m.get("kind");
            if (!(h instanceof String) || !(k instanceof String)) {
                continue;
            }
            String host = normalizeHost((String) h);
            String kind = (String) k;
            if (host.length() == 0 || kind.length() == 0) continue;
            long created = 0;
            Object c = m.get("created_at_ms");
            if (c instanceof Number) created = ((Number) c).longValue();
            Object idv = m.get("id");
            String id = (idv instanceof String
                    && ((String) idv).length() > 0)
                    ? (String) idv
                    : host + "#" + kind + "#" + created;
            Object tv = m.get("trail");
            String trail = (tv instanceof String) ? (String) tv : "";
            store.grants.add(new Grant(id, host, kind, created, trail));
        }
        return store;
    }

    // ---------------- hosts ----------------

    /** The host a URL names, lowercased — the Governor's own
     *  hostname rule, so a grant's host and a decision's host
     *  are always the same string for the same page. "" for a
     *  null, blank or unparseable URL (about:blank, file:// and
     *  friends name no site a grant could cover). */
    public static String hostOf(String url) {
        if (url == null || url.length() == 0) return "";
        try {
            String host = new URL(url).getHost();
            return (host == null) ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /** The stored form of a host: trimmed and lowercased, ""
     *  for null. */
    public static String normalizeHost(String host) {
        if (host == null) return "";
        return host.trim().toLowerCase(java.util.Locale.US);
    }
}
