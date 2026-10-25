package com.ghosthands.app;

import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The local brain's weights: Qwen2.5-1.5B-Instruct GGUFs, downloaded
 * on demand into app-private storage with HTTP Range resume and
 * size + sha256 verification before a file is moved into place —
 * the exact flow the on-device proof validated. Nothing is bundled
 * in the APK and nothing about the model ever leaves the phone.
 *
 * v2.6: TWO quants of the same brain live side by side in a small
 * registry — Q4_K_M (the pin through v2.3: faster, smaller) and
 * Q5_K_M (the pin since v2.4: sharper, larger) — and the app uses
 * whichever one the user picked (the choice persists in
 * SharedPreferences, owned by MainActivity; it defaults to
 * Q5_K_M, the v2.4 behaviour). Switching never deletes either
 * model's file: each downloads once, verifies once, and stays.
 * The registry and selection logic below are pure and host-tested;
 * the legacy MODEL_* constants remain as the Q5_K_M pin (they are
 * the Q5 spec's own values) so older references keep their meaning.
 *
 * Both pins were measured in the build environment from the actual
 * bytes as served by the official Qwen/Qwen2.5-1.5B-Instruct-GGUF
 * repo (stat + sha256sum over the completed downloads; each file's
 * size identical before and after hashing). The Q4_K_M URL is the
 * v2.3-era one, recovered from the proof's MainActivity, which the
 * v1 app inherited unchanged.
 *
 * v2.6.1: this class also owns the grammar kill-switch's pure
 * plumbing (GRAMMAR_LABEL / GRAMMAR_DEFAULT_ON / resolveGrammarOn
 * / grammarPrefValue / grammarToggleLabel) — pure and host-tested
 * like the registry above; MainActivity owns the persisted value
 * and the toggle UI, ModelEngine carries the flag into the shim.
 */
public class ModelStore {

    /** One downloadable brain: identity, presentation, and pin. */
    public static final class Spec {
        public final String id;
        public final String displayName;
        public final String shortName;
        public final String fileName;
        public final String url;
        public final long size;
        public final String sha256;

        public Spec(String id, String displayName, String shortName,
                    String fileName, String url, long size, String sha256) {
            this.id = id;
            this.displayName = displayName;
            this.shortName = shortName;
            this.fileName = fileName;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    // ---- the Q5_K_M pin (v2.4; also the legacy constants) --------
    public static final String MODEL_NAME = "qwen2.5-1.5b-instruct-q5_k_m.gguf";
    public static final String MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/"
                    + MODEL_NAME;
    public static final long MODEL_SIZE = 1285494304L;
    public static final String MODEL_SHA256 =
            "b46661073c18e5b56a41fa320975f866a00def1ff08feef4718e013258896f8c";

    // ---- the registry (v2.6) --------------------------------------
    public static final String Q4_ID = "q4_k_m";
    public static final String Q5_ID = "q5_k_m";
    /** The model an install with no saved choice uses: Q5_K_M,
     *  the behaviour since v2.4. */
    public static final String DEFAULT_ID = Q5_ID;

    private static final String HF_BASE =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/";

    public static final Spec Q4 = new Spec(Q4_ID,
            "Qwen2.5-1.5B Q4_K_M (faster, smaller)", "Q4_K_M",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            HF_BASE + "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1117320736L,
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e");
    public static final Spec Q5 = new Spec(Q5_ID,
            "Qwen2.5-1.5B Q5_K_M (sharper, larger)", "Q5_K_M",
            MODEL_NAME, MODEL_URL, MODEL_SIZE, MODEL_SHA256);

    /** Every brain the switcher offers, in picker order. */
    public static final Spec[] MODELS = { Q4, Q5 };

    /** The spec for an id, or null when the id names no known
     *  model (unknown ids are rejected, never guessed at). The id
     *  is trimmed and matched case-insensitively. */
    public static Spec specFor(String id) {
        if (id == null) return null;
        String key = id.trim().toLowerCase(Locale.US);
        for (int i = 0; i < MODELS.length; i++) {
            if (MODELS[i].id.equals(key)) return MODELS[i];
        }
        return null;
    }

    /** Resolve a persisted choice to a usable model id: the choice
     *  itself when it names a known model, the default otherwise. */
    public static String resolveId(String saved) {
        Spec s = specFor(saved);
        return s != null ? s.id : DEFAULT_ID;
    }

    // ---- the grammar kill-switch (v2.6.1) -------------------------
    //
    // v2.4 added a GBNF grammar sampler to the native generate
    // chain — the only brand-new native code path in that release,
    // and the app has closed on-device three times since (twice on
    // v2.4, once on the v2.5-era build), after a full day of stable
    // runs on v1.0-v2.3. The exact defect is NOT proven; this switch
    // is the containment. It lives beside the model picker, persists
    // the same way (a string in the same SharedPreferences), and
    // DEFAULTS TO OFF for every install, upgrades included: off,
    // the shim builds the pre-v2.4 greedy-only chain and
    // PlannerParse is again the only format defense, exactly as in
    // v1.0-v2.3. On is v2.4+ behaviour, unchanged.

    /** The settings-row label for the switch, verbatim. */
    public static final String GRAMMAR_LABEL =
            "Output grammar lock (experimental — off is the stable setting)";

    /** The switch's state in a fresh install: OFF, always. */
    public static final boolean GRAMMAR_DEFAULT_ON = false;

    /** Resolve a persisted switch value: only an explicit "on"
     *  (trimmed, any case) turns the grammar on; null, "off", and
     *  anything unrecognised resolve to the default — off. */
    public static boolean resolveGrammarOn(String saved) {
        if (saved == null) return GRAMMAR_DEFAULT_ON;
        return "on".equals(saved.trim().toLowerCase(Locale.US));
    }

    /** The persisted form of the switch state ("on" / "off"). */
    public static String grammarPrefValue(boolean on) {
        return on ? "on" : "off";
    }

    /** The toggle chip's text for a switch state. */
    public static String grammarToggleLabel(boolean on) {
        return "Grammar lock: " + (on ? "On" : "Off");
    }

    public static Spec defaultSpec() {
        return specFor(DEFAULT_ID);
    }

    /** "1.1 GB" — the one-time download size, decimal GB, one
     *  decimal place (the same rounding the UI has always shown). */
    public static String gbLabel(Spec spec) {
        return String.format(Locale.US, "%.1f GB", spec.size / 1000000000.0);
    }

    /** The picker chip's text for one model, given whether it is
     *  the active choice and whether its file is on the phone. */
    public static String pickerLabel(Spec spec, boolean active,
                                     boolean downloaded) {
        if (active) {
            return spec.shortName + (downloaded
                    ? " ✓ active" : " ✓ chosen");
        }
        return spec.shortName + (downloaded
                ? " · on phone" : " · " + gbLabel(spec) + " download");
    }

    // ---- the model manager (v3.7, DATA) -------------------------
    //
    // The DATA section's per-model rows: each known model, its
    // on-device state, and a Delete for the ones that may go.
    // The verdict below is the whole rule set, factored pure so
    // the harness pins it: a model is deletable only when its
    // file is present, it is not the BRAIN's selected model,
    // and no download session for it is in flight. Deletion
    // itself (deleteFile) removes exactly that model's file —
    // the other model, any partial download, settings and every
    // other store are outside its reach by construction.

    public static final int DELETE_OK = 0;
    public static final int DELETE_ABSENT = 1;
    public static final int DELETE_DOWNLOADING = 2;
    public static final int DELETE_IN_USE = 3;

    /**
     * May this model's file be deleted right now? The order is
     * the rows' honesty: an absent file has nothing to delete;
     * a live download session for this spec is the transient
     * fact the user is looking at (the session's spec is always
     * the selected one — a download only ever fetches the BRAIN
     * choice — so this leg is named before the in-use leg, and
     * the row's reason changes from "downloading" to "in use"
     * when the session lands); the selected model is protected
     * for as long as it is selected.
     */
    public static int deleteVerdict(Spec spec, String selectedId,
                                    boolean present,
                                    boolean downloadInFlight) {
        if (spec == null || !present) return DELETE_ABSENT;
        if (downloadInFlight) return DELETE_DOWNLOADING;
        if (spec.id.equals(resolveId(selectedId))) {
            return DELETE_IN_USE;
        }
        return DELETE_OK;
    }

    /** A byte count for a settings row: GB with two decimals
     *  at model scale, MB with one below it, raw bytes for
     *  anything smaller. */
    public static String humanSize(long bytes) {
        if (bytes >= 1000000000L) {
            return String.format(Locale.US, "%.2f GB",
                    bytes / 1000000000.0);
        }
        if (bytes >= 1000000L) {
            return String.format(Locale.US, "%.1f MB",
                    bytes / 1000000.0);
        }
        return bytes + " B";
    }

    public interface Listener {
        void onProgress(long done, long total);
        void onStatus(String s);
    }

    private final File filesDir;

    public ModelStore(File filesDir) {
        this.filesDir = filesDir;
    }

    public File fileFor(Spec spec) {
        return new File(filesDir, spec.fileName);
    }

    private File partFileFor(Spec spec) {
        return new File(filesDir, spec.fileName + ".part");
    }

    /** The default model's file (the v2.5 meaning of modelFile()). */
    public File modelFile() {
        return fileFor(defaultSpec());
    }

    private File partFile() {
        return partFileFor(defaultSpec());
    }

    public boolean ready(Spec spec) {
        File f = fileFor(spec);
        return f.exists() && f.length() == spec.size;
    }

    /**
     * v3.7 (the model manager): delete exactly this model's
     * file — nothing else in the directory is named, so nothing
     * else can go: the other model's file and any .part files
     * survive by construction (a partial is a different name,
     * and a later download resumes from it as it always has).
     * Returns true when a file was actually deleted.
     */
    public boolean deleteFile(Spec spec) {
        if (spec == null) return false;
        File f = fileFor(spec);
        return f.exists() && f.delete();
    }

    /** Whether the DEFAULT model is in place (the v2.5 meaning). */
    public boolean ready() {
        return ready(defaultSpec());
    }

    public long partialBytes(Spec spec) {
        File p = partFileFor(spec);
        return p.exists() ? p.length() : 0;
    }

    public long partialBytes() {
        return partialBytes(defaultSpec());
    }

    /** Download the default model (the v2.5 entry point). */
    public boolean download(Listener listener) {
        return download(defaultSpec(), listener);
    }

    /**
     * One download session for one model: resumes from that model's
     * .part file with Range requests and retries internally;
     * verifies size + sha256 at the end. Blocking — call from a
     * worker thread. Returns true when the model is in place and
     * verified. The other model's files are never touched.
     */
    public boolean download(Spec spec, Listener listener) {
        File part = partFileFor(spec);
        File dest = fileFor(spec);
        int attempts = 0;
        while (attempts < 6) {
            attempts++;
            long have = part.exists() ? part.length() : 0;
            if (have == spec.size) break;
            if (have > spec.size) {
                part.delete();
                have = 0;
            }
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                URL url = new URL(spec.url);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                if (have > 0) {
                    conn.setRequestProperty("Range", "bytes=" + have + "-");
                }
                int code = conn.getResponseCode();
                boolean append;
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    append = have > 0;
                } else if (code == HttpURLConnection.HTTP_OK) {
                    append = false;
                    have = 0;
                } else if (code == 416 && part.exists() && part.length() == spec.size) {
                    break;
                } else {
                    listener.onStatus("Server returned HTTP " + code + ", retrying...");
                    SystemClock.sleep(1500);
                    continue;
                }
                in = conn.getInputStream();
                out = new FileOutputStream(part, append);
                byte[] buf = new byte[65536];
                long lastUi = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    have += n;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUi > 250) {
                        lastUi = now;
                        listener.onProgress(have, spec.size);
                    }
                }
                out.flush();
                listener.onProgress(have, spec.size);
            } catch (Exception e) {
                listener.onStatus("Connection dropped (" + e.getMessage() + "), resuming...");
                SystemClock.sleep(1500);
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) { }
                if (in != null) try { in.close(); } catch (Exception ignored) { }
                if (conn != null) conn.disconnect();
            }
        }
        if (!part.exists() || part.length() != spec.size) {
            listener.onStatus("Download incomplete: "
                    + (part.exists() ? part.length() : 0) + " / " + spec.size
                    + " bytes. Tap Download to resume.");
            return false;
        }
        listener.onStatus("Verifying sha256...");
        String sha;
        try {
            sha = sha256OfFile(part);
        } catch (Exception e) {
            listener.onStatus("Verification failed: " + e.getMessage());
            return false;
        }
        if (!spec.sha256.equals(sha)) {
            part.delete();
            listener.onStatus("SHA-256 mismatch (got " + sha
                    + "). File deleted; tap Download to try again.");
            return false;
        }
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) {
            listener.onStatus("Could not move model into place.");
            return false;
        }
        listener.onStatus("Model ready and verified.");
        return true;
    }

    private static String sha256OfFile(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[262144];
        int n;
        while ((n = in.read(buf)) != -1) {
            md.update(buf, 0, n);
        }
        in.close();
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            sb.append(String.format("%02x", digest[i] & 0xff));
        }
        return sb.toString();
    }
}
