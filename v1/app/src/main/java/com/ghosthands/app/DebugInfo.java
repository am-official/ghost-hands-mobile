package com.ghosthands.app;

/**
 * v3.8 (DIAGNOSTICS): the Copy debug info bundle, pure.
 *
 * The bundle is EXACTLY the lines {@link #build} joins, from
 * exactly the values it is handed — the builder takes no goal,
 * no URL, no trail body, no saved-goal name, so none can leak:
 * the privacy scope is the signature. What it carries is what
 * a field report to the developer needs and nothing more: the
 * app version, the device and its Android version, the
 * selected model and its on-device state, the four settings
 * that change how the engine runs, and the two store counts.
 * The harness pins the line set (every line present, in order)
 * and the scope (fixture strings carrying goal-like and
 * URL-like text appear nowhere, because there is no parameter
 * they could arrive through).
 */
public final class DebugInfo {

    private DebugInfo() { }

    /** The clipboard label the Activity posts the bundle under. */
    public static final String CLIP_LABEL = "Ghost Hands debug info";

    /** The in-place line the copy reports. */
    public static final String COPIED_LINE = "Copied debug info.";

    public static String build(String versionLabel, String deviceModel,
                               String androidRelease, String modelId,
                               String modelState, boolean grammarOn,
                               int threads, boolean prefixCacheOn,
                               boolean stepShotsOn, int trailCount,
                               int shotCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ghost Hands ").append(safe(versionLabel)).append('\n');
        sb.append("Device: ").append(safe(deviceModel))
                .append(" · Android ").append(safe(androidRelease))
                .append('\n');
        sb.append("Model: ").append(safe(modelId)).append(" — ")
                .append(safe(modelState)).append('\n');
        sb.append("Grammar: ").append(onOff(grammarOn))
                .append(" · Threads: ").append(threads)
                .append(" · Prefix cache: ").append(onOff(prefixCacheOn))
                .append(" · Step screenshots: ")
                .append(onOff(stepShotsOn)).append('\n');
        sb.append("Trails: ").append(trailCount)
                .append(" · Screenshots: ").append(shotCount);
        return sb.toString();
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    /** A handed value, made single-line: a value that arrived
     *  with newlines in it cannot smuggle extra bundle lines. */
    private static String safe(String s) {
        if (s == null) return "?";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
