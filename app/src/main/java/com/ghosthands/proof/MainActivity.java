package com.ghosthands.proof;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Ghost Hands Proof: one screen that (1) downloads the Qwen2.5-1.5B
 * GGUF into app-private storage with resume + sha256 verification,
 * and (2) runs a fixed benchmark prompt through on-device llama.cpp
 * three times, showing load time and tokens/s so the numbers can be
 * copied and pasted back into chat.
 */
public class MainActivity extends Activity {

    private static final String MODEL_NAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final String MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/"
                    + MODEL_NAME;
    private static final long MODEL_SIZE = 1117320736L;
    private static final String MODEL_SHA256 =
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";
    private static final String TEST_PROMPT =
            "Reply with exactly one JSON object: {\"action\": \"wait\"}";
    private static final int MAX_TOKENS = 64;
    private static final int RUNS = 3;

    private TextView statusText;
    private ProgressBar progressBar;
    private Button downloadBtn;
    private Button benchBtn;
    private TextView resultsText;
    private Button copyBtn;

    private boolean downloading = false;
    private boolean benching = false;

    private File modelFile() {
        return new File(getFilesDir(), MODEL_NAME);
    }

    private File partFile() {
        return new File(getFilesDir(), MODEL_NAME + ".part");
    }

    private boolean modelReady() {
        File f = modelFile();
        return f.exists() && f.length() == MODEL_SIZE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Ghost Hands Proof");
        title.setTextSize(22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("On-device llama.cpp benchmark\nModel: " + MODEL_NAME
                + "\n(" + MODEL_SIZE + " bytes, sha256 verified after download)");
        root.addView(sub);

        statusText = new TextView(this);
        statusText.setText("Checking for model...");
        root.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgress(0);
        root.addView(progressBar);

        downloadBtn = new Button(this);
        downloadBtn.setText("Download model");
        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload();
            }
        });
        root.addView(downloadBtn);

        benchBtn = new Button(this);
        benchBtn.setText("Run benchmark");
        benchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBenchmark();
            }
        });
        root.addView(benchBtn);

        resultsText = new TextView(this);
        resultsText.setTextSize(13);
        resultsText.setTextIsSelectable(true);
        resultsText.setText("");
        root.addView(resultsText);

        copyBtn = new Button(this);
        copyBtn.setText("Copy results");
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ghost-hands-proof results",
                            resultsText.getText().toString()));
                    Toast.makeText(MainActivity.this, "Results copied", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(copyBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        refreshState();
        if (!modelReady()) {
            startDownload();
        }
    }

    private void refreshState() {
        if (modelReady()) {
            statusText.setText("Model ready (" + MODEL_SIZE + " bytes).");
            progressBar.setProgress(1000);
            downloadBtn.setEnabled(false);
            benchBtn.setEnabled(!benching);
        } else {
            File p = partFile();
            long have = p.exists() ? p.length() : 0;
            if (have > 0) {
                statusText.setText("Model incomplete: " + have + " / " + MODEL_SIZE
                        + " bytes downloaded. Download resumes automatically.");
                progressBar.setProgress((int) (1000L * have / MODEL_SIZE));
            } else {
                statusText.setText("Model not downloaded yet.");
                progressBar.setProgress(0);
            }
            downloadBtn.setEnabled(!downloading);
            benchBtn.setEnabled(false);
        }
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(s);
            }
        });
    }

    private void setProgress(final long done, final long total) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (total > 0) {
                    progressBar.setProgress((int) (1000L * done / total));
                }
                statusText.setText("Downloading: " + done + " / " + total + " bytes");
            }
        });
    }

    // ---------------- download ----------------

    private void startDownload() {
        if (downloading || modelReady()) return;
        downloading = true;
        downloadBtn.setEnabled(false);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    ok = downloadOnce();
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                downloading = false;
                final boolean fok = ok;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fok) {
                            Toast.makeText(MainActivity.this, "Model ready", Toast.LENGTH_SHORT).show();
                        } else if (ferr != null) {
                            statusText.setText("Download stopped: " + ferr + " Tap Download to resume.");
                        }
                        refreshState();
                    }
                });
            }
        });
        t.setName("model-download");
        t.start();
    }

    /**
     * One download pass. Resumes from the .part file with a Range
     * request; if the server ignores the range (200 instead of 206)
     * the partial file is discarded and the pass restarts the file.
     * Retries internally a few times, continuing from .part each time.
     */
    private boolean downloadOnce() throws Exception {
        File part = partFile();
        File dest = modelFile();
        int attempts = 0;
        while (attempts < 6) {
            attempts++;
            long have = part.exists() ? part.length() : 0;
            if (have == MODEL_SIZE) break;
            if (have > MODEL_SIZE) {
                // Corrupt partial: start over.
                part.delete();
                have = 0;
            }
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                URL url = new URL(MODEL_URL);
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
                } else if (code == 416 && part.exists() && part.length() == MODEL_SIZE) {
                    break;
                } else {
                    setStatus("Server returned HTTP " + code + ", retrying...");
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
                        setProgress(have, MODEL_SIZE);
                    }
                }
                out.flush();
                setProgress(have, MODEL_SIZE);
            } catch (Exception e) {
                setStatus("Connection dropped (" + e.getMessage() + "), resuming...");
                SystemClock.sleep(1500);
            } finally {
                if (out != null) try { out.close(); } catch (Exception ignored) { }
                if (in != null) try { in.close(); } catch (Exception ignored) { }
                if (conn != null) conn.disconnect();
            }
        }
        if (!part.exists() || part.length() != MODEL_SIZE) {
            setStatus("Download incomplete: "
                    + (part.exists() ? part.length() : 0) + " / " + MODEL_SIZE
                    + " bytes. Tap Download to resume.");
            return false;
        }
        setStatus("Verifying sha256...");
        String sha = sha256OfFile(part);
        if (!MODEL_SHA256.equals(sha)) {
            part.delete();
            setStatus("SHA-256 mismatch (got " + sha + "). File deleted; tap Download to try again.");
            return false;
        }
        if (dest.exists()) dest.delete();
        if (!part.renameTo(dest)) {
            setStatus("Could not move model into place.");
            return false;
        }
        setStatus("Model ready and verified.");
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

    // ---------------- benchmark ----------------

    private void appendResults(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resultsText.setText(resultsText.getText().toString() + s);
            }
        });
    }

    private void startBenchmark() {
        if (benching || !modelReady()) return;
        benching = true;
        benchBtn.setEnabled(false);
        resultsText.setText("");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runBenchmark();
                benching = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        benchBtn.setEnabled(modelReady());
                    }
                });
            }
        });
        t.setName("benchmark");
        t.start();
    }

    private void runBenchmark() {
        String path = modelFile().getAbsolutePath();
        appendResults("Ghost Hands Proof benchmark\n");
        appendResults("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")\n");
        appendResults("Model: " + MODEL_NAME + " (" + MODEL_SIZE + " bytes)\n");
        appendResults("Prompt: " + TEST_PROMPT + "\n");
        appendResults("Sampling: greedy, max " + MAX_TOKENS + " tokens, " + RUNS + " runs\n\n");

        for (int run = 1; run <= RUNS; run++) {
            appendResults("--- Run " + run + " ---\n");
            long t0 = SystemClock.elapsedRealtime();
            long handle = 0;
            try {
                handle = NativeBridge.loadModel(path);
            } catch (Throwable th) {
                appendResults("loadModel threw: " + th + "\n\n");
                return;
            }
            long loadMs = SystemClock.elapsedRealtime() - t0;
            if (handle == 0) {
                appendResults("Model load FAILED\n\n");
                return;
            }
            appendResults("Load time: " + loadMs + " ms\n");

            String[] r;
            try {
                r = NativeBridge.generate(handle, TEST_PROMPT, MAX_TOKENS);
            } catch (Throwable th) {
                appendResults("generate threw: " + th + "\n\n");
                NativeBridge.freeModel(handle);
                return;
            }
            NativeBridge.freeModel(handle);

            if (r == null || r.length < 5) {
                appendResults("generate returned no result\n\n");
                return;
            }
            long promptToks = parseLong(r[1]);
            long promptMs = parseLong(r[2]);
            long genToks = parseLong(r[3]);
            long genMs = parseLong(r[4]);
            appendResults("Prompt: " + promptToks + " tokens in " + promptMs + " ms"
                    + rate(promptToks, promptMs) + "\n");
            appendResults("Generation: " + genToks + " tokens in " + genMs + " ms"
                    + rate(genToks, genMs) + "\n");
            appendResults("Output: " + r[0] + "\n\n");
        }
        appendResults("Done.\n");
    }

    private static String rate(long toks, long ms) {
        if (ms <= 0) return "";
        double tps = toks * 1000.0 / ms;
        return String.format(" (%.1f tok/s)", tps);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
