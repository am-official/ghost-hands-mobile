package com.ghosthands.app;

/**
 * v3.8 (DIAGNOSTICS): the engine self-test's pure half — the
 * availability decision, the canonical prompt, and the report.
 *
 * The check is the proof app's health check, productized: run
 * ONE fixed prompt through the engine and report what the
 * engine itself measured (the same ModelEngine.Stats every
 * decide already produces), plus whether the reply parses as
 * a valid planner action (PlannerParse — the strict extractor
 * the loop itself trusts). Nothing here touches the trail:
 * the in-place readout under the button is the whole record,
 * and the harness pins that this class has no trail seam.
 *
 * Availability, in order: a run, replay or teach in flight
 * refuses (the engine and the page belong to it); a model not
 * downloaded refuses (there is nothing to load); anything else
 * may run. The Activity owns the engine work, on a worker
 * thread; this class owns the words.
 */
public final class SelfTest {

    private SelfTest() { }

    public static final int AVAILABLE = 0;
    public static final int BUSY = 1;
    public static final int NOT_DOWNLOADED = 2;

    /** The refusal the brief names, verbatim. */
    public static final String BUSY_LINE =
            "Self-test unavailable while a run is active";

    /** The refusal when the selected model is not on the phone. */
    public static final String NO_MODEL_LINE =
            "Self-test unavailable — the selected model is not "
            + "downloaded.";

    /** The canonical prompt: short, fixed, and shaped to draw
     *  exactly one bare action object — a done action whose
     *  summary is the test's own name, so a valid reply is
     *  unmistakable in the readout's verdict. */
    public static final String CANONICAL_PROMPT =
            "You are the planner for a phone web agent. Reply "
            + "with exactly one JSON action object and nothing "
            + "else: {\"action\": \"done\", \"summary\": "
            + "\"self test ok\"}";

    /** Generation budget for the canonical reply — comfortably
     *  more than the object needs, small enough that a rambling
     *  reply fails fast instead of filling the readout's wait. */
    public static final int MAX_TOKENS = 64;

    /** The availability decision: busy outranks not-downloaded
     *  (a run in flight against a model that was switched away
     *  from still owns the engine). */
    public static int availability(boolean busy, boolean modelReady) {
        if (busy) return BUSY;
        if (!modelReady) return NOT_DOWNLOADED;
        return AVAILABLE;
    }

    /** Whether a generate() reply is a valid action JSON —
     *  judged by the loop's own strict extractor, so the
     *  self-test cannot pass a reply the loop would reject. */
    public static boolean isValidActionReply(String reply) {
        return PlannerParse.extract(reply) != null;
    }

    /**
     * The report the readout shows. loadMs < 0 means the engine
     * already held the model (no load happened — the line says
     * so instead of inventing a time). Rates are the Stats'
     * own: prompt tokens over prompt ms, generated tokens over
     * generation ms; a zero denominator reads as "—" rather
     * than a made-up rate. A failed generate (reply == null or
     * the engine's "ERROR: ..." string) reports as a failure
     * with the engine's own words, not as rates.
     */
    public static String report(String modelLabel, long loadMs,
                                String reply, ModelEngine.Stats st) {
        StringBuilder sb = new StringBuilder();
        sb.append("Self-test — ").append(modelLabel).append('\n');
        if (loadMs >= 0) {
            sb.append("Load: ").append(loadMs).append(" ms\n");
        } else {
            sb.append("Load: already loaded\n");
        }
        if (reply == null || reply.startsWith("ERROR:")) {
            sb.append("Generation failed: ")
                    .append(reply == null ? "no reply" : reply);
            return sb.toString();
        }
        long promptTokens = (st == null) ? 0 : st.lastPromptTokens;
        long promptMs = (st == null) ? 0 : st.lastPromptMs;
        long genTokens = (st == null) ? 0 : st.lastGenTokens;
        long genMs = (st == null) ? 0 : st.lastGenMs;
        sb.append("Prompt: ").append(rate(promptTokens, promptMs))
                .append(" tok/s (").append(promptTokens)
                .append(" tokens)\n");
        sb.append("Generation: ").append(rate(genTokens, genMs))
                .append(" tok/s (").append(genTokens)
                .append(" tokens)\n");
        sb.append("Reply parsed as a valid action JSON: ")
                .append(isValidActionReply(reply) ? "yes" : "no");
        return sb.toString();
    }

    /** Tokens per second, one decimal; "—" when the measured
     *  interval carries no time to divide by. */
    public static String rate(long tokens, long ms) {
        if (ms <= 0) return "—";
        double r = tokens * 1000.0 / ms;
        return String.format(java.util.Locale.US, "%.1f",
                Double.valueOf(r));
    }
}
