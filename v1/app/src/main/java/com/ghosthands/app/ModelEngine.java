package com.ghosthands.app;

/**
 * The local brain: one loaded model, reused across every step of a
 * run (the proof reloaded per benchmark run only to time loading).
 * All calls are serialized — llama.cpp contexts are not thread-safe
 * and the planner is a single conversation anyway.
 *
 * v1.5: the native side caches the prompt prefix between calls
 * (see ghhands.c); the per-call token/timing numbers it returns are
 * captured into an immutable Stats snapshot, exposed via lastStats(),
 * so the agent can put the cache's effect on the trail. generate()'s
 * String return contract is unchanged.
 *
 * v2.4: the snapshot also carries whether the call's sampling was
 * grammar-constrained (Stats.lastGrammarConstrained, native slot
 * [7]) — the shim reports it per call, so a call that fell back to
 * unconstrained greedy is never recorded as constrained.
 *
 * v2.6.1: the engine carries the grammar kill-switch flag and
 * hands it to the shim on every generate(). It defaults to OFF —
 * the pre-v2.4 greedy-only configuration — and MainActivity sets
 * it from the persisted user setting at launch and on every
 * toggle. The sampler chain is built per call natively, so a
 * toggle takes effect on the next call with no model reload.
 */
public class ModelEngine {

    /** Immutable snapshot of one generate() call's native stats. */
    public static final class Stats {
        public final long lastPromptTokens;
        public final long lastReusedTokens;
        public final long lastEvaluatedTokens;
        public final long lastPromptMs;
        public final long lastGenTokens;
        public final long lastGenMs;
        /** v2.4: was this call's sampling grammar-constrained
         *  (the native GBNF grammar of Item 3 active)? Reported by
         *  the shim per call — it is false whenever the grammar
         *  failed to initialise natively and generation fell back
         *  to unconstrained greedy, so the trail never claims a
         *  constraint that was not actually applied. */
        public final boolean lastGrammarConstrained;

        public Stats(long lastPromptTokens, long lastReusedTokens,
                     long lastEvaluatedTokens, long lastPromptMs,
                     long lastGenTokens, long lastGenMs) {
            this(lastPromptTokens, lastReusedTokens, lastEvaluatedTokens,
                    lastPromptMs, lastGenTokens, lastGenMs, false);
        }

        public Stats(long lastPromptTokens, long lastReusedTokens,
                     long lastEvaluatedTokens, long lastPromptMs,
                     long lastGenTokens, long lastGenMs,
                     boolean lastGrammarConstrained) {
            this.lastPromptTokens = lastPromptTokens;
            this.lastReusedTokens = lastReusedTokens;
            this.lastEvaluatedTokens = lastEvaluatedTokens;
            this.lastPromptMs = lastPromptMs;
            this.lastGenTokens = lastGenTokens;
            this.lastGenMs = lastGenMs;
            this.lastGrammarConstrained = lastGrammarConstrained;
        }
    }

    private static final Stats ZERO = new Stats(0, 0, 0, 0, 0, 0);

    private long handle = 0;
    private String loadedPath;
    private Stats lastStats = ZERO;
    /** v2.6.1: the grammar kill-switch, as last set by the owner
     *  (MainActivity, from the persisted setting). False — the
     *  default, and the state before anyone sets it — means the
     *  shim never builds the grammar sampler. */
    private boolean grammarOn = false;

    public synchronized boolean isLoaded() {
        return handle != 0;
    }

    /** v2.6: the path the loaded model came from (null when
     *  nothing is loaded), so the caller can tell whether the
     *  engine holds the ACTIVE model's file or a previous choice's
     *  and reload on a switch. */
    public synchronized String loadedPath() {
        return loadedPath;
    }

    public synchronized boolean load(String path) {
        if (handle != 0 && path.equals(loadedPath)) return true;
        if (handle != 0) {
            NativeBridge.freeModel(handle);
            handle = 0;
            loadedPath = null;
        }
        long h = NativeBridge.loadModel(path);
        if (h == 0) return false;
        handle = h;
        loadedPath = path;
        lastStats = ZERO;
        return true;
    }

    /** The most recent generate() call's stats (all zeros before the
     *  first call, after a load, and after a call that produced no
     *  usable result array). */
    public synchronized Stats lastStats() {
        return lastStats;
    }

    /** v2.6.1: set the grammar kill-switch. Read by generate() on
     *  every call, so a change applies from the next call onward;
     *  no reload is involved. */
    public synchronized void setGrammarOn(boolean on) {
        grammarOn = on;
    }

    /** v2.6.1: the kill-switch state generate() will hand the shim
     *  on the next call. */
    public synchronized boolean grammarOn() {
        return grammarOn;
    }

    /**
     * Parse a native result array into a Stats. Pure and defensive:
     * a null/short array (e.g. an older 5-field build), null slots,
     * and non-numeric values all yield zeros for the affected fields
     * — this never throws.
     * Field order: [1] prompt tokens, [5] reused, [6] evaluated,
     * [2] prompt ms, [3] generated tokens, [4] generation ms,
     * [7] grammar-constrained flag ("1" = constrained; anything
     * else, including a missing slot, = not constrained).
     */
    public static Stats parseStats(String[] r) {
        return new Stats(num(r, 1), num(r, 5), num(r, 6),
                num(r, 2), num(r, 3), num(r, 4),
                r != null && r.length > 7 && r[7] != null
                        && "1".equals(r[7].trim()));
    }

    private static long num(String[] r, int i) {
        if (r == null || i < 0 || i >= r.length || r[i] == null) return 0;
        try {
            return Long.parseLong(r[i].trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Returns the generated text, or an "ERROR: ..." string. */
    public synchronized String generate(String prompt, int maxTokens) {
        if (handle == 0) return "ERROR: no model loaded";
        String[] r;
        try {
            r = NativeBridge.generate(handle, prompt, maxTokens, grammarOn);
        } catch (Throwable th) {
            lastStats = ZERO;
            return "ERROR: generate threw " + th;
        }
        if (r == null || r.length < 1 || r[0] == null) {
            lastStats = ZERO;
            return "ERROR: no result";
        }
        lastStats = parseStats(r);
        return r[0];
    }

    public synchronized void close() {
        if (handle != 0) {
            NativeBridge.freeModel(handle);
            handle = 0;
            loadedPath = null;
        }
        lastStats = ZERO;
    }
}
