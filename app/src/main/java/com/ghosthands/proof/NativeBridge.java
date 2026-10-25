package com.ghosthands.proof;

/**
 * JNI bridge to libghproof.so (a small C shim over llama.cpp).
 *
 * generate() returns a String[5]:
 *   [0] generated text (or "ERROR: ...")
 *   [1] prompt token count
 *   [2] prompt eval elapsed ms
 *   [3] generated token count
 *   [4] generation elapsed ms
 */
public class NativeBridge {
    static {
        System.loadLibrary("ghproof");
    }

    public static native long loadModel(String path);

    public static native String[] generate(long handle, String prompt, int maxTokens);

    public static native void freeModel(long handle);
}
