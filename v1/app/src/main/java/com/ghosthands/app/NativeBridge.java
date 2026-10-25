package com.ghosthands.app;

/**
 * JNI bridge to libghhands.so (a small C shim over llama.cpp, pinned
 * b11382 — the same shim design as the on-device proof).
 *
 * generate() returns a String[8]:
 *   [0] generated text (or "ERROR: ...")
 *   [1] prompt token count
 *   [2] prompt eval elapsed ms (v1.5: suffix-only on a prefix-cache
 *       hit; the reused prefix is not re-decoded)
 *   [3] generated token count
 *   [4] generation elapsed ms
 *   [5] prompt tokens reused via the prefix cache this call
 *   [6] prompt tokens actually evaluated this call
 *   [7] "1" when this call's sampling was grammar-constrained
 *       (v2.4: the shim's GBNF action grammar), else "0" — and it
 *       is "0" on every call made with the grammar switch off
 *       (v2.6.1), whatever the grammar would have done
 * Older Java code reading only [0..4] keeps working unchanged.
 *
 * The shim applies the model's own chat template to the single prompt
 * string and samples greedily, so planner output is deterministic.
 * v2.4: a GBNF grammar sampler sits ahead of the greedy sampler in
 * the chain, constraining the output to exactly one action object;
 * PlannerParse remains in force as the second line of defense.
 * v2.6.1: generate() takes the kill-switch flag — when useGrammar
 * is false the shim builds the pre-v2.4 greedy-only chain and never
 * creates the grammar sampler at all.
 * Context is 2048 tokens. The thread count arrives with loadModel
 * (v3.6: the BRAIN setting — 4 remains the default, the proof's
 * on-device setting: ~20 tok/s prompt, ~14 tok/s generation on a
 * Moto G Stylus 2025) and is baked into the context at creation,
 * so a change applies at the next load only.
 * v3.6: generate() also takes the prefix-cache switch — when
 * usePrefixCache is false the shim drops its cached prompt and
 * evaluates every prompt in full, the pre-v1.5 behaviour.
 */
public class NativeBridge {
    static {
        System.loadLibrary("ghhands");
    }

    public static native long loadModel(String path, int nThreads);

    public static native String[] generate(long handle, String prompt,
                                           int maxTokens, boolean useGrammar,
                                           boolean usePrefixCache);

    public static native void freeModel(long handle);
}
