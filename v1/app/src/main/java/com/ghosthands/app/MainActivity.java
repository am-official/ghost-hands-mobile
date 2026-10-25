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
import android.content.IntentFilter;
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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.util.Calendar;
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
    // v3.15 (R7 — multi-tab): the browser's tabs. tabViews holds
    // one WebView per open tab (hard cap RelevanceGuard.TAB_CAP,
    // 3); webView ALWAYS points at the ACTIVE tab's view, so
    // every standing use of the field — the hands, teach, the
    // screenshots, the url bar — works on the active tab
    // unchanged. Inactive views are hidden and onPause'd (see
    // activateTab). Tabs live only for the app session: nothing
    // here persists, and a relaunch starts with one tab again.
    private final java.util.List<WebView> tabViews =
            new java.util.ArrayList<WebView>();
    private volatile int activeTabIdx = 0;
    private LinearLayout tabStrip;
    private android.widget.FrameLayout webHost;
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

    // v3.16 (R8): the downloads store (governed downloads +
    // picked files share it — one list in History), the
    // page-watch store, and the two run pins for a page-watch
    // check: pendingPageWatchUrl is a check loaded and waiting
    // for Start (loadSavedGoal's loadedSavedName discipline);
    // runPageWatchUrl is the watch the CURRENT run is checking,
    // captured at Start like runSavedName.
    private Downloads downloads;
    private PageWatches pageWatches;
    private String pendingPageWatchUrl = null;
    private String runPageWatchUrl = null;
    private LinearLayout downloadsListBox;
    private LinearLayout pageWatchListBox;
    // v3.16: the trail idle downloads record into (a run's
    // downloads record into the run's own trail; an idle
    // download has none, so the downloads store keeps one of
    // its own, in its own directory — never in History's list).
    private Trail idleDownloadTrail;
    // v3.16: an approval wait is in progress (the panel is
    // someone's). Download approvals queue behind it rather
    // than fighting over the one panel — see awaitApproval.
    private volatile boolean approvalBusy = false;
    // v3.16: the one armed blob download, if any (see the
    // blob bridge in the downloads section).
    private BlobJob blobJob;

    // v3.0 (item 3): voice goals — the framework recognizer,
    // created per listening session and destroyed with it.
    private static final int REQ_RECORD_AUDIO = 7301;
    // v3.16 (R8): the system file picker's request code.
    private static final int REQ_PICK_FILE = 7302;
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
    // v3.6 section controls (second settings wave).
    // BRAIN: the CPU thread chips.
    private Button[] threadChips;
    // READING: the fallback-engine chips + the desktop switch.
    private Button[] fallbackChips;
    private Button desktopBtn;
    // ALERTS: the needs-me switch.
    private Button needsMeBtn;
    // DIAGNOSTICS section controls.
    private Button liveStatsBtn;
    private Button prefixCacheBtn;
    // DATA: the trail-retention chips and the two clear-data
    // actions (buttons + their in-place result lines — actions,
    // not settings; no Settings keys exist for them).
    private Button[] trailChips;
    private Button clearTrailsBtn;
    private TextView clearTrailsResult;
    private Button clearGoalsBtn;
    private TextView clearGoalsResult;
    // v3.7 section controls (third settings wave).
    // APPEARANCE: the accent chips, the feed-text chips, the
    // reduce-motion switch.
    private Button[] accentChips;
    private Button[] feedTextChips;
    private Button reduceMotionBtn;
    // AGENT: the auto-scroll switch + the end-of-run view chips.
    private Button autoScrollBtn;
    private Button[] endViewChips;
    // AGENT (v3.10, R2): the planner-style chips — the SAME
    // control family as the end-of-run chips, over
    // Settings.plannerStyle (the accordion's one home for the
    // style; the loop reads it only through the Agent's
    // constructor at Start).
    private Button[] styleChips;
    // VOICE & SPEECH: the speech-rate chips.
    private Button[] speechRateChips;
    // DIAGNOSTICS: the step-screenshots switch.
    private Button stepShotsBtn;
    // DATA (v3.7): the model manager's rows box (rebuilt by
    // refreshModelManager — file state is not a Settings value)
    // and the clear-browsing action (button + result line).
    private LinearLayout modelRowsBox;
    private Button clearBrowsingBtn;
    private TextView clearBrowsingResult;
    // v3.7 (model manager): the spec the in-flight download
    // session is fetching, so a row can tell "downloading now"
    // from "in use" (ModelStore.deleteVerdict). Set beside
    // `downloading` in startDownload, cleared as it lands.
    private volatile String downloadingId = null;
    // v3.7 (feed text size): the sizes buildUi resolved from
    // the setting — the timeline's lines, the trail view, the
    // cards. Initialized to Comfortable's values (the standing
    // sizes) so every path reads today's sizes until buildUi
    // resolves the setting; a change applies at the next
    // launch, like the accent.
    private float feedMainSp = 12.5f;
    private float feedSubSp = 11f;
    private float trailTextSp = 11f;
    private float cardTextSp = 13f;
    // v3.8 section controls (fourth settings wave).
    // AGENT: the screen-awake switch + the battery-warning
    // switch.
    private Button screenAwakeBtn;
    private Button batteryWarnBtn;
    // ALERTS: the finish-beep switch, the quiet-hours switch,
    // and the window's hour chips in their collapsible box.
    private Button finishBeepBtn;
    private Button quietBtn;
    private LinearLayout quietBox;
    private Button[] quietStartChips;
    private Button[] quietEndChips;
    // APPEARANCE: the button-haptics switch.
    private Button hapticsBtn;
    // DIAGNOSTICS: the self-test action + readout, the debug
    // copy action + its line, and the lifetime-stats readout.
    private Button selfTestBtn;
    private TextView selfTestResult;
    private Button copyDebugBtn;
    private TextView copyDebugResult;
    private TextView lifetimeStatsText;
    // v3.8 (screen awake): this Activity set the keep-awake
    // flag for the active run and owes the window its clear at
    // the ending — set beside `running` at Start, settled in
    // finishRunUi.
    private boolean screenAwakeHeld = false;
    // v3.8 (quiet hours): the finished watched run's change was
    // deferred by quiet hours — judged in processWatch (which
    // stamps the trail), read by finishRunUi's alert matrix and
    // the finish-beep decision.
    private boolean watchQuietDeferred = false;
    // v3.8 (finish beep): the beep's generator, created lazily
    // on the first beep and reused (one short tone per ending).
    private ToneGenerator toneGen = null;
    // v3.7 (step screenshots): the cards' step-photo strips —
    // a horizontal thumbnail row under each card's end shot,
    // filled from the card's stepScreenshotFiles.
    private HorizontalScrollView cardStepStrip;
    private LinearLayout cardStepStripBox;
    private HorizontalScrollView historyStepStrip;
    private LinearLayout historyStepStripBox;
    // v3.6 (desktop mode): the WebView's own default user agent,
    // captured in setupWebView before any change — desktop OFF
    // restores exactly this string.
    private String defaultUserAgent = null;
    // v3.6 (fallback engine): the engine the CURRENT run's
    // search fallback aims at, captured from the settings at
    // Start (like every run rule, it cannot change mid-run).
    private String runFallbackEngineId = SearchEngines.DEFAULT_ID;
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

    // v3.12 (R4 — the ask action): the question handshake — the
    // same latch family as the approval above; the agent thread
    // waits in awaitAnswer, the UI thread answers through the
    // ask panel (type, or speak through the voice path:
    // voiceForAsk routes one recognizer session's results into
    // the answer field instead of the goal field). askAnswered
    // is the only road to an answer: a timeout, a Stop or a
    // destroy releases the latch unanswered, and awaitAnswer
    // returns null — silence is never an answer.
    private LinearLayout askPanel;
    private TextView askText;
    private EditText askEdit;
    private CountDownLatch askLatch;
    private volatile boolean askAnswered = false;
    private volatile String askAnswerText = null;
    private volatile boolean voiceForAsk = false;

    // v3.12 (R4 — strategy retry): the live card's retry row
    // (Runtime-built from RetryStrategy's pure mapping over the
    // card's own trail), the trail the live card belongs to,
    // and the one pending retry the next Start will consume.
    // lastCardTrailFile is set by the two roads that land a
    // card (a run's finish, a notification's trail) — a retry
    // names THAT file as its retry_of. The pending pair is
    // consumed and cleared at the top of doStartAgent, so a
    // later ordinary Start can never inherit an override.
    private LinearLayout retryBtnRow;
    private File lastCardTrailFile = null;
    private RetryStrategy.Option pendingRetryOption = null;
    private String pendingRetryOf = null;

    // v3.13 (R5 — the trust pack): resume + site grants.
    // The launch's resume offer (a surviving marker the launch
    // verdict judged resumable) and the one pending resume the
    // next Start will consume — the retry pair's sibling,
    // consumed and cleared at the top of doStartAgent so no
    // later ordinary Start can inherit it. The offer's card
    // lives in the goal card; it is rebuilt by refreshResumeUi
    // and shows only while an offer stands.
    private RunMarker resumeOffer = null;
    private RunMarker pendingResume = null;
    private LinearLayout resumePanel;
    private TextView resumeText;
    // Site grants: the store (one JSON preferences value,
    // SavedGoals' discipline), loaded at launch, read live by
    // every run's GrantBook, mutated by the approval panel's
    // "Remember for this site" and by Settings > DATA.
    private static final String PREF_GRANTS = "site_grants";
    private SiteGrants siteGrants;
    // The approval panel's grant checkbox + the captured
    // answer (read by agentUi's approve after the wait, on the
    // approvalRemember pattern of approvalResult).
    private CheckBox rememberGrantCheck;
    private boolean approvalRemember = false;
    // DIAGNOSTICS: the governor self-check action + readout.
    private Button govCheckBtn;
    private TextView govCheckResult;
    // DIAGNOSTICS (v3.14, R6): the accessibility spike — the
    // experimental toggle, the gate readout, the two probes
    // (read / tap), the tap-target chips a read rebuilds, and
    // the arming countdown's plumbing. The spike's last sketch
    // is kept only so a chip can name its tap point; it is
    // session state, never persisted (the spike LOG is the
    // record — appendSpikeLog).
    private Button a11ySpikeBtn;
    private TextView a11yGateText;
    private TextView a11yResultText;
    private EditText a11yXEdit;
    private EditText a11yYEdit;
    private LinearLayout a11yTapChips;
    private NodeSketch.Result lastSketch;
    private final Handler spikeHandler =
            new Handler(Looper.getMainLooper());
    private Runnable pendingSpike;
    // DATA: the grants rows box (rebuilt by refreshGrantsList —
    // grant state is not a Settings value) + its result line.
    private LinearLayout grantsBox;
    private TextView grantsResult;

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

    // v3.5 (the drawer): the navigation state — the DECISIONS
    // are NavState's (pure, host-tested); these fields are only
    // its views. Screens are view groups swapped by visibility:
    // the WebView, the feed and every run view are built once
    // and never destroyed by navigation, so a run in progress
    // is never disturbed by moving between screens.
    private final NavState nav = new NavState();
    private View agentScreen;
    private ScrollView goalsScroll;
    private ScrollView routesScroll;
    private ScrollView historyScroll;
    private ScrollView settingsScroll;
    private LinearLayout goalsListBox;
    private LinearLayout routesListBox;
    private LinearLayout historyListBox;
    private LinearLayout historyCardBox;
    private ImageView historyShotView;
    private TextView historyCardText;
    private AnswerCard historyCard = null;
    private Button historyListenBtn;
    // The drawer layer (over everything), its scrim and panel,
    // and whether the last render left it shown — the panel's
    // slide follows nav.drawerOpen(); this flag only tracks
    // the animation bookkeeping.
    private FrameLayout drawerLayer;
    private View drawerScrim;
    private LinearLayout drawerPanel;
    private boolean drawerShown = false;
    private TextView[] drawerItemViews;
    // The left-edge swipe that opens the drawer (tracked in
    // dispatchTouchEvent; a tap or scroll never engages it —
    // only a fast rightward drag starting at the very edge).
    private float edgeDownX = -1f;
    private float edgeDownY = -1f;
    private boolean edgeTracking = false;
    private boolean edgeFired = false;

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
        // v3.7 (APPEARANCE): the accent resolves here, before
        // buildUi builds a single view with it. At the default
        // (cyan) these are the very values the statics were
        // initialized with — an untouched install's skin is
        // byte-for-byte the one it has always had.
        COL_CYAN = Settings.accentColor(settings.accentId);
        COL_CYAN_TINT = Settings.accentTintColor(settings.accentId);
        engine.setGrammarOn(grammarOn);
        // v3.6: the engine takes the thread count for its next
        // load and the prefix-cache switch for its next call,
        // from the same persisted model (grammar's pattern).
        engine.setThreads(settings.threads);
        engine.setPrefixCacheOn(settings.prefixCacheOn);
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
        // v3.13 (R5): the site-grant store, same discipline —
        // loaded before any run can consult it.
        siteGrants = SiteGrants.parse(prefs.getString(
                PREF_GRANTS, null));
        // v3.3 (item 1): the taught routes, same store discipline.
        routes = Routes.parse(prefs.getString(PREF_ROUTES, null));
        // v3.16 (R8): the downloads store and the page-watch
        // store, same discipline again — loaded before any
        // download can land or any check can fire.
        downloads = Downloads.parse(prefs.getString(
                Downloads.PREF_DOWNLOADS, null));
        pageWatches = PageWatches.parse(prefs.getString(
                PageWatches.PREF_PAGE_WATCHES, null));
        Alerts.ensureChannels(this);
        Alerts.armAllChecks(this, savedGoals);
        Alerts.armAllPageWatches(this, pageWatches);
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
        // v3.14 (R6): the spike's gate state can change while
        // the user is away in Android Settings — the readout
        // re-judges it on every return.
        refreshSpikeGate();
    }

    @Override
    protected void onDestroy() {
        stopFlag = true;
        releaseApproval(false);
        releaseAssistStop();
        releaseAsk(false);
        stopPulse();
        // v3.14 (R6): an armed spike probe dies with the
        // Activity — a countdown must never fire into a
        // destroyed screen.
        if (pendingSpike != null) {
            spikeHandler.removeCallbacks(pendingSpike);
            pendingSpike = null;
        }
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
            // v3.5: deep links land on the Agent screen (the
            // card lives in its log card) — NavState's landing
            // decision, the same one a goal load makes inside
            // loadSavedGoal.
            showScreen(NavState.landingScreen(NavState.LANDING_CARD));
        }
        String name = intent.getStringExtra(Alerts.EXTRA_GOAL_NAME);
        // v3.16 (R8): a page watch's check carries its alarm
        // name ("page:" + url) in the same extra — the prefix
        // routes it before any goal lookup (a goal actually
        // NAMED "page:..." would lose to the prefix; the
        // prefix is the routing, documented in PageWatches).
        if (name != null && PageWatches.isPageAlarm(name)
                && pageWatches != null) {
            PageWatches.PageWatch w = pageWatches.find(
                    PageWatches.urlFromAlarm(name));
            if (w != null) {
                loadPageWatchCheck(w);
                return;
            }
        }
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
    // v3.7 (APPEARANCE — accent): the skin's accent, no longer a
    // constant. These statics hold the resolved values; they are
    // initialized to the cyan the skin has always had and are
    // re-resolved from the settings (Settings.accentColor) in
    // onCreate, BEFORE buildUi reads them — views are built
    // once, so a new accent applies when the app restarts, and
    // the accordion copy says exactly that. Only the accent
    // moves: the navy surfaces above and COL_RED below (STOP /
    // stuck / denied, always) are not the accent's to change.
    private static int COL_CYAN = Color.rgb(56, 198, 255);           // #38C6FF
    private static int COL_CYAN_TINT = Color.rgb(159, 216, 242);     // trail text
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
        // v3.8 (button haptics): the central wiring — every
        // button this factory builds (the accordion's chips and
        // switches, Start/Stop, and the app's other chip
        // buttons) ticks on press-down when the setting is on.
        // A touch listener that never consumes: the click path
        // is untouched.
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    maybeHapticTick(v);
                }
                return false;
            }
        });
        return b;
    }

    /** v3.8 (button haptics): the ONE helper every haptic
     *  surface consults — the chip-button factory's touch hook,
     *  the drawer items, and the confirm dialogs' positive
     *  buttons. The decision is Haptics'; this is only the
     *  framework call, guarded like every courtesy in this app.
     *  Deliberately separate from hapticTick(): that one is the
     *  v2.6 Start/Stop run acknowledgement and is not gated by
     *  the button-haptics setting. */
    private void maybeHapticTick(View v) {
        if (settings == null || !Haptics.shouldTick(settings.hapticsOn)) {
            return;
        }
        try {
            View target = (v != null) ? v
                    : getWindow().getDecorView();
            if (target != null) {
                target.performHapticFeedback(
                        HapticFeedbackConstants.VIRTUAL_KEY);
            }
        } catch (Throwable ignored) {
            // A tick is a courtesy, not a requirement.
        }
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
        // v3.7 (reduce motion): read at animation time, so the
        // switch applies live — the halo holds a steady glow
        // (the run state stays legible) and no animator runs.
        if (settings != null && settings.reduceMotion) {
            if (pulseAnimator != null && pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
            }
            pillHalo.setAlpha(90);
            pillWrap.setScaleX(1f);
            pillWrap.setScaleY(1f);
            return;
        }
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
        // v3.7 (APPEARANCE): the feed text sizes resolve from
        // the setting once, here, like the accent in onCreate —
        // the views below and the timeline rows read the fields,
        // so a change applies when the app restarts.
        if (settings != null) {
            feedMainSp = Settings.feedMainSp(settings.feedTextSize);
            feedSubSp = Settings.feedSubSp(settings.feedTextSize);
            trailTextSp = Settings.trailTextSp(settings.feedTextSize);
            cardTextSp = Settings.cardTextSp(settings.feedTextSize);
        }
        // v3.5: the shell. Persistent chrome (the header) over a
        // host that holds the five screens; the drawer layers
        // over everything at the end. Screens are swapped by
        // visibility ONLY — nothing below is rebuilt when the
        // user navigates, so the WebView, the agent thread, the
        // feed and all run state survive every screen switch.
        FrameLayout shell = new FrameLayout(this);
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{COL_BG_TOP, COL_BG_BOT});
        shell.setBackground(bgGrad);
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(12), dp(14), dp(12), dp(12));
        shell.addView(chrome, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout host = new FrameLayout(this);

        // header: hamburger + wordmark + subtitle, status pill
        // at the right. The header is chrome, not a screen: it
        // (and the pill it carries) is visible on every screen.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView menuBtn = new TextView(this);
        menuBtn.setText("☰");
        menuBtn.setTextSize(19);
        menuBtn.setTextColor(COL_TEXT);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setPadding(dp(11), dp(6), dp(11), dp(6));
        menuBtn.setBackground(roundedBg(COL_CARD, COL_CARD_EDGE, 12));
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(); }
        });
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        menuLp.rightMargin = dp(10);
        header.addView(menuBtn, menuLp);
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
        chrome.addView(header);
        chrome.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // The screens. AGENT ("root" below, for the run code
        // that has always added to it) is built inline — it is
        // the old single screen's run surface, unchanged view
        // for view; the other four get their containers now and
        // their content where the old code built it (Settings)
        // or in their builders (further down).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        agentScreen = root;
        host.addView(agentScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        settingsScroll = new ScrollView(this);
        settingsScroll.setVisibility(View.GONE);
        host.addView(settingsScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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
        FrameLayout.LayoutParams setLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        setLp.topMargin = dp(12);
        // v3.5: the card moved WHOLE to the SETTINGS screen —
        // same sections, same controls, same model and keys.
        settingsScroll.addView(settingsCard, setLp);
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
        // v3.6: CPU threads — the count the engine creates the
        // model's context with. Not idle-locked: it changes
        // nothing about a loaded model, only the next load.
        smallLabel(brainBody, "CPU threads");
        LinearLayout threadRow = sectionRow(brainBody);
        threadChips = new Button[Settings.THREADS_CHOICES.length];
        for (int i = 0; i < Settings.THREADS_CHOICES.length; i++) {
            final int th = Settings.THREADS_CHOICES[i];
            threadChips[i] = rowChip(threadRow, String.valueOf(th),
                    i == 0);
            threadChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onThreadsPicked(th);
                }
            });
        }
        sectionNote(brainBody, "Brain and grammar changes are "
                + "idle-only: both lock while a run or a download "
                + "is in flight. Threads apply at the next model "
                + "load — switching brains or restarting the app "
                + "picks the new count up; a loaded model keeps "
                + "the threads it was loaded with.");

        // APPEARANCE (v3.7): the skin's knobs — the accent hue,
        // the feed's text size, and the motion switch.
        LinearLayout appearBody = accordionSection(settingsCard,
                "APPEARANCE");
        smallLabel(appearBody, "Accent color");
        LinearLayout accentRow = sectionRow(appearBody);
        accentChips = new Button[Settings.ACCENT_IDS.length];
        for (int i = 0; i < Settings.ACCENT_IDS.length; i++) {
            final String aid = Settings.ACCENT_IDS[i];
            accentChips[i] = rowChip(accentRow,
                    Settings.ACCENT_NAMES[i], i == 0);
            accentChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onAccentPicked(aid);
                }
            });
        }
        smallLabel(appearBody, "Feed text size — the steps "
                + "timeline, the trail view, and the answer cards");
        LinearLayout feedTextRow = sectionRow(appearBody);
        final String[] feedTextLabels = {"Compact", "Comfortable",
                "Large"};
        feedTextChips = new Button[feedTextLabels.length];
        for (int i = 0; i < feedTextLabels.length; i++) {
            final int pref = i;
            feedTextChips[i] = rowChip(feedTextRow,
                    feedTextLabels[i], i == 0);
            feedTextChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onFeedTextPicked(pref);
                }
            });
        }
        reduceMotionBtn = toggleButton(appearBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onReduceMotionToggled();
                    }
                });
        // v3.8: button haptics — one light tick from the
        // primary buttons (the helper is maybeHapticTick; the
        // v2.6 Start/Stop run acknowledgement is not this).
        hapticsBtn = toggleButton(appearBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onHapticsToggled();
                    }
                });
        sectionNote(appearBody, "Accent and text size are read "
                + "when the app builds its screens, so a change "
                + "applies when the app restarts — nothing "
                + "pretends otherwise. Only the accent hue "
                + "moves: the navy stays navy, and red stays "
                + "reserved for Stop, stuck and denied. Reduce "
                + "motion applies at once: the running pill "
                + "holds a steady glow instead of breathing, and "
                + "the drawer snaps instead of sliding. Button "
                + "haptics tick the primary buttons — Start and "
                + "Stop, the drawer, these chips and switches, "
                + "dialog confirms — as you press them; the run "
                + "acknowledgement tick on Start is separate and "
                + "always sounds.");

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
        // v3.7: auto-scroll — the feed's follow behaviour.
        autoScrollBtn = toggleButton(agentBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onAutoScrollToggled(); }
        });
        // v3.7: the view a finished run lands on.
        smallLabel(agentBody, "View a finished run lands on");
        LinearLayout endViewRow = sectionRow(agentBody);
        final String[] endViewLabels = {"Steps", "Trail", "Card"};
        endViewChips = new Button[endViewLabels.length];
        for (int i = 0; i < endViewLabels.length; i++) {
            final int view = i;
            endViewChips[i] = rowChip(endViewRow, endViewLabels[i],
                    i == 0);
            endViewChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onEndViewPicked(view);
                }
            });
        }
        // v3.10 (R2): the planner style — how the agent works a
        // goal, captured at Start like every run rule.
        smallLabel(agentBody, "Planner style");
        LinearLayout styleRow = sectionRow(agentBody);
        styleChips = new Button[Settings.STYLE_NAMES.length];
        for (int i = 0; i < Settings.STYLE_NAMES.length; i++) {
            final int style = i;
            styleChips[i] = rowChip(styleRow, Settings.STYLE_NAMES[i],
                    i == 0);
            styleChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onPlannerStylePicked(style);
                }
            });
        }
        // v3.8: keep the screen awake for the length of a run,
        // and the low-battery start warning.
        screenAwakeBtn = toggleButton(agentBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onScreenAwakeToggled();
                    }
                });
        batteryWarnBtn = toggleButton(agentBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onBatteryWarnToggled();
                    }
                });
        sectionNote(agentBody, "Applies from the next run. A "
                + "saved goal you load still brings its own "
                + "policy; a scheduled check still runs readonly. "
                + "Auto-scroll off means new lines never move "
                + "your place in the feed — read scrollback "
                + "mid-run and it stays yours; a finished run "
                + "still switches to its landing view, it just "
                + "does not scroll you there. Keep screen awake "
                + "holds the display on while a run or replay is "
                + "active and lets go at its end. The low-battery "
                + "warning asks before a start you tap — a run, a "
                + "replay, teaching — when the battery is under "
                + BatteryGuard.WARN_BELOW_PERCENT + "% and not "
                + "charging; it only ever asks, never blocks, and "
                + "you can turn it off right here. The planner "
                + "style is how a run works: Balanced is the "
                + "standing posture; Careful reads the page it is "
                + "on before it may finish; Fast prefers search "
                + "and the first strong result, and reads in "
                + "small chunks unless you set a chunk size "
                + "under READING; Thorough cross-checks a second "
                + "source before finishing a comparison.");

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
        // v3.6: the fallback search engine — where the search
        // action goes when the page has no search box of its
        // own (site-native search is always tried first).
        smallLabel(readBody, "Fallback search engine — used when "
                + "a page has no search box of its own");
        LinearLayout fallbackRow = sectionRow(readBody);
        fallbackChips = new Button[SearchEngines.NAMES.length];
        for (int i = 0; i < SearchEngines.NAMES.length; i++) {
            final String eid = SearchEngines.NAMES[i];
            fallbackChips[i] = rowChip(fallbackRow,
                    SearchEngines.displayName(eid), i == 0);
            fallbackChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onFallbackEnginePicked(eid);
                }
            });
        }
        // v3.6: desktop mode — a desktop user agent for the
        // WebView (its wide viewport has been on since v1).
        desktopBtn = toggleButton(readBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onDesktopToggled(); }
        });
        sectionNote(readBody, "Desktop mode asks sites for their "
                + "desktop pages. It applies to pages loaded after "
                + "the change; the page already open keeps the "
                + "form it loaded in.");

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
        // v3.7: the spoken rate for Listen and speak-on-finish.
        smallLabel(voiceBody, "Speech rate — Listen and speak "
                + "on finish");
        LinearLayout rateRow = sectionRow(voiceBody);
        final String[] rateLabels = {"Slower", "Normal", "Faster"};
        speechRateChips = new Button[rateLabels.length];
        for (int i = 0; i < rateLabels.length; i++) {
            final int rate = i;
            speechRateChips[i] = rowChip(rateRow, rateLabels[i],
                    i == 0);
            speechRateChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onSpeechRatePicked(rate);
                }
            });
        }
        sectionNote(voiceBody, "Speak on finish reads a "
                + "finished run's answer card aloud — only when "
                + "the app is in front and the run actually "
                + "answered; errors and stops are never shouted. "
                + "The speech rate applies to the next thing "
                + "spoken.");

        // ALERTS.
        LinearLayout alertBody = accordionSection(settingsCard, "ALERTS");
        buzzBtn = toggleButton(alertBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onBuzzToggled(); }
        });
        // v3.6: needs-me notifications.
        needsMeBtn = toggleButton(alertBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onNeedsMeToggled(); }
        });
        // v3.8: the finish beep and watch quiet hours.
        finishBeepBtn = toggleButton(alertBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onFinishBeepToggled();
                    }
                });
        quietBtn = toggleButton(alertBody, new View.OnClickListener() {
            @Override public void onClick(View v) {
                onQuietHoursToggled();
            }
        });
        quietBox = new LinearLayout(this);
        quietBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams quietBoxLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        quietBoxLp.topMargin = dp(4);
        alertBody.addView(quietBox, quietBoxLp);
        smallLabel(quietBox, "Quiet from");
        quietStartChips = hourChips(quietBox, true);
        smallLabel(quietBox, "Quiet until");
        quietEndChips = hourChips(quietBox, false);
        sectionNote(alertBody, "The buzz when a run ends "
                + "off-screen. Watched-goal change alerts are "
                + "not gated by this — a change is the watch's "
                + "whole signal, and it buzzes — unless quiet "
                + "hours below are holding it. The finish beep "
                + "is one short tone as a run ends, front or "
                + "background; a no-change watch and a change "
                + "held by quiet hours stay silent for it too. "
                + "Quiet hours: during the window a watched "
                + "check still runs and its verdict is recorded, "
                + "but a change raises no notification and no "
                + "buzz — the change waits on the run's card, "
                + "which notes it arrived during quiet hours. "
                + "Nothing is sent when the window ends. "
                + "Needs-me "
                + "posts a notification when a run is waiting on "
                + "you — an approval to decide, a co-pilot rescue "
                + "offered — and the app is in the background; "
                + "its tap brings you to the waiting panel. Like "
                + "every Ghost Hands notification, these appear "
                + "only if you have allowed notifications for "
                + "the app in system settings.");

        // DIAGNOSTICS (v3.6): the A/B instruments — new section
        // between ALERTS and DATA.
        LinearLayout diagBody = accordionSection(settingsCard,
                "DIAGNOSTICS");
        liveStatsBtn = toggleButton(diagBody, new View.OnClickListener() {
            @Override public void onClick(View v) { onLiveStatsToggled(); }
        });
        prefixCacheBtn = toggleButton(diagBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onPrefixCacheToggled();
                    }
                });
        // v3.7: step screenshots — a photograph at every
        // step's end, not just the run's.
        stepShotsBtn = toggleButton(diagBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onStepShotsToggled();
                    }
                });
        sectionNote(diagBody, "Live stats put the run's "
                + "generation speed and prompt-cache reuse in "
                + "the status line as it goes and in its closing "
                + "summary. Prefix cache reuses the prompt's "
                + "shared beginning between steps (on since "
                + "v1.5); turn it off to A/B a misbehaving run — "
                + "every step then pays full prompt evaluation, "
                + "the pre-v1.5 behaviour. Both are recorded on "
                + "the run's trail either way. Step screenshots "
                + "photograph the page at every step's end as "
                + "well as the run's, and the run's card shows "
                + "them as a strip; they live in the same "
                + "screenshot store, under the same DATA "
                + "retention count as the end photo.");
        // v3.8: the engine self-test — one canonical prompt,
        // the engine's own measurements, an in-place report;
        // it writes nothing to any trail.
        smallLabel(diagBody, "Engine self-test — one fixed "
                + "prompt through the brain, reported here");
        selfTestBtn = chipButton("Run engine self-test");
        selfTestBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onSelfTest();
            }
        });
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(6);
        diagBody.addView(selfTestBtn, stLp);
        selfTestResult = new TextView(this);
        selfTestResult.setTextSize(11);
        selfTestResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams stRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stRLp.topMargin = dp(4);
        diagBody.addView(selfTestResult, stRLp);
        // v3.13 (R5): the governor self-check — the real
        // Governor against four known actions (GovSelfCheck):
        // seatbelt denies an injection-shaped target, a
        // consequential action asks, a read is allowed, a
        // plain write follows the default pack's posture.
        // No model, no browsing, no trail — verdicts in place.
        smallLabel(diagBody, "Governor self-check — the "
                + "governor's four promises, tested live");
        govCheckBtn = chipButton("Run governor self-check");
        govCheckBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onGovSelfCheck();
            }
        });
        LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gcLp.topMargin = dp(6);
        diagBody.addView(govCheckBtn, gcLp);
        govCheckResult = new TextView(this);
        govCheckResult.setTextSize(11);
        govCheckResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams gcRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gcRLp.topMargin = dp(4);
        diagBody.addView(govCheckResult, gcRLp);
        // v3.8: copy debug info — the small, pinned bundle
        // (DebugInfo): version, device, model, four engine
        // settings, two store counts. Nothing else exists in
        // it to copy.
        smallLabel(diagBody, "Debug info — version, device, "
                + "model and counts, for a field report");
        copyDebugBtn = chipButton("Copy debug info");
        copyDebugBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onCopyDebugInfo();
            }
        });
        LinearLayout.LayoutParams cdLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cdLp.topMargin = dp(6);
        diagBody.addView(copyDebugBtn, cdLp);
        copyDebugResult = new TextView(this);
        copyDebugResult.setTextSize(11);
        copyDebugResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams cdRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cdRLp.topMargin = dp(4);
        diagBody.addView(copyDebugResult, cdRLp);
        // v3.8: lifetime stats — a readout over the trail
        // files, recomputed each time this screen is shown
        // (TrailStats: missing fields are skipped, never
        // zero-filled).
        smallLabel(diagBody, "Lifetime stats — from your run "
                + "trails");
        lifetimeStatsText = new TextView(this);
        lifetimeStatsText.setTextSize(12);
        lifetimeStatsText.setTextColor(COL_TEXT);
        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lsLp.topMargin = dp(4);
        diagBody.addView(lifetimeStatsText, lsLp);
        // v3.14 (Everything Train R6): the accessibility
        // spike — Beyond the Browser, fenced as a spike: an
        // experimental toggle, a state line, and two probes
        // (read the foreground app's tree as a sketch; dispatch
        // one tap). Both probes stand behind SpikeGate: this
        // toggle AND the accessibility service the user alone
        // enables in Android Settings. The probes arm on a 5
        // second delay for one practical reason: at press time
        // the foreground app is always THIS app, so an instant
        // probe could only ever test ourselves — the delay is
        // what makes the cross-app question testable at all.
        smallLabel(diagBody, "Accessibility spike — "
                + "EXPERIMENTAL (Beyond the Browser)");
        a11ySpikeBtn = toggleButton(diagBody,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        onA11ySpikeToggled();
                    }
                });
        // The one explanatory line the spike is allowed: the
        // service is the user's switch, in the system's own
        // settings, and the app cannot turn it on.
        sectionNote(diagBody, "The probes work through an "
                + "accessibility service only you can enable: "
                + "Android Settings > Accessibility > "
                + "Installed services > Ghost Hands. The app "
                + "cannot switch it on, and with this toggle "
                + "off or the service off, nothing below runs.");
        Button a11yOpenBtn = chipButton(
                "Open Android Accessibility settings");
        a11yOpenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onOpenA11ySettings();
            }
        });
        LinearLayout.LayoutParams aoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aoLp.topMargin = dp(6);
        diagBody.addView(a11yOpenBtn, aoLp);
        a11yGateText = new TextView(this);
        a11yGateText.setTextSize(11);
        a11yGateText.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams agLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        agLp.topMargin = dp(4);
        diagBody.addView(a11yGateText, agLp);
        Button a11yReadBtn = chipButton(
                "Probe: read foreground tree (5s)");
        a11yReadBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onA11yProbeRead();
            }
        });
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = dp(6);
        diagBody.addView(a11yReadBtn, arLp);
        // The coordinate tap: two numeric fields + the button.
        LinearLayout a11yCoordRow = sectionRow(diagBody);
        a11yXEdit = spikeCoordField("X px");
        a11yCoordRow.addView(a11yXEdit,
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        a11yYEdit = spikeCoordField("Y px");
        LinearLayout.LayoutParams ayLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        ayLp.leftMargin = dp(6);
        a11yCoordRow.addView(a11yYEdit, ayLp);
        Button a11yTapBtn = chipButton("Probe: tap at X, Y (5s)");
        a11yTapBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onA11yProbeTapCoords();
            }
        });
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.topMargin = dp(6);
        diagBody.addView(a11yTapBtn, atLp);
        // The chips a read rebuilds: one per clickable node,
        // each arming a tap at that node's centre.
        a11yTapChips = new LinearLayout(this);
        a11yTapChips.setOrientation(LinearLayout.VERTICAL);
        diagBody.addView(a11yTapChips,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        a11yResultText = new TextView(this);
        a11yResultText.setTextSize(11);
        a11yResultText.setTextColor(COL_TEXT);
        LinearLayout.LayoutParams arRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arRLp.topMargin = dp(6);
        diagBody.addView(a11yResultText, arRLp);

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
        // v3.6: trail retention, paired with the screenshot
        // count above — the two stores a run leaves behind.
        smallLabel(dataBody, "Run trails kept");
        LinearLayout trailRow = sectionRow(dataBody);
        trailChips = new Button[Settings.TRAILS_CHOICES.length];
        for (int i = 0; i < Settings.TRAILS_CHOICES.length; i++) {
            final int kept = Settings.TRAILS_CHOICES[i];
            trailChips[i] = rowChip(trailRow, String.valueOf(kept),
                    i == 0);
            trailChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onTrailsKeptPicked(kept);
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

        // v3.7: the model manager — one row per known model:
        // its on-device state and, where the rules allow, a
        // Delete. The rows are rebuilt by refreshModelManager
        // (file state is not a Settings value, so the rows are
        // not refreshSettingsUi's business); the rules are
        // ModelStore.deleteVerdict's — the BRAIN's model is
        // protected, a downloading model is refused, and a
        // delete removes exactly that one file.
        smallLabel(dataBody, "Models on this phone");
        modelRowsBox = new LinearLayout(this);
        modelRowsBox.setOrientation(LinearLayout.VERTICAL);
        dataBody.addView(modelRowsBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // v3.13 (R5): site grants — the remembered approvals.
        // The rows are rebuilt by refreshGrantsList (the store
        // is not a Settings value): one row per grant naming
        // site, action kind and the day it was remembered, each
        // with its Revoke, and a revoke-all under the list. A
        // revocation takes effect at the next governor
        // decision — the loop reads the store live.
        smallLabel(dataBody, "Site grants — remembered "
                + "approvals; a listed site no longer asks for "
                + "that kind of action");
        grantsBox = new LinearLayout(this);
        grantsBox.setOrientation(LinearLayout.VERTICAL);
        dataBody.addView(grantsBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        grantsResult = new TextView(this);
        grantsResult.setTextSize(11);
        grantsResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams grRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        grRLp.topMargin = dp(4);
        dataBody.addView(grantsResult, grRLp);
        refreshGrantsList();

        // v3.6: the clear-data actions — buttons, not settings.
        // Each confirms first, naming exactly what will be
        // deleted, and reports its result on the line under it.
        // Neither can reach models or settings (see DataWipe).
        smallLabel(dataBody, "Clear data — each asks first, and "
                + "what is cleared is gone for good");
        clearTrailsBtn = chipButton("Clear trails and screenshots");
        clearTrailsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onClearTrailsAndShots();
            }
        });
        LinearLayout.LayoutParams wipeTLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeTLp.topMargin = dp(6);
        dataBody.addView(clearTrailsBtn, wipeTLp);
        clearTrailsResult = new TextView(this);
        clearTrailsResult.setTextSize(11);
        clearTrailsResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams wipeTRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeTRLp.topMargin = dp(4);
        dataBody.addView(clearTrailsResult, wipeTRLp);
        clearGoalsBtn = chipButton("Clear saved goals and routes");
        clearGoalsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onClearGoalsAndRoutes();
            }
        });
        LinearLayout.LayoutParams wipeGLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeGLp.topMargin = dp(8);
        dataBody.addView(clearGoalsBtn, wipeGLp);
        clearGoalsResult = new TextView(this);
        clearGoalsResult.setTextSize(11);
        clearGoalsResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams wipeGRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeGRLp.topMargin = dp(4);
        dataBody.addView(clearGoalsResult, wipeGRLp);
        // v3.7: clear browsing data — the third clear action,
        // and the narrowest: the WebView's cookies and site
        // storage (BrowsingData's table is its whole scope).
        // It deletes no file. v3.13 (R5): the table now also
        // names the site grants — browsing-derived trust is
        // browsing data, so this clear withdraws it too.
        clearBrowsingBtn = chipButton("Clear browsing data");
        clearBrowsingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onClearBrowsingData();
            }
        });
        LinearLayout.LayoutParams wipeBLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeBLp.topMargin = dp(8);
        dataBody.addView(clearBrowsingBtn, wipeBLp);
        clearBrowsingResult = new TextView(this);
        clearBrowsingResult.setTextSize(11);
        clearBrowsingResult.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams wipeBRLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wipeBRLp.topMargin = dp(4);
        dataBody.addView(clearBrowsingResult, wipeBRLp);

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
        // v3.16 (R8 — watch this page): the URL bar's second
        // segment. One tap signs the current page's readable
        // text and watches the URL for changes (see the page
        // watch section below for the whole discipline).
        Button watchPageBtn = new Button(this);
        watchPageBtn.setText("Watch");
        watchPageBtn.setBackground(roundedBg(COL_CARD_INNER,
                COL_CARD_EDGE, 20));
        watchPageBtn.setTextColor(COL_TEXT);
        watchPageBtn.setAllCaps(false);
        watchPageBtn.setMinWidth(0);
        watchPageBtn.setMinHeight(0);
        watchPageBtn.setMinimumWidth(0);
        watchPageBtn.setMinimumHeight(0);
        watchPageBtn.setPadding(dp(14), dp(7), dp(14), dp(7));
        watchPageBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                watchCurrentPage();
            }
        });
        LinearLayout.LayoutParams wpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        wpLp.leftMargin = dp(6);
        urlRow.addView(watchPageBtn, wpLp);

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
        // v3.15 (R7): the tab strip rides on top of the stage —
        // one chip per open tab plus the + chip — above the
        // host frame that holds the tab WebViews (only the
        // active one visible). The strip is chrome: present
        // during and outside runs; refreshTabStrip rebuilds it
        // and owns its run-time dimming.
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabStrip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stripLp.bottomMargin = dp(4);
        stageCard.addView(tabStrip, stripLp);
        webHost = new android.widget.FrameLayout(this);
        stageCard.addView(webHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        webView = createTabView();
        tabViews.add(webView);
        webHost.addView(webView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.VISIBLE);
        refreshTabStrip();

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

        // v3.13 (R5 — resume interrupted runs): the resume
        // card. Hidden unless the launch found a resumable
        // marker; refreshResumeUi fills it from the held offer.
        // Resume starts a NEW run of the interrupted goal under
        // the marker's captured rules; Dismiss clears the
        // marker and nothing else.
        resumePanel = new LinearLayout(this);
        resumePanel.setOrientation(LinearLayout.VERTICAL);
        resumePanel.setBackground(roundedBg(COL_CARD, COL_CYAN, 14));
        resumePanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        resumeText = new TextView(this);
        resumeText.setTextColor(COL_TEXT);
        resumeText.setTextSize(13);
        resumePanel.addView(resumeText);
        LinearLayout resumeRow = new LinearLayout(this);
        resumeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams resumeRowLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        resumeRowLp.topMargin = dp(8);
        resumePanel.addView(resumeRow, resumeRowLp);
        Button resumeGoalBtn = new Button(this);
        resumeGoalBtn.setText("Resume goal");
        stylePrimary(resumeGoalBtn);
        resumeGoalBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onResumeOffer(); }
        });
        resumeRow.addView(resumeGoalBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button dismissBtn = new Button(this);
        dismissBtn.setText("Dismiss");
        styleQuiet(dismissBtn);
        dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onDismissOffer(); }
        });
        LinearLayout.LayoutParams dismissLp =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        dismissLp.leftMargin = dp(10);
        resumeRow.addView(dismissBtn, dismissLp);
        resumePanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams resumeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        resumeLp.topMargin = dp(10);
        goalCard.addView(resumePanel, resumeLp);

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
                // v3.12: Stop also releases an ask wait — as
                // no answer, never as an answer.
                releaseAsk(false);
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
        // v3.13 (R5 — site grants): the remember checkbox. It
        // rides the approval panel but is SHOWN only for a
        // consequential ask (awaitApproval judges from the
        // decision): a grant is a remembered consequential
        // approval, so only a consequential approval can mint
        // one. Unchecked by default, reset on every showing —
        // remembering is always a fresh, deliberate tick.
        rememberGrantCheck = new CheckBox(this);
        rememberGrantCheck.setText("Remember for this site");
        rememberGrantCheck.setTextColor(COL_TEXT);
        rememberGrantCheck.setTextSize(13);
        LinearLayout.LayoutParams rgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rgLp.topMargin = dp(8);
        approvalPanel.addView(rememberGrantCheck, rgLp);
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

        // v3.12 (R4): the ask panel — the approval panel's
        // sibling, for the planner's own clarifying question.
        // Cyan, not red or amber: a question is a conversation,
        // not a warning. The answer field takes typing or the
        // voice path (its mic runs the same recognizer flow as
        // the goal mic, routed here by voiceForAsk). There is
        // deliberately no decline button: not answering is the
        // timeout's and the Stop button's job, and either ends
        // the run honestly — the panel never invents an answer.
        askPanel = new LinearLayout(this);
        askPanel.setOrientation(LinearLayout.VERTICAL);
        askPanel.setBackground(roundedBg(COL_CARD, COL_CYAN, 14));
        askPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
        askText = new TextView(this);
        askText.setTextColor(COL_TEXT);
        askText.setTextSize(13);
        askPanel.addView(askText);
        askEdit = new EditText(this);
        askEdit.setSingleLine(false);
        askEdit.setHint("Your answer");
        askEdit.setBackground(null);
        askEdit.setTextColor(COL_TEXT);
        askEdit.setHintTextColor(COL_MUTED);
        askEdit.setTextSize(14);
        LinearLayout.LayoutParams aeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        aeLp.topMargin = dp(4);
        askPanel.addView(askEdit, aeLp);
        LinearLayout askRow = new LinearLayout(this);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams askLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        askLp2.topMargin = dp(8);
        askPanel.addView(askRow, askLp2);
        Button askMicBtn = chipButton("🎤 Voice");
        askMicBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onAskMicTapped(); }
        });
        askRow.addView(askMicBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button askAnswerBtn = new Button(this);
        askAnswerBtn.setText("Answer");
        stylePrimary(askAnswerBtn);
        askAnswerBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { releaseAsk(true); }
        });
        LinearLayout.LayoutParams aabLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2);
        aabLp.leftMargin = dp(10);
        askRow.addView(askAnswerBtn, aabLp);
        askPanel.setVisibility(View.GONE);
        LinearLayout.LayoutParams askPLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        askPLp.topMargin = dp(12);
        root.addView(askPanel, askPLp);

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
        feedText.setTextSize(trailTextSp);
        feedText.setTypeface(Typeface.MONOSPACE);
        feedText.setTextColor(COL_CYAN_TINT);
        feedText.setTextIsSelectable(true);
        // v3.0 (item 4): the answer card's box — the card text
        // (AnswerCard.cardText, pure) and its Share button.
        cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        cardBox.setVisibility(View.GONE);
        cardText = new TextView(this);
        cardText.setTextSize(cardTextSp);
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
        // v3.7 (step screenshots): the step-photo strip — a
        // horizontal thumbnail row under the end shot, filled
        // by refreshCardMedia from the card's step photos.
        cardStepStripBox = new LinearLayout(this);
        cardStepStripBox.setOrientation(LinearLayout.HORIZONTAL);
        cardStepStrip = new HorizontalScrollView(this);
        cardStepStrip.setHorizontalScrollBarEnabled(false);
        cardStepStrip.setVisibility(View.GONE);
        cardStepStrip.addView(cardStepStripBox);
        LinearLayout.LayoutParams cstLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cstLp.bottomMargin = dp(8);
        cardBox.addView(cardStepStrip, cstLp);
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
        // v3.16 (R8): watch THIS card's page — offered when
        // the card names a source URL; the tap watches the
        // live page when the browser still shows that URL
        // (watchCardPage judges), and says so when it does
        // not.
        Button watchCardBtn = chipButton("Watch page");
        watchCardBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { watchCardPage(); }
        });
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        wcLp.leftMargin = dp(8);
        cardBtnRow.addView(watchCardBtn, wcLp);
        // v3.12 (R4): the strategy-retry row — up to two
        // counter-move buttons under a stuck run's card, rebuilt
        // by refreshRetryButtons from RetryStrategy's pure
        // mapping over the card's own trail. Gone unless the
        // mapping offers something: a blind "retry as-is" is
        // never one of the buttons.
        retryBtnRow = new LinearLayout(this);
        retryBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        retryBtnRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rbLp.topMargin = dp(8);
        cardBox.addView(retryBtnRow, rbLp);
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

        // v3.5: the remaining screens and the drawer itself.
        buildGoalsScreen(host);
        buildRoutesScreen(host);
        buildHistoryScreen(host);
        buildDrawer(shell);

        setContentView(shell);
        applyScreens();
        updatePill();
        renderLog();
    }

    // ---------------- v3.5: screens + drawer ----------------

    /** A content screen's card: the ethereal card the list
     *  screens (Goals, Routes, History) present in. */
    private LinearLayout screenCard(FrameLayout host, ScrollView scroll) {
        scroll.setVisibility(View.GONE);
        host.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        scroll.addView(card, lp);
        return card;
    }

    private void buildGoalsScreen(FrameLayout host) {
        goalsScroll = new ScrollView(this);
        LinearLayout card = screenCard(host, goalsScroll);
        card.addView(cardLabel("SAVED GOALS"));
        TextView note = new TextView(this);
        note.setText("Tap a goal to load it on the Agent screen. "
                + "⏰ sets its daily check; 👁 watches its answers "
                + "for changes.");
        note.setTextSize(11);
        note.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        card.addView(note, nlp);
        goalsListBox = new LinearLayout(this);
        goalsListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(goalsListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // v3.16 (R8): page watches live in the watched goals'
        // family — the same screen, the same check discipline
        // (see the page watch section below).
        LinearLayout.LayoutParams pwlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pwlp.topMargin = dp(16);
        card.addView(cardLabel("PAGE WATCHES"), pwlp);
        TextView pwNote = new TextView(this);
        pwNote.setText("Pages you are watching (Watch in the "
                + "address bar). A check re-reads the page and "
                + "compares its numbers with the last check — "
                + "you are buzzed only when they change.");
        pwNote.setTextSize(11);
        pwNote.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams pwnp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pwnp.topMargin = dp(4);
        card.addView(pwNote, pwnp);
        pageWatchListBox = new LinearLayout(this);
        pageWatchListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(pageWatchListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void buildRoutesScreen(FrameLayout host) {
        routesScroll = new ScrollView(this);
        LinearLayout card = screenCard(host, routesScroll);
        card.addView(cardLabel("ROUTES"));
        TextView note = new TextView(this);
        note.setText("Routes you taught, replayed step for step "
                + "under the policy in force — consequential "
                + "steps ask again, password steps stop for "
                + "your own fill.");
        note.setTextSize(11);
        note.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        card.addView(note, nlp);
        Button teachNewBtn = chipButton("● Teach a new route");
        teachNewBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Teaching happens at the browser (invariant:
                // recording uses the Agent screen's WebView).
                showScreen(NavState.AGENT);
                onTeachTapped();
            }
        });
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        card.addView(teachNewBtn, tlp);
        routesListBox = new LinearLayout(this);
        routesListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(routesListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void buildHistoryScreen(FrameLayout host) {
        historyScroll = new ScrollView(this);
        LinearLayout card = screenCard(host, historyScroll);
        card.addView(cardLabel("HISTORY"));
        TextView note = new TextView(this);
        note.setText("Past runs, newest first. Tap one to see "
                + "its answer card.");
        note.setTextSize(11);
        note.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(4);
        card.addView(note, nlp);
        historyListBox = new LinearLayout(this);
        historyListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(historyListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // The picked run's card, in this screen: the same card
        // content the Agent screen shows (AnswerCard.cardText),
        // its screenshot, and its own Share / Listen controls.
        historyCardBox = new LinearLayout(this);
        historyCardBox.setOrientation(LinearLayout.VERTICAL);
        historyCardBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams hcp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hcp.topMargin = dp(12);
        card.addView(historyCardBox, hcp);
        historyCardBox.addView(cardLabel("ANSWER CARD"));
        historyShotView = new ImageView(this);
        historyShotView.setAdjustViewBounds(true);
        historyShotView.setVisibility(View.GONE);
        LinearLayout.LayoutParams hsv = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hsv.topMargin = dp(8);
        hsv.bottomMargin = dp(8);
        historyCardBox.addView(historyShotView, hsv);
        // v3.7 (step screenshots): History's step-photo strip,
        // the live card's twin (filled by showHistoryCard).
        historyStepStripBox = new LinearLayout(this);
        historyStepStripBox.setOrientation(LinearLayout.HORIZONTAL);
        historyStepStrip = new HorizontalScrollView(this);
        historyStepStrip.setHorizontalScrollBarEnabled(false);
        historyStepStrip.setVisibility(View.GONE);
        historyStepStrip.addView(historyStepStripBox);
        LinearLayout.LayoutParams hstLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hstLp.bottomMargin = dp(8);
        historyCardBox.addView(historyStepStrip, hstLp);
        historyCardText = new TextView(this);
        historyCardText.setTextSize(cardTextSp);
        historyCardText.setTextColor(COL_TEXT);
        historyCardText.setTextIsSelectable(true);
        historyCardBox.addView(historyCardText);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        historyCardBox.addView(btnRow, blp);
        Button shareBtn = chipButton("Share card");
        shareBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareHistoryCard(); }
        });
        btnRow.addView(shareBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        historyListenBtn = chipButton(SpeakText.buttonLabel(true, false));
        historyListenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onHistoryListenTapped(); }
        });
        LinearLayout.LayoutParams hlb = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        hlb.leftMargin = dp(8);
        btnRow.addView(historyListenBtn, hlb);
        // v3.16 (R8): the downloads store's list — governed
        // downloads and picked files in one place, in the
        // History screen's family. A run can read the text
        // files (txt, md, csv, html, log, json) from here
        // with read_file; the PDF line states this build's
        // limit where the user meets it.
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(16);
        card.addView(cardLabel("DOWNLOADS"), dlp);
        TextView dlNote = new TextView(this);
        dlNote.setText("Files pages downloaded (each one was "
                + "judged by your policy first) and files you "
                + "picked to read. A goal can read the text "
                + "files — txt, md, csv, html, log, json. "
                + "PDFs: cannot read text from PDFs yet.");
        dlNote.setTextSize(11);
        dlNote.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams dnp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dnp.topMargin = dp(4);
        card.addView(dlNote, dnp);
        Button pickBtn = chipButton("Read a file…");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFileToRead(); }
        });
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pbp.topMargin = dp(8);
        card.addView(pickBtn, pbp);
        downloadsListBox = new LinearLayout(this);
        downloadsListBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(downloadsListBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** v3.16 (R8): the downloads list's rows — name, size,
     *  type, date and origin, with Open (a system viewer),
     *  Share and Delete per entry. Rebuilt on entry and after
     *  every mutation, like the goals list. */
    private void refreshDownloadsList() {
        if (downloadsListBox == null) return;
        downloadsListBox.removeAllViews();
        if (downloads == null || downloads.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No files yet — when a page offers a "
                    + "download, it lands here after your policy "
                    + "judges it.");
            empty.setTextSize(12);
            empty.setTextColor(COL_MUTED);
            empty.setPadding(dp(4), dp(10), dp(4), dp(6));
            downloadsListBox.addView(empty);
            return;
        }
        List<Downloads.Entry> all = downloads.all();
        // Newest first (the store appends; the list reads
        // back-to-front, the trails list's convention).
        for (int i = all.size() - 1; i >= 0; i--) {
            final Downloads.Entry e = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameText = new TextView(this);
            nameText.setText(e.name);
            nameText.setTextSize(13);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(COL_TEXT);
            texts.addView(nameText);
            TextView meta = new TextView(this);
            String typeLine = e.mime.length() > 0 ? e.mime
                    : Downloads.mimeForName(e.name);
            String readable = DocText.isPdf(e.name, e.mime)
                    ? " · PDF — cannot read text from PDFs yet"
                    : (DocText.isReadable(e.name, e.mime)
                            ? " · readable by goals" : "");
            meta.setText(Downloads.sizeLabel(e.bytes) + " · "
                    + typeLine + " · "
                    + new SimpleDateFormat("EEE h:mm a", Locale.US)
                            .format(new Date(e.tsMs))
                    + ("picked".equals(e.origin)
                            ? " · picked by you" : "")
                    + readable);
            meta.setTextSize(11);
            meta.setTextColor(COL_MUTED);
            texts.addView(meta);
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button openBtn = chipButton("Open");
            openBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openDownload(e);
                }
            });
            row.addView(openBtn);
            Button shareBtn = chipButton("Share");
            shareBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    shareDownload(e);
                }
            });
            LinearLayout.LayoutParams shLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            shLp.leftMargin = dp(6);
            row.addView(shareBtn, shLp);
            Button delBtn = chipButton("✕");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmDeleteDownload(e);
                }
            });
            LinearLayout.LayoutParams delLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            row.addView(delBtn, delLp);
            downloadsListBox.addView(row);
        }
    }

    /** The content URI a stored file is opened/shared under
     *  (DlProvider serves only this directory). */
    private android.net.Uri downloadUri(String name) {
        return new android.net.Uri.Builder()
                .scheme("content")
                .authority(DlProvider.AUTHORITY)
                .appendPath(name)
                .build();
    }

    private void openDownload(Downloads.Entry e) {
        File f = new File(downloadsDir(), e.name);
        if (!f.isFile()) {
            Toast.makeText(this, "That file is gone from the store",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String type = e.mime.length() > 0 ? e.mime
                : Downloads.mimeForName(e.name);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(downloadUri(e.name), type);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (Exception ex) {
            Toast.makeText(this, "No app on this phone can open "
                    + "that file", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareDownload(Downloads.Entry e) {
        File f = new File(downloadsDir(), e.name);
        if (!f.isFile()) {
            Toast.makeText(this, "That file is gone from the store",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String type = e.mime.length() > 0 ? e.mime
                : Downloads.mimeForName(e.name);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(type);
        send.putExtra(Intent.EXTRA_STREAM, downloadUri(e.name));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share file"));
        } catch (Exception ex) {
            Toast.makeText(this, "Sharing is not available",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDeleteDownload(final Downloads.Entry e) {
        new AlertDialog.Builder(this)
                .setTitle("Delete file")
                .setMessage("Delete \"" + e.name + "\" from the "
                        + "downloads store? A goal reading it "
                        + "will find it gone.")
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                new File(downloadsDir(), e.name)
                                        .delete();
                                if (downloads != null) {
                                    downloads.remove(e.name);
                                    persistDownloads();
                                }
                                feed("File deleted: " + e.name);
                                refreshDownloadsList();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** v3.16 (R8): "Read a file" — the system file picker
     *  (SAF; no permission is asked or needed). The picked
     *  file is COPIED into the store at pick time, so a run
     *  never depends on a grant that may lapse. */
    private void pickFileToRead() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE
                || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        final String pickedName = pickedDisplayName(uri);
        final String pickedMime = getContentResolver().getType(uri);
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] bytes = readPickedBytes(uri);
                if (bytes == null) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this,
                                    "That file could not be read "
                                            + "(or is over the "
                                            + "25 MB limit)",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }
                String mime = (pickedMime != null
                        && pickedMime.length() > 0)
                        ? pickedMime
                        : Downloads.mimeForName(pickedName);
                final String stored = saveStoreBytes(bytes,
                        pickedName, mime, "", "picked");
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (stored == null) {
                            Toast.makeText(MainActivity.this,
                                    "The file could not be saved",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (DocText.isPdf(stored, mime)) {
                            feed("Saved " + stored + " — but "
                                    + DocText.PDF_REFUSAL
                                    + ": goals cannot read it.");
                            Toast.makeText(MainActivity.this,
                                    "Saved — but cannot read text "
                                            + "from PDFs yet",
                                    Toast.LENGTH_LONG).show();
                        } else if (DocText.isReadable(stored, mime)) {
                            feed("Saved " + stored + " — a goal "
                                    + "can read it with read_file.");
                        } else {
                            feed("Saved " + stored + " (goals can "
                                    + "only read txt, md, csv, "
                                    + "html, log and json).");
                        }
                        refreshDownloadsList();
                    }
                });
            }
        }, "gh-pick-file").start();
    }

    /** The picked file's display name (the picker's own
     *  naming), falling back to the URI's last segment. */
    private String pickedDisplayName(android.net.Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null,
                    null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.trim().length() == 0) {
            String seg = uri.getLastPathSegment();
            name = (seg == null) ? "picked file" : seg;
        }
        return Downloads.safeName(name);
    }

    /** Read a picked file's bytes, capped like a download. */
    private byte[] readPickedBytes(android.net.Uri uri) {
        java.io.InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream buf =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > Downloads.MAX_DOWNLOAD_BYTES) {
                    return null;
                }
            }
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** The drawer: a navy panel sliding over a scrim. Its head
     *  is the spectral-hand launcher icon and the wordmark; its
     *  foot is the About line (version + repo), not a screen. */
    private void buildDrawer(FrameLayout shell) {
        drawerLayer = new FrameLayout(this);
        drawerLayer.setVisibility(View.GONE);
        shell.addView(drawerLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(Color.argb(150, 2, 4, 9));
        drawerScrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeDrawer(); }
        });
        drawerLayer.addView(drawerScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setClickable(true);
        GradientDrawable panelBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(8, 14, 28), COL_BG_BOT});
        drawerPanel.setBackground(panelBg);
        drawerPanel.setPadding(dp(16), dp(18), dp(16), dp(14));
        drawerPanel.setTranslationX(-dp(280));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(276), FrameLayout.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.START;
        drawerLayer.addView(drawerPanel, plp);

        // Head: the app icon + wordmark.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager()
                    .getApplicationIcon(getApplicationInfo()));
        } catch (Exception ignored) {
            // No icon is a cosmetic loss, never a crash.
        }
        head.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout headCol = new LinearLayout(this);
        headCol.setOrientation(LinearLayout.VERTICAL);
        TextView headTitle = new TextView(this);
        headTitle.setText("Ghost Hands");
        headTitle.setTextSize(20);
        headTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        headTitle.setTextColor(COL_TEXT);
        headCol.addView(headTitle);
        TextView headSub = new TextView(this);
        headSub.setText("Ethereal on-device agent");
        headSub.setTextSize(11);
        headSub.setTextColor(COL_MUTED);
        headCol.addView(headSub);
        LinearLayout.LayoutParams hclp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hclp.leftMargin = dp(10);
        head.addView(headCol, hclp);
        drawerPanel.addView(head);
        View rule = new View(this);
        rule.setBackgroundColor(COL_CARD_EDGE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        rlp.topMargin = dp(14);
        rlp.bottomMargin = dp(6);
        drawerPanel.addView(rule, rlp);

        // Items — flat, drawer order is NavState's order.
        final String[] labels = {"Agent", "Saved Goals", "Routes",
                "History", "Settings"};
        final int[] screens = {NavState.AGENT, NavState.GOALS,
                NavState.ROUTES, NavState.HISTORY, NavState.SETTINGS};
        drawerItemViews = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int target = screens[i];
            TextView item = new TextView(this);
            item.setText(labels[i]);
            item.setTextSize(15);
            item.setTextColor(COL_TEXT);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    maybeHapticTick(v);
                    showScreen(target);
                }
            });
            drawerItemViews[i] = item;
            drawerPanel.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Foot: the About line (a footer, not a screen).
        drawerPanel.addView(new View(this), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        TextView foot = new TextView(this);
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), 0);
            version = "v" + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) { }
        foot.setText("Ghost Hands " + version
                + "\ngithub.com/littlestjames82-sys/ghost-hands-mobile");
        foot.setTextSize(10);
        foot.setTextColor(COL_MUTED);
        drawerPanel.addView(foot);
    }

    /** Apply the navigation state to the views: exactly one
     *  screen visible, the drawer's highlight on the current
     *  item. Visibility only — nothing is created or destroyed. */
    private void applyScreens() {
        int s = nav.screen();
        agentScreen.setVisibility(s == NavState.AGENT
                ? View.VISIBLE : View.GONE);
        goalsScroll.setVisibility(s == NavState.GOALS
                ? View.VISIBLE : View.GONE);
        routesScroll.setVisibility(s == NavState.ROUTES
                ? View.VISIBLE : View.GONE);
        historyScroll.setVisibility(s == NavState.HISTORY
                ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(s == NavState.SETTINGS
                ? View.VISIBLE : View.GONE);
        if (drawerItemViews != null) {
            final int[] screens = {NavState.AGENT, NavState.GOALS,
                    NavState.ROUTES, NavState.HISTORY,
                    NavState.SETTINGS};
            for (int i = 0; i < drawerItemViews.length; i++) {
                boolean current = screens[i] == s;
                drawerItemViews[i].setTextColor(current
                        ? COL_CYAN : COL_TEXT);
                drawerItemViews[i].setBackground(current
                        ? roundedBg(COL_CARD_INNER, COL_CARD_EDGE, 10)
                        : null);
            }
        }
    }

    /** Navigate: the state decides (NavState.goTo — the drawer
     *  closes behind any selection), the views follow, and the
     *  destination's content refreshes on entry so a list never
     *  shows what a dialog elsewhere changed. */
    private void showScreen(int s) {
        boolean wasDrawerOpen = nav.drawerOpen();
        nav.goTo(s);
        if (s == NavState.GOALS) refreshGoalsList();
        // v3.16 (R8): the page watches ride the Goals screen,
        // the downloads list rides History — same refresh on
        // entry discipline.
        if (s == NavState.GOALS) refreshPageWatchList();
        if (s == NavState.ROUTES) refreshRoutesList();
        if (s == NavState.HISTORY) refreshHistoryList();
        if (s == NavState.HISTORY) refreshDownloadsList();
        // v3.8: the lifetime stats are computed when the
        // Settings screen is shown (the readout's promise).
        if (s == NavState.SETTINGS) refreshLifetimeStats();
        // v3.14 (R6): the spike's gate line too — same promise.
        if (s == NavState.SETTINGS) refreshSpikeGate();
        applyScreens();
        if (wasDrawerOpen) renderDrawer();
    }

    private void openDrawer() {
        if (nav.drawerOpen()) return;
        nav.openDrawer();
        renderDrawer();
    }

    private void closeDrawer() {
        if (!nav.drawerOpen()) return;
        nav.closeDrawer();
        renderDrawer();
    }

    /** Slide the panel and fade the scrim to match the state.
     *  The layer hides only once a closing slide has finished —
     *  and only if it is still closing (a reopen mid-slide wins). */
    private void renderDrawer() {
        if (drawerLayer == null) return;
        // v3.7 (reduce motion): read at render time — the
        // drawer snaps to its state instead of sliding, layer
        // bookkeeping unchanged (a closing snap hides the
        // layer at once, where the slide hid it at its end).
        boolean still = settings != null && settings.reduceMotion;
        if (nav.drawerOpen()) {
            drawerShown = true;
            drawerLayer.setVisibility(View.VISIBLE);
            if (still) {
                drawerScrim.setAlpha(1f);
                drawerPanel.setTranslationX(0f);
                return;
            }
            drawerScrim.setAlpha(0f);
            drawerPanel.setTranslationX(-dp(280));
            drawerScrim.animate().alpha(1f).setDuration(180).start();
            drawerPanel.animate().translationX(0f).setDuration(230)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else {
            drawerShown = false;
            if (still) {
                drawerPanel.setTranslationX(-dp(280));
                drawerLayer.setVisibility(View.GONE);
                return;
            }
            drawerScrim.animate().alpha(0f).setDuration(160).start();
            drawerPanel.animate().translationX(-dp(280))
                    .setDuration(200)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (!drawerShown) {
                                drawerLayer.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }
    }

    /** Back: the resolution is NavState's (drawer first, then
     *  home, then the system). On the Agent screen with the
     *  drawer closed the press passes through — exactly what
     *  Back did before the drawer existed. */
    @Override
    public void onBackPressed() {
        int action = nav.back();
        if (action == NavState.BACK_CLOSE_DRAWER) {
            renderDrawer();
            return;
        }
        if (action == NavState.BACK_TO_AGENT) {
            applyScreens();
            return;
        }
        super.onBackPressed();
    }

    /** The left-edge swipe that opens the drawer: a rightward
     *  drag that starts within the edge strip and outruns its
     *  vertical drift. Tracked at dispatch so nothing is ever
     *  consumed unless the gesture proves itself a drawer swipe
     *  — taps, scrolls and WebView drags pass untouched. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!nav.drawerOpen()) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                edgeTracking = ev.getX() <= dp(24);
                edgeFired = false;
                edgeDownX = ev.getX();
                edgeDownY = ev.getY();
            } else if (a == MotionEvent.ACTION_MOVE && edgeTracking
                    && !edgeFired) {
                float dx = ev.getX() - edgeDownX;
                float dy = Math.abs(ev.getY() - edgeDownY);
                if (dx > dp(56) && dx > 1.6f * dy) {
                    edgeFired = true;
                    openDrawer();
                    return true;
                }
            } else if (a == MotionEvent.ACTION_UP
                    || a == MotionEvent.ACTION_CANCEL) {
                edgeTracking = false;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ---------------- WebView setup ----------------

    private void setupWebView() {
        configureWebView(webView);
    }

    /** v3.15 (R7): everything one tab's WebView needs — the
     *  settings, the clients, the listeners — factored out of
     *  the old single-view setup so every tab is configured
     *  identically. The client callbacks judge their events
     *  against the ACTIVE view (view == webView): a background
     *  tab's late page events must not touch the load lock or
     *  the url bar of the page in front. (Dialogs need no such
     *  guard: a paused, hidden view runs no JS to raise one.) */
    private void configureWebView(final WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // v3.6 (desktop mode): capture the WebView's own default
        // user agent BEFORE anything changes it — desktop OFF
        // restores exactly this string — then put the persisted
        // choice in force for the pages loaded from launch on.
        // v3.15: the capture happens on the first view only
        // (the value is identical across views); the choice is
        // put in force on THIS view, whichever tab it is.
        if (defaultUserAgent == null) {
            defaultUserAgent = s.getUserAgentString();
        }
        if (settings != null) {
            s.setUserAgentString(Settings.userAgentFor(
                    settings.desktopMode, defaultUserAgent));
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (view == webView) pageLoading = true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != webView) return;
                pageLoading = false;
                synchronized (loadLock) { loadLock.notifyAll(); }
                urlEdit.setText(url);
                // v3.15 (R7): the active tab's chip follows
                // its page (a navigation can change its host).
                refreshTabStrip();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false; // stay in our WebView
            }
        });

        // Dialog bridge on day one (house lesson): without a
        // WebChromeClient, alert/confirm/prompt are silently swallowed.
        wv.setWebChromeClient(new WebChromeClient() {
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
        wv.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_UP
                        && (teaching || assistWaiting)) {
                    onPageTapped(ev.getX(), ev.getY());
                }
                return false;
            }
        });

        // v3.16 (R8 — governed downloads): what a page offers
        // to download is now a governed decision, not a
        // footnote. The listener hands the offer to
        // onPageDownload, which judges it (Downloads.decide)
        // before a byte is fetched; the blob bridge below is
        // how blob: offers are fetched at all (see BlobBridge).
        // Both are per-view, like everything else here.
        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                onPageDownload(wv, url, userAgent,
                        contentDisposition, mimeType, contentLength);
            }
        });
        wv.addJavascriptInterface(new BlobBridge(), "GHFileBridge");
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

    // ---------------- v3.16: governed downloads (R8) ----------------
    // A page's download offer is judged before a byte moves:
    // Downloads.decide under the pack in force (the run's,
    // while a run is live; the settings default otherwise).
    // ALLOW fetches at once; ASK waits on the approval panel
    // from a worker thread (the listener itself runs on the
    // UI thread and must never block it) with silence counting
    // as a denial; DENY fetches nothing. Every outcome is
    // trail-recorded with filename, byte size and MIME.
    //
    // Three transports, one save path:
    //  - http(s): re-fetched on a worker thread with the
    //    WebView's own cookies and the offered user agent.
    //  - data:: decoded in place (Downloads.decodeDataUrl).
    //  - blob:: the page's own JS fetches the blob and hands
    //    it over the GHFileBridge in base64 chunks. The bridge
    //    is TOKEN-GATED: it accepts chunks only for the one
    //    download the app just armed (a random one-shot token,
    //    a 120-second life), so a page cannot push files
    //    through it uninvited — an offer still has to arrive
    //    through the download listener and pass the Governor
    //    first. One blob download at a time; a second offer
    //    while one is in flight is refused as busy.
    // Limits, stated plainly: 25 MB per file on every path
    // (refused up front when the size is known, cut off
    // mid-stream when it is not); the store keeps at most
    // 30 files / 100 MB (Downloads' retention, oldest first);
    // an http re-fetch follows the platform's redirects and
    // sends the WebView's cookies for that URL — a download
    // the server personalizes per POST cannot be replayed
    // this way and will fail honestly rather than save the
    // error page (the content type is checked against the
    // offer's: a text/html answer to a non-html offer is a
    // failed download, not a saved page).

    /** The pack a download is judged under, resolved as a
     *  whole (decision and approval leash from the same
     *  pack): the run's while a run is live, the settings
     *  default otherwise. */
    private PolicyPacks.Pack downloadPack() {
        if (running && pack != null) return pack;
        return PolicyPacks.loadPack(settings != null
                ? settings.defaultPackId : "standard");
    }

    private File downloadsDir() {
        File dir = new File(getFilesDir(), Downloads.DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void persistDownloads() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(Downloads.PREF_DOWNLOADS,
                        downloads == null ? "[]"
                                : downloads.serialize())
                .apply();
    }

    /** The trail a download records into: the live run's own
     *  trail while a run is going (step = the run's current
     *  step), the downloads store's standing trail otherwise. */
    private Trail downloadTrail() {
        if (running && currentTrail != null) return currentTrail;
        if (idleDownloadTrail == null) {
            idleDownloadTrail = new Trail(new File(downloadsDir(),
                    "downloads-trail.jsonl"));
        }
        return idleDownloadTrail;
    }

    private void recordDownload(String name, String mime, long bytes,
                                  String url, Governor.Decision decision,
                                  String status, Boolean approved,
                                  String note) {
        try {
            Map<String, Object> fields = Trail.fields(
                    "filename", name,
                    "mime", mime == null ? "" : mime,
                    "bytes", Long.valueOf(bytes),
                    "url", url == null ? "" : url,
                    "class", decision == null ? ""
                            : decision.classification,
                    "outcome", decision == null ? ""
                            : decision.outcome,
                    "status", status);
            if (approved != null) {
                fields.put("approved", approved);
            }
            if (note != null && note.length() > 0) {
                fields.put("note", note);
            }
            int step = (agent != null) ? agent.currentStep : 0;
            downloadTrail().record("download", step, fields);
        } catch (Exception ignored) {
            // The record never breaks the download.
        }
    }

    /** The download listener's landing (UI thread): judge the
     *  offer, then act on the judgement. */
    private void onPageDownload(final WebView src, final String url,
                                final String userAgent,
                                final String contentDisposition,
                                final String mimeType,
                                final long contentLength) {
        if (downloads == null) return;
        String guessed = android.webkit.URLUtil.guessFileName(
                url, contentDisposition, mimeType);
        final String name = Downloads.safeName(guessed);
        final String mime = (mimeType != null
                && mimeType.trim().length() > 0)
                ? mimeType.trim()
                : Downloads.mimeForName(name);
        final PolicyPacks.Pack judgePack = downloadPack();
        final Governor.Decision decision = Downloads.decide(
                judgePack.policy, name, mime, url);
        if (Governor.DENY.equals(decision.outcome)) {
            recordDownload(name, mime, contentLength, url, decision,
                    "denied", null, decision.reason);
            feed("Download denied by policy: " + name
                    + " (" + decision.reason + ")");
            Toast.makeText(this, "Download denied: " + name,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Governor.ASK.equals(decision.outcome)) {
            // The wait happens off the UI thread (see the
            // section head): queue behind any live approval,
            // then the shared panel and latch.
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean approved = awaitDownloadApproval(
                            judgePack, decision, name, mime,
                            contentLength, url);
                    if (!approved) {
                        recordDownload(name, mime, contentLength, url,
                                decision, "denied",
                                Boolean.FALSE, "not approved");
                        feed("Download not approved: " + name);
                        return;
                    }
                    startDownloadFetch(src, url, userAgent, name,
                            mime, contentLength, decision,
                            Boolean.TRUE);
                }
            }, "gh-download-approval").start();
            return;
        }
        startDownloadFetch(src, url, userAgent, name, mime,
                contentLength, decision, null);
    }

    /** A download's approval wait: behind the download
     *  approval lock (one download ask at a time) and behind
     *  any live approval of the run's (approvalBusy), then
     *  the shared handshake — panel, latch, the judging
     *  pack's leash, silence as a denial. The remember
     *  checkbox is never offered (see awaitApproval). */
    private final Object downloadApprovalLock = new Object();

    private boolean awaitDownloadApproval(PolicyPacks.Pack judgePack,
                                            Governor.Decision decision,
                                            String name, String mime,
                                            long bytes, String url) {
        synchronized (downloadApprovalLock) {
            long leashMs = (long) (judgePack.approvalTimeout * 1000.0);
            if (leashMs <= 0) leashMs = 600000L;
            long waited = 0;
            while (approvalBusy && waited < leashMs) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                waited += 200;
            }
            if (approvalBusy) return false;
            return awaitApproval(null, decision,
                    Downloads.describe(name, mime, bytes, url,
                            decision),
                    false, judgePack.approvalTimeout);
        }
    }

    /** Begin the fetch for an allowed (or approved) download,
     *  on the transport its URL names. The known-size gate
     *  stands here, ahead of every transport. */
    private void startDownloadFetch(final WebView src, final String url,
                                    final String userAgent,
                                    final String name, final String mime,
                                    final long contentLength,
                                    final Governor.Decision decision,
                                    final Boolean approved) {
        if (contentLength > Downloads.MAX_DOWNLOAD_BYTES) {
            recordDownload(name, mime, contentLength, url, decision,
                    "failed", approved,
                    "larger than the 25 MB limit");
            feed("Download too large (over 25 MB): " + name);
            return;
        }
        if (Downloads.isData(url)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    Downloads.DataUrl d = Downloads.decodeDataUrl(url);
                    if (d == null) {
                        recordDownload(name, mime, contentLength, url,
                                decision, "failed", approved,
                                "could not decode the data: URL");
                        feed("Download failed (unreadable data): "
                                + name);
                        return;
                    }
                    saveDownloadBytes(d.bytes, name,
                            d.mime.length() > 0 ? d.mime : mime,
                            url, decision, approved);
                }
            }, "gh-download-data").start();
            return;
        }
        if (Downloads.isBlob(url)) {
            armBlobDownload(src, url, name, mime, contentLength,
                    decision, approved);
            return;
        }
        if (Downloads.isHttp(url)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    fetchHttpDownload(url, userAgent, name, mime,
                            decision, approved);
                }
            }, "gh-download-http").start();
            return;
        }
        recordDownload(name, mime, contentLength, url, decision,
                "failed", approved,
                "unsupported download address");
        feed("Download failed (unsupported address): " + name);
    }

    /** The http(s) transport: a plain GET on a worker thread,
     *  the WebView's cookies riding along, the size cap
     *  enforced as the bytes arrive. */
    private void fetchHttpDownload(String url, String userAgent,
                                   String name, String mime,
                                   Governor.Decision decision,
                                   Boolean approved) {
        java.io.InputStream in = null;
        try {
            java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection)
                            new java.net.URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (userAgent != null && userAgent.length() > 0) {
                conn.setRequestProperty("User-Agent", userAgent);
            }
            String cookies = android.webkit.CookieManager
                    .getInstance().getCookie(url);
            if (cookies != null && cookies.length() > 0) {
                conn.setRequestProperty("Cookie", cookies);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                recordDownload(name, mime, -1, url, decision,
                        "failed", approved,
                        "the server answered " + code);
                feed("Download failed (server " + code + "): " + name);
                return;
            }
            // A non-html offer answered with an html page is a
            // login wall or an error page wearing the file's
            // name — fail honestly rather than save it.
            String servedType = Downloads.bareMime(
                    conn.getContentType());
            if (servedType.length() > 0
                    && "text/html".equals(servedType)
                    && !"text/html".equals(
                            Downloads.bareMime(mime))) {
                recordDownload(name, mime, -1, url, decision,
                        "failed", approved,
                        "the server answered a web page, not the file");
                feed("Download failed (the site sent a page, "
                        + "not the file): " + name);
                return;
            }
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream buf =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[65536];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > Downloads.MAX_DOWNLOAD_BYTES) {
                    recordDownload(name, mime, -1, url, decision,
                            "failed", approved,
                            "larger than the 25 MB limit");
                    feed("Download too large (over 25 MB): " + name);
                    return;
                }
            }
            saveDownloadBytes(buf.toByteArray(), name,
                    servedType.length() > 0 ? servedType : mime,
                    url, decision, approved);
        } catch (Exception e) {
            recordDownload(name, mime, -1, url, decision, "failed",
                    approved, "fetch failed: " + e.getMessage());
            feed("Download failed: " + name + " ("
                    + e.getMessage() + ")");
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) { }
            }
        }
    }

    /** The one save path every transport lands on: a unique
     *  name in the store, the bytes on disk (via a .part file
     *  renamed into place), the store entry, retention, the
     *  trail record, and the word to the user. */
    private void saveDownloadBytes(byte[] bytes, String name,
                                   String mime, String sourceUrl,
                                   Governor.Decision decision,
                                   Boolean approved) {
        String stored = saveStoreBytes(bytes, name, mime,
                sourceUrl, "download");
        if (stored == null) {
            recordDownload(name, mime,
                    bytes == null ? -1 : bytes.length,
                    sourceUrl, decision, "failed", approved,
                    "could not save the file");
            return;
        }
        recordDownload(stored, mime,
                bytes == null ? -1 : bytes.length,
                sourceUrl, decision, "saved", approved, null);
        feed("Downloaded " + stored + " ("
                + Downloads.sizeLabel(bytes == null ? -1
                        : bytes.length)
                + ") — find it under History");
    }

    /** Write bytes into the downloads store under a unique
     *  name and register the entry; returns the stored name,
     *  or null when the write failed. Shared by downloads and
     *  by the file picker's copy-in. */
    private String saveStoreBytes(byte[] bytes, String name,
                                  String mime, String sourceUrl,
                                  String origin) {
        if (bytes == null) return null;
        try {
            java.util.Set<String> existing =
                    new java.util.HashSet<String>();
            if (downloads != null) {
                for (Downloads.Entry e : downloads.all()) {
                    existing.add(e.name);
                }
            }
            File dir = downloadsDir();
            File[] present = dir.listFiles();
            if (present != null) {
                for (int i = 0; i < present.length; i++) {
                    existing.add(present[i].getName());
                }
            }
            String stored = Downloads.uniqueName(existing, name);
            File part = new File(dir, stored + ".part");
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(part);
            out.write(bytes);
            out.close();
            File dest = new File(dir, stored);
            if (!part.renameTo(dest)) {
                java.io.FileOutputStream out2 =
                        new java.io.FileOutputStream(dest);
                out2.write(bytes);
                out2.close();
                part.delete();
            }
            Downloads.Entry e = new Downloads.Entry();
            e.name = stored;
            e.mime = (mime == null) ? "" : mime;
            e.bytes = bytes.length;
            e.tsMs = System.currentTimeMillis();
            e.origin = origin;
            e.sourceUrl = (sourceUrl == null) ? "" : sourceUrl;
            if (downloads != null) {
                downloads.put(e);
                pruneDownloads();
                persistDownloads();
            }
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    refreshDownloadsList();
                }
            });
            return stored;
        } catch (Exception ex) {
            feed("Saving the file failed: " + name + " ("
                    + ex.getMessage() + ")");
            return null;
        }
    }

    /** Retention (Downloads.excess): the oldest files past
     *  the count or byte cap leave, files and entries
     *  together. */
    private void pruneDownloads() {
        if (downloads == null) return;
        List<Downloads.Entry> excess = downloads.excess();
        for (int i = 0; i < excess.size(); i++) {
            Downloads.Entry e = excess.get(i);
            new File(downloadsDir(), e.name).delete();
            downloads.remove(e.name);
        }
    }

    // ---------------- the blob bridge ----------------

    /** One armed blob download: the offer that was judged,
     *  the token that alone may feed it, and the bytes so
     *  far. All bridge calls check the token and the expiry;
     *  a call that matches neither is ignored in silence (a
     *  page poking the bridge learns nothing). */
    private static class BlobJob {
        String token;
        String name;
        String mime;
        String url;
        Governor.Decision decision;
        Boolean approved;
        long expiresMs;
        final java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream();
        boolean dead = false;
    }

    /** The GHFileBridge: chunk / done / fail, called by the
     *  injected fetch script on a binder thread. Nothing here
     *  trusts the page: the token is the whole credential,
     *  and it exists only because a judged offer armed it. */
    private class BlobBridge {
        @android.webkit.JavascriptInterface
        public void chunk(String token, String base64) {
            BlobJob job = liveBlobJob(token);
            if (job == null) return;
            try {
                byte[] data = android.util.Base64.decode(base64,
                        android.util.Base64.DEFAULT);
                synchronized (job) {
                    job.buf.write(data, 0, data.length);
                    if (job.buf.size()
                            > Downloads.MAX_DOWNLOAD_BYTES) {
                        job.dead = true;
                    }
                }
                if (job.dead) {
                    blobJob = null;
                    recordDownload(job.name, job.mime, -1, job.url,
                            job.decision, "failed", job.approved,
                            "larger than the 25 MB limit");
                    feed("Download too large (over 25 MB): "
                            + job.name);
                }
            } catch (Exception ignored) {
                // A malformed chunk kills nothing; the done
                // that never comes is the failure, on expiry.
            }
        }

        @android.webkit.JavascriptInterface
        public void done(String token) {
            BlobJob job = liveBlobJob(token);
            if (job == null) return;
            blobJob = null;
            byte[] bytes;
            synchronized (job) {
                bytes = job.buf.toByteArray();
            }
            saveDownloadBytes(bytes, job.name, job.mime, job.url,
                    job.decision, job.approved);
        }

        @android.webkit.JavascriptInterface
        public void fail(String token, String message) {
            BlobJob job = liveBlobJob(token);
            if (job == null) return;
            blobJob = null;
            recordDownload(job.name, job.mime, -1, job.url,
                    job.decision, "failed", job.approved,
                    "the page could not produce the file");
            feed("Download failed: " + job.name);
        }
    }

    /** The armed job when the token is its and it has not
     *  expired; an expired job is cleared and reads as none. */
    private BlobJob liveBlobJob(String token) {
        BlobJob job = blobJob;
        if (job == null || token == null
                || !job.token.equals(token)) {
            return null;
        }
        if (System.currentTimeMillis() > job.expiresMs) {
            blobJob = null;
            recordDownload(job.name, job.mime, -1, job.url,
                    job.decision, "failed", job.approved,
                    "the page never delivered the file");
            return null;
        }
        return job;
    }

    /** Arm the one blob job and inject the fetch script into
     *  the offering view. A second offer while one is armed
     *  (and alive) is refused as busy, in the open. */
    private void armBlobDownload(final WebView src, final String url,
                                 final String name, final String mime,
                                 final long contentLength,
                                 final Governor.Decision decision,
                                 final Boolean approved) {
        if (blobJob != null
                && System.currentTimeMillis() > blobJob.expiresMs) {
            BlobJob stale = blobJob;
            blobJob = null;
            recordDownload(stale.name, stale.mime, -1, stale.url,
                    stale.decision, "failed", stale.approved,
                    "the page never delivered the file");
        }
        BlobJob live = blobJob;
        if (live != null) {
            recordDownload(name, mime, contentLength, url, decision,
                    "failed", approved,
                    "another file is still arriving");
            feed("Download refused: another file is still "
                    + "arriving (" + live.name + ")");
            return;
        }
        final BlobJob job = new BlobJob();
        job.token = Long.toHexString(
                new java.security.SecureRandom().nextLong())
                + Long.toHexString(System.nanoTime());
        job.name = name;
        job.mime = mime;
        job.url = url;
        job.decision = decision;
        job.approved = approved;
        job.expiresMs = System.currentTimeMillis() + 120000;
        blobJob = job;
        final String js = blobFetchJs(job.token, url);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    src.evaluateJavascript(js, null);
                } catch (Exception e) {
                    if (blobJob == job) blobJob = null;
                    recordDownload(name, mime, contentLength, url,
                            decision, "failed", approved,
                            "the page could not be asked for the file");
                }
            }
        });
    }

    /** The injected blob fetch: pull the blob, hand it over
     *  in ~192 KB base64 chunks, then say done (or fail).
     *  Syntax-validated against the same script shape in the
     *  release's host checks; the token and URL are escaped
     *  in (jsEscape) — both are data here, never code. */
    private static String blobFetchJs(String token, String url) {
        return "(function(){"
                + "var tok=\"" + jsEscape(token) + "\","
                + "u=\"" + jsEscape(url) + "\";"
                + "function fail(m){try{GHFileBridge.fail(tok,"
                + "String(m));}catch(e){}}"
                + "fetch(u).then(function(r){return r.arrayBuffer();})"
                + ".then(function(buf){"
                + "var b=new Uint8Array(buf),CH=196608,off=0;"
                + "function step(){"
                + "if(off>=b.length){GHFileBridge.done(tok);return;}"
                + "var end=Math.min(off+CH,b.length),s=\"\";"
                + "for(var i=off;i<end;i+=32768){"
                + "s+=String.fromCharCode.apply(null,"
                + "b.subarray(i,Math.min(i+32768,end)));}"
                + "GHFileBridge.chunk(tok,btoa(s));"
                + "off=end;setTimeout(step,0);}"
                + "step();"
                + "}).catch(function(e){fail(e);});"
                + "})()";
    }

    /** Escape a string for a double-quoted JS literal. */
    private static String jsEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------- v3.15: the tab machinery (R7) ----------------
    // UI-thread territory: every method in this section runs on
    // (or posts to) the UI thread. The agent thread reaches it
    // through the WebHands tab operations (runOnUiAndWait).

    /** A fresh tab's view: created hidden and paused; it is
     *  shown and resumed only when it becomes the active tab. */
    private WebView createTabView() {
        WebView wv = new WebView(this);
        wv.setBackground(roundedBg(Color.rgb(6, 10, 20), 0, 10));
        wv.setClipToOutline(true);
        wv.setVisibility(View.GONE);
        return wv;
    }

    /** Make tab idx the active tab. MEMORY POLICY: the old
     *  view is onPause'd and hidden — its rendering, JS timers
     *  and network settle stop, which is what lets three tabs
     *  live beside the 1.5B model; its page state (DOM, scroll
     *  position, back/forward history) is kept by the view
     *  itself, which is what a tab IS. The webView field
     *  follows the activation, so the hands, teach, the url
     *  bar and the screenshots all land on the newly active
     *  tab without knowing tabs exist. */
    private void activateTab(int idx) {
        if (idx < 0 || idx >= tabViews.size()) return;
        WebView old = webView;
        WebView next = tabViews.get(idx);
        if (old != null && old != next) {
            old.onPause();
            old.setVisibility(View.GONE);
        }
        activeTabIdx = idx;
        webView = next;
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
        String u = webView.getUrl();
        if (urlEdit != null) urlEdit.setText(u == null ? "" : u);
        refreshTabStrip();
    }

    /** Open a new tab (at url when one is given), configured
     *  like every tab, and make it active. Returns its 0-based
     *  index, or -1 when the cap (RelevanceGuard.TAB_CAP) is
     *  already reached. */
    private int openTabInternal(String url) {
        if (tabViews.size() >= RelevanceGuard.TAB_CAP) return -1;
        WebView wv = createTabView();
        configureWebView(wv);
        tabViews.add(wv);
        webHost.addView(wv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        activateTab(tabViews.size() - 1);
        if (url != null && url.length() > 0) {
            wv.loadUrl(url.contains("://") ? url : "https://" + url);
        }
        refreshTabStrip();
        return tabViews.size() - 1;
    }

    /** Close tab idx and destroy its view (destroying frees
     *  the renderer the paused view was holding). The tab
     *  that takes its place — or the previous one, at the
     *  end — becomes active when the closed tab was. The
     *  last tab never closes. */
    private void closeTabInternal(int idx) {
        if (idx < 0 || idx >= tabViews.size()
                || tabViews.size() <= 1) return;
        WebView wv = tabViews.remove(idx);
        webHost.removeView(wv);
        wv.destroy();
        if (idx < activeTabIdx) {
            activeTabIdx--;
        } else if (idx == activeTabIdx) {
            activeTabIdx = Math.min(idx, tabViews.size() - 1);
            webView = tabViews.get(activeTabIdx);
            webView.setVisibility(View.VISIBLE);
            webView.onResume();
            String u = webView.getUrl();
            if (urlEdit != null) urlEdit.setText(u == null ? "" : u);
        }
        refreshTabStrip();
    }

    /** The strip chip's name for a tab: its host, or "blank"
     *  for a tab with no page yet. */
    private static String tabChipLabel(int idx, String url) {
        String host = (url == null) ? "" : SiteGrants.hostOf(url);
        if (host == null || host.length() == 0) host = "blank";
        return (idx + 1) + " " + host;
    }

    /** Rebuild the tab strip: one chip per open tab (number +
     *  host; the active one lit, with an x that closes it)
     *  and a + chip while under the cap. DURING A RUN (or
     *  while teaching) the whole strip is disabled and
     *  dimmed: manual tab taps would move the active tab out
     *  from under the agent mid-step, and the v3.5 invariant
     *  — a run in progress is never disturbed — wins over
     *  manual inspection. The agent's OWN tab actions are not
     *  the strip's business and keep working; a run that ends
     *  leaves its tabs as they are for the user's inspection. */
    private void refreshTabStrip() {
        if (tabStrip == null) return;
        tabStrip.removeAllViews();
        final boolean locked = running || teaching;
        for (int i = 0; i < tabViews.size(); i++) {
            final int idx = i;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(roundedBg(
                    idx == activeTabIdx ? COL_CARD : COL_CARD_INNER,
                    idx == activeTabIdx ? COL_CYAN : COL_CARD_EDGE, 10));
            chip.setPadding(dp(8), dp(2), dp(4), dp(2));
            LinearLayout.LayoutParams chipLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp(6);
            TextView label = new TextView(this);
            label.setText(tabChipLabel(idx, tabViews.get(i).getUrl()));
            label.setTextSize(11);
            label.setTextColor(idx == activeTabIdx
                    ? COL_TEXT : COL_MUTED);
            label.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    activateTab(idx);
                }
            });
            chip.addView(label);
            if (tabViews.size() > 1) {
                TextView x = new TextView(this);
                x.setText(" × ");
                x.setTextSize(12);
                x.setTextColor(COL_MUTED);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        closeTabInternal(idx);
                    }
                });
                chip.addView(x);
            }
            tabStrip.addView(chip, chipLp);
        }
        if (tabViews.size() < RelevanceGuard.TAB_CAP) {
            TextView plus = new TextView(this);
            plus.setText(" + ");
            plus.setTextSize(13);
            plus.setTextColor(COL_CYAN);
            plus.setBackground(roundedBg(COL_CARD_INNER,
                    COL_CARD_EDGE, 10));
            plus.setPadding(dp(6), dp(2), dp(6), dp(2));
            plus.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openTabInternal(null);
                }
            });
            tabStrip.addView(plus);
        }
        tabStrip.setAlpha(locked ? 0.35f : 1.0f);
        setViewsEnabled(tabStrip, !locked);
    }

    /** Enable/disable a view tree (the strip's run lock). */
    private void setViewsEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setViewsEnabled(g.getChildAt(i), enabled);
            }
        }
    }

    /** Run r on the UI thread and wait (bounded) for it — the
     *  agent thread's bridge to the tab machinery, in callJs's
     *  latch discipline. */
    private void runOnUiAndWait(Runnable r) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final Runnable task = r;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    task.run();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        refreshModelManager();
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

    /** v3.8 (quiet hours): one bound's 24 hour chips, four
     *  rows of six (a single row of 24 cannot fit a phone).
     *  The chip at index h names hour h; refreshChoices marks
     *  the stored hour. Built once; the box they live in shows
     *  and hides with the quiet-hours switch. */
    private Button[] hourChips(LinearLayout parent,
                               final boolean startBound) {
        Button[] chips = new Button[24];
        for (int r = 0; r < 4; r++) {
            LinearLayout row = sectionRow(parent);
            for (int c = 0; c < 6; c++) {
                final int hour = r * 6 + c;
                chips[hour] = rowChip(row, Settings.hourLabel(hour),
                        c == 0);
                chips[hour].setOnClickListener(
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                if (startBound) {
                                    onQuietStartPicked(hour);
                                } else {
                                    onQuietEndPicked(hour);
                                }
                            }
                        });
            }
        }
        return chips;
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
        // v3.6: the second wave mirrors the same way.
        refreshChoices(threadChips, indexOfInt(
                Settings.THREADS_CHOICES, settings.threads));
        refreshChoices(fallbackChips, indexOfStr(
                SearchEngines.NAMES, settings.fallbackEngine));
        refreshToggle(desktopBtn, "Desktop mode", settings.desktopMode);
        refreshToggle(needsMeBtn, "Needs-me notifications",
                settings.needsMeOn);
        refreshToggle(liveStatsBtn, "Live stats", settings.liveStatsOn);
        refreshToggle(prefixCacheBtn, "Prefix cache",
                settings.prefixCacheOn);
        refreshChoices(trailChips, indexOfInt(
                Settings.TRAILS_CHOICES, settings.trailsKept));
        // v3.7: the third wave mirrors the same way.
        refreshChoices(accentChips, indexOfStr(
                Settings.ACCENT_IDS, settings.accentId));
        refreshChoices(feedTextChips, settings.feedTextSize);
        refreshToggle(reduceMotionBtn, "Reduce motion",
                settings.reduceMotion);
        refreshToggle(autoScrollBtn, "Auto-scroll feed",
                settings.autoScrollOn);
        refreshChoices(endViewChips, settings.endView);
        refreshChoices(styleChips, settings.plannerStyle);
        refreshChoices(speechRateChips, settings.speechRate);
        refreshToggle(stepShotsBtn, "Step screenshots",
                settings.stepShotsOn);
        // v3.8: the fourth wave mirrors the same way.
        refreshToggle(screenAwakeBtn, "Keep screen awake during runs",
                settings.screenAwakeOn);
        refreshToggle(batteryWarnBtn, "Low-battery start warning",
                settings.batteryWarnOn);
        refreshToggle(finishBeepBtn, "Finish beep",
                settings.finishBeepOn);
        refreshToggle(quietBtn, "Watch quiet hours",
                settings.quietHoursOn);
        refreshChoices(quietStartChips, settings.quietStartHour);
        refreshChoices(quietEndChips, settings.quietEndHour);
        if (quietBox != null) {
            quietBox.setVisibility(settings.quietHoursOn
                    ? View.VISIBLE : View.GONE);
        }
        refreshToggle(hapticsBtn, "Button haptics",
                settings.hapticsOn);
        // v3.14 (R6): the spike's experimental toggle mirrors
        // the same way.
        refreshToggle(a11ySpikeBtn,
                "Accessibility spike (experimental)",
                settings.a11ySpikeOn);
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

    // ---- v3.6 handlers: mutate the model, persist, refresh ----

    /** Threads: the engine hears the new count at once, but it
     *  is a load-time value — a loaded model keeps its own count
     *  until the next load (a brain switch, a restart). */
    private void onThreadsPicked(int th) {
        settings.threads = th;
        engine.setThreads(th);
        persistSettings();
        refreshSettingsUi();
        feed("CPU threads: " + th
                + " (applies at the next model load).");
    }

    private void onFallbackEnginePicked(String id) {
        settings.fallbackEngine = id;
        persistSettings();
        refreshSettingsUi();
        feed("Fallback search engine: "
                + SearchEngines.displayName(id)
                + " (from the next run).");
    }

    /** Desktop mode: flips the WebView's user agent at once —
     *  pages loaded after the change arrive in the new form;
     *  the page already open is not reloaded. */
    private void onDesktopToggled() {
        settings.desktopMode = !settings.desktopMode;
        persistSettings();
        refreshSettingsUi();
        applyDesktopMode();
        feed(settings.desktopMode
                ? "Desktop mode: ON — pages loaded from now on "
                        + "are the desktop form."
                : "Desktop mode: OFF — pages loaded from now on "
                        + "are the mobile form, as before.");
    }

    private void onNeedsMeToggled() {
        settings.needsMeOn = !settings.needsMeOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onLiveStatsToggled() {
        settings.liveStatsOn = !settings.liveStatsOn;
        persistSettings();
        refreshSettingsUi();
    }

    /** Prefix cache: like grammar, the engine reads the switch
     *  per generate call, so the next decide of even a running
     *  agent obeys it; runs record the value captured at Start. */
    private void onPrefixCacheToggled() {
        settings.prefixCacheOn = !settings.prefixCacheOn;
        engine.setPrefixCacheOn(settings.prefixCacheOn);
        persistSettings();
        refreshSettingsUi();
        feed(settings.prefixCacheOn
                ? "Prefix cache: ON (the v1.5 behaviour)."
                : "Prefix cache: OFF — every step pays full prompt "
                        + "evaluation (pre-v1.5 behaviour).");
    }

    private void onTrailsKeptPicked(int kept) {
        settings.trailsKept = kept;
        persistSettings();
        refreshSettingsUi();
    }

    /** v3.6 (desktop mode): put the settings' user-agent choice
     *  on the WebView. The wide viewport and overview mode are
     *  deliberately untouched — they have been on since v1 in
     *  both modes; the UA string is desktop mode's whole delta.
     *  Changing the UA reloads nothing: pages loaded afterwards
     *  are the ones that change form. */
    private void applyDesktopMode() {
        if (webView == null || settings == null) return;
        String ua = Settings.userAgentFor(settings.desktopMode,
                defaultUserAgent);
        // v3.15 (R7): the choice reaches every open tab, not
        // just the active one — a background tab must not wake
        // up wearing the old mode's agent.
        if (tabViews.isEmpty()) {
            webView.getSettings().setUserAgentString(ua);
            return;
        }
        for (int i = 0; i < tabViews.size(); i++) {
            tabViews.get(i).getSettings().setUserAgentString(ua);
        }
    }

    // ---------------- v3.6: clear-data actions ----------------
    // Two wipes, each behind a confirm dialog that names exactly
    // what will be deleted, each reporting its result on the
    // line under its button. Irreversible by design — the
    // dialogs say so. Scope is DataWipe's structural promise:
    // the file wipe is handed only the trails and shots
    // directories; the goals/routes wipe touches only the two
    // preference stores (and their alarms). Models, settings
    // and the other store survive each action.

    private File trailsDir() {
        return new File(getFilesDir(), "trails");
    }

    private File shotsDir() {
        return new File(getFilesDir(), Shots.DIR_NAME);
    }

    private void onClearTrailsAndShots() {
        final int nTrails = DataWipe.countFiles(trailsDir());
        final int nShots = DataWipe.countFiles(shotsDir());
        if (nTrails == 0 && nShots == 0) {
            clearTrailsResult.setText("Nothing to clear — no "
                    + "trails or screenshots are stored.");
            return;
        }
        // A run in flight is still writing its trail: it is
        // protected by name (DataWipe skips it), and the result
        // line says so.
        final boolean protectOpen = running && currentTrail != null
                && currentTrail.getPath() != null;
        new AlertDialog.Builder(this)
                .setTitle("Clear trails and screenshots")
                .setMessage("Delete " + nTrails + " trail file"
                        + (nTrails == 1 ? "" : "s") + " and " + nShots
                        + " screenshot" + (nShots == 1 ? "" : "s")
                        + "? This cannot be undone."
                        + (protectOpen
                                ? " The run in progress keeps its "
                                        + "trail — it is still being "
                                        + "written."
                                : "")
                        + " Models, settings, saved goals and "
                        + "routes are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                maybeHapticTick(null);
                                java.util.Set<String> protect =
                                        new java.util.HashSet<String>();
                                if (protectOpen) {
                                    protect.add(currentTrail
                                            .getPath().getName());
                                }
                                int dt = DataWipe.clearDir(
                                        trailsDir(), protect);
                                int ds = DataWipe.clearDir(
                                        shotsDir(), null);
                                clearTrailsResult.setText("Cleared "
                                        + dt + " trail"
                                        + (dt == 1 ? "" : "s")
                                        + " and " + ds + " screenshot"
                                        + (ds == 1 ? "" : "s") + "."
                                        + (protectOpen
                                                ? " The open run's "
                                                        + "trail was "
                                                        + "kept."
                                                : ""));
                                refreshHistoryList();
                            }
                        })
                .show();
    }

    private void onClearGoalsAndRoutes() {
        final int nGoals = (savedGoals == null) ? 0 : savedGoals.size();
        final int nRoutes = (routes == null) ? 0 : routes.size();
        if (nGoals == 0 && nRoutes == 0) {
            clearGoalsResult.setText("Nothing to clear — no saved "
                    + "goals or taught routes are stored.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear saved goals and routes")
                .setMessage("Delete " + nGoals + " saved goal"
                        + (nGoals == 1 ? "" : "s") + " (with their "
                        + "schedules, watches and baselines) and "
                        + nRoutes + " taught route"
                        + (nRoutes == 1 ? "" : "s")
                        + "? This cannot be undone. Models, "
                        + "settings, trails and screenshots are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                maybeHapticTick(null);
                                // Disarm every scheduled check
                                // first: an alarm for a goal that
                                // no longer exists must not fire.
                                if (savedGoals != null) {
                                    List<SavedGoals.SavedGoal> all =
                                            savedGoals.all();
                                    for (int i = 0; i < all.size();
                                            i++) {
                                        if (all.get(i).hasSchedule()) {
                                            Alerts.cancelCheck(
                                                    MainActivity.this,
                                                    all.get(i).name);
                                        }
                                    }
                                }
                                savedGoals = new SavedGoals();
                                persistSavedGoals();
                                routes = Routes.parse(null);
                                persistRoutes();
                                loadedSavedName = null;
                                refreshGoalsList();
                                refreshRoutesList();
                                clearGoalsResult.setText("Cleared "
                                        + nGoals + " saved goal"
                                        + (nGoals == 1 ? "" : "s")
                                        + " and " + nRoutes
                                        + " taught route"
                                        + (nRoutes == 1 ? "" : "s")
                                        + ".");
                            }
                        })
                .show();
    }

    // ---------------- v3.7: the model manager ----------------
    // One row per known model in DATA: its state and, where
    // ModelStore.deleteVerdict allows, a Delete. The rows are
    // rebuilt whole (two models; freshness beats cleverness):
    // at build, with the model UI's refreshes (a download
    // landing, a brain switch), and after a delete.

    private void refreshModelManager() {
        if (modelRowsBox == null || store == null
                || settings == null) {
            return;
        }
        modelRowsBox.removeAllViews();
        for (int i = 0; i < ModelStore.MODELS.length; i++) {
            final ModelStore.Spec spec = ModelStore.MODELS[i];
            File f = store.fileFor(spec);
            boolean present = f.isFile();
            boolean inFlight = downloading
                    && spec.id.equals(downloadingId);
            int verdict = ModelStore.deleteVerdict(spec,
                    settings.modelId, present, inFlight);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(6);
            modelRowsBox.addView(row, rowLp);
            TextView label = new TextView(this);
            label.setText(spec.shortName + (present
                    ? " — " + ModelStore.humanSize(f.length())
                            + " on phone"
                    : " — not downloaded"));
            label.setTextSize(12);
            label.setTextColor(COL_TEXT);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            String reason = null;
            if (present) {
                Button del = chipButton("Delete");
                del.setEnabled(verdict == ModelStore.DELETE_OK);
                if (verdict == ModelStore.DELETE_OK) {
                    del.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            onDeleteModel(spec);
                        }
                    });
                } else if (verdict == ModelStore.DELETE_IN_USE) {
                    reason = "in use — switch BRAIN to delete";
                } else if (verdict
                        == ModelStore.DELETE_DOWNLOADING) {
                    reason = "downloading now — delete unlocks "
                            + "when it finishes";
                }
                LinearLayout.LayoutParams delLp =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                delLp.leftMargin = dp(8);
                row.addView(del, delLp);
            }
            if (reason != null) {
                TextView why = new TextView(this);
                why.setText(reason);
                why.setTextSize(11);
                why.setTextColor(COL_MUTED);
                LinearLayout.LayoutParams whyLp =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                whyLp.topMargin = dp(2);
                modelRowsBox.addView(why, whyLp);
            }
        }
    }

    /** Delete one model file, behind a confirm that names the
     *  model and its size and states it can be re-downloaded.
     *  The verdict is re-judged at the tap (a download may have
     *  started since the row was drawn): a model that has
     *  become protected since is refused, never deleted. */
    private void onDeleteModel(final ModelStore.Spec spec) {
        File f = store.fileFor(spec);
        boolean inFlight = downloading
                && spec.id.equals(downloadingId);
        int verdict = ModelStore.deleteVerdict(spec,
                settings.modelId, f.isFile(), inFlight);
        if (verdict == ModelStore.DELETE_DOWNLOADING) {
            Toast.makeText(this, spec.shortName
                    + " is downloading right now — delete is "
                    + "refused until it finishes",
                    Toast.LENGTH_SHORT).show();
            refreshModelManager();
            return;
        }
        if (verdict != ModelStore.DELETE_OK) {
            refreshModelManager();
            return;
        }
        final long size = f.length();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + spec.shortName)
                .setMessage("Delete " + spec.shortName + " ("
                        + ModelStore.humanSize(size)
                        + ")? The file leaves the phone; it can "
                        + "be downloaded again whenever you want "
                        + "it. The BRAIN model, the other model, "
                        + "settings and everything else are "
                        + "untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                maybeHapticTick(null);
                                boolean gone = store.deleteFile(spec);
                                refreshModelManager();
                                refreshModelUi();
                                feed(gone
                                        ? "Model deleted: "
                                                + spec.shortName
                                                + " — it can be "
                                                + "downloaded again."
                                        : "Could not delete "
                                                + spec.shortName
                                                + ".");
                            }
                        })
                .show();
    }

    // ---------------- v3.7: clear browsing data ----------------
    // The third clear action and the narrowest: the WebView's
    // cookies and site storage, then the open page reloads (a
    // page holding a now-cleared session should not sit there
    // pretending otherwise). Its scope is BrowsingData's
    // table — no file is touched; v3.13 (R5) put the site
    // grants in the table too (browsing-derived trust is
    // browsing data), so the one preference-backed store it
    // withdraws is the grants, and the confirm + result copy
    // say so and count them.

    private void onClearBrowsingData() {
        final int nGrants = (siteGrants == null) ? 0
                : siteGrants.size();
        new AlertDialog.Builder(this)
                .setTitle("Clear browsing data")
                .setMessage("Delete the browser's cookies and "
                        + "site storage"
                        + (nGrants > 0
                                ? ", and your " + nGrants
                                        + " site grant"
                                        + (nGrants == 1 ? "" : "s")
                                        + " (those sites will ask "
                                        + "again)"
                                : "")
                        + "? Pages will see this "
                        + "phone as a fresh visitor, and the "
                        + "open page reloads. Trails, screenshots, "
                        + "saved goals, routes, downloads, page "
                        + "watches, models and "
                        + "settings are NOT touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                maybeHapticTick(null);
                                CookieManager cm =
                                        CookieManager.getInstance();
                                cm.removeAllCookies(null);
                                cm.flush();
                                WebStorage.getInstance()
                                        .deleteAllData();
                                // v3.13 (R5): the grants are in
                                // this clear's scope (BrowsingData
                                // names them) — withdrawn here,
                                // beside the framework calls.
                                if (siteGrants != null) {
                                    siteGrants.revokeAll();
                                    persistGrants();
                                    refreshGrantsList();
                                }
                                String url = (webView != null)
                                        ? webView.getUrl() : null;
                                if (settings != null
                                        && settings.homePage
                                                .length() > 0) {
                                    webView.loadUrl(
                                            settings.homePage);
                                } else if (url != null
                                        && url.length() > 0) {
                                    webView.loadUrl(url);
                                }
                                clearBrowsingResult.setText(
                                        "Cleared cookies, "
                                        + "site storage"
                                        + (nGrants > 0
                                                ? " and site grants"
                                                : "")
                                        + ". Trails, "
                                        + "goals, routes, models "
                                        + "and settings were not "
                                        + "touched.");
                            }
                        })
                .show();
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

    // ---- v3.7 handlers: mutate the model, persist, refresh ----

    /** Accent: recorded at once, applied at the next launch —
     *  the views are already built with the accent they were
     *  built with, and nothing here pretends a live re-skin. */
    private void onAccentPicked(String id) {
        settings.accentId = id;
        persistSettings();
        refreshSettingsUi();
        feed("Accent: " + Settings.accentName(id)
                + " (applies when the app restarts).");
    }

    /** Feed text size: the same restart-apply as the accent —
     *  buildUi resolved the sizes it built with. */
    private void onFeedTextPicked(int pref) {
        settings.feedTextSize = pref;
        persistSettings();
        refreshSettingsUi();
        feed("Feed text size: " + Settings.feedTextSizeName(pref)
                + " (applies when the app restarts).");
    }

    /** Reduce motion: read at animation time, so it applies
     *  live — the pill is re-judged at once (updatePill starts
     *  or stills the pulse), and the next drawer render snaps
     *  or slides by the new value. */
    private void onReduceMotionToggled() {
        settings.reduceMotion = !settings.reduceMotion;
        persistSettings();
        refreshSettingsUi();
        updatePill();
        feed("Reduce motion: " + (settings.reduceMotion
                ? "ON — the pill holds steady and the drawer snaps."
                : "OFF — the pill breathes and the drawer slides."));
    }

    private void onAutoScrollToggled() {
        settings.autoScrollOn = !settings.autoScrollOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onEndViewPicked(int view) {
        settings.endView = view;
        persistSettings();
        refreshSettingsUi();
        feed("Finished runs land on: "
                + Settings.endViewName(view) + ".");
    }

    // v3.10 (R2): the planner style — a plain run-rule pick,
    // silent in the feed like the step-count picker: it takes
    // force at the next Start and is named on the run record.
    private void onPlannerStylePicked(int style) {
        settings.plannerStyle = Settings.clampPlannerStyle(style);
        persistSettings();
        refreshSettingsUi();
    }

    private void onSpeechRatePicked(int rate) {
        settings.speechRate = rate;
        persistSettings();
        refreshSettingsUi();
    }

    private void onStepShotsToggled() {
        settings.stepShotsOn = !settings.stepShotsOn;
        persistSettings();
        refreshSettingsUi();
        feed("Step screenshots: " + (settings.stepShotsOn
                ? "ON — every step's end is photographed, from "
                        + "the next run."
                : "OFF — only the run's end is photographed, "
                        + "as before."));
    }

    // ---- v3.8 handlers: mutate the model, persist, refresh ----

    /** Screen awake: read at a run's Start, so the next run is
     *  the first it can hold — a run already going keeps the
     *  sleep behaviour it started under. */
    private void onScreenAwakeToggled() {
        settings.screenAwakeOn = !settings.screenAwakeOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onBatteryWarnToggled() {
        settings.batteryWarnOn = !settings.batteryWarnOn;
        persistSettings();
        refreshSettingsUi();
        feed("Low-battery start warning: "
                + (settings.batteryWarnOn ? "ON" : "OFF") + ".");
    }

    private void onFinishBeepToggled() {
        settings.finishBeepOn = !settings.finishBeepOn;
        persistSettings();
        refreshSettingsUi();
    }

    private void onQuietHoursToggled() {
        settings.quietHoursOn = !settings.quietHoursOn;
        persistSettings();
        refreshSettingsUi();
        feed("Watch quiet hours: " + (settings.quietHoursOn
                ? "ON — " + Settings.hourLabel(settings.quietStartHour)
                        + " to " + Settings.hourLabel(
                                settings.quietEndHour)
                        + "; changes inside the window wait quietly "
                        + "on their cards."
                : "OFF — changes alert as they land."));
    }

    private void onQuietStartPicked(int hour) {
        settings.quietStartHour = Settings.clampHour(hour);
        persistSettings();
        refreshSettingsUi();
    }

    private void onQuietEndPicked(int hour) {
        settings.quietEndHour = Settings.clampHour(hour);
        persistSettings();
        refreshSettingsUi();
    }

    private void onHapticsToggled() {
        settings.hapticsOn = !settings.hapticsOn;
        persistSettings();
        refreshSettingsUi();
    }

    // ---- v3.8: the low-battery gate + the screen-awake hold ----

    /** The battery the sticky intent reports right now:
     *  {levelPercent (or -1 when the intent names none),
     *  charging ? 1 : 0}. Read at Start time, never cached —
     *  the number the dialog states is the number the phone
     *  reported at the tap. */
    private int[] batteryState() {
        int level = -1;
        int charging = 0;
        try {
            Intent sticky = registerReceiver(null, new IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) {
                int raw = sticky.getIntExtra(
                        BatteryManager.EXTRA_LEVEL, -1);
                int scale = sticky.getIntExtra(
                        BatteryManager.EXTRA_SCALE, -1);
                if (raw >= 0 && scale > 0) {
                    level = Math.round(100f * raw / scale);
                }
                int status = sticky.getIntExtra(
                        BatteryManager.EXTRA_STATUS, -1);
                if (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status
                                == BatteryManager.BATTERY_STATUS_FULL) {
                    charging = 1;
                }
            }
        } catch (Exception ignored) {
            // No reading, no warning — the decision says so.
        }
        return new int[]{level, charging};
    }

    /** The gate every user-initiated start passes through
     *  (Start, a replay's Run, Teach). Returns true when the
     *  warning dialog took the start: "Start anyway" runs
     *  proceed (the exact start the user tapped, deferred one
     *  tap), Cancel starts nothing. Returns false when the
     *  start should proceed at once. BatteryGuard owns the
     *  judgement; this owns the dialog. It can never
     *  hard-block: the only paths out are proceed-now,
     *  proceed-on-tap, or the user's own Cancel. */
    private boolean batteryGate(final Runnable proceed) {
        int[] b = batteryState();
        boolean warn = settings != null && BatteryGuard.shouldWarn(
                settings.batteryWarnOn, b[0], b[1] == 1, true);
        if (!warn) return false;
        new AlertDialog.Builder(this)
                .setTitle("Low battery")
                .setMessage(BatteryGuard.message(b[0]))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start anyway",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                maybeHapticTick(null);
                                proceed.run();
                            }
                        })
                .show();
        return true;
    }

    /** Set the keep-awake hold for a run that is starting, when
     *  the setting is on. Paired with clearScreenAwake in
     *  finishRunUi — every ending passes through there. */
    private void applyScreenAwakeForRun() {
        applyScreenAwakeForRun(settings != null
                && settings.screenAwakeOn);
    }

    /** v3.13 (R5): the same hold under an explicit rule — a
     *  resumed run's captured screen-awake value, which may
     *  differ from the saved setting's. */
    private void applyScreenAwakeForRun(boolean on) {
        if (on) {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            screenAwakeHeld = true;
        }
    }

    /** Release the hold this Activity set (never another's:
     *  the flag is only cleared when this run set it). */
    private void clearScreenAwake() {
        if (screenAwakeHeld) {
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            screenAwakeHeld = false;
        }
    }

    // ---- v3.8 DIAGNOSTICS actions -----------------------------

    /** The engine self-test: refused while work owns the
     *  engine or a model is missing (SelfTest.availability);
     *  otherwise one canonical prompt on a worker thread,
     *  reported in place. Writes nothing to any trail and
     *  touches neither the page nor run state — the engine's
     *  own calls are all it makes. An engine already holding
     *  the ACTIVE model is used as-is; any other state loads
     *  the BRAIN-selected model (timed) first. */
    private void onSelfTest() {
        final ModelStore.Spec spec = activeSpec();
        int availability = SelfTest.availability(
                running || teaching, store.ready(spec));
        if (availability == SelfTest.BUSY) {
            selfTestResult.setText(SelfTest.BUSY_LINE);
            return;
        }
        if (availability == SelfTest.NOT_DOWNLOADED) {
            selfTestResult.setText(SelfTest.NO_MODEL_LINE);
            return;
        }
        selfTestBtn.setEnabled(false);
        selfTestResult.setText("Running self-test…");
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String activePath = store.fileFor(spec)
                        .getAbsolutePath();
                boolean already = engine.isLoaded()
                        && activePath.equals(engine.loadedPath());
                long loadMs = -1;
                String report;
                if (!already) {
                    long t0 = System.currentTimeMillis();
                    boolean ok = engine.load(activePath);
                    loadMs = System.currentTimeMillis() - t0;
                    if (!ok) {
                        report = SelfTest.report(spec.displayName,
                                loadMs, "ERROR: model failed to load",
                                engine.lastStats());
                        finishSelfTest(report);
                        return;
                    }
                }
                String reply = engine.generate(
                        SelfTest.CANONICAL_PROMPT, SelfTest.MAX_TOKENS);
                report = SelfTest.report(spec.displayName, loadMs,
                        reply, engine.lastStats());
                finishSelfTest(report);
            }
        });
        t.setName("ghost-hands-selftest");
        t.start();
    }

    private void finishSelfTest(final String report) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                selfTestResult.setText(report);
                selfTestBtn.setEnabled(true);
            }
        });
    }

    /** v3.13 (R5): the governor self-check — pure and instant:
     *  GovSelfCheck builds fresh Governors over the real packs
     *  and judges four crafted actions; the verdicts render in
     *  place, failures included (a drifted governor shown as
     *  passing would make the check worthless). It touches no
     *  trail, no setting and no page, so it needs no busy gate
     *  and runs on this thread. The fourth case judges the
     *  user's CURRENT default pack, so it stays true after a
     *  pack change. */
    private void onGovSelfCheck() {
        maybeHapticTick(null);
        GovSelfCheck.Result r = GovSelfCheck.run(
                settings != null ? settings.defaultPackId
                        : "standard");
        govCheckResult.setText(r.render());
        govCheckResult.setTextColor(r.allPass() ? COL_MUTED : COL_RED);
    }

    // ---- v3.14 (R6): the accessibility spike ----------------
    //
    // Two probes behind SpikeGate's two gates, both re-judged
    // at press time and again at fire time (a gate closed
    // during the 5-second arming delay stops the probe, it
    // does not merely delay it). Every probe outcome — read,
    // tap, refusal, gate block — lands in the spike log
    // (appendSpikeLog). Nothing here is reachable from the
    // agent loop; the browser agent shares no code path with
    // any of it.

    /** One coordinate field of the tap probe. */
    private EditText spikeCoordField(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setHint(hint);
        e.setBackground(null);
        e.setTextColor(COL_TEXT);
        e.setHintTextColor(COL_MUTED);
        e.setTextSize(13);
        return e;
    }

    private void onA11ySpikeToggled() {
        settings.a11ySpikeOn = !settings.a11ySpikeOn;
        persistSettings();
        refreshSettingsUi();
        refreshSpikeGate();
        feed("Accessibility spike: " + (settings.a11ySpikeOn
                ? "ON — the probes live or die by the service "
                        + "gate too (see Diagnostics)."
                : "OFF — the probes are inert."));
    }

    /** The gate readout, re-judged whenever the Settings
     *  screen is shown or the app returns to the foreground —
     *  the service half of the gate changes in Android's
     *  settings, outside this app. */
    private void refreshSpikeGate() {
        if (a11yGateText == null || settings == null) return;
        a11yGateText.setText(SpikeGate.gateLine(
                GhostA11yService.instance() != null,
                settings.a11ySpikeOn));
    }

    /** Navigation only: opens the system's Accessibility
     *  settings page. The app cannot enable the service —
     *  this button goes to the door, it does not open it. */
    private void onOpenA11ySettings() {
        try {
            startActivity(new Intent(
                    android.provider.Settings
                            .ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            if (a11yResultText != null) {
                a11yResultText.setText("Could not open the "
                        + "Accessibility settings page from "
                        + "here — find it under Android "
                        + "Settings > Accessibility.");
            }
        }
    }

    /** Both gates, judged now. */
    private boolean spikeGateOpen() {
        return settings != null && SpikeGate.probesAllowed(
                GhostA11yService.instance() != null,
                settings.a11ySpikeOn);
    }

    /** The gate-block outcome, shared by both probes: the
     *  readout says which gate is closed, the log records
     *  that a probe was attempted and blocked. */
    private void spikeBlocked(String probe) {
        String line = SpikeGate.gateLine(
                GhostA11yService.instance() != null,
                settings != null && settings.a11ySpikeOn);
        if (a11yResultText != null) a11yResultText.setText(line);
        appendSpikeLog("gate_blocked", "probe=" + probe);
    }

    /** Arm a probe on the 5-second delay (see the buildUi
     *  block for why the delay exists). Arming again replaces
     *  the pending probe — one experiment at a time. */
    private void armSpike(String announcement, Runnable fire) {
        if (pendingSpike != null) {
            spikeHandler.removeCallbacks(pendingSpike);
        }
        pendingSpike = fire;
        if (a11yResultText != null) {
            a11yResultText.setText(announcement
                    + " Fires in 5 seconds — switch to the "
                    + "target app now; it acts on whatever is "
                    + "foreground then.");
        }
        spikeHandler.postDelayed(fire, 5000);
    }

    private void onA11yProbeRead() {
        maybeHapticTick(null);
        if (!spikeGateOpen()) {
            spikeBlocked("read");
            return;
        }
        armSpike("Read probe armed.", new Runnable() {
            @Override public void run() {
                pendingSpike = null;
                doA11yRead();
            }
        });
    }

    private void onA11yProbeTapCoords() {
        maybeHapticTick(null);
        if (!spikeGateOpen()) {
            spikeBlocked("tap");
            return;
        }
        final int x;
        final int y;
        try {
            x = Integer.parseInt(
                    a11yXEdit.getText().toString().trim());
            y = Integer.parseInt(
                    a11yYEdit.getText().toString().trim());
        } catch (Exception e) {
            if (a11yResultText != null) {
                a11yResultText.setText("Enter both coordinates "
                        + "first (screen pixels — a read "
                        + "sketch's bounds are in the same "
                        + "space).");
            }
            return;
        }
        if (x < 0 || y < 0) {
            if (a11yResultText != null) {
                a11yResultText.setText("Coordinates are screen "
                        + "pixels and cannot be negative.");
            }
            return;
        }
        armSpike("Tap probe armed at (" + x + ", " + y + ").",
                new Runnable() {
                    @Override public void run() {
                        pendingSpike = null;
                        doA11yTap(x, y, "coordinates");
                    }
                });
    }

    /** PROBE READ, fired: capture the foreground window's
     *  tree through the service, sketch it (NodeSketch), show
     *  the sketch, and offer its clickable nodes as tap
     *  chips. The gates are re-judged at fire time. */
    private void doA11yRead() {
        if (!spikeGateOpen()) {
            spikeBlocked("read");
            return;
        }
        GhostA11yService svc = GhostA11yService.instance();
        GhostA11yService.Capture cap = svc.captureForeground();
        if (cap.root == null) {
            if (a11yResultText != null) {
                a11yResultText.setText("Read probe: no active "
                        + "window could be read. The service "
                        + "reported no root node — the "
                        + "foreground may be a surface the tree "
                        + "does not cover.");
            }
            appendSpikeLog("read_failed",
                    "no root in the active window");
            return;
        }
        NodeSketch.Result r = NodeSketch.sketch(
                cap.packageName, cap.root);
        lastSketch = r;
        StringBuilder sb = new StringBuilder();
        sb.append("Foreground app: ")
                .append(cap.packageName == null
                        ? "(package unknown)" : cap.packageName)
                .append('\n');
        sb.append("Nodes visited: ").append(cap.visited);
        if (cap.capped) {
            sb.append(" (conversion stopped at the spike's "
                    + "bounds — the sketch is partial)");
        }
        sb.append(" · shown: ").append(r.entries.size())
                .append(" · not shown: ").append(r.omitted)
                .append('\n');
        sb.append("Password nodes: ").append(r.passwordNodes)
                .append(" · entries with text withheld: ")
                .append(r.withheldCount).append('\n');
        if (cap.keyguardLocked) {
            sb.append("Keyguard was LOCKED: this tree's text "
                    + "is withheld wholesale.\n");
        }
        sb.append(r.render());
        if (a11yResultText != null) a11yResultText.setText(sb);
        rebuildTapChips(r);
        appendSpikeLog("read",
                "pkg=" + (cap.packageName == null
                        ? "?" : cap.packageName)
                + " visited=" + cap.visited
                + " shown=" + r.entries.size()
                + " omitted=" + r.omitted
                + " password=" + r.passwordNodes
                + " withheld=" + r.withheldCount
                + " keyguard=" + (cap.keyguardLocked
                        ? "locked" : "open")
                + " capped=" + cap.capped);
    }

    /** After a read: one chip per clickable node (the first
     *  eight — the chips are a probe convenience, not a
     *  control surface), each arming a tap at that node's
     *  centre through the same gated path as the coordinate
     *  probe. */
    private void rebuildTapChips(NodeSketch.Result r) {
        if (a11yTapChips == null) return;
        a11yTapChips.removeAllViews();
        java.util.List<NodeSketch.Entry> taps = r.clickables();
        int n = Math.min(taps.size(), 8);
        for (int i = 0; i < n; i++) {
            final NodeSketch.Entry e = taps.get(i);
            final int[] p = e.tapPoint();
            Button b = chipButton("Tap [" + e.number + "] at ("
                    + p[0] + ", " + p[1] + ")");
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    maybeHapticTick(null);
                    if (!spikeGateOpen()) {
                        spikeBlocked("tap");
                        return;
                    }
                    armSpike("Tap probe armed at node ["
                            + e.number + "]'s centre.",
                            new Runnable() {
                                @Override public void run() {
                                    pendingSpike = null;
                                    doA11yTap(p[0], p[1],
                                            "node [" + e.number
                                                    + "]");
                                }
                            });
                }
            });
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            a11yTapChips.addView(b, lp);
        }
        if (taps.size() > n) {
            TextView more = new TextView(this);
            more.setText((taps.size() - n) + " more clickable "
                    + "node(s) — tap them by coordinates.");
            more.setTextSize(11);
            more.setTextColor(COL_MUTED);
            a11yTapChips.addView(more);
        }
    }

    /** PROBE TAP, fired: dispatch ONE tap through the
     *  service and report the platform's answer honestly —
     *  "completed" means the gesture was injected; what it
     *  hit, the platform cannot say. Gates re-judged here. */
    private void doA11yTap(final int x, final int y,
                           final String source) {
        if (!spikeGateOpen()) {
            spikeBlocked("tap");
            return;
        }
        GhostA11yService svc = GhostA11yService.instance();
        boolean accepted = svc.tapAt(x, y,
                new GhostA11yService.TapCallback() {
                    @Override public void onCompleted() {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (a11yResultText != null) {
                                    a11yResultText.setText(
                                            "Tap at (" + x + ", "
                                            + y + ") via " + source
                                            + ": gesture COMPLETED"
                                            + " — injected by the "
                                            + "platform. What it "
                                            + "hit is unknowable "
                                            + "from here; a "
                                            + "completed gesture "
                                            + "is delivery, not "
                                            + "effect.");
                                }
                                appendSpikeLog("tap",
                                        "at=" + x + "," + y
                                        + " source=" + source
                                        + " result=completed");
                            }
                        });
                    }

                    @Override public void onCancelled() {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (a11yResultText != null) {
                                    a11yResultText.setText(
                                            "Tap at (" + x + ", "
                                            + y + ") via " + source
                                            + ": gesture CANCELLED "
                                            + "by the platform "
                                            + "(interrupted, or "
                                            + "the window changed "
                                            + "under it).");
                                }
                                appendSpikeLog("tap",
                                        "at=" + x + "," + y
                                        + " source=" + source
                                        + " result=cancelled");
                            }
                        });
                    }
                });
        if (!accepted) {
            if (a11yResultText != null) {
                a11yResultText.setText("Tap at (" + x + ", " + y
                        + "): the service REFUSED the gesture "
                        + "before dispatch — no tap happened.");
            }
            appendSpikeLog("tap_refused",
                    "at=" + x + "," + y + " source=" + source);
        }
    }

    /** The spike's record: one SpikeLog line appended to the
     *  local log file, tail-trimmed at SpikeLog's cap. Spike
     *  logging is best-effort and silent — a failed log write
     *  must never take a probe down with it. */
    private void appendSpikeLog(String what, String detail) {
        try {
            File f = new File(getFilesDir(), SpikeLog.FILE_NAME);
            String existing = f.isFile() ? readTextFile(f) : "";
            if (existing == null) existing = "";
            String next = existing + SpikeLog.line(
                    System.currentTimeMillis(), what, detail);
            next = SpikeLog.trimToTail(next,
                    SpikeLog.MAX_KEPT_CHARS);
            java.io.FileOutputStream out =
                    new java.io.FileOutputStream(f, false);
            try {
                out.write(next.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) { }
    }

    /** Copy debug info: exactly DebugInfo's bundle — its
     *  signature is the privacy scope (no goal, URL, trail
     *  body or goal name exists among its inputs). */
    private void onCopyDebugInfo() {
        ModelStore.Spec spec = activeSpec();
        File mf = store.fileFor(spec);
        String modelState = mf.isFile()
                ? ModelStore.humanSize(mf.length()) + " on phone"
                : "not downloaded";
        String bundle = DebugInfo.build(appVersionLabel(),
                Build.MODEL, Build.VERSION.RELEASE,
                settings.modelId, modelState, settings.grammarOn,
                settings.threads, settings.prefixCacheOn,
                settings.stepShotsOn,
                DataWipe.countFiles(trailsDir()),
                DataWipe.countFiles(shotsDir()));
        try {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(
                        DebugInfo.CLIP_LABEL, bundle));
            }
            copyDebugResult.setText(DebugInfo.COPIED_LINE);
        } catch (Exception e) {
            copyDebugResult.setText("Copy failed — the clipboard "
                    + "refused it.");
        }
    }

    /** "3.8.0 (code 30)" — the About block's form, factored
     *  for the debug bundle. */
    private String appVersionLabel() {
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Exception ignored) {
            return "?";
        }
    }

    /** The lifetime stats, computed from the trail files each
     *  time the Settings screen is shown: parse every trail,
     *  distill its facts (TrailStats), aggregate, format. A
     *  file that will not read contributes nothing — the same
     *  tolerance History shows a damaged trail. */
    private void refreshLifetimeStats() {
        if (lifetimeStatsText == null) return;
        List<TrailStats.RunFacts> all =
                new ArrayList<TrailStats.RunFacts>();
        File dir = trailsDir();
        File[] found = (dir == null) ? null : dir.listFiles();
        if (found != null) {
            for (int i = 0; i < found.length; i++) {
                if (!found[i].isFile() || !found[i].getName()
                        .endsWith(".jsonl")) {
                    continue;
                }
                String text = readTextFile(found[i]);
                if (text == null) continue;
                List<Map<String, Object>> events =
                        new ArrayList<Map<String, Object>>();
                String[] lines = text.split("\n");
                for (int j = 0; j < lines.length; j++) {
                    String line = lines[j].trim();
                    if (line.length() == 0) continue;
                    Object parsed;
                    try {
                        parsed = Json.parse(line);
                    } catch (Exception e) {
                        continue;
                    }
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ev =
                                (Map<String, Object>) parsed;
                        events.add(ev);
                    }
                }
                all.add(TrailStats.factsFromEvents(events));
            }
        }
        lifetimeStatsText.setText(
                TrailStats.format(TrailStats.compute(all)));
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
        // v3.7: the model manager's rows read this beside
        // `downloading` (a session only ever fetches the
        // active spec — the BRAIN choice).
        downloadingId = activeSpec().id;
        downloadBtn.setEnabled(false);
        refreshBrainChips();
        refreshGrammarUi();
        refreshModelManager();
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
                downloadingId = null;
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
            refreshRetryButtons();
        } else {
            feedText.setVisibility(View.GONE);
            cardBox.setVisibility(View.GONE);
            timelineList.setVisibility(View.VISIBLE);
            rebuildTimeline();
        }
        // v3.7 (auto-scroll): the follow is the setting's call
        // (Settings.feedScrollFollows, read live — a display
        // preference, like speak-on-finish). Off, no render
        // ever moves the user's scroll position: not a new
        // line mid-run, not the end-of-run view switch above.
        if (Settings.feedScrollFollows(settings == null
                || settings.autoScrollOn)) {
            feedScroll.post(new Runnable() {
                @Override public void run() {
                    feedScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
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
        main.setTextSize(feedMainSp);
        col.addView(main);
        row.subText = new TextView(this);
        row.subText.setTextColor(COL_MUTED);
        row.subText.setTextSize(feedSubSp);
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
        return awaitApproval(action, decision, describe, true,
                (pack == null) ? PolicyPacks.DEFAULT_APPROVAL_TIMEOUT
                        : pack.approvalTimeout);
    }

    /**
     * v3.16 (R8): the approval handshake with its two knobs
     * named — whether the remember-for-this-site checkbox is
     * offered (a download approval offers it never: site
     * grants remember CONSEQUENTIAL ACTION approvals, and a
     * download is not one of the loop's actions), and the
     * timeout in seconds (a download is judged under the pack
     * in force for IT — the run's while running, the settings
     * default otherwise — whose leash may differ from the
     * field's). While any wait runs, approvalBusy stands: a
     * second asker (a download arriving mid-approval) queues
     * behind it instead of overwriting the panel and the
     * latch out from under the first.
     */
    private boolean awaitApproval(final GhAction action,
                                  final Governor.Decision decision,
                                  final String describe,
                                  final boolean offerRemember,
                                  final double timeoutSeconds) {
        approvalBusy = true;
        approvalResult = false;
        approvalRemember = false;
        approvalLatch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // v3.5: the approval panel lives on the Agent
                // screen — an approval that arrives while the
                // user sits on another screen brings them to it,
                // so the wait is never spent on a hidden panel.
                showScreen(NavState.AGENT);
                approvalText.setText("Approval needed — nothing runs until you decide.\n\n"
                        + describe + "\n\n"
                        + decision.classification + ": " + decision.reason);
                // v3.13 (R5): the remember checkbox is offered
                // only when this ask is a consequential one —
                // the only kind a grant can ever answer.
                // v3.16: ...and only when this ask offers it
                // (a download approval never does).
                if (rememberGrantCheck != null) {
                    rememberGrantCheck.setChecked(false);
                    rememberGrantCheck.setVisibility(
                            offerRemember
                                    && Governor.CONSEQUENTIAL.equals(
                                            decision.classification)
                                    ? View.VISIBLE : View.GONE);
                }
                approvalPanel.setVisibility(View.VISIBLE);
            }
        });
        // v3.6 (needs-me): the wait begins here — if the setting
        // is on and the app is in the background, say so out
        // loud (NeedsMe's three legs; a foreground wait never
        // notifies). The governor's wait itself is untouched:
        // same latch, same timeout, silence still never consent.
        if (NeedsMe.shouldNotify(settings != null && settings.needsMeOn,
                true, inForeground)) {
            Alerts.postNeedsYou(MainActivity.this, Alerts.NEEDS_APPROVAL);
        }
        boolean answered = false;
        try {
            long timeoutMs = (long) (timeoutSeconds * 1000.0);
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
        approvalBusy = false;
        // No answer in time (or Stop) counts as a decline: silence is
        // never consent.
        return answered && approvalResult && !stopFlag;
    }

    private void releaseApproval(boolean approved) {
        approvalResult = approved;
        // v3.13 (R5): capture the remember tick with the answer,
        // on this (UI) thread, before the agent thread wakes —
        // the askAnswerText pattern. A denial remembers
        // nothing, whatever the box says.
        approvalRemember = approved && rememberGrantCheck != null
                && rememberGrantCheck.getVisibility() == View.VISIBLE
                && rememberGrantCheck.isChecked();
        CountDownLatch latch = approvalLatch;
        if (latch != null) latch.countDown();
    }

    // ---------------- v3.12: the ask handshake ----------------
    // The approval handshake's sibling: the agent thread waits,
    // the user answers in the ask panel (typed, or spoken
    // through the voice path). The wait runs on the timeout the
    // loop passed — the active pack's approval timeout — so an
    // ask counts against the pack clock exactly like an
    // approval wait. Silence discipline: a timeout, a Stop or a
    // destroy releases the latch UNANSWERED and this returns
    // null; the loop stops the run rather than guess. A blank
    // field is no answer either.

    private String awaitAnswer(final String question, long timeoutMs) {
        askAnswered = false;
        askAnswerText = null;
        askLatch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // v3.5 invariant, same as the approval panel:
                // the ask panel lives on the Agent screen — a
                // question that arrives while the user sits on
                // another screen brings them to it.
                showScreen(NavState.AGENT);
                askText.setText("The agent is asking:\n\n" + question);
                askEdit.setText("");
                askPanel.setVisibility(View.VISIBLE);
            }
        });
        // v3.6 (needs-me): an ask that opens while the app is in
        // the background is a needs-me wait when the setting is
        // on — same three legs as an approval's (NeedsMe judges;
        // a foreground wait never notifies).
        if (NeedsMe.shouldNotify(settings != null && settings.needsMeOn,
                true, inForeground)) {
            Alerts.postNeedsYou(MainActivity.this, Alerts.NEEDS_ASK);
        }
        boolean answered = false;
        try {
            long t = timeoutMs;
            if (t <= 0) t = 600000L;
            answered = askLatch.await(t, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        runOnUiThread(new Runnable() {
            @Override public void run() {
                askPanel.setVisibility(View.GONE);
            }
        });
        if (!answered || !askAnswered || stopFlag) return null;
        // askAnswerText was captured on the UI thread at the
        // Answer tap, before the countDown (the approvalResult
        // pattern): what the user left in the field, typed or
        // dictated. Blank is no answer.
        if (askAnswerText == null) return null;
        String text = askAnswerText.trim();
        return (text.length() == 0) ? null : text;
    }

    private void releaseAsk(boolean answered) {
        if (answered) {
            askAnswerText = (askEdit == null) ? null
                    : askEdit.getText().toString();
            askAnswered = true;
        }
        CountDownLatch latch = askLatch;
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
                boolean ok = awaitApproval(action, decision, describe);
                // v3.13 (R5 — site grants): an approved
                // consequential with the remember tick mints a
                // grant for this site + action kind, provenanced
                // to the run that earned it. The store is the
                // same live one the loop's GrantBook reads, so
                // the grant can answer a later step of THIS run
                // too. A page that names no host mints nothing —
                // a grant must be able to name its site.
                if (ok && approvalRemember
                        && Governor.CONSEQUENTIAL.equals(
                                decision.classification)) {
                    String host = SiteGrants.hostOf(
                            webView == null ? null : webView.getUrl());
                    if (host.length() > 0 && siteGrants != null) {
                        SiteGrants.Grant g = siteGrants.add(host,
                                action.action,
                                System.currentTimeMillis(),
                                currentTrail == null
                                        || currentTrail.getPath() == null
                                        ? ""
                                        : currentTrail.getPath()
                                                .getName());
                        if (g != null) {
                            persistGrants();
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    refreshGrantsList();
                                }
                            });
                            feed("Remembered: " + g.kind + " on "
                                    + g.host + " will not ask "
                                    + "again — revoke it in "
                                    + "Settings > DATA > Site grants.");
                        }
                    }
                }
                return ok;
            }

            @Override public String askUser(String question,
                                            long timeoutMs) {
                // v3.12 (R4): the planner's clarifying question —
                // the ask handshake above. A stopped run never
                // opens the panel; an unanswered wait returns
                // null and the loop stops honestly.
                if (stopFlag) return null;
                return awaitAnswer(question, timeoutMs);
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

            @Override public void onStepEnd(int step) {
                // v3.7 (step screenshots): the loop calls this
                // only for runs made with the setting on (it is
                // a run rule, captured at Start like the rest).
                captureStepScreenshot(step);
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
                // v3.5: like the approval panel, the pause
                // panel lives on the Agent screen (its page is
                // there too) — surface it there, never leave it
                // waiting on a hidden screen.
                showScreen(NavState.AGENT);
                assistText.setText(message != null
                        && message.length() > 0 ? message
                        : "Stuck here. Tap the element yourself in "
                        + "the page, then press Resume — or press "
                        + "Stop.");
                assistPanel.setVisibility(View.VISIBLE);
            }
        });
        // v3.6 (needs-me): a CO-PILOT rescue offer (identifyTap)
        // that opens while the app is in the background notifies
        // the same way an approval does. The route-fill pause
        // (identifyTap false) is deliberately not a needs-me
        // wait — the setting's promise names approvals and
        // rescues — so it stays panel-only, as before.
        if (identifyTap && NeedsMe.shouldNotify(
                settings != null && settings.needsMeOn, true,
                inForeground)) {
            Alerts.postNeedsYou(MainActivity.this, Alerts.NEEDS_COPILOT);
        }
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
    /**
     * The visible-view photograph both screenshot kinds share
     * (v3.3's capture, factored out in v3.7 for the step
     * shots): draw the WebView's visible content on the UI
     * thread, write it as a JPEG on the caller's. Returns true
     * when the file landed. Strictly best-effort — a zero-size
     * view, a dead latch, a full disk all read as false, and
     * no failure here ever reaches a run.
     */
    private boolean captureVisibleViewJpeg(File dest) {
        try {
            if (webView == null || dest == null) return false;
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
            if (!latch.await(5, TimeUnit.SECONDS)) return false;
            Bitmap bm = box[0];
            if (bm == null) return false;
            FileOutputStream fos = new FileOutputStream(dest);
            try {
                bm.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            } finally {
                fos.close();
            }
            bm.recycle();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

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
            if (!captureVisibleViewJpeg(new File(dir, name))) {
                return;
            }
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
            // v3.6 (trail retention): the trails store is pruned
            // at this same housekeeping point — the run's end —
            // under its own new cap (see pruneTrails).
            pruneTrails();
        } catch (Throwable ignored) {
            // The photo never breaks the run.
        }
    }

    /**
     * v3.7 (step screenshots): one step's photograph, taken
     * from the agent thread through the Ui.onStepEnd hook (the
     * loop calls it only for runs made with the setting on).
     * The same capture as the run-end photo, the step's own
     * file name, and a "step_screenshot" record so the run's
     * card can show the strip. NO housekeeping here on
     * purpose: retention is the run-end pruning's business,
     * and it counts the whole store — step shots included —
     * under the one DATA retention setting, exactly as the
     * accordion copy states. Best-effort, like the end photo:
     * a step is never failed by its photograph.
     */
    private void captureStepScreenshot(int step) {
        try {
            if (currentTrail == null || currentTrail.getPath() == null
                    || webView == null) {
                return;
            }
            File dir = shotsDir();
            if (!dir.exists()) dir.mkdirs();
            String name = Shots.stepFileNameForTrail(
                    currentTrail.getPath().getName(), step);
            if (name == null) return;
            if (!captureVisibleViewJpeg(new File(dir, name))) {
                return;
            }
            currentTrail.record("step_screenshot", step,
                    Trail.fields("file", name));
        } catch (Throwable ignored) {
            // The photo never breaks the step.
        }
    }

    /**
     * v3.6 (DATA — trail retention): keep the newest
     * settings.trailsKept trail files, under TrailPrune's rule —
     * protected names never go and do not consume keep slots.
     * Protected here: the currently open run's trail (this
     * housekeeping runs at its end, while the watch verdict is
     * still to be written into it) and every watched goal's
     * baseline trail (named in the goal record when the baseline
     * was set; a baseline older than v3.6 names none and so
     * protects none — its first watched run under this build
     * stamps the name). Best-effort: housekeeping failures are
     * swallowed like the screenshot's own.
     */
    private void pruneTrails() {
        try {
            File dir = trailsDir();
            File[] files = dir.listFiles();
            if (files == null) return;
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isFile()
                        && files[i].getName().endsWith(".jsonl")) {
                    names.add(files[i].getName());
                }
            }
            java.util.Set<String> protect =
                    new java.util.HashSet<String>();
            if (savedGoals != null) {
                List<SavedGoals.SavedGoal> all = savedGoals.all();
                for (int i = 0; i < all.size(); i++) {
                    SavedGoals.SavedGoal g = all.get(i);
                    if (g.hasBaseline()
                            && g.baselineTrailName != null
                            && g.baselineTrailName.length() > 0) {
                        protect.add(g.baselineTrailName);
                    }
                }
            }
            if (currentTrail != null && currentTrail.getPath() != null) {
                protect.add(currentTrail.getPath().getName());
            }
            int keep = (settings != null) ? settings.trailsKept
                    : Settings.TRAILS_DEFAULT;
            List<String> old = TrailPrune.excess(names, keep, protect);
            for (int i = 0; i < old.size(); i++) {
                new File(dir, old.get(i)).delete();
            }
        } catch (Throwable ignored) {
            // Housekeeping never breaks a run.
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
        // v3.12 (R4): the card this finish is about to show
        // belongs to THIS trail — a retry offered on it names
        // this file as its retry_of.
        lastCardTrailFile = trailFile;
        // v3.8 (screen awake): every ending reaches here, so
        // every ending releases the hold the run's start set.
        clearScreenAwake();
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
        // v3.16 (R8): a page-watch check run earns its verdict
        // the same way — against the page's own freshly
        // extracted text (the WebView still shows the run's
        // final page, as the screenshot above relies on). The
        // extraction is async, so the verdict lands a moment
        // after the card: processPageWatchCheck records it in
        // this trail, rebuilds the card with its banner, and
        // posts by the goal watches' notification rules —
        // which is why the matrix below and the finish beep
        // stand down for this run (the guards name
        // runPageWatchUrl, which stands until the verdict
        // lands).
        if (endCard != null && runPageWatchUrl != null) {
            processPageWatchCheck(currentTrail, trailFile, goal);
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
            if (watchVerdict != null && watchVerdict.changed()
                    && watchQuietDeferred) {
                // v3.8 (quiet hours): the verdict is recorded
                // (the trail and the card's banner carry it,
                // banner noting the quiet arrival) — and that
                // is all. No notification, no buzz, and nothing
                // is queued to send when the window ends: the
                // deferral is a wait, not a schedule.
            } else if (watchVerdict != null && watchVerdict.changed()) {
                String first = watchVerdict.diffLines.isEmpty()
                        ? "the answer changed"
                        : watchVerdict.diffLines.get(0);
                Alerts.postWatchChanged(MainActivity.this, runSavedName,
                        first, trailFile.getAbsolutePath());
                vibrateFinish();
            } else if (watchVerdict != null
                    && WatchCompare.NO_CHANGE.equals(watchVerdict.state)) {
                // Quiet by design.
            } else if (!inForeground && runPageWatchUrl == null) {
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
            // v3.8 (finish beep): ONE short beep at this same
            // finish moment, foreground or background — except
            // the endings the app promises silence to: a
            // watched run's NO_CHANGE and a change quiet hours
            // deferred (FinishBeep judges; this only sounds).
            // v3.16 (R8): a page-watch check's beep is deferred
            // with its verdict (finishPageWatchCheck sounds it
            // under the watch's silence rules) — this run's
            // verdict has not landed yet at this line.
            boolean beepSilenced = watchQuietDeferred
                    || runPageWatchUrl != null
                    || (watchVerdict != null
                            && WatchCompare.NO_CHANGE.equals(
                                    watchVerdict.state));
            if (FinishBeep.shouldBeep(settings != null
                    && settings.finishBeepOn, true, beepSilenced)) {
                beepFinish();
            }
        }
        // v2.6: every path that reaches here ended normally
        // (including a failed model load), so the abrupt-end
        // marker has done its job.
        deleteRunMarker();
        runOnUiThread(new Runnable() {
            @Override public void run() {
                // v3.12 (R4): a retry's desktop override was
                // run-scoped — the settings' own UA choice is
                // back in force from the moment the run ends,
                // not just from the next start (which re-applies
                // it anyway).
                applyDesktopMode();
                startBtn.setEnabled(true);
                stopBtn.setEnabled(false);
                refreshBrainChips();
                refreshGrammarUi();
                // v3.15 (R7): the run is over — the tab strip
                // unlocks with its tabs left exactly as the
                // run left them, for the user's inspection.
                refreshTabStrip();
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
        // v3.8: teaching is a user-initiated start too — the
        // low-battery gate judges before the intro dialog, and
        // "Start anyway" lands here on the same intro.
        if (batteryGate(new Runnable() {
            @Override public void run() { showTeachIntro(); }
        })) {
            return;
        }
        showTeachIntro();
    }

    private void showTeachIntro() {
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
                                maybeHapticTick(null);
                                startTeaching();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startTeaching() {
        // v3.15 (R7): teaching pins tab 1 — a route is taught
        // on the first tab's page, and the strip locks while
        // teaching (refreshTabStrip reads the teaching flag).
        activateTab(0);
        synchronized (teachLock) {
            teachSteps.clear();
            teachPendingField = null;
            String url = webView.getUrl();
            teachStartUrl = (url == null) ? "" : url;
            teaching = true;
        }
        refreshTabStrip();
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
                        // v3.15 (R7): teaching is over — the
                        // tab strip unlocks.
                        refreshTabStrip();
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
    /** v3.5: the routes dialog graduated to its own screen;
     *  this entry point is just the navigation. */
    private void showRoutes() {
        showScreen(NavState.ROUTES);
    }

    /** The Routes screen's rows — the v3.3 dialog's rows with
     *  their behaviour unchanged (Run confirms, then replays
     *  on the Agent screen; ✕ deletes). Rebuilt on entry and
     *  after a delete. */
    private void refreshRoutesList() {
        if (routesListBox == null) return;
        routesListBox.removeAllViews();
        if (routes == null || routes.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No routes yet — tap Teach a new "
                    + "route above to record one.");
            empty.setTextSize(12);
            empty.setTextColor(COL_MUTED);
            empty.setPadding(dp(4), dp(10), dp(4), dp(6));
            routesListBox.addView(empty);
            return;
        }
        LinearLayout list = routesListBox;
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
                    confirmRouteReplay(r);
                }
            });
            row.addView(runBtn);
            Button delBtn = chipButton("✕");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    routes.remove(r.name);
                    persistRoutes();
                    feed("Route deleted: " + r.name);
                    refreshRoutesList();
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
    /** A replay's Run tap (v3.8): through the low-battery
     *  gate, like Start — "Start anyway" runs the very same
     *  replay. */
    private void startRouteReplay(final Routes.Route route) {
        if (batteryGate(new Runnable() {
            @Override public void run() { doStartRouteReplay(route); }
        })) {
            return;
        }
        doStartRouteReplay(route);
    }

    private void doStartRouteReplay(final Routes.Route route) {
        if (running) return;
        if (teaching) {
            Toast.makeText(this, "Teaching a route — Done or "
                    + "Cancel first", Toast.LENGTH_SHORT).show();
            return;
        }
        // v3.5: a replay lives where the WebView lives — the
        // Agent screen — wherever its Run was tapped.
        showScreen(NavState.AGENT);
        // v3.15 (R7): a replay pins tab 1 — the browser goes
        // back to its first tab before the replay's hands are
        // made, and stays there (the strip locks with the run).
        activateTab(0);
        stopFlag = false;
        stopping = false;
        running = true;
        refreshTabStrip();
        runSavedName = null;
        runWatched = false;
        watchQuietDeferred = false;
        // v3.8: a replay is a run for the screen-awake hold.
        applyScreenAwakeForRun();
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
        // v3.6: the second wave rides along (threads, fallback
        // engine, desktop mode, prefix cache, live stats), and
        // the fallback engine + UA are captured for the replay's
        // own browsing exactly as for a run's.
        runFallbackEngineId = settings.fallbackEngine;
        applyDesktopMode();
        // v3.13 (R5): a replay carries the grant book too — a
        // replay is a run for grant purposes (its govern
        // records stamp a spent grant beside the route
        // provenance). No retry or resume stamp: a replay is
        // neither.
        // v3.15 (R7): a replay is single-tab — it pins tab 1
        // (activateTab(0) below put the browser there before
        // the hands were made) and its Agent is built with
        // tabs disabled, so its route steps always drive the
        // first tab's page.
        agent = new Agent(hands, engine, pack, currentTrail,
                agentUi(), activeModelId, grammarOn,
                settings.maxSteps, settings.copilotOn,
                settings.autoReadOn, settings.chunkPref,
                settings.threads, settings.fallbackEngine,
                settings.desktopMode, settings.prefixCacheOn,
                settings.liveStatsOn,
                settings.stepShotsOn, settings.endView,
                settings.screenAwakeOn, settings.batteryWarnOn,
                settings.plannerStyle, null, null, null,
                grantBook(), false);
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
        // v3.7: the step-photo strip rides under the end shot.
        fillStepStrip(cardStepStrip, cardStepStripBox,
                lastCard != null ? lastCard.stepScreenshotFiles
                        : null);
        refreshListenUi();
    }

    /**
     * v3.7 (step screenshots): fill a card's step-photo strip
     * — one thumbnail per step photograph the card names and
     * the store still holds (retention may honestly have taken
     * some; a missing file is skipped, never a hole). The
     * strip hides when there is nothing to show, which is
     * every card from a run made with the setting off.
     * Thumbnails decode sampled (the strip is 72dp tall; the
     * full frames stay on disk for the end shot's view).
     */
    private void fillStepStrip(HorizontalScrollView strip,
                               LinearLayout box,
                               List<String> files) {
        if (strip == null || box == null) return;
        box.removeAllViews();
        int shown = 0;
        if (files != null) {
            File dir = shotsDir();
            for (int i = 0; i < files.size(); i++) {
                File f = new File(dir, files.get(i));
                if (!f.isFile()) continue;
                BitmapFactory.Options bounds =
                        new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
                if (bounds.outHeight <= 0) continue;
                BitmapFactory.Options opts =
                        new BitmapFactory.Options();
                int sample = 1;
                while (bounds.outHeight / (sample * 2) >= 144) {
                    sample *= 2;
                }
                opts.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeFile(
                        f.getAbsolutePath(), opts);
                if (bm == null) continue;
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                dp(72));
                if (shown > 0) lp.leftMargin = dp(6);
                box.addView(iv, lp);
                shown++;
            }
        }
        strip.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
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
        // v3.7 (speech rate): every spoken path lands here —
        // Listen, History's Listen, speak-on-finish — so the
        // setting is applied in this one place, to the next
        // utterance. At Normal it is exactly 1.0, as always.
        tts.setSpeechRate(Settings.speechRateValue(settings != null
                ? settings.speechRate : Settings.RATE_NORMAL));
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

    /** The Start tap's entry (v3.8): the low-battery gate
     *  judges first — it either lets the start proceed at once
     *  or takes it into its dialog, whose "Start anyway" runs
     *  the very same start. */
    private void startAgent() {
        if (batteryGate(new Runnable() {
            @Override public void run() { doStartAgent(); }
        })) {
            return;
        }
        doStartAgent();
    }

    private void doStartAgent() {
        if (running) return;
        // v3.3: while teaching, the page belongs to the user's
        // demonstration — a run starting mid-lesson would mix
        // two drivers on one WebView.
        if (teaching) {
            Toast.makeText(this, "Teaching a route — Done or "
                    + "Cancel first", Toast.LENGTH_SHORT).show();
            return;
        }
        // v3.13 (R5): a pending resume names its own model —
        // the readiness gate judges THAT spec, not the brain
        // the settings currently select.
        final ModelStore.Spec startSpec = (pendingResume != null)
                ? ModelStore.specFor(ModelStore.resolveId(
                        pendingResume.modelId))
                : activeSpec();
        if (!store.ready(startSpec)) {
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
        // v3.15 (R7): the tab strip locks (dimmed, taps off)
        // for the run's duration — refreshTabStrip reads the
        // running flag it was just given.
        refreshTabStrip();
        // v3.12 (R4): consume a pending strategy retry. The
        // override folds into the run-rule values captured
        // below — fallback engine, desktop mode, style — for
        // THIS run only; the settings model is read, never
        // written, and the pending pair is cleared here so a
        // later ordinary Start can never inherit it.
        final RetryStrategy.Option retryOption = pendingRetryOption;
        final String retryOf = pendingRetryOf;
        pendingRetryOption = null;
        pendingRetryOf = null;
        // v3.13 (R5): consume a pending resume the same way.
        // The marker's captured rules resolve through the
        // Settings model itself (Settings.fromMap — the rules
        // were stored in its own encodings), so the resumed run
        // is built from exactly the values its predecessor
        // began under; the SAVED settings are neither read for
        // this run (every eff* below prefers the marker) nor
        // modified by it (nothing here writes the model). A
        // rule the marker does not carry resolves to the
        // standing default, as an absent key always has.
        final RunMarker resume = pendingResume;
        pendingResume = null;
        final Settings resumeRules = (resume != null)
                ? Settings.fromMap(resume.rules) : null;
        final String effModelId = (resume != null)
                ? resumeRules.modelId : activeModelId;
        final boolean effGrammar = (resume != null)
                ? resumeRules.grammarOn : grammarOn;
        final String effFallback = (resume != null)
                ? resumeRules.fallbackEngine
                : (retryOption != null
                        && RetryStrategy.KIND_FALLBACK.equals(retryOption.kind))
                        ? retryOption.value : settings.fallbackEngine;
        final boolean effDesktop = (resume != null)
                ? resumeRules.desktopMode
                : (retryOption != null
                        && RetryStrategy.KIND_DESKTOP.equals(retryOption.kind))
                        ? true : settings.desktopMode;
        final int effStyle = (resume != null)
                ? resumeRules.plannerStyle
                : (retryOption != null
                        && RetryStrategy.KIND_STYLE.equals(retryOption.kind))
                        ? Settings.STYLE_THOROUGH : settings.plannerStyle;
        // v3.13 (R5): the pack this run actually starts under —
        // the marker's for a resume; the standing rule (a check
        // launch forces readonly) otherwise.
        final String effPackId = (resume != null)
                ? Settings.resolvePack(resume.rule(RunMarker.RULE_PACK))
                : (scheduleLaunched ? "readonly" : currentPackId);
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
        // v3.16 (R8): pin the run's page watch the same way —
        // a check loaded and started earns its verdict against
        // that watch (finishPageWatchCheck); the pending pin
        // is consumed here whatever the run turns out to be.
        runPageWatchUrl = pendingPageWatchUrl;
        pendingPageWatchUrl = null;
        // v3.8: a watched run's quiet-hours verdict starts
        // undecided; processWatch settles it at the finish.
        watchQuietDeferred = false;
        // v3.8: the screen-awake hold, when the setting is on —
        // cleared in finishRunUi, which every ending reaches.
        // v3.13: a resumed run holds by ITS captured rule.
        applyScreenAwakeForRun(resume != null
                ? resumeRules.screenAwakeOn : settings.screenAwakeOn);
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
        if (retryOption != null) {
            feed("Retry of " + (retryOf == null ? "a stuck run" : retryOf)
                    + " — " + retryOption.label
                    + " (this run only; your settings are unchanged)");
        }
        if (resume != null) {
            feed("Resumed from " + resume.trailName
                    + " — the interrupted run's pack, model and "
                    + "rules (your settings are unchanged)");
        }
        feed("— run started");
        // v2.6: from here until the run's stop, an abrupt death
        // leaves evidence behind (see RunMarker).
        // v3.13 (R5): a plain manual run's marker is RESUMABLE —
        // it carries this trail's name and the run's captured
        // rules (captureRunRules). A scheduled-check or watched-
        // goal run writes the PLAIN marker instead: those runs
        // are readonly and repeat by design, and never leave a
        // resumable marker behind.
        if (!scheduleLaunched && !runWatched) {
            writeRunMarker(goal, trailFile.getName(),
                    captureRunRules(effPackId, resume, resumeRules,
                            effModelId, effGrammar, effFallback,
                            effDesktop, effStyle));
        } else {
            writeRunMarker(goal);
        }

        final Agent.Ui ui = agentUi();

        // v3.0 (item 2): the run's policy. A goal loaded from a
        // check notification runs readonly whatever it was saved
        // under — the note on the goal card said so before Start.
        // v3.13 (R5): effPackId is exactly that rule for an
        // ordinary start, and the marker's pack for a resume.
        pack = PolicyPacks.loadPack(effPackId);
        hands = new WebHands();
        // v3.4: the run takes its step budget, co-pilot gate,
        // auto-read and chunk preference from the settings model
        // — the knobs captured here, at Start, for the whole run.
        // v3.6: the second wave is captured the same way, and the
        // two browsing surfaces the loop reaches through the
        // hands and the WebView — the search fallback's engine
        // and the user agent — are put in force for the run.
        // v3.13 (R5): runRules is the settings model for an
        // ordinary start (the very same object, value for
        // value) and the marker's resolved rules for a resume.
        final Settings runRules = (resume != null)
                ? resumeRules : settings;
        runFallbackEngineId = effFallback;
        applyDesktopMode();
        if (effDesktop != settings.desktopMode) {
            // v3.12 (R4) + v3.13 (R5): this run's desktop choice
            // differs from the setting's — a retry forcing it on,
            // or a resume restoring the dead run's own choice
            // either way. The UA is this run only; finishRunUi
            // re-applies the settings' own choice when the run
            // ends, as does every later start.
            webView.getSettings().setUserAgentString(
                    Settings.userAgentFor(effDesktop,
                            defaultUserAgent));
        }
        agent = new Agent(hands, engine, pack, currentTrail, ui,
                effModelId, effGrammar,
                runRules.maxSteps, runRules.copilotOn,
                runRules.autoReadOn, runRules.chunkPref,
                runRules.threads, effFallback,
                effDesktop, runRules.prefixCacheOn,
                runRules.liveStatsOn,
                runRules.stepShotsOn, runRules.endView,
                runRules.screenAwakeOn, runRules.batteryWarnOn,
                effStyle,
                (retryOption == null) ? null : retryOption.stamp,
                retryOf,
                (resume == null) ? null : resume.trailName,
                grantBook(),
                // v3.15 (R7): tabs are an ordinary run's tool.
                // A watched or scheduled run pins tab 1 — the
                // loop forces the browser onto its first tab
                // at the start and refuses tab actions all run.
                !(scheduleLaunched || runWatched));
        final Agent runAgent = agent;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // v2.6: the engine must hold the ACTIVE model's
                    // file — after a switch it may hold the other
                    // quant's, or none.
                    // v3.13 (R5): startSpec is the active spec for
                    // an ordinary start and the resumed run's own
                    // model for a resume.
                    String activePath = store.fileFor(startSpec)
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

    // ---------------- v3.13: site grants (R5) ----------------

    private void persistGrants() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_GRANTS,
                        siteGrants == null ? "[]"
                                : siteGrants.serialize())
                .apply();
    }

    /** The GrantBook every run is built with: a LIVE view of
     *  the grant store, so a grant minted at one approval can
     *  answer a later step of the same run, and a revocation in
     *  Settings takes effect at the next decision. */
    private Agent.GrantBook grantBook() {
        return new Agent.GrantBook() {
            @Override public SiteGrants.Grant find(String host,
                                                   String actionKind) {
                return (siteGrants == null) ? null
                        : siteGrants.match(host, actionKind);
            }
        };
    }

    /** Rebuild the DATA section's grant rows from the store.
     *  File/store state is not a Settings value, so this is not
     *  refreshSettingsUi's business — it is called where the
     *  store can change: build, a panel-minted grant, a revoke,
     *  a browsing-data clear. */
    private void refreshGrantsList() {
        if (grantsBox == null) return;
        grantsBox.removeAllViews();
        if (siteGrants == null || siteGrants.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No site grants yet. Tick \"Remember "
                    + "for this site\" while approving an action "
                    + "and that site stops asking for that kind "
                    + "of action.");
            empty.setTextSize(11);
            empty.setTextColor(COL_MUTED);
            grantsBox.addView(empty);
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "MMM d, yyyy", Locale.US);
        List<SiteGrants.Grant> all = siteGrants.all();
        for (int i = 0; i < all.size(); i++) {
            final SiteGrants.Grant g = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText(g.host + " · " + g.kind + " · "
                    + fmt.format(new java.util.Date(g.createdAtMs)));
            label.setTextSize(12);
            label.setTextColor(COL_TEXT);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button revoke = chipButton("Revoke");
            revoke.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (siteGrants != null
                            && siteGrants.revoke(g.id)) {
                        persistGrants();
                        grantsResult.setText("Revoked the grant "
                                + "for " + g.kind + " on " + g.host
                                + " — that site asks again from "
                                + "the next decision.");
                        refreshGrantsList();
                    }
                }
            });
            LinearLayout.LayoutParams rkLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            rkLp.leftMargin = dp(8);
            row.addView(revoke, rkLp);
            LinearLayout.LayoutParams rowLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(6);
            grantsBox.addView(row, rowLp);
        }
        Button revokeAll = chipButton("Revoke all grants");
        revokeAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (siteGrants == null) return;
                int n = siteGrants.revokeAll();
                persistGrants();
                grantsResult.setText("Revoked " + n + " grant"
                        + (n == 1 ? "" : "s")
                        + " — every site asks again.");
                refreshGrantsList();
            }
        });
        LinearLayout.LayoutParams raLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        raLp.topMargin = dp(8);
        grantsBox.addView(revokeAll, raLp);
    }

    // ---------------- v3.13: resume (R5) ----------------

    /** Show or hide the goal card's resume card from the held
     *  offer. The offer text names the goal and when the dead
     *  run began; the rules it will resume under are the
     *  marker's, and the card says the settings stay untouched. */
    private void refreshResumeUi() {
        if (resumePanel == null) return;
        if (resumeOffer == null) {
            resumePanel.setVisibility(View.GONE);
            return;
        }
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "MMM d, h:mm a", Locale.US);
        resumeText.setText("Last run was interrupted (the app "
                + "or phone died) — goal: " + resumeOffer.goal
                + "\nStarted " + fmt.format(new java.util.Date(
                        resumeOffer.startedAtMs))
                + ". Resume starts a fresh run of this goal with "
                + "the same pack, model and style it began "
                + "under; your saved settings are not used and "
                + "not changed.");
        resumePanel.setVisibility(View.VISIBLE);
    }

    /** Resume tap: the marker becomes the pending resume the
     *  next Start consumes (doStartAgent), and the start goes
     *  through startAgent so every normal gate applies — the
     *  battery guard included. A model the marker names that
     *  is not on the phone refuses here and KEEPS the offer:
     *  the interruption is real either way. */
    private void onResumeOffer() {
        RunMarker m = resumeOffer;
        if (m == null) return;
        if (running) {
            Toast.makeText(this, "A run is already going",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ModelStore.Spec spec = ModelStore.specFor(
                ModelStore.resolveId(m.modelId));
        if (!store.ready(spec)) {
            Toast.makeText(this, "The interrupted run's model ("
                    + spec.displayName + ") is not downloaded",
                    Toast.LENGTH_LONG).show();
            return;
        }
        resumeOffer = null;
        refreshResumeUi();
        pendingResume = m;
        pendingRetryOption = null;
        pendingRetryOf = null;
        loadedSavedName = null;
        pendingPageWatchUrl = null;
        scheduleLaunched = false;
        goalEdit.setText(m.goal);
        goalEdit.setSelection(m.goal.length());
        showScreen(NavState.AGENT);
        startAgent();
    }

    /** Dismiss tap: clear the marker, nothing else — the dead
     *  run's trail stays in the store as its own record. */
    private void onDismissOffer() {
        resumeOffer = null;
        deleteRunMarker();
        refreshResumeUi();
        feed("Resume dismissed — the interrupted run's trail "
                + "is kept in History.");
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
        // v3.8 (quiet hours): a CHANGED verdict judged inside
        // the window is stamped quiet on its own event — the
        // card's banner reads the stamp, and finishRunUi reads
        // the same judgement from watchQuietDeferred to hold
        // the notification and the buzz. The verdict itself,
        // and the baseline's advance below, are untouched: the
        // check fully happened; only its alert waits.
        boolean quietDefer = settings != null
                && QuietHours.shouldDefer(settings.quietHoursOn,
                        verdict.changed(),
                        Calendar.getInstance().get(
                                Calendar.HOUR_OF_DAY),
                        settings.quietStartHour,
                        settings.quietEndHour);
        watchQuietDeferred = quietDefer;
        Map<String, Object> fields = Trail.fields(
                "goal_name", runSavedName,
                "verdict", verdict.state,
                "since_ms", Long.valueOf(
                        g.hasBaseline() ? g.baselineTsMs : 0));
        if (quietDefer) {
            fields.put("quiet", Boolean.TRUE);
        }
        if (!verdict.diffLines.isEmpty()) {
            fields.put("diff",
                    new ArrayList<Object>(verdict.diffLines));
        }
        trail.record("watch", step, fields);
        // v3.6: the baseline also names its source trail, so
        // trail retention can protect the reference run while
        // it is the baseline (TrailPrune / pruneTrails).
        g.setBaseline(sig, summary, System.currentTimeMillis(),
                (trail.getPath() == null) ? ""
                        : trail.getPath().getName());
        persistSavedGoals();
        return verdict;
    }

    // ---------------- v3.16: page watches (R8) ----------------
    // A page watch is the goal watches' sibling: one URL, an
    // evidence signature of its readable text (WatchCompare's
    // discipline — PageWatches.signatureFor), checks on the
    // same notify-and-launch machinery, verdicts by the same
    // rules. Two differences, both structural: the signature
    // signs the PAGE's text (extracted with the reader's own
    // Eyes.readTextJs from the live WebView), not an answer's
    // facts; and a check completes only when the run ended
    // DONE on the watched page itself (PageWatches.urlsMatch)
    // — a capture of any other page must never move this
    // watch's baseline.
    //
    // Creation is the USER's tap (the address bar's Watch
    // segment, or an answer card's Watch page chip). It is
    // therefore never a planner action and no Governor ever
    // judges it: its class, if it needs naming, is "a local
    // write of the app's own state" — and when a run is live,
    // the creation is recorded in that run's trail as a
    // "page_watch" event stamped by:"user", the provenance
    // the ask answers carry. The harness pins the stamp.

    private void persistPageWatches() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PageWatches.PREF_PAGE_WATCHES,
                        pageWatches == null ? "[]"
                                : pageWatches.serialize())
                .apply();
    }

    /** A page's readable text, whole (the reader's cap), from
     *  a live view — the extraction a watch signs, at creation
     *  and at a check alike. The callback lands on the UI
     *  thread with the text, or "" when the page yields none. */
    private interface PageTextCallback {
        void onText(String text);
    }

    private void extractPageText(WebView wv,
                                 final PageTextCallback cb) {
        if (wv == null) {
            cb.onText("");
            return;
        }
        try {
            wv.evaluateJavascript(
                    Eyes.readTextJs(0, Eyes.READ_TOTAL_CAP_CHARS),
                    new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(
                                String value) {
                            String json = decodeJs(value);
                            Eyes.ReadChunk chunk =
                                    Eyes.parseRead(json);
                            cb.onText(chunk.text == null
                                    ? "" : chunk.text);
                        }
                    });
        } catch (Exception e) {
            cb.onText("");
        }
    }

    /** The address bar's Watch segment: sign the CURRENT
     *  page and watch its URL. Allowed during a run — the
     *  extraction is read-only JS and the store write is the
     *  user's own (see the section head). */
    private void watchCurrentPage() {
        final WebView wv = webView;
        final String url = (wv == null) ? null : wv.getUrl();
        if (url == null || !Downloads.isHttp(url)) {
            Toast.makeText(this, "Open a page first — there is "
                    + "nothing to watch yet", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (pageWatches == null) pageWatches = new PageWatches();
        if (pageWatches.find(url) != null) {
            feed("Already watching this page — its baseline "
                    + "stands (Saved Goals ▸ Page watches).");
            Toast.makeText(this, "Already watching this page",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        extractPageText(wv, new PageTextCallback() {
            @Override public void onText(String text) {
                createPageWatch(url, text);
            }
        });
    }

    /** An answer card's Watch page chip: watch the card's
     *  source page — when the browser still shows it. */
    private void watchCardPage() {
        if (lastCard == null || lastCard.sourceUrl == null
                || lastCard.sourceUrl.length() == 0) {
            Toast.makeText(this, "This card names no page to "
                    + "watch", Toast.LENGTH_SHORT).show();
            return;
        }
        String current = (webView == null) ? null : webView.getUrl();
        if (current == null
                || !PageWatches.urlsMatch(current,
                        lastCard.sourceUrl)) {
            Toast.makeText(this, "That page is no longer open "
                    + "— reopen it to watch it",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        watchCurrentPage();
    }

    /** Create (or re-baseline) a page watch from a fresh
     *  capture: the baseline is the capture itself, the
     *  verdict BASELINE, and — when a run is live — the
     *  creation is recorded in its trail, stamped as the
     *  user's. */
    private void createPageWatch(String url, String text) {
        PageWatches.PageWatch w = new PageWatches.PageWatch(url);
        long now = System.currentTimeMillis();
        w.setBaseline(PageWatches.signatureFor(text), text, now);
        w.lastVerdict = WatchCompare.BASELINE;
        w.lastCheckTsMs = now;
        pageWatches.put(w);
        persistPageWatches();
        if (currentTrail != null && running) {
            try {
                currentTrail.record("page_watch",
                        (agent != null) ? agent.currentStep : 0,
                        Trail.fields(
                                "url", url,
                                "by", "user",
                                "verdict", WatchCompare.BASELINE,
                                "entries", Long.valueOf(
                                        w.baseline == null ? 0
                                                : w.baseline
                                                        .entries
                                                        .size())));
            } catch (Exception ignored) {
                // The record never breaks the creation.
            }
        }
        feed("Watching this page: " + PageWatches.label(url)
                + " — baseline set from what it says now; "
                + "checks compare against it (Saved Goals)");
        Toast.makeText(this, "Watching: "
                + PageWatches.label(url), Toast.LENGTH_SHORT)
                .show();
        refreshPageWatchList();
    }

    /** The Goals screen's page-watch rows: the URL, its last
     *  verdict, and the goal watches' own controls — ⏰ the
     *  daily check, Check (load the check now), ✕ delete. */
    private void refreshPageWatchList() {
        if (pageWatchListBox == null) return;
        pageWatchListBox.removeAllViews();
        if (pageWatches == null || pageWatches.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No page watches yet — open a page "
                    + "and tap Watch in the address bar.");
            empty.setTextSize(12);
            empty.setTextColor(COL_MUTED);
            empty.setPadding(dp(4), dp(10), dp(4), dp(6));
            pageWatchListBox.addView(empty);
            return;
        }
        List<PageWatches.PageWatch> all = pageWatches.all();
        for (int i = 0; i < all.size(); i++) {
            final PageWatches.PageWatch w = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nameText = new TextView(this);
            nameText.setText(PageWatches.label(w.url));
            nameText.setTextSize(14);
            nameText.setTypeface(Typeface.DEFAULT_BOLD);
            nameText.setTextColor(COL_TEXT);
            texts.addView(nameText);
            TextView meta = new TextView(this);
            StringBuilder metaText = new StringBuilder();
            metaText.append(w.url);
            if (w.hasSchedule()) {
                metaText.append("\n· daily check at ")
                        .append(SavedGoals.timeLabel(w.schedMinutes));
            }
            if (w.lastVerdict.length() > 0 && w.lastCheckTsMs > 0) {
                metaText.append("\nLast check ")
                        .append(new SimpleDateFormat(
                                "EEE h:mm a", Locale.US).format(
                                new Date(w.lastCheckTsMs)))
                        .append(": ")
                        .append(pageWatchVerdictLine(w));
            } else {
                metaText.append("\nNo check yet — the baseline "
                        + "is what the page said when you "
                        + "watched it");
            }
            meta.setText(metaText.toString());
            meta.setTextSize(11);
            meta.setTextColor(COL_MUTED);
            texts.addView(meta);
            texts.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    loadPageWatchCheck(w);
                }
            });
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button schedBtn = chipButton(
                    w.hasSchedule() ? "⏰ ✓" : "⏰");
            schedBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    editPageWatchSchedule(w);
                }
            });
            row.addView(schedBtn);
            Button checkBtn = chipButton("Check");
            checkBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    loadPageWatchCheck(w);
                }
            });
            LinearLayout.LayoutParams ckLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            ckLp.leftMargin = dp(6);
            row.addView(checkBtn, ckLp);
            Button delBtn = chipButton("✕");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirmDeletePageWatch(w);
                }
            });
            LinearLayout.LayoutParams delLp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(6);
            row.addView(delBtn, delLp);
            pageWatchListBox.addView(row);
        }
    }

    /** A watch's last verdict in the card's words. */
    private static String pageWatchVerdictLine(
            PageWatches.PageWatch w) {
        if (WatchCompare.NO_CHANGE.equals(w.lastVerdict)) {
            return "no change";
        }
        if (WatchCompare.CHANGED.equals(w.lastVerdict)) {
            return "CHANGED"
                    + (w.lastDiff.isEmpty() ? ""
                            : " — " + w.lastDiff.get(0));
        }
        if (WatchCompare.BASELINE.equals(w.lastVerdict)) {
            return "baseline set";
        }
        if (WatchCompare.NOT_DONE.equals(w.lastVerdict)) {
            return "could not complete — baseline unchanged";
        }
        return w.lastVerdict;
    }

    private void confirmDeletePageWatch(
            final PageWatches.PageWatch w) {
        new AlertDialog.Builder(this)
                .setTitle("Delete page watch")
                .setMessage("Stop watching " + w.url + "?"
                        + (w.hasSchedule()
                                ? " Its daily check goes with it."
                                : ""))
                .setPositiveButton("Delete",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                pageWatches.remove(w.url);
                                Alerts.cancelPageWatchCheck(
                                        MainActivity.this, w.url);
                                persistPageWatches();
                                if (w.url.equals(
                                        pendingPageWatchUrl)) {
                                    pendingPageWatchUrl = null;
                                }
                                feed("Page watch deleted: "
                                        + PageWatches.label(w.url));
                                refreshPageWatchList();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void editPageWatchSchedule(
            final PageWatches.PageWatch w) {
        if (!w.hasSchedule()) {
            openPageWatchTimePicker(w);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(PageWatches.label(w.url)
                        + " — daily check")
                .setItems(new CharSequence[]{
                        "Change time (" + SavedGoals.timeLabel(
                                w.schedMinutes) + ")",
                        "Remove schedule"},
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface d, int which) {
                                if (which == 0) {
                                    openPageWatchTimePicker(w);
                                } else {
                                    w.schedMinutes =
                                            PageWatches.NO_SCHEDULE;
                                    Alerts.cancelPageWatchCheck(
                                            MainActivity.this,
                                            w.url);
                                    persistPageWatches();
                                    feed("Daily check removed: "
                                            + PageWatches.label(
                                                    w.url));
                                    refreshPageWatchList();
                                }
                            }
                        })
                .show();
    }

    private void openPageWatchTimePicker(
            final PageWatches.PageWatch w) {
        int h = w.hasSchedule() ? w.schedMinutes / 60 : 7;
        int m = w.hasSchedule() ? w.schedMinutes % 60 : 0;
        new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {
                    @Override public void onTimeSet(TimePicker view,
                                                    int hour,
                                                    int minute) {
                        w.schedMinutes = SavedGoals.minutesOfDay(
                                hour, minute);
                        persistPageWatches();
                        Alerts.armPageWatchCheck(
                                MainActivity.this, w);
                        feed("Daily check set: "
                                + PageWatches.label(w.url) + " at "
                                + SavedGoals.timeLabel(
                                        w.schedMinutes)
                                + " — a notification will offer "
                                + "the check; it runs when you tap "
                                + "it, readonly");
                        refreshPageWatchList();
                    }
                }, h, m, true).show();
    }

    /** Load a page watch's check, ready to start: the
     *  generated readonly goal, the pin for the run, and the
     *  note saying what will be compared. The check
     *  notification's tap lands here too (handleIntent). */
    private void loadPageWatchCheck(PageWatches.PageWatch w) {
        goalEdit.setText(PageWatches.checkGoal(w.url));
        goalEdit.setSelection(goalEdit.getText().length());
        loadedSavedName = null;
        pendingPageWatchUrl = w.url;
        scheduleLaunched = true;
        goalNoteText.setVisibility(View.VISIBLE);
        goalNoteText.setText("Page watch check: "
                + PageWatches.label(w.url)
                + " — this run uses the READONLY policy. Its "
                + "verdict compares the page with "
                + (w.hasBaseline()
                        ? "the last completed check ("
                                + new SimpleDateFormat(
                                        "EEE h:mm a", Locale.US)
                                                .format(new Date(
                                                        w.baselineTsMs))
                                + ")"
                        : "no baseline yet")
                + " — you are buzzed only if it changed.");
        feed("Page watch check loaded: "
                + PageWatches.label(w.url) + " (readonly policy)");
        showScreen(NavState.AGENT);
    }

    /**
     * A page-watch check run has ended (finishRunUi calls
     * this): extract the final page's text and judge the
     * watch. Completed — done, and the final page IS the
     * watched page — the verdict is judged against the
     * baseline the run found, recorded in the trail as a
     * "watch" event (the goal watches' shape, the label in
     * goal_name's place, the url and a page_watch flag
     * added, so the answer card's banner reads it untouched),
     * and only then does the baseline advance. Any other
     * ending records NOT_DONE and moves nothing. Quiet hours
     * and the notification matrix are the goal watches'
     * own: a quiet change is recorded, never posted; a change
     * posts and buzzes; no change is silence; a check that
     * could not complete keeps the ordinary off-screen
     * run-finished word.
     */
    private void processPageWatchCheck(final Trail trail,
                                         final File trailFile,
                                         final String goal) {
        final String watchUrl = runPageWatchUrl;
        if (trail == null || watchUrl == null
                || pageWatches == null) {
            runPageWatchUrl = null;
            return;
        }
        final PageWatches.PageWatch w = pageWatches.find(watchUrl);
        if (w == null) {
            // Deleted mid-run: the run simply ends unwatched.
            runPageWatchUrl = null;
            return;
        }
        final int step = lastStopStep(trail.events());
        final boolean doneRun = WatchCompare
                .doneSummaryFromEvents(trail.events()) != null;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                extractPageText(webView, new PageTextCallback() {
                    @Override public void onText(String text) {
                        finishPageWatchCheck(trail, trailFile,
                                goal, w, watchUrl, step, doneRun,
                                text);
                    }
                });
            }
        });
    }

    private void finishPageWatchCheck(final Trail trail,
                                        final File trailFile,
                                        final String goal,
                                        final PageWatches.PageWatch w,
                                        final String watchUrl,
                                        final int step,
                                        final boolean doneRun,
                                        final String pageText) {
        runPageWatchUrl = null;
        String fetchedUrl = (webView == null) ? null
                : webView.getUrl();
        long now = System.currentTimeMillis();
        boolean completed = doneRun
                && PageWatches.urlsMatch(fetchedUrl, watchUrl)
                && pageText.length() > 0;
        if (!completed) {
            PageWatches.recordNotDone(w, now);
            persistPageWatches();
            trail.record("watch", step, Trail.fields(
                    "goal_name", PageWatches.label(watchUrl),
                    "url", watchUrl,
                    "page_watch", Boolean.TRUE,
                    "verdict", WatchCompare.NOT_DONE,
                    "since_ms", Long.valueOf(
                            w.hasBaseline() ? w.baselineTsMs : 0)));
            AnswerCard card = AnswerCard.fromEvents(
                    trail.events(), goal);
            if (card != null) showAnswerCard(card);
            if (!inForeground && card != null) {
                Alerts.postRunFinished(this, card,
                        trailFile.getAbsolutePath());
                if (settings == null || settings.buzzOn) {
                    vibrateFinish();
                }
            }
            // The deferred beep (see the completed path): a
            // check that could not complete keeps the ordinary
            // run's beep — nothing about it promised silence.
            if (FinishBeep.shouldBeep(settings != null
                    && settings.finishBeepOn, true, false)) {
                beepFinish();
            }
            refreshPageWatchList();
            return;
        }
        // Judge against the baseline the run found — the
        // record names it (since_ms) before it moves.
        WatchCompare.Verdict verdict =
                PageWatches.verdictFor(w, pageText);
        boolean quietDefer = settings != null
                && QuietHours.shouldDefer(settings.quietHoursOn,
                        verdict.changed(),
                        Calendar.getInstance().get(
                                Calendar.HOUR_OF_DAY),
                        settings.quietStartHour,
                        settings.quietEndHour);
        Map<String, Object> fields = Trail.fields(
                "goal_name", PageWatches.label(watchUrl),
                "url", watchUrl,
                "page_watch", Boolean.TRUE,
                "verdict", verdict.state,
                "since_ms", Long.valueOf(
                        w.hasBaseline() ? w.baselineTsMs : 0));
        if (quietDefer) fields.put("quiet", Boolean.TRUE);
        if (!verdict.diffLines.isEmpty()) {
            fields.put("diff",
                    new ArrayList<Object>(verdict.diffLines));
        }
        trail.record("watch", step, fields);
        PageWatches.applyCompletedCheck(w, pageText, now);
        persistPageWatches();
        AnswerCard card = AnswerCard.fromEvents(trail.events(),
                goal);
        if (card != null) showAnswerCard(card);
        if (verdict.changed() && quietDefer) {
            // Recorded; that is all (the goal watches' rule).
        } else if (verdict.changed()) {
            String first = verdict.diffLines.isEmpty()
                    ? "the page changed"
                    : verdict.diffLines.get(0);
            Alerts.postWatchChanged(this,
                    PageWatches.label(watchUrl), first,
                    trailFile.getAbsolutePath());
            vibrateFinish();
        } else if (WatchCompare.NO_CHANGE.equals(verdict.state)) {
            // Quiet by design.
        } else if (!inForeground && card != null) {
            Alerts.postRunFinished(this, card,
                    trailFile.getAbsolutePath());
            if (settings == null || settings.buzzOn) {
                vibrateFinish();
            }
        }
        // The finish beep stood down in finishRunUi for this
        // run (its verdict was still landing); it lands here,
        // under the watch's silence rules.
        boolean beepSilenced = quietDefer
                || WatchCompare.NO_CHANGE.equals(verdict.state);
        if (FinishBeep.shouldBeep(settings != null
                && settings.finishBeepOn, true, beepSilenced)) {
            beepFinish();
        }
        refreshPageWatchList();
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
        // v3.16: loading a goal supersedes a loaded page-watch
        // check (the pins are alternatives, never both).
        pendingPageWatchUrl = null;
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
        // v3.5: loading a goal means looking at it — the goal
        // field, the browser and Start all live on the Agent
        // screen, whatever screen the load came from (the
        // Goals screen, a scheduled-check notification).
        showScreen(NavState.AGENT);
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

    /** v3.5: the saved-goals dialog graduated to its own
     *  screen; this entry point is just the navigation (the
     *  list refreshes on entry, see showScreen). */
    private void showSavedGoals() {
        showScreen(NavState.GOALS);
    }

    /** The Goals screen's rows — the v3.0 dialog's rows with
     *  their behaviour unchanged: the texts load the goal (and
     *  loading switches to the Agent screen), ⏰ edits the
     *  daily check, 👁 toggles the watch, ✕ asks to delete.
     *  Rebuilt on entry and after every in-screen mutation, so
     *  the list never shows a state a dialog already changed. */
    private void refreshGoalsList() {
        if (goalsListBox == null) return;
        goalsListBox.removeAllViews();
        if (savedGoals == null || savedGoals.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No saved goals yet — write a goal on "
                    + "the Agent screen and tap Save goal.");
            empty.setTextSize(12);
            empty.setTextColor(COL_MUTED);
            empty.setPadding(dp(4), dp(10), dp(4), dp(6));
            goalsListBox.addView(empty);
            return;
        }
        LinearLayout list = goalsListBox;
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
                }
            });
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button schedBtn = chipButton(g.hasSchedule() ? "⏰ ✓" : "⏰");
            schedBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
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
                    feed(g.watched
                            ? "Watching " + g.name + ": finished "
                            + "runs compare their numbers with "
                            + "the last check — you are buzzed "
                            + "only when they change"
                            : "Stopped watching " + g.name
                            + " (baseline cleared)");
                    refreshGoalsList();
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
                        refreshGoalsList();
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
                                    refreshGoalsList();
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
                refreshGoalsList();
            }
        }, h, m, true).show();
    }

    // ---------------- v3.0: answer cards (item 4) ----------------

    private String cardDateLabel() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new Date());
    }

    /** v3.12 (R4): rebuild the live card's retry row from
     *  RetryStrategy's pure mapping over the card's own trail.
     *  No card, no trail file, or no mapped shape -> the row is
     *  gone: the card keeps exactly its pre-v3.12 controls. */
    private void refreshRetryButtons() {
        if (retryBtnRow == null) return;
        retryBtnRow.removeAllViews();
        List<RetryStrategy.Option> options =
                new ArrayList<RetryStrategy.Option>();
        if (lastCard != null && lastCardTrailFile != null
                && lastCard.sourceEvents != null) {
            options = RetryStrategy.optionsFor(lastCard.sourceEvents);
        }
        if (options.isEmpty()) {
            retryBtnRow.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < options.size(); i++) {
            final RetryStrategy.Option option = options.get(i);
            Button b = chipButton(option.label);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startRetry(option);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(8);
            retryBtnRow.addView(b, lp);
        }
        retryBtnRow.setVisibility(View.VISIBLE);
    }

    /** v3.12 (R4): a retry tap — a NEW run of the card's goal
     *  with the named override pending for it. The goal text
     *  comes from the card (the run's own record); the saved-
     *  goal pin is cleared, deliberately: a retry is a fresh
     *  manual run of that goal text, and pinning it to a saved
     *  goal it may only resemble would earn it a watch verdict
     *  against the wrong baseline. The start itself goes
     *  through startAgent, so every normal gate applies (the
     *  battery guard included); doStartAgent consumes the
     *  pending override. The user's settings are never
     *  modified — the override lives only in the new run's
     *  construction values and its trail stamps. */
    private void startRetry(RetryStrategy.Option option) {
        if (option == null || lastCard == null) return;
        if (running) {
            Toast.makeText(this, "A run is already going",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        goalEdit.setText(lastCard.goal);
        loadedSavedName = null;
        // v3.16 (R8): a retry is a fresh manual run — a loaded
        // page-watch check must not ride along and earn a
        // verdict against it.
        pendingPageWatchUrl = null;
        // v3.13 (R5): a deliberate new start supersedes a
        // pending resume left standing by a cancelled battery
        // dialog — the pending pair must never hijack a start
        // it was not minted for. (The launch's OFFER is a
        // separate field and survives: only the unconsumed
        // pending is dropped, as a fresh resume tap re-mints it.)
        pendingResume = null;
        pendingRetryOption = option;
        pendingRetryOf = (lastCardTrailFile == null) ? null
                : lastCardTrailFile.getName();
        showScreen(NavState.AGENT);
        startAgent();
    }

    private void showAnswerCard(AnswerCard card) {
        if (card == null) return;
        lastCard = card;
        // v3.7 (end-of-run view): a finished run lands where
        // the setting says — Card, as always, unless Steps or
        // Trail was chosen. Read live, like speak-on-finish
        // (the run's trail names the value in force at its
        // Start); the card itself is lastCard either way, one
        // tap from the Card chip.
        logMode = Settings.feedLogModeFor(settings != null
                ? settings.endView : Settings.END_CARD);
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

    /** v3.5: the Past chip's picker graduated to the History
     *  screen; this entry point is just the navigation. */
    private void showPastRuns() {
        showScreen(NavState.HISTORY);
    }

    /** The History screen's list: every trail file, newest
     *  first (the file names are timestamps, so name order is
     *  time order). Each row opens the answer card it rebuilds
     *  to, in this screen (showHistoryCard) — the v3.0 picker's
     *  behaviour, with the card shown here instead of borrowed
     *  into the Agent screen's log card. */
    private void refreshHistoryList() {
        if (historyListBox == null) return;
        historyListBox.removeAllViews();
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
            TextView empty = new TextView(this);
            empty.setText("No past runs yet.");
            empty.setTextSize(12);
            empty.setTextColor(COL_MUTED);
            empty.setPadding(dp(4), dp(10), dp(4), dp(6));
            historyListBox.addView(empty);
            return;
        }
        for (int i = 0; i < trails.size(); i++) {
            final File f = trails.get(i);
            TextView row = new TextView(this);
            row.setText(f.getName());
            row.setTextSize(13);
            row.setTextColor(COL_TEXT);
            row.setPadding(dp(8), dp(11), dp(8), dp(11));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showHistoryCard(f);
                }
            });
            historyListBox.addView(row);
        }
    }

    /** A past run's card, rebuilt from its trail (the same
     *  AnswerCard.fromTrailText the Agent card uses) and shown
     *  in the History screen, screenshot included when the
     *  trail names one and the file is still in the store. */
    private void showHistoryCard(File file) {
        String text = readTextFile(file);
        AnswerCard card = (text == null) ? null
                : AnswerCard.fromTrailText(text, null);
        if (card == null) {
            Toast.makeText(this, "That trail has no finished run",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        historyCard = card;
        historyCardText.setText(card.cardText());
        Bitmap bm = null;
        if (card.screenshotFile.length() > 0) {
            File f = new File(new File(getFilesDir(),
                    Shots.DIR_NAME), card.screenshotFile);
            if (f.isFile()) {
                bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            }
        }
        if (bm != null) {
            historyShotView.setImageBitmap(bm);
            historyShotView.setVisibility(View.VISIBLE);
        } else {
            historyShotView.setImageBitmap(null);
            historyShotView.setVisibility(View.GONE);
        }
        // v3.7: the past run's step-photo strip, the live
        // card's twin.
        fillStepStrip(historyStepStrip, historyStepStripBox,
                card.stepScreenshotFiles);
        refreshHistoryListenUi();
        historyCardBox.setVisibility(View.VISIBLE);
        feed("Answer card: " + file.getName());
    }

    private void shareHistoryCard() {
        if (historyCard == null) {
            Toast.makeText(this, "Pick a past run first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT,
                historyCard.shareText(cardDateLabel()));
        startActivity(Intent.createChooser(send, "Share answer card"));
    }

    private void refreshHistoryListenUi() {
        if (historyListenBtn == null) return;
        boolean avail = !ttsFailed;
        historyListenBtn.setText(SpeakText.buttonLabel(avail, ttsSpeaking));
        historyListenBtn.setEnabled(SpeakText.buttonEnabled(avail));
        historyListenBtn.setTextColor(avail ? COL_TEXT : COL_MUTED);
    }

    /** History's Listen: the same engine and stop behaviour as
     *  the Agent card's Listen, speaking the picked past card. */
    private void onHistoryListenTapped() {
        if (historyCard == null) {
            Toast.makeText(this, "Pick a past run first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (ttsSpeaking && tts != null) {
            tts.stop();
            ttsSpeaking = false;
            refreshListenUi();
            refreshHistoryListenUi();
            return;
        }
        if (ttsFailed) return;
        if (tts != null && ttsReady) {
            speakCard(historyCard);
            refreshHistoryListenUi();
            return;
        }
        final AnswerCard toSpeak = historyCard;
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    installTtsListener();
                    refreshListenUi();
                    speakCard(toSpeak);
                    refreshHistoryListenUi();
                } else {
                    ttsFailed = true;
                    ttsSpeaking = false;
                    refreshListenUi();
                    refreshHistoryListenUi();
                    Toast.makeText(MainActivity.this,
                            "Text-to-speech isn't available on "
                            + "this phone", Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        // v3.12 (R4): this card, too, names its trail — a
        // stuck run reopened from its finish notification can
        // offer its retries like any live card.
        lastCardTrailFile = file;
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
        voiceForAsk = false;
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

    /** v3.12 (R4): the ask panel's mic — the same recognizer
     *  flow as the goal mic (same availability and permission
     *  gates, and the voice-goals setting gates it too), with
     *  the session's results routed into the ANSWER field by
     *  voiceForAsk. The flag is set before any permission
     *  round-trip so a granted request still lands in the
     *  answer; the goal mic clears it for its own sessions. */
    private void onAskMicTapped() {
        if (settings != null && !settings.voiceOn) {
            Toast.makeText(this, "Voice input is off in Settings",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        voiceForAsk = true;
        if (speechListening) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceForAsk = false;
            Toast.makeText(this, "Speech recognition isn't available "
                    + "on this phone", Toast.LENGTH_SHORT).show();
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
            voiceForAsk = false;
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
                voiceForAsk = false;
                feed("Voice input stopped (recognizer error " + error + ")");
                refreshMicUi();
            }
            @Override public void onResults(Bundle results) {
                speechListening = false;
                ArrayList<String> heard = (results == null) ? null
                        : results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);
                String text = VoiceGoal.pickHeard(heard);
                // v3.12 (R4): an ask session's dictation lands
                // in the ANSWER field — for review and the
                // Answer tap, exactly like a goal lands in the
                // goal field for review and Start. It never
                // submits itself.
                boolean forAsk = voiceForAsk;
                voiceForAsk = false;
                if (text != null) {
                    if (forAsk && askEdit != null) {
                        askEdit.setText(text);
                        askEdit.setSelection(text.length());
                        feed("Heard answer: " + text);
                    } else {
                        goalEdit.setText(text);
                        goalEdit.setSelection(text.length());
                        feed("Heard goal: " + text);
                    }
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

    /** v3.8 (finish beep): one short tone on the notification
     *  stream, from a generator kept for the app's life (a
     *  per-beep create/release would race its own tone's end).
     *  Guarded like every courtesy here: a silent device or a
     *  refused stream must never break the finish path. */
    private void beepFinish() {
        try {
            if (toneGen == null) {
                toneGen = new ToneGenerator(
                        AudioManager.STREAM_NOTIFICATION, 90);
            }
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 200);
        } catch (Throwable ignored) { }
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
    // only witness an abrupt death leaves; checkRunMarker judges
    // it (v3.13: a stale one clears silently, a resumable one
    // becomes the resume offer, the rest get the v2.6 line). The
    // marker's content is RunMarker's (pure, host-tested); these
    // helpers are just its file.

    private File runMarkerFile() {
        return new File(getFilesDir(), "run-in-progress.json");
    }

    private void writeRunMarker(String goal) {
        writeRunMarker(new RunMarker(goal,
                System.currentTimeMillis(), activeModelId));
    }

    /** v3.13 (R5): the resumable form — the trail name plus the
     *  run's captured rules, so a launch can offer the resume.
     *  Only a plain manual agent run writes this form;
     *  scheduled-check and watched-goal runs write the plain
     *  marker above (they are readonly and repeat by design —
     *  never resumable), and route replays write the plain one
     *  too. */
    private void writeRunMarker(String goal, String trailName,
                                Map<String, String> rules) {
        writeRunMarker(new RunMarker(goal,
                System.currentTimeMillis(), activeModelId,
                trailName, rules));
    }

    private void writeRunMarker(RunMarker m) {
        try {
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

    /** v3.13 (R5): the run rules a resumable marker captures,
     *  in the Settings model's own encodings — the far side of
     *  a death resolves the map with Settings.fromMap, so the
     *  resumed run is built by the very resolvers that built
     *  this one. The values are the EFFECTIVE ones: a retry's
     *  override is part of the run being marked, so a death
     *  mid-retry resumes the retry's rules, not the settings'. */
    private Map<String, String> captureRunRules(String packId,
            RunMarker resume, Settings resumeRules,
            String effModelId, boolean effGrammar,
            String effFallback, boolean effDesktop, int effStyle) {
        Settings src = (resume != null) ? resumeRules : settings;
        Map<String, String> rules =
                new java.util.LinkedHashMap<String, String>();
        rules.put(RunMarker.RULE_PACK, packId);
        rules.put(Settings.KEY_MODEL_ID, effModelId);
        rules.put(Settings.KEY_GRAMMAR_ON,
                ModelStore.grammarPrefValue(effGrammar));
        rules.put(Settings.KEY_MAX_STEPS,
                String.valueOf(src.maxSteps));
        rules.put(Settings.KEY_COPILOT, Settings.onOff(src.copilotOn));
        rules.put(Settings.KEY_AUTO_READ,
                Settings.onOff(src.autoReadOn));
        rules.put(Settings.KEY_CHUNK_PREF,
                Settings.chunkPrefName(src.chunkPref));
        rules.put(Settings.KEY_THREADS,
                String.valueOf(src.threads));
        rules.put(Settings.KEY_FALLBACK_ENGINE, effFallback);
        rules.put(Settings.KEY_DESKTOP_MODE,
                Settings.onOff(effDesktop));
        rules.put(Settings.KEY_PREFIX_CACHE,
                Settings.onOff(src.prefixCacheOn));
        rules.put(Settings.KEY_LIVE_STATS,
                Settings.onOff(src.liveStatsOn));
        rules.put(Settings.KEY_STEP_SHOTS,
                Settings.onOff(src.stepShotsOn));
        rules.put(Settings.KEY_END_VIEW,
                Settings.endViewName(src.endView));
        rules.put(Settings.KEY_SCREEN_AWAKE,
                Settings.onOff(src.screenAwakeOn));
        rules.put(Settings.KEY_BATTERY_WARN,
                Settings.onOff(src.batteryWarnOn));
        rules.put(Settings.KEY_PLANNER_STYLE,
                Settings.styleName(effStyle));
        return rules;
    }

    private void deleteRunMarker() {
        try {
            File f = runMarkerFile();
            if (f.exists()) f.delete();
        } catch (Exception ignored) { }
    }

    /**
     * v3.13 (R5): the launch reads the surviving marker
     * through RunMarker.launchVerdict. A stale marker (its
     * trail already ended, or it aged past a week) clears
     * silently; a resumable one becomes the goal card's offer
     * and its file STAYS until the user resumes or dismisses
     * (a second death must not lose the offer); anything else
     * keeps the v2.6 behaviour — the abrupt line, then clear.
     */
    private void checkRunMarker() {
        File f = runMarkerFile();
        if (!f.exists()) return;
        RunMarker m = RunMarker.parse(readTextFile(f));
        if (m == null) {
            feed("Last run ended abruptly (app or phone died).");
            deleteRunMarker();
            return;
        }
        boolean ended = false;
        if (m.trailName.length() > 0) {
            File tf = new File(trailsDir(), m.trailName);
            if (tf.isFile()) {
                ended = RunMarker.trailEnded(readTextFile(tf));
            }
        }
        int verdict = RunMarker.launchVerdict(m,
                System.currentTimeMillis(), ended);
        if (verdict == RunMarker.VERDICT_CLEAR) {
            deleteRunMarker();
            return;
        }
        if (verdict == RunMarker.VERDICT_OFFER) {
            resumeOffer = m;
            refreshResumeUi();
            feed("Last run was interrupted — Resume on the "
                    + "Agent screen starts its goal again.");
            return;
        }
        feed(RunMarker.abruptLine(m));
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

        /** v1.2: per-page read position for the read action.
         *  v3.15 (R7): one cursor per TAB — the map is keyed by
         *  tab index and readCursor() resolves the ACTIVE tab's
         *  cursor, so two tabs keep independent read positions
         *  (through v3.14 the single cursor reset on every URL
         *  change, which a tab switch would have triggered
         *  constantly). Read sessions key on tab|url for the
         *  same reason (sessionKey). */
        private final java.util.Map<Integer, Eyes.ReadCursor> readCursors =
                new java.util.HashMap<Integer, Eyes.ReadCursor>();

        private Eyes.ReadCursor readCursor() {
            Integer k = Integer.valueOf(activeTabIdx);
            Eyes.ReadCursor c = readCursors.get(k);
            if (c == null) {
                c = new Eyes.ReadCursor();
                readCursors.put(k, c);
            }
            return c;
        }

        private String sessionKey(String url) {
            return activeTabIdx + "|" + url;
        }

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
                readCursor().offset = 0;
            }
            readSessions.put(sessionKey(readCursor().url), s);
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
            ReadSession s = readSessions.get(sessionKey(readCursor().url));
            if (s == null) {
                settlePageText();
                s = collectReadSession(Integer.MIN_VALUE);
            } else {
                s = collectReadSession(s.candIndex);
            }
            Eyes.ReadChunk c = Eyes.parseRead(callJs(
                    Eyes.readTextAtJs(s.candIndex, readCursor().offset,
                            activeChunkChars), 15000));
            if (c.total < 0) {
                lastReadMainTier = false;
                return extractReadChunk(readCursor().offset);
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
            readCursor().resetFor(current);
            Eyes.ReadChunk chunk = extractChunk();
            String thinNote = "";
            if (Eyes.shouldThinRetry(current, readCursor().thinRetriedUrls,
                    chunk.total)) {
                readCursor().thinRetriedUrls.add(current);
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
            if (readCursor().offset >= chunk.total) {
                // Past the end (page shrank, or a repeat read after
                // the final chunk): restart from the top.
                readCursor().offset = 0;
                wrapped = true;
                chunk = extractChunk();
            }
            if (chunk.text.length() == 0) {
                return "error: no readable text found on this page";
            }
            int start = chunk.start;
            readCursor().offset = start + chunk.text.length();
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

        /** v3.9 (R1): choose with the same self-healing re-find
         *  as type. The scan variant returns ghChoose's own ok
         *  string (it names the option actually picked — the
         *  planner needs to hear it), so a healed success appends
         *  the re-find note to that string instead of replacing
         *  it; a genuine failure (no such option) passes through
         *  unchanged. */
        @Override public String choose(int el, String text) throws Exception {
            Eyes.Heal heal = healDecision(el);
            if (heal == null || heal.kind == Eyes.Heal.MATCH) {
                return callJs(Eyes.chooseJs(el, text), 15000);
            }
            if (heal.kind == Eyes.Heal.REFOUND) {
                String r = callJs(Eyes.chooseScanJs(heal.scanIndex, el,
                        text), 15000);
                if (r.startsWith("ok")) {
                    return r + " (element moved; re-found as #"
                            + (heal.scanIndex + 1) + ")";
                }
                return r;
            }
            return "error: element " + el + " not on page";
        }

        /** v3.9 (R1): toggle with the same self-healing re-find.
         *  For a radio group the perceived element is the group's
         *  head (its first member); the identity the heal
         *  compares is name+type, which every member shares, so
         *  a re-found member resolves the group from itself and
         *  the named choice is selected within it. */
        @Override public String toggle(int el, String text) throws Exception {
            Eyes.Heal heal = healDecision(el);
            if (heal == null || heal.kind == Eyes.Heal.MATCH) {
                return callJs(Eyes.toggleJs(el, text), 15000);
            }
            if (heal.kind == Eyes.Heal.REFOUND) {
                String r = callJs(Eyes.toggleScanJs(heal.scanIndex, el,
                        text), 15000);
                if (r.startsWith("ok")) {
                    return r + " (element moved; re-found as #"
                            + (heal.scanIndex + 1) + ")";
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
         *  (c) otherwise fall back to a search-engine HTML search
         *  (DuckDuckGo by default; the engine is a READING
         *  setting since v3.6 — SearchEngines).
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

            // Path (c), the fallback. v3.6: the engine is the
            // run's READING setting (SearchEngines — the mapping
            // is pure and host-pinned); DuckDuckGo, the default,
            // produces the pre-v3.6 URL and report byte-for-byte.
            String url = SearchEngines.searchUrl(runFallbackEngineId,
                    java.net.URLEncoder.encode(query, "UTF-8"));
            gotoUrl(url);
            return SearchEngines.searchedMessage(runFallbackEngineId,
                    query);
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

        // ---------------- v3.16: documents (R8) -----------------
        // The read_file action's body: the downloads store's
        // readable files, read in chunks exactly as a page is
        // (the run's chunk size, the page reader's framing —
        // Eyes.formatReadChunk with a "file NAME" tier, so the
        // loop's own read accounting reads it as a read). The
        // extraction is cached per run (the store cannot change
        // under a run except by the run's own page downloading
        // — and a name read before it lands simply was not in
        // documentNames when the prompt was built; the cursor
        // is per document, and reaching a document's end wraps
        // to its start on the next read, as the page cursor
        // does).

        private final java.util.Map<String, Integer> docOffsets =
                new java.util.HashMap<String, Integer>();
        private final java.util.Map<String, String> docTexts =
                new java.util.HashMap<String, String>();

        @Override public java.util.List<String> documentNames() {
            java.util.List<String> out =
                    new java.util.ArrayList<String>();
            if (downloads != null) {
                for (Downloads.Entry e : downloads.all()) {
                    if (DocText.isReadable(e.name, e.mime)) {
                        out.add(e.name);
                    }
                }
            }
            return out;
        }

        @Override public String readDocument(String name,
                                             int chunkChars) {
            Downloads.Entry entry = (downloads == null)
                    ? null : downloads.find(name);
            if (entry == null) {
                java.util.List<String> names = documentNames();
                return "error: no readable file named \"" + name
                        + "\" in the downloads store"
                        + (names.isEmpty()
                                ? " (the store holds no readable files)"
                                : " — readable files: "
                                        + joinNames(names));
            }
            if (!DocText.isReadable(entry.name, entry.mime)) {
                return "error: " + DocText.refusalFor(
                        entry.name, entry.mime);
            }
            String text = docTexts.get(name);
            if (text == null) {
                File f = new File(downloadsDir(), entry.name);
                if (!f.isFile()) {
                    return "error: " + name + " is listed in the "
                            + "store but its file is gone";
                }
                byte[] bytes;
                try {
                    bytes = java.nio.file.Files.readAllBytes(
                            f.toPath());
                } catch (Exception ex) {
                    return "error: could not read " + name
                            + " (" + ex.getMessage() + ")";
                }
                text = DocText.extract(entry.name, entry.mime,
                        bytes);
                if (text == null) {
                    return "error: " + DocText.refusalFor(
                            entry.name, entry.mime);
                }
                docTexts.put(name, text);
            }
            int size = (chunkChars > 0) ? chunkChars
                    : Eyes.READ_CHUNK_CHARS;
            Integer boxed = docOffsets.get(name);
            int off = (boxed == null) ? 0 : boxed.intValue();
            if (off > text.length()) off = 0;
            int end = Math.min(text.length(), off + size);
            String chunk = text.substring(off, end);
            docOffsets.put(name, Integer.valueOf(
                    end >= text.length() ? 0 : end));
            return Eyes.formatReadChunk(off, chunk, text.length(),
                    "file " + name);
        }

        private String joinNames(java.util.List<String> names) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(names.get(i));
            }
            return sb.toString();
        }

        // ---------------- v3.15: the tab operations (R7) --------
        // The Agent's tab surface against the Activity's tab
        // machinery. The counts and the active index are plain
        // field reads (volatile / list size); everything that
        // MOVES a view goes through the UI thread and waits, in
        // callJs's latch discipline (runOnUiAndWait), so when a
        // tab action returns, the next perceive really does see
        // the newly active tab. The read state follows the tab
        // (the per-tab cursors above), never the hands instance.

        @Override public int tabCount() {
            return tabViews.size();
        }

        @Override public int activeTabIndex() {
            return activeTabIdx;
        }

        @Override public String tabUrl(final int index) {
            if (index < 0 || index >= tabViews.size()) return null;
            final String[] out = new String[1];
            runOnUiAndWait(new Runnable() {
                @Override public void run() {
                    out[0] = tabViews.get(index).getUrl();
                }
            });
            return out[0];
        }

        @Override public String openTab(final String url) {
            final int[] out = new int[]{-1};
            runOnUiAndWait(new Runnable() {
                @Override public void run() {
                    out[0] = openTabInternal(url);
                }
            });
            if (out[0] < 0) {
                return "error: tab limit — " + RelevanceGuard.TAB_CAP
                        + " tabs already open";
            }
            if (url != null && url.length() > 0) {
                waitForPageSettled();
            }
            return "ok: opened tab " + (out[0] + 1)
                    + (url != null && url.length() > 0
                            ? " (" + url + ")" : "");
        }

        @Override public String switchTab(final int index) {
            if (index < 0 || index >= tabViews.size()) {
                return "error: no tab " + (index + 1) + " — tabs open: 1.."
                        + tabViews.size();
            }
            if (index == activeTabIdx) {
                return "ok: already on tab " + (index + 1);
            }
            runOnUiAndWait(new Runnable() {
                @Override public void run() {
                    activateTab(index);
                }
            });
            return "ok: switched to tab " + (index + 1);
        }

        @Override public String closeTab(final int index) {
            if (tabViews.size() <= 1) {
                return "error: cannot close the last tab";
            }
            if (index < 0 || index >= tabViews.size()) {
                return "error: no tab " + (index + 1) + " — tabs open: 1.."
                        + tabViews.size();
            }
            runOnUiAndWait(new Runnable() {
                @Override public void run() {
                    closeTabInternal(index);
                }
            });
            return "ok: closed tab " + (index + 1);
        }
    }
}
