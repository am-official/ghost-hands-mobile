package com.ghosthands.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Ghost Hands: a goal-driven web agent that lives entirely on the
 * phone. A local model (Qwen2.5-1.5B via llama.cpp) plans one JSON
 * action at a time; the Governor port judges every action before it
 * runs; consequential actions wait for an on-screen approval; every
 * decision is written to the trail before anything executes.
 *
 * Framework-only Java: no AndroidX, no Kotlin, no lambdas.
 */
public class MainActivity extends Activity {

    private ModelStore store;
    private final ModelEngine engine = new ModelEngine();

    private TextView statusText;
    private ProgressBar progressBar;
    private Button downloadBtn;
    private EditText urlEdit;
    private WebView webView;
    private EditText goalEdit;
    private Button startBtn;
    private Button stopBtn;
    private LinearLayout approvalPanel;
    private TextView approvalText;
    private ScrollView feedScroll;
    private TextView feedText;
    private Button feedTabBtn;
    private Button trailTabBtn;
    private TextView pillText;
    private LinearLayout timelineList;
    private Button viewTrailBtn;

    private boolean downloading = false;
    private volatile boolean running = false;
    private volatile boolean stopFlag = false;
    // Through v2.9 the log card had two views (steps / trail);
    // v3.0's answer card is the third. logMode: 0 steps, 1 trail,
    // 2 card (replaces the old showTrail boolean).
    private volatile int logMode = 0;
    private Button cardTabBtn;
    private LinearLayout cardBox;
    private TextView cardText;
    private AnswerCard lastCard = null;

    // v3.0 (item 2): the saved-goals store (one JSON preferences
    // value), the pack the next run will use, and what — if
    // anything — the goal field was loaded from. A check-
    // notification load sets scheduleLaunched, and a run started
    // from it goes under the readonly pack whatever the saved
    // pack says; the goal card says so before Start.
    private SavedGoals savedGoals;
    private String currentPackId = "standard";
    private String loadedSavedName = null;
    private boolean scheduleLaunched = false;
    private TextView goalNoteText;

    // v3.2 (watch mode): the saved goal the CURRENT run belongs
    // to, captured at Start. The fields above describe what is
    // loaded in the goal card right now (the user can load a
    // different goal there mid-run); the run's verdict belongs
    // to the goal it was started from, so it is pinned here.
    private String runSavedName = null;
    private boolean runWatched = false;

    // v3.0 (item 3): voice goals — the framework recognizer,
    // created per listening session and destroyed with it.
    private static final int REQ_RECORD_AUDIO = 7301;
    private Button micBtn;
    private SpeechRecognizer speechRec;
    private boolean speechListening = false;

    // v3.0 (item 5): whether the app is in the foreground — a
    // run that ends off-screen owes the user a notification; one
    // that ends on-screen just shows its card.
    private volatile boolean inForeground = false;

    // v2.6 (model switcher): the persisted brain choice (a
    // ModelStore registry id), resolved at launch; the default is
    // Q5_K_M, the behaviour since v2.4. Since v3.4 the key's
    // canonical name is Settings.KEY_MODEL_ID and the value rides
    // the settings model; this field mirrors it.
    private static final String PREFS_NAME = "ghosthands";
    private String activeModelId = ModelStore.DEFAULT_ID;
    private Button modelQ4Btn;
    private Button modelQ5Btn;

    // v2.6.1 (grammar kill-switch): the persisted "Output grammar
    // lock" choice, resolved at launch; it defaults to OFF — the
    // pre-v2.4 configuration — for every install, upgrades
    // included. Carried into the engine (ModelEngine.setGrammarOn)
    // and handed to each Agent for its trail records. Since v3.4
    // the key's canonical name is Settings.KEY_GRAMMAR_ON; this
    // field mirrors the model's value.
    private boolean grammarOn = ModelStore.GRAMMAR_DEFAULT_ON;
    private Button grammarBtn;

    // v3.4 (settings accordion): the ONE settings model — every
    // setting in the app resolves through it (Settings), persists
    // through it (persistSettings), and is controlled exactly
    // once, in the accordion built in buildUi. activeModelId and
    // grammarOn above are now mirrors of the model's first two
    // fields (the code that always read them still does); the
    // model is loaded before buildUi and is the source of truth
    // the mirrors are refreshed from at every change.
    private Settings settings;
    // AGENT section controls.
    private Button[] packChips;
    private Button[] stepChips;
    private Button copilotBtn;
    private Button autoReadBtn;
    // READING section controls.
    private Button[] chunkChips;
    private Button settleBtn;
    // VOICE & SPEECH section controls.
    private Button voiceBtn;
    private Button speakFinishBtn;
    // ALERTS section controls.
    private Button buzzBtn;
    // DATA section controls + the About footer's line.
    private Button[] shotChips;
    private EditText homeEdit;
    private TextView aboutText;
    // The accordion's moving parts (parallel lists, header order).
    private final List<LinearLayout> accBodies =
            new ArrayList<LinearLayout>();
    private final List<TextView> accChevrons =
            new ArrayList<TextView>();

    // v2.6 (press acknowledgement): true from the Stop tap until
    // the agent thread actually halts; the pill shows STOPPING
    // (amber, steady) in that window.
    private volatile boolean stopping = false;

    // v2.6 (AGENT ACTIVE pulse): the halo behind the status pill
    // and its animator; runs only while a run is live.
    private FrameLayout pillWrap;
    private GradientDrawable pillHalo;
    private ValueAnimator pulseAnimator;

    private Agent agent;
    private Trail currentTrail;
    private PolicyPacks.Pack pack;

    // approval handshake (agent thread waits, UI thread answers)
    private CountDownLatch approvalLatch;
    private volatile boolean approvalResult = false;

    // v3.3 (item 1): taught routes — the store (one JSON
    // preferences value, SavedGoals' discipline), and the live
    // teach session: while teaching, the user's own taps and
    // typing in the WebView are recorded into teachSteps;
    // teachPendingField is a field the user tapped into whose
    // value is read (and whose step is minted) only when they
    // move on — a password field's value is never read at all
    // (see Routes and Eyes.valueScanJs).
    private static final String PREF_ROUTES = "routes";
    private Routes routes;
    private volatile boolean teaching = false;
    private final List<Routes.Step> teachSteps =
            new ArrayList<Routes.Step>();
    private String teachStartUrl = "";
    private ElementInfo teachPendingField = null;
    private final Object teachLock = new Object();
    private LinearLayout teachPanel;
    private TextView teachText;
    private Button teachBtn;

    // v3.3 (item 2): the answer card's screenshot view.
    private ImageView shotView;

    // v3.3 (item 3): read-aloud — the framework TTS engine,
    // created lazily on the first Listen tap. ttsFailed latches
    // an unavailable engine (the control then shows disabled,
    // per SpeakText's labels).
    private TextToSpeech tts = null;
    private volatile boolean ttsReady = false;
    private volatile boolean ttsFailed = false;
    private volatile boolean ttsSpeaking = false;
    private Button listenBtn;

    // v3.3 (item 4 + item 1): the pause handshake — the agent
    // thread waits (Copilot.Pause judges the transitions), the
    // UI thread answers. One panel serves both pauses: the
    // co-pilot tap (identifyTap: Resume arms only once a tap
    // was identified) and a route's password fill (Resume is
    // enough). assistTapInfo is the identified element of the
    // user's tap; the touch plumbing fills it.
    private LinearLayout assistPanel;
    private TextView assistText;
    private CountDownLatch assistLatch;
    private volatile ElementInfo assistTapInfo = null;
    private volatile boolean assistStop = false;
    private volatile boolean assistWaiting = false;
    private volatile boolean assistIdentify = false;
    private volatile boolean assistResumeFlag = false;
    private volatile Copilot.Pause assistPause = null;
    private final Object pageTapLock = new Object();

    // v3.3: the current run's hands, kept so the co-pilot's tap
    // identification can match against the last perceived map.
    private WebHands hands;

    // page-load tracking for the hands
    private final Object loadLock = new Object();
    private volatile boolean pageLoading = false;

    // ---------------- lifecycle ----------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COL_BG_BOT);
        getWindow().setNavigationBarColor(COL_BG_BOT);
        store = new ModelStore(getFilesDir());
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        // v3.4: the settings model loads first — the brain and
        // grammar mirrors below are ITS first two fields, read
        // through the same resolvers that always read them
        // (Settings.fromMap delegates to ModelStore), so an
        // existing install's stored choices survive the
        // accordion move byte-for-byte.
        settings = loadSettings(prefs);
        activeModelId = settings.modelId;
        grammarOn = settings.grammarOn;
        engine.setGrammarOn(grammarOn);
        // v3.4 (setting c): a fresh run starts from the stored
        // default pack, not a hardcoded "standard".
        currentPackId = settings.defaultPackId;
        pack = PolicyPacks.loadPack(currentPackId);
        buildUi();
        refreshMicUi();
        setupWebView();
        // v3.4 (setting m): a set home page loads at launch.
        // Blank — the field's empty state, and every install
        // before this setting existed — loads nothing, exactly
        // the behaviour the app has always had.
        if (settings.homePage.length() > 0) {
            urlEdit.setText(settings.homePage);
            webView.loadUrl(settings.homePage);
        }
        // v3.0: the saved-goals store, its alarms, and whatever
        // the launch intent carries (a check notification's goal,
        // a finish notification's answer card).
        savedGoals = SavedGoals.parse(prefs.getString(
                Alerts.PREF_SAVED_GOALS, null));
        // v3.3 (item 1): the taught routes, same store discipline.
        routes = Routes.parse(prefs.getString(PREF_ROUTES, null));
        Alerts.ensureChannels(this);
        Alerts.armAllChecks(this, savedGoals);
        handleIntent(getIntent());
        // v2.6: a surviving run marker means the previous run never
        // reached a stop — say so before anything else speaks.
        checkRunMarker();
        refreshModelUi();
        if (!store.ready(activeSpec())) {
            startDownload();
        } else {
            feed("Model ready. Open a page with the address bar, "
                    + "type a goal, and tap Start.");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // v3.0: the app is singleTop — a check or finish
        // notification tap lands here on the live instance.
        handleIntent(intent);
    }

    @Override
    protected void onPause() {
        // v3.0 (item 5): from here a run that ends is a run that
        // ended off-screen — it owes a finish notification.
        inForeground = false;
        // v2.6: the pulse is a live-run signal, not a screensaver;
        // it stops when the app leaves the foreground and resumes
        // (via updatePill) only if a run is still live.
        stopPulse();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        updatePill();
    }

    @Override
    protected void onDestroy() {
        stopFlag = true;
        releaseApproval(false);
        releaseAssistStop();
        stopPulse();
        if (speechRec != null) {
            speechRec.destroy();
            speechRec = null;
        }
        // v3.3 (item 3): the TTS engine is a system resource;
        // it leaves with the Activity.
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsSpeaking = false;
        }
        engine.close();
        super.onDestroy();
    }

    /** v3.0: what a launch (or a notification tap on the live
     *  instance) asks the app to show: a finished run's answer
     *  card (the finish notification carries its trail path), or
     *  a saved goal loaded ready to start (a check notification
     *  carries its name — fromCheck, so the run goes readonly). */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String cardTrail = intent.getStringExtra(Alerts.EXTRA_CARD_TRAIL);
        if (cardTrail != null) {
            showCardForTrailFile(new File(cardTrail), null);
        }
        String name = intent.getStringExtra(Alerts.EXTRA_GOAL_NAME);
        if (name != null && savedGoals != null) {
            SavedGoals.SavedGoal g = savedGoals.find(name);
            if (g != null) {
                loadSavedGoal(g, intent.getBooleanExtra(
                        Alerts.EXTRA_FROM_CHECK, false));
            }
        }
    }

    /** The persisted brain choice as a registry spec (resolveId
     *  guarantees the id is a known one). */
    private ModelStore.Spec activeSpec() {
        return ModelStore.specFor(activeModelId);
    }

    // ---------------- UI construction ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------------- ethereal theme (v1.4 reskin) ----------------
    //
    // Presentation only: the concept look (near-black navy, cyan glow
    // edges, red reserved for stop/danger) applied with framework
    // GradientDrawable shapes and system fonts. No view changes what
    // it does; every id, listener, and callback is the v1.3 one.

    private static final int COL_BG_TOP = Color.rgb(6, 11, 24);      // #060B18
    private static final int COL_BG_BOT = Color.rgb(4, 7, 15);       // #04070F
    private static final int COL_CARD = Color.rgb(10, 18, 32);       // #0A1220
    private static final int COL_CARD_INNER = Color.rgb(13, 24, 48); // button fill
    private static final int COL_CARD_EDGE = Color.rgb(36, 80, 110); // glow border
    private static final int COL_CYAN = Color.rgb(56, 198, 255);     // #38C6FF
    private static final int COL_RED = Color.rgb(255, 74, 74);       // #FF4A4A
    private static final int COL_RED_FILL = Color.rgb(42, 14, 18);   // stop fill
    private static final int COL_TEXT = Color.rgb(234, 246, 255);    // #EAF6FF
    private static final int COL_MUTED = Color.rgb(143, 168, 191);   // #8FA8BF
    private static final int COL_AMBER = Color.rgb(255, 196, 77);    // guard/ask

    private GradientDrawable roundedBg(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(radiusDp));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private GradientDrawable cardBg() {
        return roundedBg(COL_CARD, COL_CARD_EDGE, 14);
    }

    private void stylePrimary(Button b) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(12));
        g.setColor(COL_CYAN);
        b.setBackground(g);
        b.setTextColor(Color.rgb(3, 30, 43));
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
    }

    private void styleQuiet(Button b) {
        b.setBackground(roundedBg(COL_CARD_INNER, COL_CARD_EDGE, 12));
        b.setTextColor(COL_TEXT);
        b.setAllCaps(false);
    }

    private void styleDanger(Button b) {
        b.setBackground(roundedBg(COL_RED_FILL, COL_RED, 12));
        b.setTextColor(COL_RED);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
    }

    private Button chipButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(3), dp(10), dp(3));
        b.setBackground(roundedBg(COL_CARD_INNER, COL_CARD_EDGE, 12));
        b.setTextColor(COL_MUTED);
        return b;
    }

    private TextView cardLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(COL_CYAN);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(0.18f);
        return t;
    }

    /** The header status pill: red (and pulsing, v2.6) while the
     *  agent runs, amber steady while a stop is in flight, cyan
     *  when the active model is ready, muted otherwise. Derived
     *  from the same state the status line reports; updated
     *  wherever that state changes (refreshModelUi, download
     *  callbacks, agent status, the Start/Stop taps). Like every
     *  view touch in this class it is only ever called on the UI
     *  thread. */
    private void updatePill() {
        if (pillText == null) return;
        String label;
        int color;
        int edge = COL_CARD_EDGE;
        if (stopping && running) {
            label = "● STOPPING";
            color = COL_AMBER;
            edge = Color.rgb(122, 92, 34);
        } else if (running) {
            label = "● AGENT ACTIVE";
            color = COL_RED;
            edge = Color.rgb(122, 34, 48);
        } else if (downloading) {
            label = "● DOWNLOADING";
            color = COL_CYAN;
        } else if (store != null && store.ready(activeSpec())) {
            label = "● MODEL READY · " + activeSpec().shortName;
            color = COL_CYAN;
        } else {
            label = "● NO MODEL";
            color = COL_MUTED;
        }
        pillText.setText(label);
        pillText.setTextColor(color);
        pillText.setBackground(roundedBg(COL_CARD, edge, 20));
        // The pulse lives exactly as long as the run does.
        if (running && !stopping) {
            startPulse();
        } else {
            stopPulse();
        }
    }

    /** v2.6: the AGENT ACTIVE breathing glow — a soft red halo
     *  behind the pill swelling and fading on a ~1.4 s cycle, with
     *  a slight scale breath. Idempotent; a no-op when the halo is
     *  already breathing. If the device has animations disabled
     *  system-wide the animator simply never advances and the pill
     *  stays lit — the state is still legible. */
    private void startPulse() {
        if (pillWrap == null || pillHalo == null) return;
        if (pulseAnimator == null) {
            pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
            pulseAnimator.setDuration(1400);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator animation) {
                    float f = ((Float) animation.getAnimatedValue()).floatValue();
                    if (pillHalo != null) pillHalo.setAlpha((int) (115 * f));
                    if (pillWrap != null) {
                        pillWrap.setScaleX(1f + 0.05f * f);
                        pillWrap.setScaleY(1f + 0.05f * f);
                    }
                }
            });
        }
        if (!pulseAnimator.isRunning()) pulseAnimator.start();
    }

    /** v2.6: stop the pulse dead and return the halo to rest. */
    private void stopPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
        if (pillHalo != null) pillHalo.setAlpha(0);
        if (pillWrap != null) {
            pillWrap.setScaleX(1f);
            pillWrap.setScaleY(1f);
        }
    }

    /** v2.6: one short haptic tick confirming a Start/Stop tap was
     *  received. Fully guarded: a missing vibrator, a missing
     *  permission, or any platform surprise must never break the
     *  tap path it rides on. */
    private void hapticTick() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(15,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(15);
            }
        } catch (Throwable ignored) {
            // A tick is a courtesy, not a requirement.
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(14), dp(12), dp(12));
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COL_BG_TOP, COL_BG_BOT});
        root.setBackground(bgGrad);

        // header: wordmark + subtitle, status pill at the right
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Ghost Hands");
        title.setTextSize(30);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setTextColor(COL_TEXT);
        titleCol.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Ethereal on-device agent");
        sub.setTextSize(12);
        sub.setTextColor(COL_MUTED);
        titleCol.addView(sub);
        header.addView(titleCol, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // v2.6: the pill sits in a padded wrap whose background is
        // the pulse halo (invisible at rest); the pill itself is
        // unchanged from the v1.4 skin.
        pillWrap = new FrameLayout(this);
        pillWrap.setPadding(dp(3), dp(3), dp(3), dp(3));
        pillHalo = new GradientDrawable();
        pillHalo.setShape(GradientDrawable.RECTANGLE);
        pillHalo.setCornerRadius(dp(22));
        pillHalo.setColor(COL_RED);
        pillHalo.setAlpha(0);
        pillWrap.setBackground(pillHalo);
        pillText = new TextView(this);
        pillText.setTextSize(10);
        pillText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pillText.setPadding(dp(10), dp(5), dp(10), dp(5));
        pillWrap.addView(pillText);
        header.addView(pillWrap);
        root.addView(header);

        // model card: detailed status line, cyan progress, download
        LinearLayout modelCard = new LinearLayout(this);
        modelCard.setOrientation(LinearLayout.VERTICAL);
        modelCard.setBackground(cardBg());
        modelCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        modelLp.topMargin = dp(12);
        root.addView(modelCard, modelLp);

        statusText = new TextView(this);
        statusText.setText("Checking for model...");
        statusText.setTextSize(12);
        statusText.setTextColor(COL_MUTED);
        modelCard.addView(statusText);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setProgressTintList(ColorStateList.valueOf(COL_CYAN));
        progressBar.setProgressBackgroundTintList(
                ColorStateList.valueOf(Color.rgb(18, 32, 52)));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = dp(8);
        modelCard.addView(progressBar, pbLp);

        downloadBtn = new Button(this);
        downloadBtn.setText("Download model (1.1 GB)");
        styleQuiet(downloadBtn);
        downloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDownload(); }
        });
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlLp.topMargin = dp(8);
        modelCard.addView(downloadBtn, dlLp);

        // ---------------- v3.4: SETTINGS, in an accordion ----
        // Every setting the app has lives in this one card, in
        // collapsible sections, exactly once (the one-control
        // rule — a duplicated setting in this app's family once
        // silently erased itself). The BRAIN chips and the
        // grammar switch MOVED here from the model card above;
        // they are the same views, the same handlers, and the
        // same preference keys (Settings), so nothing about how
        // they behave — or what an upgrade's stored values mean —
        // changed in the move. Changes persist as they are made
        // (persistSettings); there is no Apply button.
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setBackground(cardBg());
        settingsCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        setLp.topMargin = dp(12);
        root.addView(settingsCard, setLp);
        settingsCard.addView(cardLabel("SETTINGS"));
        TextView setNote = new TextView(this);
        setNote.setText("Every setting lives here, once. Changes "
                + "save as you tap.");
        setNote.setTextSize(11);
        setNote.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams setNoteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        setNoteLp.topMargin = dp(4);
        settingsCard.addView(setNote, setNoteLp);

        // BRAIN: the v2.6 model picker and the v2.6.1 grammar
        // kill-switch, relocated — same views, handlers and
        // refresh methods (refreshBrainChips / refreshGrammarUi)
        // as they had in the model card.
        LinearLayout brainBody = accordionSection(settingsCard, "BRAIN");
        smallLabel(brainBody, "Model — both downloads are kept; "
                + "switching never deletes a file");
        LinearLayout brainRow = new LinearLayout(this);
        brainRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(6);
        brainBody.addView(brainRow, brLp);
        modelQ4Btn = chipButton("Q4_K_M");
        modelQ4Btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onModelPicked(ModelStore.Q4_ID); }
        });
        brainRow.addView(modelQ4Btn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        modelQ5Btn = chipButton("Q5_K_M");
        modelQ5Btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onModelPicked(ModelStore.Q5_ID); }
        });
        LinearLayout.LayoutParams q5Lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        q5Lp.leftMargin = dp(8);
        brainRow.addView(modelQ5Btn, q5Lp);
        // The grammar label is ModelStore.GRAMMAR_LABEL, verbatim.
        TextView grammarLabel = new TextView(this);
        grammarLabel.setText(ModelStore.GRAMMAR_LABEL);
        grammarLabel.setTextSize(11);
        grammarLabel.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams glLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        glLp.topMargin = dp(10);
        brainBody.addView(grammarLabel, glLp);
        grammarBtn = chipButton(ModelStore.grammarToggleLabel(grammarOn));
        grammarBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onGrammarToggled(); }
        });
        LinearLayout.LayoutParams gbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gbLp.topMargin = dp(6);
        brainBody.addView(grammarBtn, gbLp);
        sectionNote(brainBody, "Brain and grammar changes are "
                + "idle-only: both lock while a run or a download "
                + "is in flight.");

        // AGENT: the defaults a new run starts from.
        LinearLayout agentBody = accordionSection(settingsCard, "AGENT");
        smallLabel(agentBody, "Default policy for new runs");
        LinearLayout packRow = sectionRow(agentBody);
        packChips = new Button[PolicyPacks.NAMES.length];
        for (int i = 0; i < PolicyPacks.NAMES.length; i++) {
            final String pid = PolicyPacks.NAMES[i];
            packChips[i] = rowChip(packRow, packChipLabel(pid), i == 0);
            packChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onDefaultPackPicked(pid);
                }
            });
        }
        smallLabel(agentBody, "Max steps per run");
        LinearLayout stepRow = sectionRow(agentBody);
        stepChips = new Button[Settings.MAX_STEPS_CHOICES.length];
        for (int i = 0; i < Settings.MAX_STEPS_CHOICES.length; i++) {
            final int steps = Settings.MAX_STEPS_CHOICES[i];
            stepChips[i] = rowChip(stepRow, String.valueOf(steps), i == 0);
            stepChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onMaxStepsPicked(steps);
                }
            });
        }
        copilotBtn = toggleButton(agentBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onCopilotToggled(); }
        });
        autoReadBtn = toggleButton(agentBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onAutoReadToggled(); }
        });
        sectionNote(agentBody, "Applies from the next run. A "
                + "saved goal you load still brings its own "
                + "policy; a scheduled check still runs readonly.");

        // READING: how page text is served to the planner.
        LinearLayout readBody = accordionSection(settingsCard, "READING");
        smallLabel(readBody, "Read chunk size");
        LinearLayout chunkRow = sectionRow(readBody);
        final String[] chunkLabels = {"Auto", "Large", "Small"};
        chunkChips = new Button[chunkLabels.length];
        for (int i = 0; i < chunkLabels.length; i++) {
            final int pref = i;
            chunkChips[i] = rowChip(chunkRow, chunkLabels[i], i == 0);
            chunkChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onChunkPrefPicked(pref);
                }
            });
        }
        settleBtn = toggleButton(readBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onSettleToggled(); }
        });
        sectionNote(readBody, "Auto serves smaller chunks on "
                + "dense pages (the behaviour since v2.9). The "
                + "density settle waits for a page's text to stop "
                + "growing before a read; off reads sooner, "
                + "sometimes thinner.");

        // VOICE & SPEECH.
        LinearLayout voiceBody = accordionSection(settingsCard,
                "VOICE & SPEECH");
        voiceBtn = toggleButton(voiceBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onVoiceToggled(); }
        });
        speakFinishBtn = toggleButton(voiceBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onSpeakFinishToggled();
                    }
                });
        sectionNote(voiceBody, "Speak on finish reads a "
                + "finished run's answer card aloud — only when "
                + "the app is in front and the run actually "
                + "answered; errors and stops are never shouted.");

        // ALERTS.
        LinearLayout alertBody = accordionSection(settingsCard, "ALERTS");
        buzzBtn = toggleButton(alertBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onBuzzToggled(); }
        });
        sectionNote(alertBody, "The buzz when a run ends "
                + "off-screen. Watched-goal change alerts are "
                + "not gated by this — a change is the watch's "
                + "whole signal, and it always buzzes.");

        // DATA.
        LinearLayout dataBody = accordionSection(settingsCard, "DATA");
        smallLabel(dataBody, "Run screenshots kept");
        LinearLayout shotRow = sectionRow(dataBody);
        shotChips = new Button[Settings.SHOTS_CHOICES.length];
        for (int i = 0; i < Settings.SHOTS_CHOICES.length; i++) {
            final int kept = Settings.SHOTS_CHOICES[i];
            shotChips[i] = rowChip(shotRow, String.valueOf(kept), i == 0);
            shotChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onShotsKeptPicked(kept);
                }
            });
        }
        smallLabel(dataBody, "Home page — loaded when the app "
                + "starts; blank loads nothing");
        homeEdit = new EditText(this);
        homeEdit.setSingleLine(true);
        homeEdit.setText(settings.homePage);
        homeEdit.setHint("https://example.com");
        homeEdit.setBackground(null);
        homeEdit.setTextColor(COL_TEXT);
        homeEdit.setHintTextColor(COL_MUTED);
        homeEdit.setTextSize(13);
        homeEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        homeEdit.setOnEditorActionListener(
                new TextView.OnEditorActionListener() {
                    @Override public boolean onEditorAction(TextView v,
                            int actionId, KeyEvent event) {
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            commitHomePage();
                            return true;
                        }
                        return false;
                    }
                });
        homeEdit.setOnFocusChangeListener(
                new View.OnFocusChangeListener() {
                    @Override public void onFocusChange(View v,
                            boolean hasFocus) {
                        if (!hasFocus) commitHomePage();
                    }
                });
        LinearLayout.LayoutParams homeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        homeLp.topMargin = dp(6);
        dataBody.addView(homeEdit, homeLp);

        // The About block: the accordion's foot, not a setting.
        aboutText = new TextView(this);
        aboutText.setTextSize(11);
        aboutText.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aboutLp.topMargin = dp(14);
        settingsCard.addView(aboutText, aboutLp);
        refreshSettingsUi();
        refreshAbout();

        // address bar: full-width pill, glyph left, GO segment right
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setBackground(roundedBg(Color.rgb(7, 13, 26), COL_CARD_EDGE, 24));
        urlRow.setPadding(dp(12), dp(3), dp(3), dp(3));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        urlLp.topMargin = dp(12);
        root.addView(urlRow, urlLp);
        TextView linkGlyph = new TextView(this);
        linkGlyph.setText("⌖");
        linkGlyph.setTextSize(15);
        linkGlyph.setTextColor(COL_CYAN);
        urlRow.addView(linkGlyph);
        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setHint("https://example.com");
        urlEdit.setText("https://example.com");
        urlEdit.setBackground(null);
        urlEdit.setTextColor(COL_TEXT);
        urlEdit.setHintTextColor(COL_MUTED);
        urlEdit.setTextSize(13);
        urlEdit.setPadding(dp(8), 0, dp(4), 0);
        urlRow.addView(urlEdit, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button goBtn = new Button(this);
        goBtn.setText("GO");
        GradientDrawable goBg = new GradientDrawable();
        goBg.setShape(GradientDrawable.RECTANGLE);
        goBg.setCornerRadius(dp(20));
        goBg.setColor(COL_CYAN);
        goBtn.setBackground(goBg);
        goBtn.setTextColor(Color.rgb(3, 30, 43));
        goBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        goBtn.setAllCaps(false);
        goBtn.setMinWidth(0);
        goBtn.setMinHeight(0);
        goBtn.setMinimumWidth(0);
        goBtn.setMinimumHeight(0);
        goBtn.setPadding(dp(18), dp(7), dp(18), dp(7));
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goToUrlBar(); }
        });
        urlRow.addView(goBtn);

        // the stage: the agent's WebView inside a rounded card. The
        // card's padding is the bezel: the WebView's square corners
        // stay inside the card's rounded outline on framework widgets.
        LinearLayout stageCard = new LinearLayout(this);
        stageCard.setOrientation(LinearLayout.VERTICAL);
        stageCard.setBackground(cardBg());
        stageCard.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 3);
        stageLp.topMargin = dp(12);
        root.addView(stageCard, stageLp);
        webView = new WebView(this);
        webView.setBackground(roundedBg(Color.rgb(6, 10, 20), 0, 10));
        webView.setClipToOutline(true);
        stageCard.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        // goal card: PROMPT label, borderless input, sparkle at right
        LinearLayout goalCard = new LinearLayout(this);
        goalCard.setOrientation(LinearLayout.VERTICAL);
        goalCard.setBackground(cardBg());
        goalCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams goalLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        goalLp.topMargin = dp(12);
        root.addView(goalCard, goalLp);
        LinearLayout goalLabelRow = new LinearLayout(this);
        goalLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        goalLabelRow.setGravity(Gravity.CENTER_VERTICAL);
        goalLabelRow.addView(cardLabel("PROMPT"), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // v3.0 (item 3): the mic, beside the goal field — voice
        // fills the field for review, it never starts a run. Its
        // label/enabled state is VoiceGoal's, via refreshMicUi.
        micBtn = chipButton(VoiceGoal.buttonLabel(true, false));
        micBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMicTapped(); }
        });
        goalLabelRow.addView(micBtn);
        TextView sparkle = new TextView(this);
        sparkle.setText("✦");
        sparkle.setTextSize(16);
        sparkle.setTextColor(COL_CYAN);
        LinearLayout.LayoutParams sparkLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sparkLp.leftMargin = dp(8);
        goalLabelRow.addView(sparkle, sparkLp);
        goalCard.addView(goalLabelRow);
        goalEdit = new EditText(this);
        goalEdit.setSingleLine(false);
        goalEdit.setHint("Goal in plain words, e.g. find the store's opening hours");
        goalEdit.setBackground(null);
        goalEdit.setTextColor(COL_TEXT);
        goalEdit.setHintTextColor(COL_MUTED);
        goalEdit.setTextSize(14);
        LinearLayout.LayoutParams geLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        geLp.topMargin = dp(4);
        goalCard.addView(goalEdit, geLp);

        // v3.0 (item 2): where the goal came from, when it came
        // from somewhere — a saved goal names its pack; a check
        // load states the readonly rule before Start is pressed.
        goalNoteText = new TextView(this);
        goalNoteText.setTextSize(11);
        goalNoteText.setTextColor(COL_AMBER);
        goalNoteText.setVisibility(View.GONE);
        LinearLayout.LayoutParams gnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gnLp.topMargin = dp(4);
        goalCard.addView(goalNoteText, gnLp);

        // v3.0 (item 2): save the current goal / open the saved
        // list, as quiet chips under the field.
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams saveRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        saveRowLp.topMargin = dp(8);
        goalCard.addView(saveRow, saveRowLp);
        Button saveGoalBtn = chipButton("Save goal");
        saveGoalBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSaveGoalDialog(); }
        });
        saveRow.addView(saveGoalBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button savedListBtn = chipButton("Saved goals");
        savedListBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSavedGoals(); }
        });
        LinearLayout.LayoutParams slLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        slLp.leftMargin = dp(8);
        saveRow.addView(savedListBtn, slLp);

        // v3.3 (item 1): teach a route / open the routes list —
        // a second quiet chip row under the goal's.
        LinearLayout routeRow = new LinearLayout(this);
        routeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams routeRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        routeRowLp.topMargin = dp(8);
        goalCard.addView(routeRow, routeRowLp);
        teachBtn = chipButton("● Teach");
        teachBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onTeachTapped(); }
        });
        routeRow.addView(teachBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button routesBtn = chipButton("Routes");
        routesBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showRoutes(); }
        });
        LinearLayout.LayoutParams rtLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        rtLp.leftMargin = dp(8);
        routeRow.addView(routesBtn, rtLp);

        // start / stop row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(12);
        root.addView(btnRow, btnLp);
        startBtn = new Button(this);
        startBtn.setText("▶ START");
        startBtn.setTextSize(16);
        startBtn.setPadding(0, dp(12), 0, dp(12));
        stylePrimary(startBtn);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startAgent(); }
        });
        btnRow.addView(startBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stopBtn = new Button(this);
        stopBtn.setText("■ STOP");
        stopBtn.setTextSize(16);
        stopBtn.setPadding(0, dp(12), 0, dp(12));
        styleDanger(stopBtn);
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopFlag = true;
                // v2.6: the tap acknowledges NOW, on this thread —
                // pill to STOPPING (amber, steady, pulse off), a
                // feed line, a haptic tick — while the agent thread
                // halts on its own schedule (an in-flight native
                // generation may finish first). The pill settles
                // only when the run truly ends.
                if (running) {
                    stopping = true;
                    updatePill();
                    feed("— stop requested");
                    hapticTick();
                }
                releaseApproval(false);
                // v3.3: Stop also answers a co-pilot / route
                // pause — as a decline, like an approval.
                releaseAssistStop();
            }
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        stopLp.leftMargin = dp(10);
        btnRow.addView(stopBtn, stopLp);

        // approval panel (hidden until the governor asks)
        approvalPanel = new LinearLayout(this);
        approvalPanel.setOrientation(LinearLayout.VERTICAL);
        approvalPanel.setBackground(roundedBg(COL_CARD, COL_RED, 14));
        approvalPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        approvalText = new TextView(this);
        approvalText.setTextColor(COL_TEXT);
        approvalText.setTextSize(13);
        approvalPanel.addView(approvalText);
        LinearLayout apprRow = new LinearLayout(this);
        apprRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams apprLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        apprLp.topMargin = dp(8);
        approvalPanel.addView(apprRow, apprLp);
        Button approveBtn = new Button(this);
        approveBtn.setText("Approve");
        stylePrimary(approveBtn);
        approveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { releaseApproval(true); }
        });
        apprRow.addView(approveBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button denyBtn = new Button(this);
        denyBtn.setText("Deny");
        styleDanger(denyBtn);
        denyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { releaseApproval(false); }
        });
        LinearLayout.LayoutParams denyLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        denyLp.leftMargin = dp(10);
        apprRow.addView(denyBtn, denyLp);
        approvalPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = dp(12);
        root.addView(approvalPanel, apLp);

        // v3.3 (item 1): the teach bar — visible while a route is
        // being taught; it counts the recorded steps as they land
        // and carries Done (name + save) and Cancel.
        teachPanel = new LinearLayout(this);
        teachPanel.setOrientation(LinearLayout.VERTICAL);
        teachPanel.setBackground(roundedBg(COL_CARD, COL_CYAN, 14));
        teachPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        teachText = new TextView(this);
        teachText.setTextColor(COL_TEXT);
        teachText.setTextSize(13);
        teachPanel.addView(teachText);
        LinearLayout teachRow = new LinearLayout(this);
        teachRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tchLp.topMargin = dp(8);
        teachPanel.addView(teachRow, tchLp);
        Button teachDoneBtn = new Button(this);
        teachDoneBtn.setText("Done — save route");
        stylePrimary(teachDoneBtn);
        teachDoneBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishTeaching(true); }
        });
        teachRow.addView(teachDoneBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button teachCancelBtn = new Button(this);
        teachCancelBtn.setText("Cancel");
        styleDanger(teachCancelBtn);
        teachCancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finishTeaching(false); }
        });
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tcLp.leftMargin = dp(10);
        teachRow.addView(teachCancelBtn, tcLp);
        teachPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tpLp.topMargin = dp(12);
        root.addView(teachPanel, tpLp);

        // v3.3 (items 4 + 1): the pause panel — the co-pilot tap
        // and a route's password fill wait here. Resume answers
        // the pause (in co-pilot mode only once a tap was
        // identified; Copilot.Pause judges), Stop declines it.
        assistPanel = new LinearLayout(this);
        assistPanel.setOrientation(LinearLayout.VERTICAL);
        assistPanel.setBackground(roundedBg(COL_CARD, COL_AMBER, 14));
        assistPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        assistText = new TextView(this);
        assistText.setTextColor(COL_TEXT);
        assistText.setTextSize(13);
        assistPanel.addView(assistText);
        LinearLayout assistRow = new LinearLayout(this);
        assistRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        asLp.topMargin = dp(8);
        assistPanel.addView(assistRow, asLp);
        Button resumeBtn = new Button(this);
        resumeBtn.setText("Resume");
        stylePrimary(resumeBtn);
        resumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAssistResume(); }
        });
        assistRow.addView(resumeBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button assistStopBtn = new Button(this);
        assistStopBtn.setText("Stop");
        styleDanger(assistStopBtn);
        assistStopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { releaseAssistStop(); }
        });
        LinearLayout.LayoutParams asStopLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        asStopLp.leftMargin = dp(10);
        assistRow.addView(assistStopBtn, asStopLp);
        assistPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams aspLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aspLp.topMargin = dp(12);
        root.addView(assistPanel, aspLp);

        // secondary row: trail view, export, copy (quiet buttons)
        LinearLayout secRow = new LinearLayout(this);
        secRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = dp(10);
        root.addView(secRow, secLp);
        viewTrailBtn = new Button(this);
        viewTrailBtn.setText("View Trail");
        styleQuiet(viewTrailBtn);
        viewTrailBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logMode = 1; renderLog(); }
        });
        secRow.addView(viewTrailBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button exportBtn = new Button(this);
        exportBtn.setText("Export Log");
        styleQuiet(exportBtn);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportTrail(); }
        });
        LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        exLp.leftMargin = dp(8);
        secRow.addView(exportBtn, exLp);
        Button copyBtn = new Button(this);
        copyBtn.setText("Copy Log");
        styleQuiet(copyBtn);
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyLog(); }
        });
        LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        cpLp.leftMargin = dp(8);
        secRow.addView(copyBtn, cpLp);

        // log card: TRAIL LOG header with Steps/Trail chips, then the
        // scroll area (timeline rows in steps view, raw JSON in trail
        // view — same content, themed)
        LinearLayout logCard = new LinearLayout(this);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setBackground(cardBg());
        logCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2);
        logLp.topMargin = dp(12);
        root.addView(logCard, logLp);
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        logHead.addView(cardLabel("TRAIL LOG"), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        feedTabBtn = chipButton("Steps");
        feedTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logMode = 0; renderLog(); }
        });
        logHead.addView(feedTabBtn);
        trailTabBtn = chipButton("Trail");
        trailTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logMode = 1; renderLog(); }
        });
        LinearLayout.LayoutParams ttLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ttLp.leftMargin = dp(6);
        logHead.addView(trailTabBtn, ttLp);
        // v3.0 (item 4): the answer card is the log card's third
        // view; Past opens the trail-file picker whose card lands
        // in the same place.
        cardTabBtn = chipButton("Card");
        cardTabBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { logMode = 2; renderLog(); }
        });
        LinearLayout.LayoutParams ctLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ctLp.leftMargin = dp(6);
        logHead.addView(cardTabBtn, ctLp);
        Button pastBtn = chipButton("Past");
        pastBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPastRuns(); }
        });
        LinearLayout.LayoutParams pastLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pastLp.leftMargin = dp(6);
        logHead.addView(pastBtn, pastLp);
        logCard.addView(logHead);

        timelineList = new LinearLayout(this);
        timelineList.setOrientation(LinearLayout.VERTICAL);
        feedText = new TextView(this);
        feedText.setTextSize(11);
        feedText.setTypeface(Typeface.MONOSPACE);
        feedText.setTextColor(Color.rgb(159, 216, 242));
        feedText.setTextIsSelectable(true);
        // v3.0 (item 4): the answer card's box — the card text
        // (AnswerCard.cardText, pure) and its Share button.
        cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        cardBox.setVisibility(View.GONE);
        cardText = new TextView(this);
        cardText.setTextSize(13);
        cardText.setTextColor(COL_TEXT);
        cardText.setTextIsSelectable(true);
        cardText.setText("(no finished run yet — a run's answer "
                + "card appears here)");
        // v3.3 (item 2): the run's screenshot, when the trail
        // names one and the file is still in the shots store.
        shotView = new ImageView(this);
        shotView.setAdjustViewBounds(true);
        shotView.setVisibility(View.GONE);
        LinearLayout.LayoutParams shotLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        shotLp.bottomMargin = dp(8);
        cardBox.addView(shotView, shotLp);
        cardBox.addView(cardText);
        LinearLayout cardBtnRow = new LinearLayout(this);
        cardBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(8);
        cardBox.addView(cardBtnRow, cbLp);
        Button shareCardBtn = chipButton("Share card");
        shareCardBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareCard(); }
        });
        cardBtnRow.addView(shareCardBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // v3.3 (item 3): read the card aloud (framework TTS).
        listenBtn = chipButton(SpeakText.buttonLabel(true, false));
        listenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onListenTapped(); }
        });
        LinearLayout.LayoutParams liLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        liLp.leftMargin = dp(8);
        cardBtnRow.addView(listenBtn, liLp);
        LinearLayout logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.addView(timelineList);
        logContainer.addView(feedText);
        logContainer.addView(cardBox);
        feedScroll = new ScrollView(this);
        feedScroll.addView(logContainer);
        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        fsLp.topMargin = dp(6);
        logCard.addView(feedScroll, fsLp);

        setContentView(root);
        updatePill();
        renderLog();
    }

    // ---------------- WebView setup ----------------

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoading = true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoading = false;
                synchronized (loadLock) { loadLock.notifyAll(); }
                urlEdit.setText(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // stay in our WebView
            }
        });

        // Dialog bridge on day one (house lesson): without a
        // WebChromeClient, alert/confirm/prompt are silently swallowed.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message,
                                     final JsResult result) {
                recordDialog("alert", message, "shown");
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final JsResult result) {
                recordDialog("confirm", message, "shown");
                if (isFinishing()) { result.cancel(); return true; }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, final JsPromptResult result) {
                recordDialog("prompt", message, "shown");
                if (isFinishing()) { result.cancel(); return true; }
                final EditText input = new EditText(MainActivity.this);
                if (defaultValue != null) input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }
        });

        // v3.3 (items 1 + 4): the user's own taps in the page are
        // observed — never consumed (the page must receive every
        // tap exactly as if we were not here). Teach mode records
        // them as route steps; a co-pilot pause identifies them
        // against the current perception. Identification runs off
        // the UI thread (it round-trips the page's JS).
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_UP
                        && (teaching || assistWaiting)) {
                    onPageTapped(ev.getX(), ev.getY());
                }
                return false;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                if (currentTrail != null && agent != null) {
                    currentTrail.record("download", agent.currentStep, Trail.fields(
                            "url", url, "mime", mimeType,
                            "bytes", Long.valueOf(contentLength)));
                }
                feed("Download offered by page: " + url
                        + " (in-app downloads are recorded, not saved, in v1)");
                Toast.makeText(MainActivity.this, "Download: " + url,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void recordDialog(String kind, String message, String handled) {
        if (currentTrail != null) {
            int step = (agent != null) ? agent.currentStep : 0;
            currentTrail.record("dialog", step, Trail.fields(
                    "kind", kind, "message",
                    message == null ? "" : (message.length() > 200
                            ? message.substring(0, 200) : message),
                    "handled", handled));
        }
    }

    private void goToUrlBar() {
        String url = urlEdit.getText().toString().trim();
        if (url.length() == 0) return;
        if (!url.contains("://")) url = "https://" + url;
        webView.loadUrl(url);
    }

    // ---------------- model download ----------------

    private void refreshModelUi() {
        ModelStore.Spec spec = activeSpec();
        if (store.ready(spec)) {
            statusText.setText(spec.displayName + " ready (" + spec.size
                    + " bytes, sha256 verified).");
            progressBar.setProgress(1000);
            downloadBtn.setVisibility(View.GONE);
        } else {
            long have = store.partialBytes(spec);
            if (have > 0) {
                statusText.setText(spec.displayName + " incomplete: " + have
                        + " / " + spec.size + " bytes. Download resumes.");
                progressBar.setProgress((int) (1000L * have / spec.size));
            } else {
                statusText.setText(spec.displayName + " not downloaded yet ("
                        + ModelStore.gbLabel(spec) + ", one time).");
                progressBar.setProgress(0);
            }
            downloadBtn.setVisibility(View.VISIBLE);
            downloadBtn.setEnabled(!downloading);
            downloadBtn.setText("Download " + spec.shortName + " ("
                    + ModelStore.gbLabel(spec) + ")");
        }
        refreshBrainChips();
        refreshGrammarUi();
        refreshAbout();
        updatePill();
    }

    /** v2.6: the picker chips mirror the registry state — the
     *  label is ModelStore.pickerLabel, the active chip is cyan,
     *  and both chips lock while a run or a download is in flight
     *  (switching is an idle-time act). */
    private void refreshBrainChips() {
        if (modelQ4Btn == null || modelQ5Btn == null) return;
        ModelStore.Spec[] specs = { ModelStore.Q4, ModelStore.Q5 };
        Button[] chips = { modelQ4Btn, modelQ5Btn };
        for (int i = 0; i < specs.length; i++) {
            boolean active = specs[i].id.equals(activeModelId);
            boolean downloaded = store.ready(specs[i]);
            chips[i].setText(ModelStore.pickerLabel(specs[i], active, downloaded));
            chips[i].setTextColor(active ? COL_CYAN
                    : (downloaded ? COL_TEXT : COL_MUTED));
            chips[i].setEnabled(!running && !downloading);
        }
    }

    /** v2.6: a brain-picker tap. The choice persists first; then
     *  it takes effect by unloading the engine and loading the
     *  chosen file when it is on the phone — or by downloading it
     *  (verified, resumable) when it is not. Neither model's file
     *  is ever deleted. Switching mid-run is refused outright. */
    private void onModelPicked(String id) {
        if (running) {
            Toast.makeText(this, "Finish the run before switching brains",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloading) return;
        ModelStore.Spec spec = ModelStore.specFor(id);
        if (spec == null || spec.id.equals(activeModelId)) {
            refreshModelUi();
            return;
        }
        activeModelId = spec.id;
        // v3.4: the choice persists through the settings model
        // (the same "model_id" key the direct write always used —
        // Settings.toMap carries it).
        settings.modelId = spec.id;
        persistSettings();
        engine.close();
        refreshModelUi();
        feed("Brain: " + spec.displayName + " chosen.");
        if (store.ready(spec)) {
            final ModelStore.Spec chosen = spec;
            statusText.setText("Loading " + chosen.displayName + "...");
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = engine.load(
                            store.fileFor(chosen).getAbsolutePath());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusText.setText(ok
                                    ? chosen.displayName + " loaded and active."
                                    : "Could not load " + chosen.displayName
                                            + "; it will load at the next run.");
                            refreshModelUi();
                        }
                    });
                }
            });
            t.setName("model-switch-load");
            t.start();
        } else {
            startDownload();
        }
    }

    /** v2.6.1: the kill-switch chip mirrors the setting — its text
     *  is ModelStore.grammarToggleLabel, amber while the grammar is
     *  ON (the experimental state) and muted while it is OFF (the
     *  stable one), and it locks while a run or a download is in
     *  flight, exactly like the brain chips: the switch can only
     *  change while nothing is running. */
    private void refreshGrammarUi() {
        if (grammarBtn == null) return;
        grammarBtn.setText(ModelStore.grammarToggleLabel(grammarOn));
        grammarBtn.setTextColor(grammarOn ? COL_AMBER : COL_MUTED);
        grammarBtn.setEnabled(!running && !downloading);
    }

    /** v2.6.1: a kill-switch tap. The choice persists first, then
     *  takes effect in the engine at once — the native sampler
     *  chain is built per generate call, so no model reload is
     *  involved and the next run (indeed the next step) uses the
     *  new setting. Refused mid-run, inert mid-download, like a
     *  brain switch. */
    private void onGrammarToggled() {
        if (running) {
            Toast.makeText(this, "Finish the run before changing the grammar lock",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloading) return;
        grammarOn = !grammarOn;
        // v3.4: persists through the settings model (the same
        // "grammar_on" key, in ModelStore.grammarPrefValue's
        // encoding — Settings.toMap carries it).
        settings.grammarOn = grammarOn;
        persistSettings();
        engine.setGrammarOn(grammarOn);
        refreshGrammarUi();
        feed(grammarOn
                ? "Output grammar lock: ON (experimental, v2.4 behaviour)."
                : "Output grammar lock: OFF (stable, pre-v2.4 behaviour).");
    }

    // ---------------- v3.4: settings (the accordion) ------------
    //
    // The bridge: SharedPreferences <-> the Settings model. The
    // Activity reads every Settings key once, at launch
    // (loadSettings), and writes the model's whole canonical map
    // on every change (persistSettings) — one path each way, so
    // a key can never be read through one encoding and written
    // through another.

    private Settings loadSettings(SharedPreferences prefs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i < Settings.ALL_KEYS.length; i++) {
            String v = prefs.getString(Settings.ALL_KEYS[i], null);
            if (v != null) m.put(Settings.ALL_KEYS[i], v);
        }
        return Settings.fromMap(m);
    }

    private void persistSettings() {
        SharedPreferences.Editor ed =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (Map.Entry<String, String> e : settings.toMap().entrySet()) {
            ed.putString(e.getKey(), e.getValue());
        }
        ed.apply();
    }

    /** One accordion section: a tappable header row (title +
     *  chevron) over a body that starts collapsed. Tapping a
     *  header closes every section, then opens this one if it
     *  was closed — one section open at a time. The body is
     *  returned for the section's controls. Expand/collapse is
     *  an instant visibility toggle (framework-simple, nothing
     *  to desync) and, like all accordion behaviour, is
     *  device-verified UI. */
    private LinearLayout accordionSection(LinearLayout card, String title) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(9), 0, dp(9));
        header.setClickable(true);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(12);
        t.setTextColor(COL_TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setLetterSpacing(0.12f);
        header.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final TextView chev = new TextView(this);
        chev.setText("▸");
        chev.setTextSize(13);
        chev.setTextColor(COL_CYAN);
        header.addView(chev);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        final LinearLayout bodyRef = body;
        final TextView chevRef = chev;
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean opening = bodyRef.getVisibility() != View.VISIBLE;
                for (int i = 0; i < accBodies.size(); i++) {
                    accBodies.get(i).setVisibility(View.GONE);
                    accChevrons.get(i).setText("▸");
                }
                if (opening) {
                    bodyRef.setVisibility(View.VISIBLE);
                    chevRef.setText("▾");
                }
            }
        });
        card.addView(header);
        card.addView(body);
        accBodies.add(body);
        accChevrons.add(chev);
        return body;
    }

    /** A small muted label inside a section body. */
    private TextView smallLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        parent.addView(t, lp);
        return t;
    }

    /** A muted explanatory note at a section's foot — where a
     *  setting's "when does this apply" answer lives. */
    private void sectionNote(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(t, lp);
    }

    /** A horizontal chip row inside a section body. */
    private LinearLayout sectionRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(row, lp);
        return row;
    }

    /** One equal-weight chip in a section row. */
    private Button rowChip(LinearLayout row, String label, boolean first) {
        Button b = chipButton(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (!first) lp.leftMargin = dp(6);
        row.addView(b, lp);
        return b;
    }

    /** A full-width toggle chip in a section body (its text is
     *  refreshSettingsUi's business, like the grammar chip's). */
    private Button toggleButton(LinearLayout parent,
                                View.OnClickListener listener) {
        Button b = chipButton("");
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    /** A pack id's chip label ("readonly" -> "Readonly"). */
    private static String packChipLabel(String packId) {
        if (packId == null || packId.length() == 0) return "";
        return packId.substring(0, 1).toUpperCase(Locale.US)
                + packId.substring(1);
    }

    private void refreshToggle(Button b, String label, boolean on) {
        if (b == null) return;
        b.setText(label + ": " + (on ? "On" : "Off"));
        b.setTextColor(on ? COL_CYAN : COL_MUTED);
    }

    private void refreshChoices(Button[] chips, int selected) {
        if (chips == null) return;
        for (int i = 0; i < chips.length; i++) {
            chips[i].setTextColor(i == selected ? COL_CYAN : COL_TEXT);
        }
    }

    private static int indexOfInt(int[] values, int v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == v) return i;
        }
        return 0;
    }

    private static int indexOfStr(String[] values, String v) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(v)) return i;
        }
        return 0;
    }

    /** Mirror the whole model into the accordion controls. The
     *  home field is deliberately NOT refreshed here — it is a
     *  text field the user may be typing in; it is set at build
     *  and on its own commit. */
    private void refreshSettingsUi() {
        if (settings == null) return;
        refreshChoices(packChips,
                indexOfStr(PolicyPacks.NAMES, settings.defaultPackId));
        refreshChoices(stepChips, indexOfInt(
                Settings.MAX_STEPS_CHOICES, settings.maxSteps));
        refreshToggle(copilotBtn, "Co-pilot assists", settings.copilotOn);
        refreshToggle(autoReadBtn, "Auto-read on arrival",
                settings.autoReadOn);
        refreshChoices(chunkChips, settings.chunkPref);
        refreshToggle(settleBtn, "Density settle", settings.settleOn);
        refreshToggle(voiceBtn, "Voice goals", settings.voiceOn);
        refreshToggle(speakFinishBtn, "Speak the answer on finish",
                settings.speakOnFinish);
        refreshToggle(buzzBtn, "Finish buzz", settings.buzzOn);
        refreshChoices(shotChips, indexOfInt(
                Settings.SHOTS_CHOICES, settings.shotsKept));
    }

    /** The About block: version, the active model (name + file
     *  size from the registry spec), and the source repo as
     *  plain text. Refreshed with the model UI, so a brain
     *  switch rewrites the model line. */
    private void refreshAbout() {
        if (aboutText == null) return;
        String ver = "?";
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            ver = pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Exception ignored) { }
        ModelStore.Spec spec = activeSpec();
        aboutText.setText("Ghost Hands " + ver
                + "\nModel: " + spec.displayName + " · " + spec.size
                + " bytes"
                + "\nSource: github.com/littlestjames82-sys/"
                + "ghost-hands-mobile");
    }

    // ---- the handlers: mutate the model, persist, refresh ----

    private void onDefaultPackPicked(String id) {
        settings.defaultPackId = id;
        // The stored default is also the standing choice a plain
        // run starts from (a saved goal loaded afterwards still
        // overrides it for that goal, as it always has).
        currentPackId = id;
        persistSettings();
        refreshSettingsUi();
        feed("Default policy: " + id + " (applies from the next run).");
    }

    private void onMaxStepsPicked(int steps) {
        settings.maxSteps = steps;
        persistSettings();
        refreshSettingsUi();
    }

    private void onChunkPrefPicked(int pref) {
        settings.chunkPref = pref;
        persistSettings();
        refreshSettingsUi();
    }

    private void onShotsKeptPicked(int kept) {
        settings.shotsKept = kept;
        persistSettings();
        refreshSettingsUi();
    }

    private void onCopilotToggled() {
        settings.copilotOn = !settings.copilotOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onAutoReadToggled() {
        settings.autoReadOn = !settings.autoReadOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onSettleToggled() {
        settings.settleOn = !settings.settleOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onVoiceToggled() {
        settings.voiceOn = !settings.voiceOn;
        persistSettings();
        refreshSettingsUi();
        // A UI gate: the mic chip appears/disappears at once.
        refreshMicUi();
    }

    private void onSpeakFinishToggled() {
        settings.speakOnFinish = !settings.speakOnFinish;
        persistSettings();
        refreshSettingsUi();
    }

    private void onBuzzToggled() {
        settings.buzzOn = !settings.buzzOn;
        persistSettings();
        refreshSettingsUi();
    }

    /** The home page field commits on Done or on losing focus:
     *  the value is normalized through the model (Settings);
     *  an invalid value keeps the old setting and says so, a
     *  valid one persists and applies at the next launch. */
    private void commitHomePage() {
        if (homeEdit == null || settings == null) return;
        String norm = Settings.normalizeHomePage(
                homeEdit.getText().toString());
        if (norm == null) {
            Toast.makeText(this, "That doesn't look like a web "
                    + "address — home page unchanged",
                    Toast.LENGTH_SHORT).show();
            homeEdit.setText(settings.homePage);
            return;
        }
        if (!norm.equals(settings.homePage)) {
            settings.homePage = norm;
            persistSettings();
            feed(norm.length() > 0
                    ? "Home page set: " + norm
                            + " (loads at the next launch)."
                    : "Home page cleared — nothing loads at launch.");
        }
        homeEdit.setText(norm);
    }

    private void startDownload() {
        if (downloading || store.ready(activeSpec())) return;
        downloading = true;
        downloadBtn.setEnabled(false);
        refreshBrainChips();
        refreshGrammarUi();
        updatePill();
        final ModelStore.Spec spec = activeSpec();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = store.download(spec, new ModelStore.Listener() {
                    @Override public void onProgress(final long done, final long total) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                progressBar.setProgress((int) (1000L * done / total));
                                statusText.setText("Downloading " + spec.shortName
                                        + ": " + done + " / " + total);
                                updatePill();
                            }
                        });
                    }

                    @Override public void onStatus(final String s) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() { statusText.setText(s); }
                        });
                    }
                });
                downloading = false;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        refreshModelUi();
                        if (ok) {
                            feed("Model downloaded and verified. The brain is on the phone now.");
                        }
                    }
                });
            }
        });
        t.setName("model-download");
        t.start();
    }

    // ---------------- feed / trail display ----------------
    //
    // v1.4: the steps view renders the same feed lines as a timeline
    // (glyph in a ring + step text + muted sub-result). The text of
    // every event is unchanged; only its arrangement is new. Trail
    // view still shows the raw JSONL, themed. Classification is by
    // the line's own wording, the same wording the agent has always
    // produced: guard corrections get the amber flag, stuck / denied
    // / error stops get the red warning, completed steps the cyan
    // check, everything else the muted diamond.

    private final StringBuilder feedBuffer = new StringBuilder();

    private void feed(final String line) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                feedBuffer.append(line).append('\n');
                if (logMode == 0) renderLog();
            }
        });
    }

    private void renderLog() {
        if (feedTabBtn != null) {
            feedTabBtn.setTextColor(logMode == 0 ? COL_CYAN : COL_MUTED);
            trailTabBtn.setTextColor(logMode == 1 ? COL_CYAN : COL_MUTED);
            cardTabBtn.setTextColor(logMode == 2 ? COL_CYAN : COL_MUTED);
        }
        if (logMode == 1) {
            timelineList.setVisibility(View.GONE);
            cardBox.setVisibility(View.GONE);
            feedText.setVisibility(View.VISIBLE);
            feedText.setText(currentTrail == null
                    ? "(no run yet — the trail of the current run appears here)"
                    : currentTrail.toJsonLines());
        } else if (logMode == 2) {
            timelineList.setVisibility(View.GONE);
            feedText.setVisibility(View.GONE);
            cardBox.setVisibility(View.VISIBLE);
            if (lastCard != null) {
                cardText.setText(lastCard.cardText());
            }
            refreshCardMedia();
        } else {
            feedText.setVisibility(View.GONE);
            cardBox.setVisibility(View.GONE);
            timelineList.setVisibility(View.VISIBLE);
            rebuildTimeline();
        }
        feedScroll.post(new Runnable() {
            @Override public void run() {
                feedScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** One timeline row: ring glyph, main line, optional sub-lines. */
    private class TimelineRow {
        TextView subText;
        boolean runHeader;

        void addSub(String s) {
            if (subText.getVisibility() != View.VISIBLE) {
                subText.setText(s);
                subText.setVisibility(View.VISIBLE);
            } else {
                subText.setText(subText.getText() + "\n" + s);
            }
        }
    }

    private void rebuildTimeline() {
        timelineList.removeAllViews();
        TimelineRow last = null;
        String[] lines = feedBuffer.toString().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().length() == 0) continue;
            if (line.startsWith("   -> ") && last != null) {
                last.addSub(line.trim());
                continue;
            }
            if ((line.startsWith("Goal: ") || line.startsWith("Trail: "))
                    && last != null && last.runHeader) {
                last.addSub(line);
                continue;
            }
            last = addTimelineRow(line);
        }
    }

    private TimelineRow addTimelineRow(String line) {
        String glyph;
        int color;
        if (line.startsWith("---")) {
            glyph = "◆"; color = COL_CYAN;
        } else if (line.contains("guard corrected")) {
            glyph = "⚑"; color = COL_AMBER;
        } else if (line.startsWith("Stopped")) {
            if (line.startsWith("Stopped (done)")) {
                glyph = "✓"; color = COL_CYAN;
            } else {
                glyph = "⚠"; color = COL_RED;
            }
        } else if (line.startsWith("Step") && line.contains("not one JSON")) {
            glyph = "⚠"; color = COL_RED;
        } else if (line.startsWith("Step") && line.contains("-> deny")) {
            glyph = "⚠"; color = COL_RED;
        } else if (line.startsWith("Step") && line.contains("-> ask")) {
            glyph = "●"; color = COL_AMBER;
        } else if (line.startsWith("Step")) {
            glyph = "✓"; color = COL_CYAN;
        } else if (line.startsWith("Model failed")) {
            glyph = "⚠"; color = COL_RED;
        } else if (line.startsWith("Model ready")
                || line.startsWith("Model downloaded")) {
            glyph = "✓"; color = COL_CYAN;
        } else {
            glyph = "◆"; color = COL_MUTED;
        }

        TimelineRow row = new TimelineRow();
        row.runHeader = line.startsWith("---");

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(4), 0, dp(4));
        TextView badge = new TextView(this);
        badge.setText(glyph);
        badge.setTextSize(12);
        badge.setTextColor(color);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(COL_CARD);
        ring.setStroke(dp(1), color);
        badge.setBackground(ring);
        r.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        colLp.leftMargin = dp(10);
        r.addView(col, colLp);
        TextView main = new TextView(this);
        main.setText(line);
        main.setTextColor(COL_TEXT);
        main.setTextSize(12.5f);
        col.addView(main);
        row.subText = new TextView(this);
        row.subText.setTextColor(COL_MUTED);
        row.subText.setTextSize(11);
        row.subText.setVisibility(View.GONE);
        col.addView(row.subText);
        timelineList.addView(r);
        return row;
    }

    private void copyLog() {
        String text = (logMode == 1)
                ? (currentTrail == null ? "" : currentTrail.toJsonLines())
                : (logMode == 2 && lastCard != null)
                        ? lastCard.shareText(cardDateLabel())
                        : feedBuffer.toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("ghost-hands log", text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportTrail() {
        if (currentTrail == null) {
            Toast.makeText(this, "No trail yet — run the agent first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Ghost Hands trail");
        intent.putExtra(Intent.EXTRA_TEXT, currentTrail.toJsonLines());
        startActivity(Intent.createChooser(intent, "Share trail"));
    }

    // ---------------- approval handshake ----------------

    private boolean awaitApproval(final GhAction action,
                                  final Governor.Decision decision,
                                  final String describe) {
        approvalResult = false;
        approvalLatch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                approvalText.setText("Approval needed — nothing runs until you decide.\n\n"
                        + describe + "\n\n"
                        + decision.classification + ": " + decision.reason);
                approvalPanel.setVisibility(View.VISIBLE);
            }
        });
        boolean answered = false;
        try {
            long timeoutMs = (long) (pack.approvalTimeout * 1000.0);
            if (timeoutMs <= 0) timeoutMs = 600000L;
            answered = approvalLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                approvalPanel.setVisibility(View.GONE);
            }
        });
        // No answer in time (or Stop) counts as a decline: silence is
        // never consent.
        return answered && approvalResult && !stopFlag;
    }

    private void releaseApproval(boolean approved) {
        approvalResult = approved;
        CountDownLatch latch = approvalLatch;
        if (latch != null) latch.countDown();
    }

    // ---------------- v3.3: the run's screen ----------------

    /** The Ui every run kind (agent run, route replay) drives:
     *  the feed, the status pill, approvals, and the v3.3 pauses
     *  (the co-pilot tap and a route's password fill). */
    private Agent.Ui agentUi() {
        return new Agent.Ui() {
            @Override public void feed(String line) {
                MainActivity.this.feed(line);
            }

            @Override public void status(final String s) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        statusText.setText(s);
                        updatePill();
                    }
                });
            }

            @Override public boolean approve(GhAction action, Governor.Decision decision,
                                             String describe) {
                if (stopFlag) return false;
                return awaitApproval(action, decision, describe);
            }

            @Override public boolean stopRequested() {
                return stopFlag;
            }

            @Override public boolean foreground() {
                return inForeground;
            }

            @Override public ElementInfo awaitUserTap(int step,
                                                      long timeoutMs) {
                if (stopFlag) return null;
                int outcome = runPause(step, null, true, timeoutMs);
                return (outcome == PAUSE_RESUMED) ? assistTapInfo : null;
            }

            @Override public boolean awaitUserResume(int step,
                                                     String message,
                                                     long timeoutMs) {
                if (stopFlag) return false;
                return runPause(step, message, false, timeoutMs)
                        == PAUSE_RESUMED;
            }
        };
    }

    // ---------------- v3.3: the pause handshake -----------------
    // One panel, two pauses (Copilot.Pause judges the co-pilot's
    // transitions; a route fill needs only Resume). The waiting
    // budget is the timeout the loop passed — the pack's
    // approval timeout — so a pause counts against the pack
    // clock exactly like an approval wait, and its expiry
    // resolves as a decline.

    private static final int PAUSE_RESUMED = 1;
    private static final int PAUSE_DECLINED = 0;

    private int runPause(int step, final String message,
                         final boolean identifyTap, long timeoutMs) {
        assistTapInfo = null;
        assistStop = false;
        assistResumeFlag = false;
        assistIdentify = identifyTap;
        final Copilot.Pause pause = new Copilot.Pause();
        pause.offer();
        assistPause = pause;
        assistLatch = new CountDownLatch(1);
        assistWaiting = true;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                assistText.setText(message != null
                        && message.length() > 0 ? message
                        : "Stuck here. Tap the element yourself in "
                        + "the page, then press Resume — or press "
                        + "Stop.");
                assistPanel.setVisibility(View.VISIBLE);
            }
        });
        boolean answered = false;
        try {
            long t = timeoutMs;
            if (t <= 0) t = 600000L;
            answered = assistLatch.await(t, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assistWaiting = false;
        assistIdentify = false;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                assistPanel.setVisibility(View.GONE);
            }
        });
        if (!answered || assistStop || stopFlag) return PAUSE_DECLINED;
        if (identifyTap) {
            return pause.resumed() ? PAUSE_RESUMED : PAUSE_DECLINED;
        }
        return assistResumeFlag ? PAUSE_RESUMED : PAUSE_DECLINED;
    }

    /** The pause panel's Resume: in co-pilot mode it lands only
     *  once a tap was identified (Copilot.Pause refuses
     *  otherwise — the panel says so and keeps waiting); in fill
     *  mode the tap IS the answer. */
    private void onAssistResume() {
        Copilot.Pause pause = assistPause;
        if (pause == null) return;
        if (assistIdentify) {
            if (pause.resume()) {
                CountDownLatch latch = assistLatch;
                if (latch != null) latch.countDown();
            } else {
                assistText.setText("Tap an element in the page "
                        + "first — I'll name it here when I see it. "
                        + "Then press Resume.");
            }
        } else {
            assistResumeFlag = true;
            CountDownLatch latch = assistLatch;
            if (latch != null) latch.countDown();
        }
    }

    /** The pause panel's Stop (and the main Stop button, and
     *  onDestroy): the pause is declined. */
    private void releaseAssistStop() {
        assistStop = true;
        Copilot.Pause pause = assistPause;
        if (pause != null) pause.decline();
        CountDownLatch latch = assistLatch;
        if (latch != null) latch.countDown();
    }

    private void setAssistNote(final String note) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (assistWaiting) assistText.setText(note);
            }
        });
    }

    // ---------------- v3.3: the run's photograph ----------------

    private static boolean trailHasStop(Trail trail) {
        if (trail == null) return false;
        List<Map<String, Object>> events = trail.events();
        for (int i = 0; i < events.size(); i++) {
            if ("stop".equals(String.valueOf(events.get(i).get("type")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * v3.3 (item 2): photograph the WebView's visible content at
     * the end of a finished run and record the "screenshot"
     * event naming the file, so the answer card built right
     * after (and every Past rebuild) can show it. Retention is
     * Shots' policy, applied as the new photo lands. Strictly
     * best-effort: any failure — a zero-size view, a dead
     * latch, a full disk — leaves the run exactly as it was;
     * the photo never breaks the ending.
     */
    private void captureRunScreenshot() {
        try {
            if (currentTrail == null || currentTrail.getPath() == null
                    || webView == null) {
                return;
            }
            final File dir = new File(getFilesDir(), Shots.DIR_NAME);
            if (!dir.exists()) dir.mkdirs();
            final String name = Shots.fileNameForTrail(
                    currentTrail.getPath().getName());
            if (name == null) return;
            final Bitmap[] box = new Bitmap[1];
            final CountDownLatch latch = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        int w = webView.getWidth();
                        int h = webView.getHeight();
                        if (w > 0 && h > 0) {
                            Bitmap bm = Bitmap.createBitmap(w, h,
                                    Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bm);
                            webView.draw(canvas);
                            box[0] = bm;
                        }
                    } catch (Throwable ignored) { }
                    latch.countDown();
                }
            });
            if (!latch.await(5, TimeUnit.SECONDS)) return;
            Bitmap bm = box[0];
            if (bm == null) return;
            FileOutputStream fos = new FileOutputStream(
                    new File(dir, name));
            try {
                bm.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            } finally {
                fos.close();
            }
            bm.recycle();
            currentTrail.record("screenshot", 0,
                    Trail.fields("file", name));
            File[] files = dir.listFiles();
            if (files != null) {
                List<String> names = new ArrayList<String>();
                for (int i = 0; i < files.length; i++) {
                    if (files[i].isFile()) names.add(files[i].getName());
                }
                // v3.4 (setting l): the retention count is the
                // settings' number, handed to Shots' policy.
                List<String> old = Shots.excess(names,
                        settings != null ? settings.shotsKept
                                : Shots.MAX_KEPT);
                for (int i = 0; i < old.size(); i++) {
                    new File(dir, old.get(i)).delete();
                }
            }
        } catch (Throwable ignored) {
            // The photo never breaks the run.
        }
    }

    /**
     * The run-finished path, shared by agent runs and route
     * replays (through v3.2 it was startAgent's thread-finally;
     * v3.3's replay ends the same way, so both call this): the
     * screenshot first (v3.3 — the card built below reads it off
     * the trail), then the answer card, the watch verdict, the
     * notification matrix, and the UI reset.
     */
    private void finishRunUi(final File trailFile, String goal) {
        running = false;
        stopping = false;
        // v3.3 (item 2): a finished run is photographed while the
        // WebView still shows its final page.
        if (trailHasStop(currentTrail)) {
            captureRunScreenshot();
        }
        // v3.0 (items 4 + 5): a run that ended leaves its answer
        // card in the log card; one that ended off-screen also
        // posts the finish notification (its tap reopens this
        // trail's card) and buzzes once. A run that never
        // started — a model that would not load — has no stop
        // event and no card.
        AnswerCard endCard = (currentTrail == null) ? null
                : AnswerCard.fromEvents(currentTrail.events(), goal);
        // v3.2 (watch mode): a watched goal's run earns its
        // verdict before the card is shown — the verdict joins
        // the trail, a done run advances the goal's baseline,
        // and the card rebuilt below carries the watch banner.
        WatchCompare.Verdict watchVerdict = null;
        if (endCard != null && runWatched) {
            watchVerdict = processWatch(currentTrail);
            if (watchVerdict != null) {
                endCard = AnswerCard.fromEvents(
                        currentTrail.events(), goal);
            }
        }
        if (endCard != null) {
            final AnswerCard fc = endCard;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    showAnswerCard(fc);
                }
            });
            // The notification matrix (BUILD_NOTES v3.2): a
            // watched run that CHANGED posts its notification —
            // first diff line and all — foreground or not,
            // because the change is the one thing the watch
            // exists to surface; a watched run with NO change
            // posts nothing (silence is the feature); every other
            // ending keeps the v3.0 rule: notify only when the
            // run ended off-screen.
            if (watchVerdict != null && watchVerdict.changed()) {
                String first = watchVerdict.diffLines.isEmpty()
                        ? "the answer changed"
                        : watchVerdict.diffLines.get(0);
                Alerts.postWatchChanged(MainActivity.this, runSavedName,
                        first, trailFile.getAbsolutePath());
                vibrateFinish();
            } else if (watchVerdict != null
                    && WatchCompare.NO_CHANGE.equals(watchVerdict.state)) {
                // Quiet by design.
            } else if (!inForeground) {
                Alerts.postRunFinished(MainActivity.this, fc,
                        trailFile.getAbsolutePath());
                // v3.4 (setting k): the finish buzz gates THIS
                // vibrate — the ordinary run-finished buzz. The
                // watch-changed vibrate above is deliberately
                // not gated: a change is the watch's core signal.
                if (settings == null || settings.buzzOn) {
                    vibrateFinish();
                }
            }
            // v3.4 (setting j): speak the answer. The exact
            // rule: the setting is on, the app is in the
            // foreground, and the run ended DONE (its card
            // carries a real answer). Errors, stuck, stopped
            // and budget endings — and every off-screen finish —
            // are never spoken.
            if (settings != null && settings.speakOnFinish
                    && inForeground && "done".equals(fc.outcome)) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        autoSpeakCard(fc);
                    }
                });
            }
        }
        // v2.6: every path that reaches here ended normally
        // (including a failed model load), so the abrupt-end
        // marker has done its job.
        deleteRunMarker();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                refreshBrainChips();
                refreshGrammarUi();
                updatePill();
            }
        });
    }

    // ---------------- v3.3: teach a route (item 1) --------------

    /**
     * A tap in the WebView (observed, never consumed). Coordinates
     * arrive in view pixels; elementFromPoint wants CSS pixels,
     * so they are divided by the WebView's scale. Identification
     * runs on a worker under a lock — overlapping taps queue
     * rather than interleave their page reads.
     */
    private void onPageTapped(final float viewX, final float viewY) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                synchronized (pageTapLock) {
                    try {
                        float scale = webView.getScale();
                        if (scale <= 0) scale = 1;
                        ElementInfo id = Eyes.parseIdentity(callJs(
                                Eyes.pointIdentityJs(viewX / scale,
                                        viewY / scale), 8000));
                        if (teaching) {
                            handleTeachTap(id);
                        } else if (assistWaiting && assistIdentify) {
                            handleAssistTap(id);
                        }
                    } catch (Exception ignored) {
                        // An unidentifiable tap is information
                        // for the pause, silence for teach.
                        if (!teaching && assistWaiting && assistIdentify) {
                            setAssistNote("That tap could not be "
                                    + "identified — try another "
                                    + "element.");
                        }
                    }
                }
            }
        });
        t.setName("ghost-hands-tap");
        t.start();
    }

    /** Teach: fold one identified user tap into the route being
     *  recorded. A typable field becomes the pending field (its
     *  step is minted when the user moves on); anything else
     *  flushes the pending field and lands as a tap step. */
    private void handleTeachTap(ElementInfo id) {
        synchronized (teachLock) {
            if (!teaching) return;
            if (id == null) {
                setTeachText("That tap could not be identified — "
                        + "not recorded. " + teachCountLine());
                return;
            }
            if (Routes.isTypableField(id)) {
                // A re-tap of the field already pending (the
                // user repositioning the cursor mid-typing) is
                // still the same demonstration — flushing here
                // would mint a partial-value step and a second
                // one at the end, and replay would type twice.
                if (teachPendingField != null
                        && Eyes.identityMatches(teachPendingField, id)) {
                    return;
                }
                flushTeachFieldLocked();
                teachPendingField = id;
                if (Routes.isPasswordType(id.type)) {
                    setTeachText("Password field \""
                            + teachName(id) + "\" — it will be "
                            + "recorded only as a filled field, "
                            + "never its contents. Type, then tap "
                            + "elsewhere or press Done.");
                } else {
                    setTeachText("Field \"" + teachName(id)
                            + "\" — type, then tap elsewhere or "
                            + "press Done; the value is recorded "
                            + "when you move on.");
                }
                return;
            }
            flushTeachFieldLocked();
            if (Routes.identityKey(id.name, id.href, id.tag,
                    id.type) == null) {
                setTeachText("That element carries nothing to "
                        + "identify it by — not recorded. "
                        + teachCountLine());
                return;
            }
            Routes.Step step = Routes.tapStep(id);
            teachSteps.add(step);
            setTeachText("Recorded: " + step.describe() + ". "
                    + teachCountLine());
        }
    }

    /**
     * Mint the pending field's type step, if it earns one: the
     * field is re-found by identity (the heal's scan + decision)
     * and its CURRENT value read — unless it is a password field,
     * whose value is never read (Eyes.valueScanJs refuses it in
     * the page; Eyes.parseFieldValue refuses it again here): a
     * password field's step is redacted whatever was typed. A
     * non-password field left empty earns no step — a focus tap
     * is not a demonstration. Caller holds teachLock.
     */
    private void flushTeachFieldLocked() {
        ElementInfo pending = teachPendingField;
        if (pending == null) return;
        teachPendingField = null;
        try {
            List<ElementInfo> scan = Eyes.parseIdentityScan(
                    callJs(Eyes.identityScanJs(), 15000));
            Eyes.Heal heal = Eyes.decideHeal(pending, null, scan);
            if (heal.kind != Eyes.Heal.REFOUND) {
                // The field left the page (or became ambiguous)
                // before its value could be read: no step, said
                // out loud rather than silently dropped.
                setTeachText("A field you tapped left the page "
                        + "before its value could be recorded — "
                        + "its step was skipped. "
                        + teachCountLine());
                return;
            }
            Eyes.FieldValue fv = Eyes.parseFieldValue(callJs(
                    Eyes.valueScanJs(heal.scanIndex), 8000));
            if (!fv.found) return;
            if (fv.redacted) {
                teachSteps.add(Routes.typeStep(pending, "password",
                        null));
                setTeachText("Recorded: "
                        + teachSteps.get(teachSteps.size() - 1)
                                .describe()
                        + ". " + teachCountLine());
            } else if (fv.value != null && fv.value.length() > 0) {
                teachSteps.add(Routes.typeStep(pending, fv.type,
                        fv.value));
                setTeachText("Recorded: "
                        + teachSteps.get(teachSteps.size() - 1)
                                .describe()
                        + ". " + teachCountLine());
            }
        } catch (Exception e) {
            setTeachText("A field's value could not be read — its "
                    + "step was skipped. " + teachCountLine());
        }
    }

    private static String teachName(ElementInfo id) {
        Routes.Step s = Routes.tapStep(id);
        return s.displayName();
    }

    private String teachCountLine() {
        return teachSteps.size() + " step(s) recorded so far.";
    }

    private void setTeachText(final String text) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (teaching) teachText.setText(text);
            }
        });
    }

    /** The Teach chip: before recording starts, the dialog
     *  states the security line — passwords are never recorded,
     *  and replay will stop at those steps for the user's own
     *  fill. Only an explicit Start teaches. */
    private void onTeachTapped() {
        if (teaching) return;
        if (running) {
            Toast.makeText(this, "A run is going — stop it before "
                    + "teaching", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Teach a route")
                .setMessage("Use the page as yourself: navigate "
                        + "with the address bar, tap and type as "
                        + "usual. Your taps and typing are recorded "
                        + "as a route, anchored to what each "
                        + "element is, so it can be replayed later "
                        + "under the policy pack you choose.\n\n"
                        + "Passwords are never recorded: a password "
                        + "field is saved only as \"a field was "
                        + "filled\", and replay stops at that step "
                        + "so you can type it yourself.")
                .setPositiveButton("Start teaching",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                startTeaching();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startTeaching() {
        synchronized (teachLock) {
            teachSteps.clear();
            teachPendingField = null;
            String url = webView.getUrl();
            teachStartUrl = (url == null) ? "" : url;
            teaching = true;
        }
        teachText.setText("Teaching — the start page is recorded. "
                + "Navigate, tap and type; every identified step "
                + "lands here. Password fields are never recorded.");
        teachPanel.setVisibility(View.VISIBLE);
        teachBtn.setEnabled(false);
        feed("— teaching a route from " + teachStartUrl);
    }

    /** Done (save) or Cancel (discard). Saving first flushes a
     *  pending field — its value read may take a moment, so the
     *  flush runs on a worker and the name dialog follows on the
     *  UI thread. */
    private void finishTeaching(final boolean save) {
        if (!teaching) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                final List<Routes.Step> steps =
                        new ArrayList<Routes.Step>();
                final String startUrl;
                synchronized (teachLock) {
                    if (save) flushTeachFieldLocked();
                    steps.addAll(teachSteps);
                    startUrl = teachStartUrl;
                    teaching = false;
                    teachPendingField = null;
                    teachSteps.clear();
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        teachPanel.setVisibility(View.GONE);
                        teachBtn.setEnabled(true);
                        if (!save) {
                            feed("— teaching cancelled; nothing "
                                    + "was saved");
                            return;
                        }
                        askRouteName(startUrl, steps);
                    }
                });
            }
        });
        t.setName("ghost-hands-teach-end");
        t.start();
    }

    private void askRouteName(final String startUrl,
                              final List<Routes.Step> steps) {
        final EditText input = new EditText(this);
        input.setHint("Route name, e.g. Library login");
        new AlertDialog.Builder(this)
                .setTitle("Save route")
                .setMessage(steps.size() + " step(s) from "
                        + startUrl)
                .setView(input)
                .setPositiveButton("Save",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                String name = input.getText()
                                        .toString().trim();
                                if (name.length() == 0) {
                                    Toast.makeText(MainActivity.this,
                                            "A route needs a name — "
                                            + "nothing saved",
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                Routes.Route r = new Routes.Route();
                                r.name = name;
                                r.startUrl = startUrl;
                                r.steps.addAll(steps);
                                routes.put(r);
                                persistRoutes();
                                feed("Route saved: \"" + name
                                        + "\" — " + steps.size()
                                        + " step(s). Replay it from "
                                        + "Routes; consequential "
                                        + "steps ask again at replay "
                                        + "time.");
                            }
                        })
                .setNegativeButton("Discard", null)
                .show();
    }

    private void persistRoutes() {
        SharedPreferences.Editor ed =
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putString(PREF_ROUTES, routes.serialize());
        ed.apply();
    }

    /** The routes list: each route with its start host and step
     *  count, a Run (which confirms the pack in force first —
     *  the replay's consequential steps ask under it) and a
     *  delete. */
    private void showRoutes() {
        if (routes == null || routes.size() == 0) {
            Toast.makeText(this, "No routes yet — tap Teach to "
                    + "record one", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Routes");
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(list);
        builder.setView(scroll);
        builder.setNegativeButton("Close", null);
        final AlertDialog dlg = builder.create();
        List<Routes.Route> all = routes.all();
        for (int i = 0; i < all.size(); i++) {
            final Routes.Route r = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameText = new TextView(this);
            nameText.setText(r.name);
            nameText.setTextSize(14);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(COL_TEXT);
            texts.addView(nameText);
            TextView meta = new TextView(this);
            String host = Routes.hostOf(r.startUrl);
            meta.setText((host.length() > 0 ? host + " · " : "")
                    + r.steps.size() + " step(s) · starts at "
                    + r.startUrl);
            meta.setTextSize(11);
            meta.setTextColor(COL_MUTED);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button runBtn = chipButton("▶ Run");
            runBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    confirmRouteReplay(r);
                }
            });
            row.addView(runBtn);
            Button delBtn = chipButton("✕");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    routes.remove(r.name);
                    persistRoutes();
                    feed("Route deleted: " + r.name);
                }
            });
            LinearLayout.LayoutParams delLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            row.addView(delBtn, delLp);
            list.addView(row);
        }
        dlg.show();
    }

    /** Replay is confirmed with the pack named: the pack in
     *  force is the app's selected one, and the confirmation
     *  says consequential steps will ask again under it. */
    private void confirmRouteReplay(final Routes.Route r) {
        new AlertDialog.Builder(this)
                .setTitle("Replay route")
                .setMessage("Replay \"" + r.name + "\" under the "
                        + currentPackId + " policy? Each step is "
                        + "governed as it runs — consequential "
                        + "steps (like form submits) ask again, and "
                        + "a password step stops for your own fill.")
                .setPositiveButton("Replay",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                startRouteReplay(r);
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** A route replay: the Agent's governed replay loop on its
     *  own thread, ending through the same finish path as an
     *  agent run (answer card, screenshot, notification). No
     *  model is needed — a replay never plans — so it runs even
     *  before the model download lands. */
    private void startRouteReplay(final Routes.Route route) {
        if (running) return;
        if (teaching) {
            Toast.makeText(this, "Teaching a route — Done or "
                    + "Cancel first", Toast.LENGTH_SHORT).show();
            return;
        }
        stopFlag = false;
        stopping = false;
        running = true;
        runSavedName = null;
        runWatched = false;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        logMode = 0;
        updatePill();
        hapticTick();

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss",
                Locale.US).format(new Date());
        final File trailFile = new File(
                new File(getFilesDir(), "trails"),
                "trail-" + stamp + ".jsonl");
        currentTrail = new Trail(trailFile);
        feed("--- Route replay ---");
        feed("Route: " + route.name);
        feed("Trail: " + trailFile.getAbsolutePath());
        writeRunMarker("route: " + route.name);

        pack = PolicyPacks.loadPack(currentPackId);
        hands = new WebHands();
        // v3.4: replays take the settings' run parameters too —
        // one knob set governs every Agent the app builds.
        agent = new Agent(hands, engine, pack, currentTrail,
                agentUi(), activeModelId, grammarOn,
                settings.maxSteps, settings.copilotOn,
                settings.autoReadOn, settings.chunkPref);
        final Agent runAgent = agent;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runAgent.runRoute(route);
                } finally {
                    finishRunUi(trailFile, "Route: " + route.name);
                }
            }
        });
        t.setName("ghost-hands-route");
        t.start();
    }

    // ---------------- v3.3: read-aloud (item 3) ----------------

    /** The card's media row state: the screenshot (shown when
     *  the card names one and the file is still in the store)
     *  and the Listen control (SpeakText's labels). Called from
     *  renderLog's card branch, so live and Past cards alike
     *  land here. */
    private void refreshCardMedia() {
        if (shotView != null) {
            Bitmap bm = null;
            if (lastCard != null
                    && lastCard.screenshotFile.length() > 0) {
                File f = new File(new File(getFilesDir(),
                        Shots.DIR_NAME), lastCard.screenshotFile);
                if (f.isFile()) {
                    bm = BitmapFactory.decodeFile(
                            f.getAbsolutePath());
                }
            }
            if (bm != null) {
                shotView.setImageBitmap(bm);
                shotView.setVisibility(View.VISIBLE);
            } else {
                shotView.setImageBitmap(null);
                shotView.setVisibility(View.GONE);
            }
        }
        refreshListenUi();
    }

    private void refreshListenUi() {
        if (listenBtn == null) return;
        boolean avail = !ttsFailed;
        listenBtn.setText(SpeakText.buttonLabel(avail, ttsSpeaking));
        listenBtn.setEnabled(SpeakText.buttonEnabled(avail));
        listenBtn.setTextColor(avail ? COL_TEXT : COL_MUTED);
    }

    /** Listen: speaks the current card; while speaking, the
     *  same control is the Stop. The engine is created lazily —
     *  a phone whose TTS fails to initialize gets the disabled
     *  "Listen n/a" control instead of a dead button. */
    private void onListenTapped() {
        if (lastCard == null) {
            Toast.makeText(this, "No card yet — finish a run first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttsSpeaking && tts != null) {
            tts.stop();
            ttsSpeaking = false;
            refreshListenUi();
            return;
        }
        if (ttsFailed) return;
        if (tts != null && ttsReady) {
            speakCard();
            return;
        }
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    installTtsListener();
                    refreshListenUi();
                    speakCard();
                } else {
                    ttsFailed = true;
                    ttsSpeaking = false;
                    refreshListenUi();
                    Toast.makeText(MainActivity.this,
                            "Text-to-speech isn't available on "
                            + "this phone", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** The engine's progress listener — installed once, at
     *  init, whichever path built the engine: speaking state
     *  lands in ttsSpeaking and the Listen button follows. */
    private void installTtsListener() {
        tts.setOnUtteranceProgressListener(
                new UtteranceProgressListener() {
                    @Override public void onStart(
                            String utteranceId) { }
                    @Override public void onDone(
                            String utteranceId) {
                        ttsSpeaking = false;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                refreshListenUi();
                            }
                        });
                    }
                    @Override public void onError(
                            String utteranceId) {
                        ttsSpeaking = false;
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                refreshListenUi();
                            }
                        });
                    }
                });
    }

    private void speakCard() {
        speakCard(lastCard);
    }

    /** Speak a specific card (v3.4: the auto-speak path speaks
     *  the run's own end card, which is also lastCard — but the
     *  parameter keeps the two paths honest about WHICH card). */
    private void speakCard(AnswerCard card) {
        if (tts == null || !ttsReady || card == null) return;
        String text = SpeakText.of(card);
        if (text.length() == 0) return;
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "ghosthands-card");
        if (r == TextToSpeech.SUCCESS) ttsSpeaking = true;
        refreshListenUi();
    }

    /** v3.4 (setting j): the quiet auto-speak — the same engine
     *  and formatter as Listen, with none of Listen's toasts: if
     *  speech cannot start, it simply does not happen. Called on
     *  the UI thread from finishRunUi, under the rule stated
     *  there (setting on, foreground, done answer). */
    private void autoSpeakCard(final AnswerCard card) {
        if (card == null || ttsFailed) return;
        if (tts != null && ttsReady) {
            speakCard(card);
            return;
        }
        if (tts != null) return; // still starting: stay quiet
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    installTtsListener();
                    refreshListenUi();
                    speakCard(card);
                } else {
                    ttsFailed = true;
                    ttsSpeaking = false;
                    refreshListenUi();
                }
            }
        });
    }

    // ---------------- v3.3: the co-pilot's tap ------------------

    /** A co-pilot pause is open and the user tapped the page:
     *  identify the tap against the CURRENT perception (the
     *  run's hands hold it) — a unique identity match names the
     *  element and arms Resume; anything else says so and keeps
     *  waiting, exactly as the pause promised. */
    private void handleAssistTap(ElementInfo pointIdentity) {
        Copilot.Pause pause = assistPause;
        if (pause == null || !pause.isOpen()) return;
        ElementInfo matched = (hands == null || pointIdentity == null)
                ? null : hands.matchIdentity(pointIdentity);
        if (matched == null) {
            setAssistNote("That tap could not be identified — try "
                    + "another element.");
            return;
        }
        if (pause.tapIdentified()) {
            assistTapInfo = matched;
            setAssistNote("You tapped \"" + matched.name
                    + "\" — press Resume to continue, or tap "
                    + "something else.");
        }
    }

    // ---------------- agent run ----------------

    private void startAgent() {
        if (running) return;
        // v3.3: while teaching, the page belongs to the user's
        // demonstration — a run starting mid-lesson would mix
        // two drivers on one WebView.
        if (teaching) {
            Toast.makeText(this, "Teaching a route — Done or "
                    + "Cancel first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!store.ready(activeSpec())) {
            Toast.makeText(this, "Model not downloaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        final String goal = goalEdit.getText().toString().trim();
        if (goal.length() == 0) {
            Toast.makeText(this, "Type a goal first", Toast.LENGTH_SHORT).show();
            return;
        }
        stopFlag = false;
        stopping = false;
        running = true;
        // v3.2 (watch mode): pin the run's owner. A run started
        // from a watched saved goal — loaded from the list or
        // from its check notification — earns a verdict when it
        // ends, judged against that goal's baseline (see the
        // run-finished path and processWatch).
        SavedGoals.SavedGoal loadedGoal = (loadedSavedName != null
                && savedGoals != null)
                ? savedGoals.find(loadedSavedName) : null;
        runSavedName = (loadedGoal != null) ? loadedGoal.name : null;
        runWatched = loadedGoal != null && loadedGoal.watched;
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        logMode = 0;
        // v2.6: the tap acknowledges in this same frame, before any
        // engine work exists to wait for — Start latched (above),
        // the pill flips to AGENT ACTIVE and starts pulsing, a
        // haptic tick lands. The feed line follows with the run
        // header below.
        updatePill();
        hapticTick();
        refreshBrainChips();
        refreshGrammarUi();

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File trailFile = new File(new File(getFilesDir(), "trails"), "trail-" + stamp + ".jsonl");
        currentTrail = new Trail(trailFile);
        feed("--- New run ---");
        feed("Goal: " + goal);
        feed("Trail: " + trailFile.getAbsolutePath());
        feed("— run started");
        // v2.6: from here until the run's stop, an abrupt death
        // leaves evidence behind (see RunMarker).
        writeRunMarker(goal);

        final Agent.Ui ui = agentUi();

        // v3.0 (item 2): the run's policy. A goal loaded from a
        // check notification runs readonly whatever it was saved
        // under — the note on the goal card said so before Start.
        pack = PolicyPacks.loadPack(scheduleLaunched
                ? "readonly" : currentPackId);
        hands = new WebHands();
        // v3.4: the run takes its step budget, co-pilot gate,
        // auto-read and chunk preference from the settings model
        // — the knobs captured here, at Start, for the whole run.
        agent = new Agent(hands, engine, pack, currentTrail, ui,
                activeModelId, grammarOn,
                settings.maxSteps, settings.copilotOn,
                settings.autoReadOn, settings.chunkPref);
        final Agent runAgent = agent;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // v2.6: the engine must hold the ACTIVE model's
                    // file — after a switch it may hold the other
                    // quant's, or none.
                    String activePath = store.fileFor(activeSpec())
                            .getAbsolutePath();
                    if (!engine.isLoaded()
                            || !activePath.equals(engine.loadedPath())) {
                        ui.status("Loading model...");
                        boolean ok = engine.load(activePath);
                        if (!ok) {
                            ui.feed("Model failed to load.");
                            ui.status("Model load failed");
                            return;
                        }
                        ui.status("Model loaded");
                    }
                    runAgent.run(goal);
                } finally {
                    finishRunUi(trailFile, goal);
                }
            }
        });
        t.setName("ghost-hands-agent");
        t.start();
    }

    // ---------------- v3.0: saved goals + checks (item 2) ----------------

    private void persistSavedGoals() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(Alerts.PREF_SAVED_GOALS, savedGoals.serialize())
                .apply();
    }

    // ---------------- v3.2: watch mode ----------------

    /**
     * Judge a finished watched run against its goal's baseline
     * and record the verdict in the trail (the "watch" event the
     * answer card's banner reads). Returns the verdict, or null
     * when the run turns out not to belong to a watched goal
     * after all (deleted or unwatched mid-run — the run simply
     * ends unwatched, with no event and no baseline touched).
     *
     * Only a DONE run produces a signature (WatchCompare). A
     * done run is compared against the baseline it found, the
     * verdict is recorded against THAT baseline (record-before-
     * it-moves), and only then does the baseline advance to this
     * run — so the next check diffs against this one, as the UI
     * copy says. A run that ended any other way records
     * NOT_DONE and leaves the baseline exactly where it was: a
     * failed check is not a new answer.
     */
    private WatchCompare.Verdict processWatch(Trail trail) {
        if (trail == null || runSavedName == null
                || savedGoals == null) {
            return null;
        }
        SavedGoals.SavedGoal g = savedGoals.find(runSavedName);
        if (g == null || !g.watched) return null;
        List<Map<String, Object>> events = trail.events();
        int step = lastStopStep(events);
        WatchCompare.Signature sig =
                WatchCompare.signatureFromEvents(events);
        if (sig == null) {
            trail.record("watch", step, Trail.fields(
                    "goal_name", runSavedName,
                    "verdict", WatchCompare.NOT_DONE));
            return new WatchCompare.Verdict(WatchCompare.NOT_DONE,
                    new ArrayList<String>());
        }
        String summary = WatchCompare.doneSummaryFromEvents(events);
        WatchCompare.Verdict verdict = WatchCompare.compare(
                g.baseline, g.baselineSummary, sig, summary);
        Map<String, Object> fields = Trail.fields(
                "goal_name", runSavedName,
                "verdict", verdict.state,
                "since_ms", Long.valueOf(
                        g.hasBaseline() ? g.baselineTsMs : 0));
        if (!verdict.diffLines.isEmpty()) {
            fields.put("diff",
                    new ArrayList<Object>(verdict.diffLines));
        }
        trail.record("watch", step, fields);
        g.setBaseline(sig, summary, System.currentTimeMillis());
        persistSavedGoals();
        return verdict;
    }

    /** The step number of a trail's stop event (0 when none). */
    private static int lastStopStep(List<Map<String, Object>> events) {
        int step = 0;
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> ev = events.get(i);
            if ("stop".equals(String.valueOf(ev.get("type")))) {
                Object s = ev.get("step");
                if (s instanceof Number) {
                    step = ((Number) s).intValue();
                }
            }
        }
        return step;
    }

    /** Load a saved goal into the field, ready to start. A check
     *  load (fromCheck) leaves the saved pack untouched but arms
     *  the readonly rule for the run, and the note line says so. */
    private void loadSavedGoal(SavedGoals.SavedGoal g, boolean fromCheck) {
        goalEdit.setText(g.goal);
        goalEdit.setSelection(g.goal.length());
        loadedSavedName = g.name;
        scheduleLaunched = fromCheck;
        if (!fromCheck) {
            currentPackId = g.packId;
        }
        goalNoteText.setVisibility(View.VISIBLE);
        if (fromCheck) {
            goalNoteText.setText("Scheduled check: " + g.name
                    + " — this run uses the READONLY policy, "
                    + "whatever pack it was saved under."
                    + (g.watched
                            ? " It is WATCHED: its answer is "
                            + "compared with the last check, and "
                            + "you are buzzed only if it changed."
                            : ""));
            feed("Scheduled check loaded: " + g.name
                    + " (readonly policy)");
        } else {
            goalNoteText.setText("Saved goal: " + g.name
                    + " · policy " + g.packId
                    + (g.hasSchedule()
                            ? " · " + SavedGoals.scheduleLabel(g) : "")
                    + (g.watched ? " · watching" : ""));
            feed("Saved goal loaded: " + g.name);
        }
    }

    private void showSaveGoalDialog() {
        final String goalText = goalEdit.getText().toString().trim();
        if (goalText.length() == 0) {
            Toast.makeText(this, "Type a goal first", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText nameEdit = new EditText(this);
        nameEdit.setHint("Name this goal");
        if (loadedSavedName != null) nameEdit.setText(loadedSavedName);
        new AlertDialog.Builder(this)
                .setTitle("Save goal")
                .setView(nameEdit)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String name = nameEdit.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(MainActivity.this,
                                    "Give the goal a name first",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // Re-saving a goal keeps its schedule —
                        // and (v3.2) its watch and baseline.
                        SavedGoals.SavedGoal existing = savedGoals.find(name);
                        int sched = (existing != null)
                                ? existing.schedMinutes
                                : SavedGoals.NO_SCHEDULE;
                        SavedGoals.SavedGoal fresh =
                                new SavedGoals.SavedGoal(
                                        name, goalText, currentPackId,
                                        sched);
                        fresh.copyWatchFrom(existing);
                        savedGoals.put(fresh);
                        persistSavedGoals();
                        loadedSavedName = name;
                        scheduleLaunched = false;
                        goalNoteText.setVisibility(View.VISIBLE);
                        goalNoteText.setText("Saved goal: " + name
                                + " · policy " + currentPackId
                                + (sched != SavedGoals.NO_SCHEDULE
                                        ? " · " + SavedGoals.scheduleLabel(
                                                savedGoals.find(name)) : "")
                                + (fresh.watched ? " · watching" : ""));
                        feed("Goal saved: " + name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSavedGoals() {
        if (savedGoals.size() == 0) {
            Toast.makeText(this, "No saved goals yet — write a goal "
                    + "and tap Save goal", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Saved goals")
                .setView(list)
                .setNegativeButton("Close", null);
        final AlertDialog dlg = builder.create();
        List<SavedGoals.SavedGoal> all = savedGoals.all();
        for (int i = 0; i < all.size(); i++) {
            final SavedGoals.SavedGoal g = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameText = new TextView(this);
            nameText.setText(g.name);
            nameText.setTextSize(14);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(COL_TEXT);
            texts.addView(nameText);
            TextView meta = new TextView(this);
            String snippet = g.goal.length() > 60
                    ? g.goal.substring(0, 60) + "…" : g.goal;
            // v3.2: a watched goal shows its marker and, under
            // it, the baseline line — the last check and what it
            // found (WatchCompare.entriesLine; the summary
            // itself for a numberless baseline).
            StringBuilder metaText = new StringBuilder();
            metaText.append(snippet).append(" · policy ").append(g.packId);
            if (g.hasSchedule()) {
                metaText.append(" · ")
                        .append(SavedGoals.scheduleLabel(g));
            }
            if (g.watched) {
                metaText.append("\nWatching");
                if (g.hasBaseline()) {
                    String line = WatchCompare.entriesLine(g.baseline);
                    if (line.length() == 0) {
                        line = g.baselineSummary.replaceAll(
                                "\\s+", " ").trim();
                        if (line.length() > 60) {
                            line = line.substring(0, 59) + "…";
                        }
                    }
                    metaText.append(" · last check ")
                            .append(new SimpleDateFormat(
                                    "EEE h:mm a", Locale.US).format(
                                    new Date(g.baselineTsMs)))
                            .append(": ").append(line);
                } else {
                    metaText.append(" · no check yet — the next "
                            + "finished run sets the baseline");
                }
            }
            meta.setText(metaText.toString());
            meta.setTextSize(11);
            meta.setTextColor(COL_MUTED);
            texts.addView(meta);
            texts.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    loadSavedGoal(g, false);
                    dlg.dismiss();
                }
            });
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button schedBtn = chipButton(g.hasSchedule() ? "⏰ ✓" : "⏰");
            schedBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    editSchedule(g);
                }
            });
            row.addView(schedBtn);
            // v3.2: the watch toggle. Unwatching clears the
            // baseline with the flag — a watch restarted later
            // starts fresh rather than diffing against a stale
            // answer from before the pause.
            Button watchBtn = chipButton(g.watched ? "👁 ✓" : "👁");
            watchBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    g.watched = !g.watched;
                    if (!g.watched) g.clearBaseline();
                    persistSavedGoals();
                    dlg.dismiss();
                    feed(g.watched
                            ? "Watching " + g.name + ": finished "
                            + "runs compare their numbers with "
                            + "the last check — you are buzzed "
                            + "only when they change"
                            : "Stopped watching " + g.name
                            + " (baseline cleared)");
                }
            });
            LinearLayout.LayoutParams watchLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            watchLp.leftMargin = dp(6);
            row.addView(watchBtn, watchLp);
            Button delBtn = chipButton("✕");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dlg.dismiss();
                    confirmDeleteGoal(g);
                }
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            row.addView(delBtn, delLp);
            list.addView(row);
        }
        dlg.show();
    }

    private void confirmDeleteGoal(final SavedGoals.SavedGoal g) {
        new AlertDialog.Builder(this)
                .setTitle("Delete saved goal")
                .setMessage("Delete \"" + g.name + "\""
                        + (g.hasSchedule()
                                ? " and its daily check?" : "?"))
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        savedGoals.remove(g.name);
                        Alerts.cancelCheck(MainActivity.this, g.name);
                        persistSavedGoals();
                        if (g.name.equals(loadedSavedName)) {
                            loadedSavedName = null;
                            scheduleLaunched = false;
                            goalNoteText.setVisibility(View.GONE);
                        }
                        feed("Saved goal deleted: " + g.name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editSchedule(final SavedGoals.SavedGoal g) {
        if (!g.hasSchedule()) {
            openTimePicker(g);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(g.name + " — daily check")
                .setItems(new CharSequence[]{
                        "Change time (" + SavedGoals.timeLabel(g.schedMinutes) + ")",
                        "Remove schedule"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                if (which == 0) {
                                    openTimePicker(g);
                                } else {
                                    g.schedMinutes = SavedGoals.NO_SCHEDULE;
                                    Alerts.cancelCheck(MainActivity.this, g.name);
                                    persistSavedGoals();
                                    feed("Daily check removed: " + g.name);
                                }
                            }
                        })
                .show();
    }

    private void openTimePicker(final SavedGoals.SavedGoal g) {
        int h = g.hasSchedule() ? g.schedMinutes / 60 : 7;
        int m = g.hasSchedule() ? g.schedMinutes % 60 : 0;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, int hour, int minute) {
                g.schedMinutes = SavedGoals.minutesOfDay(hour, minute);
                persistSavedGoals();
                Alerts.armCheck(MainActivity.this, g);
                feed("Daily check set: " + g.name + " at "
                        + SavedGoals.timeLabel(g.schedMinutes)
                        + " — a notification will offer the check; "
                        + "it runs when you tap it, readonly");
            }
        }, h, m, true).show();
    }

    // ---------------- v3.0: answer cards (item 4) ----------------

    private String cardDateLabel() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date());
    }

    private void showAnswerCard(AnswerCard card) {
        if (card == null) return;
        lastCard = card;
        logMode = 2;
        renderLog();
    }

    private void shareCard() {
        if (lastCard == null) {
            Toast.makeText(this, "No card yet — finish a run first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, lastCard.shareText(cardDateLabel()));
        startActivity(Intent.createChooser(send, "Share answer card"));
    }

    /** The trail picker's list: every trail file, newest first
     *  (the file names are timestamps, so name order is time
     *  order). Each opens as the answer card it rebuilds to. */
    private void showPastRuns() {
        File dir = new File(getFilesDir(), "trails");
        File[] found = dir.listFiles();
        List<File> trails = new ArrayList<File>();
        if (found != null) {
            for (int i = 0; i < found.length; i++) {
                if (found[i].isFile() && found[i].getName().endsWith(".jsonl")) {
                    trails.add(found[i]);
                }
            }
        }
        // Newest first: insertion-free sort by name, descending.
        for (int i = 0; i < trails.size(); i++) {
            for (int j = i + 1; j < trails.size(); j++) {
                if (trails.get(j).getName().compareTo(trails.get(i).getName()) > 0) {
                    File tmp = trails.get(i);
                    trails.set(i, trails.get(j));
                    trails.set(j, tmp);
                }
            }
        }
        if (trails.isEmpty()) {
            Toast.makeText(this, "No past runs yet", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<File> files = trails;
        CharSequence[] labels = new CharSequence[files.size()];
        for (int i = 0; i < files.size(); i++) {
            labels[i] = files.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("Past runs")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        showCardForTrailFile(files.get(which), null);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCardForTrailFile(File file, String fallbackGoal) {
        String text = readTextFile(file);
        AnswerCard card = (text == null) ? null
                : AnswerCard.fromTrailText(text, fallbackGoal);
        if (card == null) {
            Toast.makeText(this, "That trail has no finished run",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showAnswerCard(card);
        feed("Answer card: " + file.getName());
    }

    private static String readTextFile(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return new String(buf.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- v3.0: voice goals (item 3) ----------------

    private void refreshMicUi() {
        if (micBtn == null) return;
        // v3.4 (setting i): voice goals off hides the chip
        // entirely — the goal field is keyboard-only, and the
        // recognizer is never touched.
        if (settings != null && !settings.voiceOn) {
            micBtn.setVisibility(View.GONE);
            return;
        }
        micBtn.setVisibility(View.VISIBLE);
        boolean avail = SpeechRecognizer.isRecognitionAvailable(this);
        micBtn.setText(VoiceGoal.buttonLabel(avail, speechListening));
        micBtn.setEnabled(VoiceGoal.buttonEnabled(avail, speechListening));
        micBtn.setTextColor(avail ? COL_TEXT : COL_MUTED);
    }

    private void onMicTapped() {
        if (speechListening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition isn't available "
                    + "on this phone", Toast.LENGTH_SHORT).show();
            refreshMicUi();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_AUDIO) return;
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            Toast.makeText(this, "Microphone permission denied — "
                    + "voice goals are off", Toast.LENGTH_SHORT).show();
            feed("Voice goals: microphone permission denied");
        }
    }

    /** One listening session: a fresh recognizer, results into
     *  the goal field for review — never auto-started (the user
     *  reads, edits, and taps Start themselves). */
    private void startListening() {
        if (speechRec != null) {
            speechRec.destroy();
            speechRec = null;
        }
        speechRec = SpeechRecognizer.createSpeechRecognizer(this);
        speechRec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle partial) { }
            @Override public void onEvent(int eventType, Bundle params) { }
            @Override public void onError(int error) {
                speechListening = false;
                feed("Voice input stopped (recognizer error " + error + ")");
                refreshMicUi();
            }
            @Override public void onResults(Bundle results) {
                speechListening = false;
                ArrayList<String> heard = (results == null) ? null
                        : results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);
                String text = VoiceGoal.pickHeard(heard);
                if (text != null) {
                    goalEdit.setText(text);
                    goalEdit.setSelection(text.length());
                    feed("Heard goal: " + text);
                } else {
                    Toast.makeText(MainActivity.this,
                            "Didn't catch that — try again",
                            Toast.LENGTH_SHORT).show();
                }
                refreshMicUi();
            }
        });
        Intent ri = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ri.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        ri.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Integer.valueOf(3));
        ri.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the goal");
        speechListening = true;
        refreshMicUi();
        try {
            speechRec.startListening(ri);
        } catch (Exception e) {
            speechListening = false;
            feed("Voice input could not start");
            refreshMicUi();
        }
    }

    /** v3.0 (item 5): the finish buzz — one short pulse, guarded
     *  exactly like the v2.6 haptic tick. */
    private void vibrateFinish() {
        try {
            Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib == null || !vib.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(
                        200, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vib.vibrate(200);
            }
        } catch (Exception ignored) { }
    }

    // ---------------- run marker (v2.6) ----------------
    //
    // Written at run start, deleted by the run thread's finally on
    // every normal ending. A marker still on disk at launch is the
    // only witness an abrupt death leaves; checkRunMarker surfaces
    // it once and clears it. The marker's content is RunMarker's
    // (pure, host-tested); these helpers are just its file.

    private File runMarkerFile() {
        return new File(getFilesDir(), "run-in-progress.json");
    }

    private void writeRunMarker(String goal) {
        try {
            RunMarker m = new RunMarker(goal,
                    System.currentTimeMillis(), activeModelId);
            FileOutputStream out = new FileOutputStream(runMarkerFile());
            out.write(m.serialize().getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
            out.close();
        } catch (Exception ignored) {
            // The marker is evidence, not machinery: a failed write
            // must never block a run.
        }
    }

    private void deleteRunMarker() {
        try {
            File f = runMarkerFile();
            if (f.exists()) f.delete();
        } catch (Exception ignored) { }
    }

    private void checkRunMarker() {
        File f = runMarkerFile();
        if (!f.exists()) return;
        String text = null;
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            text = new String(bos.toByteArray(), "UTF-8");
        } catch (Exception ignored) { }
        RunMarker m = RunMarker.parse(text);
        feed(m != null ? RunMarker.abruptLine(m)
                : "Last run ended abruptly (app or phone died).");
        deleteRunMarker();
    }

    // ---------------- the hands (WebView body) ----------------

    /** Runs JS on the UI thread and waits for its string result. */
    private String callJs(final String js, long timeoutMs) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    webView.evaluateJavascript(js, new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            out[0] = value;
                            latch.countDown();
                        }
                    });
                } catch (Exception e) {
                    out[0] = null;
                    latch.countDown();
                }
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new Exception("page did not answer in time");
        }
        return decodeJs(out[0]);
    }

    private static String decodeJs(String raw) {
        if (raw == null) return "";
        try {
            Object o = Json.parse(raw);
            if (o instanceof String) return (String) o;
        } catch (Exception ignored) { }
        return raw;
    }

    /** Give a navigation a moment to start, then wait for it to finish
     *  (bounded), then a short settle for late DOM changes. */
    private void waitForPageSettled() {
        try {
            Thread.sleep(350);
            synchronized (loadLock) {
                long deadline = System.currentTimeMillis() + 9000;
                while (pageLoading) {
                    long remain = deadline - System.currentTimeMillis();
                    if (remain <= 0) break;
                    loadLock.wait(remain);
                }
            }
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class WebHands implements Agent.Hands {

        /** v1.2: per-page read position for the read action. */
        private final Eyes.ReadCursor readCursor = new Eyes.ReadCursor();

        /** v2.0: the map this body most recently perceived — the
         *  identity source for the self-healing tap/type below.
         *  The agent always perceives through this method in the
         *  same step it acts, so the cache is the proposal's own
         *  perception. */
        private java.util.List<ElementInfo> lastPerceivedMap;

        @Override public Eyes.Perception perceive() throws Exception {
            String raw = callJs(Eyes.MAP_JS, 20000);
            // MAP_JS returns a JSON string; evaluateJavascript wraps it
            // once more, so decode the outer layer then hand Eyes the
            // re-quoted inner string it expects.
            Eyes.Perception p = Eyes.parseMap(Json.quote(raw));
            // v1.2: MAP_JS stashed the raw candidates on
            // window.__gh.all; Java shaped the numbered map, so now
            // stash exactly the shaped nodes as window.__gh.els —
            // hands actions address elements by the map's numbers.
            callJs(Eyes.stashJs(p.elements), 8000);
            // v2.8: the preview prefers content. MAP_JS also
            // measured the page's main-content candidates; when
            // one clears the bar (the read path's own pick), the
            // preview leads with that candidate's opening text
            // instead of the page's first screenful — which on
            // real sites is the navigation wall. Only the preview
            // changes: title, URL and the element map (everything
            // the recognition rules read) are untouched.
            int win = Eyes.pickMainContent(p.readCandidates,
                    p.legacyCandidateIndex);
            if (win >= 0) {
                String winnerPreview = Eyes.parsePreviewText(
                        callJs(Eyes.previewAtJs(win), 8000));
                p.textPreview = Eyes.preferredPreview(p.textPreview,
                        winnerPreview);
            }
            lastPerceivedMap = p.elements;
            return p;
        }

        private ElementInfo perceivedElement(int el) {
            if (lastPerceivedMap == null) return null;
            for (ElementInfo e : lastPerceivedMap) {
                if (e.number == el) return e;
            }
            return null;
        }

        /** v3.3 (item 4): the perceived element a live identity
         *  belongs to — the co-pilot's tap identification. The
         *  unique member of the last perceived map the heal's
         *  rules match to the tapped point's identity, or null
         *  when there is none, or several (a guessed tap is
         *  worse than an honest "couldn't identify it"). */
        ElementInfo matchIdentity(ElementInfo identity) {
            if (identity == null || lastPerceivedMap == null) {
                return null;
            }
            ElementInfo found = null;
            for (ElementInfo e : lastPerceivedMap) {
                if (Eyes.identityMatches(identity, e)) {
                    if (found != null) return null;
                    found = e;
                }
            }
            return found;
        }

        /**
         * v2.0 self-healing verdict for acting on element el: ask
         * the page what NOW sits at the stash slot; if it is no
         * longer the perceived element, scan the live DOM for the
         * perceived identity. The decision itself is Eyes.decideHeal
         * (pure, host-tested); this method only gathers the live
         * readings. Returns null when there is no perceived element
         * to heal against (no perception yet) — the caller then
         * acts exactly as before.
         */
        private Eyes.Heal healDecision(int el) throws Exception {
            ElementInfo perceived = perceivedElement(el);
            if (perceived == null) return null;
            ElementInfo atStash = Eyes.parseIdentity(
                    callJs(Eyes.identityJs(el), 8000));
            java.util.List<ElementInfo> scan = null;
            if (!Eyes.identityMatches(perceived, atStash)) {
                scan = Eyes.parseIdentityScan(
                        callJs(Eyes.identityScanJs(), 15000));
            }
            return Eyes.decideHeal(perceived, atStash, scan);
        }

        /** v2.9 (item 3): the chunk size the loop last named for
         *  a read — the standing Eyes.READ_CHUNK_CHARS, or the
         *  dense page's smaller chunk. Set at the top of read();
         *  every extraction of that read (first, thin-retry,
         *  wrap) serves the same size. */
        private int activeChunkChars = Eyes.READ_CHUNK_CHARS;

        /** One extraction of the page text at a cursor offset. */
        private Eyes.ReadChunk extractReadChunk(int offset) throws Exception {
            return Eyes.parseRead(callJs(Eyes.readTextJs(offset,
                    activeChunkChars), 15000));
        }

        /** v2.8: a URL's pinned read session — the extraction
         *  root chosen for it (candIndex >= 0: the main-content
         *  tier's candidate; -1: the full-page tier) and the tier
         *  that choice implies. Sessions live as long as the
         *  hands (one run), so one URL is always read through
         *  one root per run: the chunk cursor, the end-of-text
         *  state and the agent's URL-keyed read bookkeeping all
         *  describe a single extraction of the page. */
        private class ReadSession {
            int candIndex = -1;
            boolean mainTier = false;
        }

        private final java.util.Map<String, ReadSession> readSessions =
                new java.util.HashMap<String, ReadSession>();

        /** The tier the most recent extractChunk actually
         *  served (a legacy-fallback chunk labels itself full
         *  page even in a main-content session). */
        private boolean lastReadMainTier = false;

        /** v2.8: poll the page's text length until it flattens
         *  or the probe budget expires (Eyes.settleDecision —
         *  the pure decision, host-tested; this is only the
         *  polling). Runs once per read session, before its
         *  first extraction. Best-effort: a failed probe
         *  proceeds unsettled — settling is polish on the read,
         *  never a gate on it. */
        private void settlePageText() throws Exception {
            int[] probes = new int[Eyes.SETTLE_MAX_PROBES];
            int n = 0;
            // v3.4 (setting h): the loop's gate is the pure
            // Eyes.settleMayProbe — the budget AND the density-
            // settle setting together. Off, zero probes are
            // taken and the read proceeds unsettled; on, the
            // loop below is the v2.8 loop, probe for probe.
            boolean settleOn = settings == null || settings.settleOn;
            while (Eyes.settleMayProbe(settleOn, n)) {
                int len = Eyes.parseTextLength(
                        callJs(Eyes.pageTextLengthJs(), 8000));
                if (len < 0) return;
                probes[n++] = len;
                int[] soFar = new int[n];
                System.arraycopy(probes, 0, soFar, 0, n);
                if (Eyes.settleDecision(soFar) != Eyes.SETTLE_WAIT) {
                    return;
                }
                if (n < Eyes.SETTLE_MAX_PROBES) {
                    sleepQuietly(Eyes.SETTLE_INTERVAL_MS);
                }
            }
        }

        /** v2.8: (re)collect the read candidates for the
         *  cursor's URL and pin the session's root by the pure
         *  pick. When a live session's pick MOVES — the page
         *  changed under it (hydration finished, a re-render
         *  swapped the content root) — the read cursor restarts
         *  at the top: its offsets belonged to a text that no
         *  longer exists. previousCand is the pinned index, or
         *  Integer.MIN_VALUE for a brand-new session. */
        private ReadSession collectReadSession(int previousCand)
                throws Exception {
            Eyes.ReadCandidates rc = Eyes.parseReadCandidates(
                    callJs(Eyes.readCandidatesJs(), 15000));
            int pick = Eyes.pickMainContent(rc.list, rc.legacyIndex);
            ReadSession s = new ReadSession();
            s.candIndex = pick;
            s.mainTier = pick >= 0;
            if (previousCand != Integer.MIN_VALUE
                    && pick != previousCand) {
                readCursor.offset = 0;
            }
            readSessions.put(readCursor.url, s);
            return s;
        }

        /** v2.8: one session-aware extraction at the cursor's
         *  offset, through the session's pinned root. The
         *  candidate stash dies with every MAP_JS (each step's
         *  perception replaces window.__gh), so every extraction
         *  first re-collects — on a stable page the pick is the
         *  same and only the stash is refreshed. A pick that
         *  still races a re-render (the script reports total -1)
         *  serves this one chunk from the legacy extractor,
         *  exactly as pre-v2.8, and labels it full page. */
        private Eyes.ReadChunk extractChunk() throws Exception {
            ReadSession s = readSessions.get(readCursor.url);
            if (s == null) {
                settlePageText();
                s = collectReadSession(Integer.MIN_VALUE);
            } else {
                s = collectReadSession(s.candIndex);
            }
            Eyes.ReadChunk c = Eyes.parseRead(callJs(
                    Eyes.readTextAtJs(s.candIndex, readCursor.offset,
                            activeChunkChars), 15000));
            if (c.total < 0) {
                lastReadMainTier = false;
                return extractReadChunk(readCursor.offset);
            }
            lastReadMainTier = s.mainTier;
            return c;
        }

        private void sleepQuietly(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** v2.3: one gentle scroll pass — down in viewport steps
         *  with short pauses so lazy rendering trips, then back to
         *  the top so the page is left as the agent found it. */
        private void scrollPass() throws Exception {
            for (int i = 0; i < 4; i++) {
                callJs(Eyes.scrollJs("down"), 8000);
                sleepQuietly(500);
            }
            callJs("(function(){window.scrollTo(0,0);"
                    + "return 'ok: back to top';})()", 8000);
            sleepQuietly(500);
        }

        /** v1.2 read action: extract the page's readable text, one
         *  chunk per call. The cursor walks the text across calls and
         *  restarts when the URL changes or the end is reached (a read
         *  past the end wraps to the top, saying so in the result).
         *  v2.3: a THIN first extraction (total below
         *  Eyes.THIN_READ_THRESHOLD — weather.com's 10-day page gave
         *  back 2,390 chars of navigation skeleton in the field)
         *  spends this URL's one retry for the run: wait ~2.5 s for
         *  hydration and re-extract; if still thin, one scroll pass
         *  to trip lazy rendering and a final extraction. The best
         *  (largest) extraction is used, and the result string
         *  appends the retry's honest account (Eyes.thinRetryNote).
         *  Planner reads and the loop's auto-read share this body —
         *  and this retry — with no separate path. The decisions are
         *  Eyes' pure helpers; only the waiting/scrolling/extraction
         *  lives here, and none of it is host-executable.
         *  v2.8: extraction goes through the read session
         *  (extractChunk): a density settle precedes the session's
         *  first extraction, the session's pinned root serves every
         *  chunk (the main-content subtree when one cleared the
         *  bar, else the chrome-suppressed full-page walk), the
         *  result names the tier that served it, and the chunk's
         *  own start is authoritative — a session whose pick moved
         *  mid-read restarts its cursor (collectReadSession), and
         *  chunk.start is where the served text actually began.
         *  v2.9 (item 3 — adaptive chunks): the loop names the
         *  chunk size per read — smaller on a page whose
         *  perception measures dense — and a result served at the
         *  smaller size says so (Agent.DENSE_READ_NOTE), so the
         *  trail and the planner both know the chunks shrank. */
        @Override public String read(int chunkChars) throws Exception {
            activeChunkChars = (chunkChars > 0)
                    ? chunkChars : Eyes.READ_CHUNK_CHARS;
            String current = callJs("location.href", 8000);
            readCursor.resetFor(current);
            Eyes.ReadChunk chunk = extractChunk();
            String thinNote = "";
            if (Eyes.shouldThinRetry(current, readCursor.thinRetriedUrls,
                    chunk.total)) {
                readCursor.thinRetriedUrls.add(current);
                int firstTotal = chunk.total;
                boolean firstTier = lastReadMainTier;
                sleepQuietly(2500);
                Eyes.ReadChunk retry = extractChunk();
                boolean retryTier = lastReadMainTier;
                chunk = Eyes.pickBestExtraction(chunk, retry);
                if (chunk == retry) lastReadMainTier = retryTier;
                else lastReadMainTier = firstTier;
                if (Eyes.isThinRead(chunk.total)) {
                    scrollPass();
                    boolean chunkTier = lastReadMainTier;
                    retry = extractChunk();
                    retryTier = lastReadMainTier;
                    Eyes.ReadChunk best =
                            Eyes.pickBestExtraction(chunk, retry);
                    // The label follows the chunk that won: the
                    // retry's tier when it won, else the tier the
                    // standing chunk was served under.
                    lastReadMainTier = (best == retry)
                            ? retryTier : chunkTier;
                    chunk = best;
                }
                if (chunk.total > 0) {
                    thinNote = Eyes.thinRetryNote(firstTotal, chunk.total);
                }
            }
            if (chunk.total == 0) {
                return "error: no readable text found on this page";
            }
            boolean wrapped = false;
            if (readCursor.offset >= chunk.total) {
                // Past the end (page shrank, or a repeat read after
                // the final chunk): restart from the top.
                readCursor.offset = 0;
                wrapped = true;
                chunk = extractChunk();
            }
            if (chunk.text.length() == 0) {
                return "error: no readable text found on this page";
            }
            int start = chunk.start;
            readCursor.offset = start + chunk.text.length();
            String out = Eyes.formatReadChunk(start, chunk.text, chunk.total,
                    lastReadMainTier ? Eyes.READ_TIER_MAIN
                            : Eyes.READ_TIER_FULL);
            if (wrapped) {
                out = out.replaceFirst("ok: read",
                        "ok: restarted at the top of the page; read");
            }
            if (thinNote.length() > 0) {
                out = out + "\n" + thinNote;
            }
            if (activeChunkChars < Eyes.READ_CHUNK_CHARS) {
                out = out + "\n" + Agent.DENSE_READ_NOTE;
            }
            return out;
        }

        /** v2.0: tap with self-healing re-find. Between perception
         *  and this call sits a 40-100 s model generation, and the
         *  live DOM churns in that window (the v1.9 field run lost
         *  two taps to "element 11 not on page" this way). When the
         *  stash slot still holds the perceived element, act exactly
         *  as before; when it does not but the identity survives
         *  uniquely elsewhere in the DOM, act on the re-found node
         *  and say so in the result; when it is gone or ambiguous,
         *  return the original failure string unchanged. */
        @Override public String tap(int el) throws Exception {
            Eyes.Heal heal = healDecision(el);
            if (heal == null || heal.kind == Eyes.Heal.MATCH) {
                String r = callJs(Eyes.tapJs(el), 15000);
                if (r.startsWith("ok")) waitForPageSettled();
                return r;
            }
            if (heal.kind == Eyes.Heal.REFOUND) {
                String r = callJs(Eyes.tapScanJs(heal.scanIndex, el), 15000);
                if (r.startsWith("ok")) {
                    waitForPageSettled();
                    return "ok: tapped #" + el + " (element moved; "
                            + "re-found as #" + (heal.scanIndex + 1) + ")";
                }
                return r;
            }
            return "error: element " + el + " not on page";
        }

        /** v2.0: type with the same self-healing re-find as tap.
         *  A healed success reports the composed string; a genuine
         *  action failure from the re-found node (e.g. a select
         *  without the requested option) passes through unchanged. */
        @Override public String type(int el, String text) throws Exception {
            Eyes.Heal heal = healDecision(el);
            if (heal == null || heal.kind == Eyes.Heal.MATCH) {
                return callJs(Eyes.typeJs(el, text), 15000);
            }
            if (heal.kind == Eyes.Heal.REFOUND) {
                String r = callJs(Eyes.typeScanJs(heal.scanIndex, el, text),
                        15000);
                if (r.startsWith("ok")) {
                    return "ok: typed into #" + el + " (element moved; "
                            + "re-found as #" + (heal.scanIndex + 1) + ")";
                }
                return r;
            }
            return "error: element " + el + " not on page";
        }

        @Override public String gotoUrl(final String url) throws Exception {
            final String target = (url.contains("://") ? url : "https://" + url);
            runOnUiThread(new Runnable() {
                @Override public void run() { webView.loadUrl(target); }
            });
            waitForPageSettled();
            return "ok: navigated to " + target;
        }

        @Override public String scroll(String dir) throws Exception {
            String r = callJs(Eyes.scrollJs(dir), 15000);
            try { Thread.sleep(300); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return r;
        }

        /** v1.1 dedicated search action:
         *  (a) Wikipedia hosts go straight to Special:Search on the
         *      current host;
         *  (b) otherwise, if the current map holds a likely search
         *      input, type the query there and submit its form;
         *  (c) otherwise fall back to a DuckDuckGo HTML search.
         *  Bounded: it only ever touches the current page's own
         *  search control, or navigates to a search results page.
         *  v2.1: path (b)'s target choice is Eyes.pickSearchTarget
         *  (pure, host-tested): the classic search-box rule first,
         *  then the broader lookup-box rule — a text-like input
         *  whose enclosing form's visible text marks it as a
         *  location/city/ZIP lookup (weather.gov's forecast form,
         *  missed in the first non-Wikipedia field run). Two or
         *  more broader-rule candidates = ambiguity = no guess:
         *  the chooser returns null and path (c) runs, as before.
         *  The planner's query is typed through unchanged.
         *  v2.2: between the classic rule and the ambient form-text
         *  rule sits the explicit-label tier — an input whose own
         *  associated <label> names it a lookup box (weather.gov's
         *  "inputstring", labelled "Local forecast by "City, St"
         *  or ZIP code") beats any number of ambient candidates,
         *  which is what the v2.1 retest's step 1 needed: the
         *  ambient rule had refused the page outright. */
        @Override public String search(String query) throws Exception {
            String current = callJs("location.href", 8000);
            String host = "";
            try {
                String h = new java.net.URL(current).getHost();
                if (h != null) host = h.toLowerCase();
            } catch (Exception ignored) { }

            if (host.equals("wikipedia.org") || host.endsWith(".wikipedia.org")) {
                String url = "https://" + host + "/wiki/Special:Search?search="
                        + java.net.URLEncoder.encode(query, "UTF-8") + "&fulltext=1";
                gotoUrl(url);
                return "ok: searched Wikipedia for \"" + query + "\"";
            }

            Eyes.Perception p = perceive();
            ElementInfo target = Eyes.pickSearchTarget(p.elements);
            if (target != null) {
                String typed = type(target.number, query);
                if (typed == null || !typed.startsWith("ok")) {
                    return typed == null
                            ? "error: could not type into the page search box"
                            : typed;
                }
                String sub = callJs(Eyes.submitSearchJs(target.number), 15000);
                if (sub != null && sub.startsWith("ok")) waitForPageSettled();
                // v2.3: a labelled/ambient lookup box names itself in
                // the result (Eyes.searchBoxText) so the planner
                // knows what kind of box it used when the follow-up
                // page answers like one; the classic tier's string is
                // unchanged.
                String boxText = Eyes.searchBoxText(target);
                if (boxText != null && boxText.length() > 0) {
                    return Eyes.formatSearchedViaBox(boxText, query);
                }
                return "ok: searched this site for \"" + query + "\" ("
                        + typed + "; " + sub + ")";
            }

            String url = "https://html.duckduckgo.com/html/?q="
                    + java.net.URLEncoder.encode(query, "UTF-8");
            gotoUrl(url);
            return "ok: no search box on this page; searched DuckDuckGo for \""
                    + query + "\"";
        }

        @Override public String back() throws Exception {
            final boolean[] could = new boolean[1];
            final CountDownLatch latch = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    could[0] = webView.canGoBack();
                    if (could[0]) webView.goBack();
                    latch.countDown();
                }
            });
            latch.await(10, TimeUnit.SECONDS);
            if (!could[0]) return "error: no page to go back to";
            waitForPageSettled();
            return "ok: went back";
        }

        /** v1.6: the error-page recovery reload. When the error
         *  page names the URL that failed, navigate back to it —
         *  the WebView's current URL is the chrome-error
         *  placeholder, not the failed page. Otherwise reload
         *  whatever is current. Either way the same bounded
         *  settle wait gotoUrl uses. */
        @Override public String reload(String failedUrl) throws Exception {
            if (failedUrl != null && failedUrl.length() > 0) {
                gotoUrl(failedUrl);
                return "ok: reloaded " + failedUrl;
            }
            runOnUiThread(new Runnable() {
                @Override public void run() { webView.reload(); }
            });
            waitForPageSettled();
            return "ok: reloaded current page";
        }
    }
}
