# Ghost Hands Mobile v1 — build notes

Built Oct 24, 2026. The product: a goal-driven web agent that lives
entirely on the phone. Foundation reused from the on-device proof
(~/workspace/ghost-hands-mobile/BUILD_NOTES.md): same pinned llama.cpp
b11382 Android build, same JNI shim design, same model download flow.

## v3.4.0 (Oct 25, 2026) — Settings, In An Accordion

Ryan's ask: "Add more settings and put them into a collapsible
accordion." Host tests 910 -> 957 (+47, all green,
foreground-verified). No new permissions (the set is v3.3's
exactly: INTERNET, ACCESS_NETWORK_STATE, VIBRATE,
RECORD_AUDIO). No new JS — the accordion and the home page ride
framework views and WebView.loadUrl only, so the harness's
emitted-script validation surface is unchanged. Grammar
default still OFF; the crash A/B is untouched by this release.

THE ONE-CONTROL RULE, FIRST. Every setting has exactly one
control, living in the accordion. This app family's settings
history is why: in the GhostIDE line a duplicated "autosave"
control once shared a key and a view id with the real one and
silently erased the setting. So the BRAIN chip row and the
grammar switch (v2.6 / v2.6.1) MOVED into the accordion's BRAIN
section — same views, same handlers, same refresh methods,
same preference keys — and the model card keeps only status,
progress and the download button. Nothing exists twice.

THE MODEL UNDER THE UI. A new pure class, Settings, is the one
source of truth: it resolves a whole settings object from the
persisted string map (defaults, clamping, lenient booleans),
and serializes back to the same map. The Activity reads every
key once at launch (loadSettings) and writes the model's whole
canonical map on every change (persistSettings) — one read
path, one write path, no Apply button; a change persists in
the tap that makes it. MIGRATION IS BY CONSTRUCTION: the two
legacy keys keep their names ("model_id", "grammar_on") and
their encodings — fromMap resolves them through ModelStore's
own resolvers, toMap writes the grammar value through
ModelStore.grammarPrefValue — so an upgrade's stored brain and
grammar choices mean exactly what they meant to the inline
controls, byte for byte (host-tested, including the standing
rule that only an explicit "on" is on). activeModelId and
grammarOn survive as mirrors the existing code keeps reading;
the handlers refresh them from the model at every change.
Choice numbers SNAP to the nearest offered value (ties to the
smaller), so a hand-edited or future value lands on the
closest thing the UI can show and the two can never disagree.

THE ACCORDION. A SETTINGS card sits directly under the model
card: six sections (BRAIN, AGENT, READING, VOICE & SPEECH,
ALERTS, DATA) as tappable header rows with a chevron; one open
at a time; expansion is an instant visibility toggle (no
animation state to desync). An About block at the card's foot
(not a setting): version name/code from PackageInfo, the
active model + its file size, and the source repo URL as plain
text, refreshed with the model UI. HONEST LIMIT: accordion
expand/collapse and the settings screens' feel are
device-verified UI — the host harness covers the model, the
migration and every wired behavior below, not the tapping.

THE SETTINGS, AND WHAT EACH ONE ACTUALLY DRIVES.
(a) Model picker — relocated, unchanged semantics (idle-only,
downloads kept). (b) Grammar lock — relocated, unchanged
semantics (idle-only, default OFF, the A/B instrument: every
decide/stop/run_summary trail record still carries
"grammar_setting" and "model"). (c) Default policy pack — the
four packs as chips; the pick sets currentPackId too, so a
plain run starts from it; a loaded saved goal still brings its
own pack and a schedule-launched run still forces readonly.
(d) Max steps 10/25/40 — was the Agent.MAX_STEPS constant; now
a run parameter on a new full Agent constructor (the old
constructors delegate with the standing values, which is why
all 910 prior tests behave byte-identically). The "run" trail
record gains "max_steps", and the loop-exit stop names the
budget, not the constant. Route replays take the same
parameters — one knob set governs every Agent the app builds.
(e) Co-pilot assists on/off — offerAssist's first statement;
off, a stuck stop is a stuck stop, exactly pre-v3.3. (f)
Auto-read on arrival on/off — one && at the v1.3 trigger.
(g) Read chunk Auto/Large/Small — the loop's two chunk
consultations now go through Settings.readChunkChars(pref,
weight): Large is always 2400, Small always 1200, Auto is
v2.9's density judgement untouched. (h) Density settle on/off —
the settle loop's condition is the pure Eyes.settleMayProbe
(setting AND the v2.8 probe budget); off takes zero probes and
the read proceeds unsettled; on, the loop is probe-for-probe
the v2.8 loop. (i) Voice goals on/off — refreshMicUi hides the
mic chip outright; the recognizer is never touched while off.
(j) Speak the answer on finish — the rule, exactly: the
setting is on AND the app is in the foreground AND the run
ended DONE (the end card's outcome is "done"). Errors, stuck,
stopped and budget endings, and every off-screen finish, are
never spoken. The path reuses v3.3's SpeakText + engine; the
LISTEN button's own behavior is undisturbed (its utterance
listener was extracted verbatim into installTtsListener,
shared by both speak paths and installed once at engine init).
(k) Finish buzz on/off — gates ONLY the off-screen finish
vibrate in finishRunUi. The watch-CHANGED buzz is deliberately
NOT gated (a change is the watch's core signal), and the
section copy says so in the app. (l) Screenshot retention
10/20/50 — captureRunScreenshot hands the number to
Shots.excess, whose policy was already parameterized. (m)
Home page — a text field committing on Done/focus-loss
through Settings.normalizeHomePage: blank stays blank (nothing
loads at launch, the historical behavior), a bare host gains
"https://", whitespace inside / an empty host / a host with no
dot, no port and not "localhost" is rejected (null) — the old
value is kept and a toast says so. A set value loads once, in
onCreate after the WebView exists, and is mirrored into the
address bar.

GUARDS ARE NOT SETTINGS-SHAPED. The gates that touch guards
only ever REMOVE something: co-pilot off removes a rescue
offer, auto-read off removes a convenience read, settle off
removes polish probes, chunk forcing picks between the two
chunk sizes the loop already used. No guard's math, cap or
threshold is reachable from the accordion; the done-grounding
guard, the repeat-action guard, the governor and every pack
rule behave exactly as before at every setting combination
(the auto-read-off host test watches the grounding guard
correct an ungrounded done twice and then fall silent, by
design, with the setting off).

DEVICE PASS OWED (Ryan): open the accordion; flip model and
grammar idle-only and mid-run (locked); set max steps 10 +
co-pilot off and run the weather goal to feel the budget stop
with no rescue; set a home page, relaunch, watch it load;
speak-on-finish with a quick done run in the foreground.

## v3.3.0 (Oct 25, 2026) — The Remaining Four

The four feature pitches held back from v3.0–v3.2, shipped
together: teach a route, a screenshot trail, read-aloud
answers, and the co-pilot tap. Host tests 855 -> 910 (+55,
all green, foreground-verified). No new permissions (the set
is v3.2's exactly: INTERNET, ACCESS_NETWORK_STATE, VIBRATE,
RECORD_AUDIO). Grammar default still OFF; model defaults
untouched. Three new trail event types, all registered and
sweep-covered: "route", "screenshot", "assist".

ITEM 1 — TEACH A ROUTE (macros). A Teach chip on the goal
card opens a dialog that states the security line BEFORE
recording starts, then teach mode: the user navigates with
the live address bar and uses the page as themselves, while
their taps (observed through an OnTouchListener that never
consumes the event — the page receives every tap as if the
app were not there) are identified by Eyes.pointIdentityJs
(document.elementFromPoint, walked up to the map collector's
own candidate set, read with the heal's identOf) and recorded
as steps anchored by ELEMENT IDENTITY — a link by name+href,
a control by name+tag+type (Routes.identityKey, the repeat-
guard's keying rule). Never coordinates, never map numbers.
A tap on a typable field starts a pending field instead: its
step is minted when the user moves on, by re-finding the
field through the heal's scan + decideHeal and reading its
current value by scan index (Eyes.valueScanJs). THE SECURITY
LINE, held in three places so no single bug can cross it: a
password field's value is never read in the page (valueScanJs
reports the field redacted without touching .value);
parseFieldValue refuses a password value a second time on
the Java side (a payload claiming one parses with an empty
value); and Routes.typeStep — the one place type steps are
minted — drops the value at construction for a password
field whatever the caller passed. The stored step records
only that a field was filled, marked redacted; the serialized
store carries no text key for it. Corollary, stated plainly:
a tapped password field becomes a redacted step whether or
not anything was typed, because the app refuses to even
measure a password field's contents. Routes persist by name
in one JSON preferences value, SavedGoals' store discipline
(tolerant parse, replace-by-name). A field left empty earns
no step; a re-tap of the pending field is the same
demonstration, not a flush (replay would otherwise type
twice); a field that leaves the page before its flush is
skipped out loud in the teach bar, never silently.

REPLAY (Agent.runRoute) is governed like a run: the "run"
event opens the trail ("Route: <name>"), a "route" event
marks the start, and the start navigation plus every step is
classified by the pack selected for the replay, recorded
BEFORE execution with provenance "route_replay" on its
govern / approval / execute / result records, and executed
through the same Hands machinery — so the hands' heal stands
behind every step exactly as for a planner action. Each step
resolves its identity live against a fresh perception
(Routes.resolve: the unique identityMatches hit; none or
several resolves to null, never a guess). An unresolvable
step stops the replay with an error stop NAMING the step
("route step 2 could not be found on this page: tap
\"Dashboard\""). A consequential step (a form submit) ASKS
at replay time under the pack, exactly as in an agent run —
a recording is not a blank check. A redacted step is never
executed at all: the replay pauses ("route" events,
user_fill -> user_filled with provenance USER), the user
fills the field in the page themselves and presses Resume;
Stop (or the pause expiring) ends the replay as stopped,
naming the step. A replay needs no model — it never plans —
so it runs before the model download lands. HONEST LIMIT:
routes are identity-anchored, not immortal. A site redesign
that renames or re-shapes an element breaks its step, and
the replay says which step failed rather than guessing.

ITEM 2 — SCREENSHOT TRAIL. When a run (agent run or route
replay) ends with a stop event, the finish path — shared by
both run kinds since this release (finishRunUi, extracted
from startAgent's thread-finally) — first draws the WebView's
visible content to a bitmap and writes it as a JPEG (quality
80) into the app's private shots/ store, named for its trail
(trail-<stamp>.jpg, Shots.fileNameForTrail), then records a
"screenshot" trail event naming the file, all BEFORE the
answer card is built — so the card, live and Past-rebuilt
alike, carries the photo as data (AnswerCard.screenshotFile,
from the event, the one formatter for both). Retention is
Shots' pure policy: at most the newest 20 screenshots are
kept, older deleted as new ones land (host-tested). The card
shows the photo above its text when the card names one and
the file is still in the store; an old card whose photo was
pruned simply shows no photo. The share TEXT stays
text-only — no file name appears in cardText or shareText
(pinned). Capture is strictly best-effort: any failure (a
zero-size view, a dead latch, a full disk) is swallowed and
the run ends exactly as it would have. HONEST LIMITS: the
screenshot shows the VISIBLE view only — the viewport as the
run left it, not the full page; and the bitmap capture
itself is device-verified only (the host harness pins the
naming, the retention policy and the card data assembly —
it cannot draw an Android view).

ITEM 3 — READ-ALOUD ANSWERS. A Listen chip joins Share card
on the answer card, driving the framework TextToSpeech (no
new permission — TTS needs none — no dependency). The engine
is created lazily on the first tap; on success the card is
spoken, and while speaking the same chip reads "■ Stop" and
stops the speech. What is said is SpeakText.of (pure,
host-tested): goal, answer/outcome, the watch banner when
the run was watched, the "What it found" excerpt when the
findings fallback filled it, the facts, and the source by
NAME — the source title when there is one, else the host's
name with "www." dropped. A raw URL is never spoken:
SpeakText.scrub rewrites any URL-shaped token that slipped
into the goal, answer or a fact (a planner can write one
into its summary) to its host name, and the watch diff's
"->" becomes the word "to"; numbers are left as written —
the engine voices digits, currency and percents itself, and
the tests pin that no "http" survives in any spoken shape.
If TTS is unavailable or fails to initialize, the control
latches disabled with the label "Listen n/a" (SpeakText's
label/enabled rules, the VoiceGoal pattern). HONEST LIMIT:
voice quality, pronunciation and pacing are the phone's TTS
engine's, not the app's — the app decides what is said, not
how it sounds.

ITEM 4 — CO-PILOT TAP. When an agent run would stop as
STUCK — and only then: the unchanged-map detector, the
repeated-action counter, and the planner's own stuck action
all route through Agent.offerAssist; errors, budget stops,
denials and the user's Stop never do — Copilot.eligible asks
three questions (reason is exactly "stuck", the app is in the
foreground, the run has spent fewer than Copilot.MAX_ASSISTS
= 2 assists). An eligible run pauses instead of stopping:
the feed says, in substance, "Stuck here. Tap the element
yourself in the page, then press Resume — or press Stop.",
and the loop blocks in Ui.awaitUserTap with the PACK'S
APPROVAL TIMEOUT as the wait budget — the pause counts
against the pack clock exactly like an approval wait, and
expiry resolves as a decline (the run stops stuck), never
as consent. While paused, the user's taps are identified
through the current perception: the point's identity
(pointIdentityJs) matched against the run's last perceived
map by the heal's rules (WebHands.matchIdentity — a unique
match or an honest "could not be identified", which keeps
the pause waiting). The pause itself is Copilot.Pause, a
pure state machine the panel's buttons answer to: Resume
lands only from TAPPED (a tap was identified), so a Resume
pressed too early says "tap an element first" and keeps
waiting. The trail records "assist" offered, then tapped —
with provenance USER and the element's descriptor: the tap
was the user's hand, and it is never recorded as an agent
action (the loop test counts the run's executes to prove
it). On resume the loop continues with the history note
"the user tapped '<name>' to get past a stuck point" and
its stuck state reset (the repeated-action key is forgiven;
at the unchanged-map stop the step re-perceives and decides
fresh). Declined or expired: an "assist" declined event,
and the run stops stuck exactly as before. Backgrounded
when stuck fires: no pause is offered and nothing changes
(the loop test's backgrounded Ui throws if a pause is even
attempted). HONEST LIMITS: co-pilot is foreground-only by
design; its waits count against the pack clock (above); and
the touch identification is device-verified only (the host
harness pins eligibility, the pause machine, and the full
loop with a scripted rescue — it cannot feel a fingertip).

FIXTURE DISCLOSURE: Agent.Ui gained three methods in this
release (foreground, awaitUserTap, awaitUserResume), so the
harness's two pre-existing Ui stubs (stubUi, approvingUi)
gained inert implementations — foreground() false (the
co-pilot is never offered on those "screens") and pauses
declined — which is exactly what keeps all 855 pre-v3.3
scenarios ending the way they always have. No existing
assertion changed; no production default changed.

DEVICE PASS OWED (Ryan): teach a short route on a real
site (a login with a password step is the acid test — the
replay must stop at the password step and continue after
the manual fill); replay it after killing and relaunching
the app (persistence); end any run and check its card shows
the screenshot, then open the same run from Past and check
again; tap Listen on a card (and on a watched card) and
confirm the source is named, never spelled out; drive a run
into a stuck page on screen and take the co-pilot tap —
then background the app mid-stuck on another run and
confirm it just stops stuck. The weather goal still owes
its first end-to-end answer; run it on defaults (Q5,
grammar OFF) on this build.

## v3.2.0 (Oct 25, 2026) — Watch Mode

A saved goal can now be WATCHED. Its scheduled checks keep
the v3.0 mechanics exactly — notify-and-launch, readonly, the
user taps and presses Start — but a finished watched run now
produces a VERDICT: its answer is compared against the stored
baseline from the last completed watched run, and the user is
told only what matters. Changed: a notification and a banner
with the diff. No change: silence. The watch is toggled per
goal in the saved-goals list (the 👁 chip); the list shows a
"Watching" marker and, under the goal, its baseline line —
"last check Sun 7:02 AM: Kahului $698, Honolulu $734".

ITEM 1 — THE COMPARATOR (WatchCompare, pure). The design
problem, stated plainly: the 1.5B phrases every answer
differently, so comparing done summaries as text would cry
wolf on every check. The comparator therefore compares the
run's EVIDENCE: its signature is the numeric values in
(a) the scratchpad facts and (b) the done summary, each
value carrying a label — up to three normalized words from
the text since the previous number — so a difference reads
"kahului fare: $698 -> $617". Values tokenize exactly as
the grounding guard's numberTokens does (grouping commas
dropped, decimals kept); labels normalize case and
punctuation away. Comparison cancels in phases: exact
(label, value) pairs; leftover values that survive under a
drifted label (the rewording shield — this is what makes
two differently-worded summaries with the same numbers a
NO CHANGE, pinned in the harness, including the case where
one run noted its facts and the other never did); leftovers
paired by shared label; a single leftover pair; the rest
reported as "new:" / "gone:". Each distinct value enters
the signature once (facts scanned first, so a noted value's
own label wins over the summary's wording of it): a fare the
planner noted and then repeated in its summary is one piece
of evidence, not two, and whether the next check's planner
notes it again cannot change the count. A run with NO
numbers anywhere falls back to normalized-summary equality
(lowercased, punctuation collapsed) — that fallback exists
for numberless goals and only there. Only DONE runs produce
a signature; every other ending produces none and never
touches the baseline.

ITEM 2 — BASELINE STORE + WATCH FLAG (SavedGoals). A saved
goal gains a watched flag and a baseline: the signature,
summary, and completion time of its last watched DONE run,
serialized inside the goal's own record. The first watched
done run sets the baseline and reports "baseline set" —
never a change. Each later watched done run compares
against the baseline it found, then REPLACES it: the diff
is always against the previous check, and the UI copy (the
goal card, the banner's "since") says so. Unwatching clears
the baseline with the flag; re-saving a goal under its name
carries the watch and baseline over, as it already carried
the schedule; deleting the goal deletes both. Parsing stays
backward-tolerant: pre-v3.2 records parse unwatched, and a
malformed baseline costs the baseline only, never the goal.
An empty-but-present signature is a real baseline (the
numberless case above, judged by its summary).

ITEM 3 — VERDICT SURFACES. (a) The answer card gains a
watch banner from the run's new "watch" trail event
(registered in Trail.EVENT_TYPES and covered by the
harness registry sweep, plus an explicit record/rebuild
test): "Watch: no change since Sun Oct 25, 7:02 AM.",
"Watch: changed since ..." followed by the diff lines,
"Watch: baseline set — later checks compare against this
run.", or, for a run that ended without an answer,
"Watch: couldn't check this time — the baseline is
unchanged." Live and Past-rebuilt cards render the same
banner, as with every other card section. (b) The
notification matrix, kept deliberately simple and
predictable: watched + CHANGED → a notification carrying
the first diff line, posted foreground or background (the
change is the one thing the watch exists to surface), plus
the finish buzz; watched + NO CHANGE → no notification, no
buzz, banner only — silence is the feature; watched + not
done → the ordinary v3.0 finish rule (a failed check is
worth knowing, on the off-screen rule as before); first
watched run (baseline set) → also the ordinary rule.
(c) The saved-goals list's baseline line, above. The watch
event is recorded against the baseline the verdict was
judged on BEFORE the baseline advances (record-before-it-
moves), on the agent thread beside the v3.0 finish path;
the run's watch ownership is pinned at Start, so loading a
different goal mid-run cannot steal or lose a verdict.

TESTS. 855 host checks pass (825 pre-existing, unchanged,
plus 30 new): signature extraction and label normalization;
the reworded-same-numbers pin; a label-paired fare drop in
exactly one diff line; appeared/vanished lines; the
numberless fallback (same and different); signatures from
trail events (done only; duplicate/unadded notes handled as
the notebook holds them); the baseline store round-trip,
legacy records, junk baselines, the numberless baseline,
re-save carry-over and unwatch clearing; the verdict matrix
end to end (baseline set → quiet repeat → one-line change
diffed against the previous check → failed check leaves
the baseline standing); and the card banner live and
rebuilt from trail text.

HONEST LIMITS. Label pairing is a heuristic: two different
facts can share a label window ("fare" twice over), and a
heavily reworded check can trade a paired change line for
gone + new lines saying the same thing less prettily.
Numbers are compared as written: "$698" vs "698 dollars"
under genuinely different labels reads as gone + new rather
than one change (the value-cancellation phase rescues the
common case, where the value itself survives rewording).
Two distinct facts that share ONE value ("Kahului $698,
Honolulu $698") are one signature entry between them — the
price of counting each value once — so if one of the pair
moves and the other doesn't, the verdict reports the new
value appearing rather than which twin moved. Scheduled
checks remain notify-and-launch and readonly — the watch
judges runs, it does not make them unattended. As since
v3.0, notifications surface only if the user has allowed
them for the app in system settings (POST_NOTIFICATIONS
remains undeclared); a no-change check is silent by design,
not by permission. The grammar A/B is untouched: the
grammar lock still defaults OFF, and the model defaults
stand. No new permissions, no new trail types beyond
"watch", no contract change.

## v3.1.0 (Oct 25, 2026) — Break the Loop

THE LOOP DIAGNOSIS. V3.0's first field run
(trail-20261025-025203, the Hawaii fares goal) did almost
everything right — voice goal in, site-native search, the
dense-chunk read serving real fares at step 3 (Honolulu
$734, Kahului $698, Kailua-Kona $64x) — and then threw the
run away: at step 4 the planner proposed tapping element
#1 "Google Search" for the third time (it had landed at
step 2, after the race retry re-decided it), the run ended
at 5 steps / 745 s, and the answer card's answer line was
the bare "Error: Element 1 not on page." — the fares nowhere
on it. Three defects, three fixes; everything else from
v3.0.0 stands untouched (fit ladder, tiered reader, heal,
grammar default still OFF, model defaults, icon, and the
791 pre-existing tests — 825 pass now, +34 new).

ITEM 1 — REPEAT-ACTION GUARD (RelevanceGuard + the Agent
ledger). Why nothing stopped the loop: every older guard
keys on CONSECUTIVE state — the stuck counter counts
per page+action, the repeat-search rule needs "nothing
moved since" — and the step-3 read sitting between the two
taps disarmed each of them in turn. The fix asks the
question against the RUN: the loop keeps a ledger of the
semantic keys of SUCCESSFULLY executed actions (a failed
attempt did nothing and proves nothing, so it never enters
the ledger; its retries belong to the race / stale-stash
machinery within the step and the stuck counter across
steps). Keys are semantic, not positional: for tap/type,
the element identity the v2.0 heal itself verifies —
name + href when the element carries an href, else
name + tag + type; for goto, the URL. Search keeps its
dedicated corrections; note/done/stuck are exempt by
nature; read/scroll/back/wait are exempt (pagination and
navigation are legitimately repetitive). A re-proposal of
an executed key is corrected through the standard guard
machinery — trail "guard" event, feed line, and a history
note naming what was already done ("you already tapped
'Google Search'") and steering at the moves that make
progress: read more, note the facts, or finish. The cap
is 2 corrections PER KEY per run; after it, the proposal
executes and the stuck machinery owns any further
repetition. Placed before the stuck counter, so a
corrected repeat costs no stuck credit. Pinned pure (key
derivation incl. same-name/different-href and
identity-genuinely-changed) and end to end (a scripted
run replays the field's tap / read / tap pattern: the
repeat is corrected, the tap executes exactly once, and
the planner finishes with a done whose numbers ground
against the read fares; a second scripted run pins the
cap — two corrections, then the repeat executes).

ITEM 2 — ANSWER CARD FINDINGS FALLBACK (AnswerCard).
A non-done ending wasted the run's real findings: the
card led with the raw stop/error line as its answer.
Now, when a run ends WITHOUT a done summary AND its
scratchpad is empty, the card (and its share text) gains
a "What it found (excerpt from the source page):"
section carrying the opening text of the LAST successful
read the trail records — a planner read's ok result, or
an auto-read's extraction — with the read framing
stripped and the text capped at 400 chars. When a done
summary exists it stays the answer; when notes exist
they stay the facts section; the fallback fires only
when both are absent. The outcome line is also labelled
in the on-screen card now, as the share text already
labelled it: "Answer:" for a done, "Outcome:" for every
other ending — a bare error no longer poses as an
answer. Host-tested against the field run's recorded
shape (error stop after a fares read — the card shows
the fares excerpt) plus the done/notes/no-read shapes.

ITEM 3 — SCRATCHPAD NUDGE (Agent). The field planner
never used note once, though the fares were exactly what
the scratchpad exists for. The contract already names
the action; what was missing was a reminder at the
moment a fact is in front of the planner. The FIRST
successful read of a run now appends a one-time hint to
that read's history entry (Agent.NOTE_HINT): record a
fact the goal needs with the note action before moving
on; the final answer is checked against the notes and
the reads. Once per run, never repeated; a run that
never reads successfully never sees it. It rides the
read's history entry, so the fit ladder's rung 1 (which
stubs a read chunk at its header colon) takes the hint
with the chunk text it annotates. Host-tested: present
exactly once from the prompt after the first read on,
absent before any read and absent in a read-less run.

HONEST LIMITS. The repeat guard corrects repeats of
EXECUTED actions, keyed on identity — a planner that
varies its mistakes (different elements, different dead
ends) can still wander, and the stuck counter and the
step cap remain the backstop, as before. The identity
key for an href-less control is page-blind (two sites'
identical "Search" submit buttons share a key), so a
cross-page re-tap can draw a correction it did not earn;
the per-key cap of 2 bounds that harm, and the
correction's note tells the planner what was assumed.
The findings excerpt is a view of the recorded trail,
whose read results are truncated at 300 chars — the
excerpt can only ever be as complete as the record. The
nudge is one hint, not a discipline: whether the planner
notes remains its choice, and the done-grounding guard
still judges the final answer against ALL its evidence
(reads as well as notes), so an unnoted but well-read
run answers exactly as before. No new trail event types
(the guard reuses "guard"), no new permissions, no
contract change (the note hint is history text, so the
332-est contract pin stands untouched).

## v3.0.0 (Oct 25, 2026) — The Feature Release

Four features and a rider, all on top of v2.9.0 untouched:
the fit ladder, the tiered reader, the identity heal, the
grammar A/B (default still OFF), the model defaults, and the
launcher icon all stand as they were. 791 host tests pass
(714 pre-existing, unchanged, plus 77 new for this release).

ITEM 1 — FACT SCRATCHPAD (a governed notebook for multi-page
goals). New planner action "note" carrying a short fact
string, classified readonly by the governor and allowed under
every policy pack. Notes live in a pure Scratchpad: dedupe by
normalized fact text, cap 12 facts, per-fact cap 200 chars
(truncated with an ellipsis marker). Each fact is stamped
with the URL + title of the page it was noted on, and every
note lands in the trail as a registered "note" event
(recorded with its verdict — added / duplicate / full —
BEFORE the notebook is touched). The notebook renders into
the prompt as a compact FACTS block inside the stable prefix;
an empty notebook leaves the prompt byte-identical to v2.9.
The block joins the fit ladder as its LAST rung: facts are
stubbed to their count only after history, preview, map, and
section have all been degraded, and the ladder reports it
(the "fit" trail event carries facts_stubbed, and the feed
says so) — a run never silently loses its evidence to the
budget. DONE-GROUNDING UPGRADE: the v2.1 numeric done guard
checked only the summary's shape (any digit passed, invented
or not). It now checks against evidence — the run's read
history union its noted facts: a summary number grounds the
done only when that number appears in the evidence; invented
numbers are corrected exactly like numberless ones (same
reason, same cap of 2). A plain statement of inability still
grounds with no digits. HONEST LIMIT: facts are
planner-asserted evidence, not proof. The guard treats them
as the run's own record of what it read; it cannot verify a
note against the page, only against the run's history.

ITEM 2 — SAVED GOALS + SCHEDULED CHECKS. The goal card gains
Save goal / Saved goals: a persisted list (name, goal text,
policy pack id, optional daily time) stored as one JSON
preferences value. Tap a saved goal to load it; per-goal
controls set or remove its daily time and delete it
(deleting disarms its alarm). Scheduling is AlarmManager
(setAndAllowWhileIdle, no exact-alarm permission) firing a
notification — "Time for your check: <name> — tap to run" —
whose tap opens the app with the goal loaded ready to start.
HONEST SHAPE, stated plainly: scheduled checks are
NOTIFY-AND-LAUNCH, not unattended background browsing.
Android restricts background browsing, and v3.0 does not
attempt it: nothing browses until the user taps the
notification and presses Start. A check-launched goal runs
under the READONLY policy whatever pack it was saved under,
and the goal card says so before Start. Two more limits,
also plain: alarms do not survive a reboot (no boot receiver
in v3.0; the app re-arms every scheduled goal each time it
opens), and doze can drift a fire time by minutes.

ITEM 3 — VOICE GOALS. A mic button beside the goal field
uses the framework SpeechRecognizer (no new dependencies).
First tap requests RECORD_AUDIO at runtime — the ONLY
permission this release adds to the manifest. Recognized
text goes INTO the goal field for review and editing; it
never auto-starts a run. Where the device has no recognizer,
the button shows disabled with a "Voice n/a" label. HONEST
LIMIT: the framework recognizer may be network-backed
depending on the phone's speech service — the audio path is
the phone vendor's, not this app's, and on a phone whose
speech service is cloud-based the dictated goal leaves the
device. The app adds no audio handling of its own.

ITEM 4 — ANSWER CARDS. When a run ends (done, stuck, error,
stopped, budget, denied) the log card gains a third view,
Card: the goal, the outcome line (the done summary, or the
stop reason in plain words), the final page's title + URL as
the source, the run stats (steps, seconds, prompt reuse)
from the run summary, and the scratchpad facts when any
exist. A Share button sends the plain-text version (goal,
answer/outcome, source, date) through the share sheet. The
same card opens for PAST runs from the trail view (the Past
chip lists trail files newest first): the card is a pure
formatter over trail events — one builder for live runs and
history alike — so a pre-v3.0 trail still cards (its goal
falls back, its facts section is empty). A trail with no
stop event makes no card.

ITEM 5 — FINISH NOTIFICATION (the rider). When a run ends
with the app in the background, a notification carries the
outcome line (truncated to notification length) with one
short vibrate; its tap reopens the app on that run's answer
card. Foreground endings show the card alone. HONEST LIMIT:
this release deliberately does NOT declare the
POST_NOTIFICATIONS permission (RECORD_AUDIO is the release's
only new permission), so on Android 13+ these notifications
— check reminders included — appear only when the user has
allowed notifications for the app in system settings. The
app posts regardless; the OS decides.

CONTRACT BUDGET. The note action's syntax and rule joined
the planner contract, paid for with the v2.4 discipline
(trims in sentences no test or guard note quotes verbatim:
the one-action reminder compressed, two fillers dropped);
the block holds its 332-est pin and the standing text its
292 cap. No pre-existing test was changed for this release;
two latent-defect lessons from v2.7/v2.9 are pinned instead:
every Trail event type in the registry is swept by a test
that records it, and the loop test emits both new types
("run", "note") end to end.

## v2.9.0 (Oct 25, 2026) — The Fit Guarantee

THE GUARANTEE. From this version, no run dies of prompt overflow
the way the v2.8 weather run did. Before every engine call the
assembled step prompt is measured with the budgeter's estimator;
if it does not fit the 1,700-est-token working budget, a
deterministic degradation ladder trims it until it fits, and if
nothing fits, the run stops itself with the honest reason "page
too dense for the model's context" plus the run summary. The
engine's "ERROR: prompt too long for context" can no longer be a
run's epitaph when the overflow was knowable in advance. The
guarantee is in ESTIMATOR terms: the ladder guarantees the
ESTIMATE fits. A pathological page could still tokenize denser
than the estimator predicts (see the audit below for how the
estimator was tightened and what it still assumes); the app
cannot promise what it cannot count, and this note does not
claim otherwise.

THE FIELD DEATH IT ANSWERS (trail-20261025-015133, v2.8.0). The
first weather run where the machinery mostly won: the v2.2 race
retry fired live, the heal's re-decided tap landed on the
AccuWeather result, the v2.8 tiered reader served "chars 0-2400
of 9074 (main content)" — and step 5 died on the engine's
prompt-too-long error. The v1.8 budgeter trimmed HISTORY to the
budget, but on an element-dense page the CURRENT perception
itself (a 40-element map, a content-led preview, the read-state
accounting, and the 2,400-char read result sitting in history)
is the heavy part, and nothing degraded it before the call.

ITEM 2 FIRST (the estimator audit — the ladder is only as honest
as its ruler). The step-5 prompt's composition was reconstructed
part for part and measured with the v2.2 estimator frozen in the
test harness: 2,367 est tokens against the raw text, i.e. the old
estimator would have called a 2,574-token prompt 2,367 — and,
worse, a slimmer decide in the same shape could pass the old
ruler while still overflowing the 2,048 context. The divergence
is the HOURLY TEXT: AccuWeather-class main content is fragment
lines ("54°", "10%", "2 AM", "RealFeel® 55°", "Wind NNE 6 mph")
that tokenize at roughly a token per 2 chars, twice the prose
rate the estimator charged. One 2,400-char hourly chunk: old
estimator 600 (pure prose rate — its "http" line rule never
fires), tightened 700. THE FIX: a third estimator rule beside the
http one — a line with at least 2 digits AND digits making up at
least 1/3 of its non-space chars is charged at chars/2; per line
the estimator takes the MAX of the applicable surcharges, never
the sum. The 1/3 threshold was picked against the shipped
corpus: history's own read-header lines run 12-27% digit share
and stay prose-priced (pinned by test), hourly fragments run
33-100%. THE REGRESSION PAIR, pinned in the harness: the
reconstructed composition rates 2,574 (tightened) BEFORE
degradation — over budget — and the ladder's output rates under
budget after. The estimator's other assumptions are unchanged
(prose at chars/4, URL lines at chars/3) and remain
approximations: they are calibrated for the page shapes this app
actually reads, not universal truths.

ITEM 1 (the ladder — Agent.fitPrompt, pure over the assembled
parts; the loop applies it to every decide). Rung 0 is the
standing buildPrompt, byte-identical for prompts that already
fit. Then, least damaging first, re-measuring after each rung:
(1) HISTORY READ STUBS — every history read chunk shrinks to the
fixed small form (step line + the read accounting header +
"(read text omitted to fit the prompt budget)"); the agent keeps
knowing WHAT it read and how much, and can read again.
(2) PREVIEW TRUNCATION — the page-text preview drops to 120
chars. (3) MAP REDUCTION — element names cap at 24 chars and
hrefs at 40 (3a); if that is not enough, the map keeps the top 24
elements (3b), priority to the controls (search inputs, buttons,
form fields, selects) and to goal-matching links, original
numbers and map order preserved (never renumbered), headed by
"map: 24 of 40 elements (page dense)" so the model knows the
map is a window, not the page. (4) SECTION COUNTS — the
auto-read section's text leaves and only its accounting remains,
as a note in the history ("note: this page was auto-read (chars
2400-7588 of 9074) but its text is omitted from this prompt to
fit the budget; use read to see it."); the arrival banner goes
with the text, because it would otherwise claim the text is
above when it is not. NOT degradable, at any rung: the contract,
the goal, the page line, and the guards' inputs (the ladder
renders from the same perception the guards judged). If rung 4
still does not fit, fitPrompt returns the non-fit and the loop
stops with reason "budget" and the dense-page summary — honest
by construction, since an irreducible prompt means the goal
itself (the only part that can be that big and untouchable)
exceeds the context. On the reconstructed field composition the
ladder fits at rung 3 (map capped), est 1,687, with the auto-read
section's next chunk fully intact: the step-5 decide that died
now goes to the engine with the forecast in it. When any rung
fires, the trail records a "fit" event (rungs, est tokens) and
the feed says the page was dense and the prompt was trimmed.
The old buildPromptWithinBudget stays in the tree, still pinned
by its tests, but is no longer the loop's fitter.

ITEM 3 (adaptive read chunks). The current page's perception
weight is measured in the same estimator terms (page line + map
+ preview, laid out as the prompt lays them). At 600 est tokens
or more the page is dense and read serves 1,200-char chunks
instead of 2,400 (the dense rung also means one read cannot
single-handedly blow the following decide), and the read result
says so: "(read smaller chunks on this dense page)". Chunk,
cursor, and end-of-text semantics are unchanged — a dense page
just takes more reads to walk. THRESHOLD CALIBRATION, stated
plainly because the margin is thin: the field AccuWeather
perception weighs 614; a SATURATED Wikipedia map (40 prose links
plus a prose preview, the map's char caps full) weighs 546; the
threshold sits at 600, between them, close to the ceiling the
map's own caps impose on perception weight. A maximally heavy
Wikipedia-class page can cross it and simply gets smaller
chunks — a mild cost, not a failure — but there is no room to
widen the margin from inside the current caps, and this note
says so rather than dressing the threshold up as roomy.

ALSO FIXED (a latent v2.7 defect, found by this version's test
work and disclosed in full): since v2.7 the loop has recorded
"stale_stash" trail events when the v2.7 stash-refresh retry
fires, but the event type was never registered in Trail, and
Trail.record throws on unregistered types — the retry would
have killed the agent thread with an exception the first time
it fired on device, instead of re-deciding. It has never fired
on device (every field trail through v2.8 closes with a normal
stop + run summary; the v2.8 run's race retry was the v2.2
path, not this one). v2.9 registers "stale_stash" (and the new
"fit") and the harness now runs the retry end to end: stale tap,
identical fresh perception, re-decide, done — it throws without
the registration and passes with it. No v2.7 logic changed;
this is the registry catching up to the code that was already
shipped.

HOST PROOF. 714 checks, 0 failed (foreground; 667 pre-existing,
none edited — the only pre-existing test code touched is the
stub Hands' read() signature, which follows the interface's new
read(int)). New coverage: the digit rule's units (fragments at
chars/2, the read header NOT surcharged, a URL+digit line pays
its worst rate once); the estimator regression pair (frozen
2,367 vs tightened 2,574 on the reconstructed composition; chunk
600 vs 700); the stub form's units (accounting kept, text gone,
non-reads and errored reads untouched, inputs unmutated); rung
isolation fixtures (rung 1 closed by stubbing alone with the
preview whole; rung 2 closed by the preview truncation with the
map unreduced; rung 3 closed by the reduced map with its header,
the search box and the goal-matching link kept unrenumbered and
a tail element gone; rung 4 closed by counts alone with the
banner removed); the ladder's end (a giant goal does not fit,
prompt null); the map helpers (renderElements at the standing
caps is render() byte for byte; topElements keeps 24 ascending
with controls and matches); the perception-weight pins (field
614, saturated-Wikipedia 546) and the chunk predicate; the trail
registrations; and three loop runs (the stale-stash retry
end to end; the honest dense stop — reason "budget", the fit's
summary, a run summary, and NO decide event, the engine never
called; adaptive chunks captured at 1,200 on the dense page and
2,400 on a light page).

GATES. Manifest versionCode 21 / versionName 2.9.0. Build green;
apksigner signer SHA-256
de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b
(the preserved Oct 24 keystore; upgrades install in place, models
kept). aapt2 badging verified 2.9.0 / code 21. Full arm64-v8a lib
set (libllama, libggml, libggml-base, libggml-cpu, libghhands,
libomp). No lambdas (the build's dex scan passed). The v2.8
launcher icon is unchanged. Delivered APK: 3,131,659 bytes,
sha256 ca3e94d6641bbb72bd92ea9bbc8095ea80e03b93dd0dff2fc8e665a7b6bc65b8
(size and hash verified with foreground commands on the
delivered copy, per the v2.8 lesson).

HONEST LIMITS. (1) The fit guarantee is in estimator terms (see
the head of this section): the estimator is calibrated, not
exact, and a page denser than any shape in the audit could still
diverge; what v2.9 removes is the KNOWN divergence and the
unknowing death. (2) Degradation is lossy by design: a run on a
dense page decides from stubs, a shorter preview, and a windowed
map — the guards' view is never degraded, but the model's is,
and a goal whose answer lives only in a stubbed chunk needs the
agent to read again (the stub tells it exactly where). (3) The
adaptive-chunk threshold's margin is thin by construction (see
item 3). (4) Nothing here changes the eyes' fidelity: iframes,
shadow DOM, and canvas still read sparse, and weather.com-class
pages are still the hard case. (5) The grammar A/B is untouched:
the grammar toggle remains, default OFF, and the v2.6.1 protocol
stands — the next weather run on defaults (grammar off, Q5)
remains both the crash A/B's next data point and, now, the fit
guarantee's first device trial. (6) Device proof of v2.9 itself
is Ryan's install + the weather goal re-run; the host harness
proves the ladder against the reconstructed composition, not
against the live AccuWeather page.

## v2.8.0 (Oct 25, 2026) — Real-Site Reading (+ launcher icon)

WHAT THE EXTRACTOR DOES NOW. Through v2.7 the read path walked
one root — the first <main>, else <article>, else the body — in
document order, so on JS-heavy real sites the front of every read
was header/nav/aside chrome and the content sat past the first
chunks (the v2.1 field run: weather.com yielded 2,390 chars of
navigation skeleton, and the run died of prompt overflow before
reaching the forecast). The read path is now tiered. A collector
script (Eyes' read-sense) scores candidate containers —
article/main/section/div in document order — with deterministic
metrics from the same visibility-blind walk the extractor uses:
cleaned text length, PARAGRAPH-ISH length (text inside p, li,
td/th, blockquote, dd/dt, pre, h1-h6, where prose lives), LINK
length (text inside anchors), and a CHROME flag (the candidate
is, or sits inside, a header/nav/footer/aside element or a
navigation/banner/contentinfo role). A pure Java pick
(Eyes.pickMainContent) chooses the extraction root:

- THE BAR (clearsMainBar, integer ratios): not chrome; at
  least 600 cleaned chars; paragraph-ish share at least 1/2;
  link density at most 2/5. The separators are measured, not
  guessed: an article body's paragraph share is ~0.9 and a
  search-results block's is ~0.1 (its snippets live in link and
  layout markup); the nav-wrapper that motivated the density
  cap measured 0.48 link density while Wikipedia's <main>,
  wikilinks and all, measures ~0.33.
- THE PICK: (1) the legacy root wins when it is strong — when
  the element readTextJs would root at clears the bar it is
  picked, and its extraction is the pre-v2.8 text byte-for-byte,
  so pages that already read well cannot regress; (2) else the
  best score (paraLen discounted by the non-link share) wins,
  with a tightness tie-break — any clearer within 9/10 of the
  best score and carrying less text beats it, because nested
  wrappers around the same content differ mainly by the chrome
  they dilute it with; (3) no clearer → the FULL-PAGE tier: the
  legacy root walk with chrome subtrees suppressed (semantic
  chrome elements and roles only; no class-name guessing).

**Host tests: 667 passed / 0 failed** (604 carried from v2.7.0,
all green with ZERO fixture changes, plus 63 new v2.8 checks).
APK: 3,131,660 bytes, sha256
`71122e2d96b8aa4672e12ce0ec1419068e679cb248b7a0fa418225d29d079954`,
versionCode 20 / versionName 2.8.0, signed with the preserved
product keystore (cert SHA-256 de6969f0…25cd71b), full arm64-v8a
lib set, no lambdas. Download (expires Oct 27, 2026, 05:27 UTC):
https://muse.ai/files/1450549544789833/1123987526804454/hutxdj4d5qf3pvpua5etprzi/ghost-hands.apk

ITEM 1 — MAIN-CONTENT EXTRACTION. WebHands now reads through a
per-URL SESSION pinned at its first extraction: the pick, the
root and the tier are fixed for the session, so every chunk of
a page comes from one extraction of it. The result string names
the tier that served it — "ok: read chars 0-2400 of 8412 (main
content); …" vs "(full page)" — placed between the total and
the semicolon so Agent's three parsers (readTotalChars,
readReachedEnd, autoReadSection) read tiered strings exactly
as untiered ones (host-pinned). Because MAP_JS wipes
window.__gh at every perception, the session re-collects its
candidates before each chunk; on a stable page the pick is
identical and only the stash is refreshed. If a re-collection's
pick MOVES (the page genuinely changed — hydration completed,
a re-render swapped the content root), the read cursor
restarts at the top of the new extraction and chunk.start
(which read() now treats as authoritative) says where the
served text began. A candidate node that dies anyway (the
script reports total -1) serves that one chunk from the legacy
extractor, labelled full page.

DEDUPE/COMPLETION ACROSS TIERS, stated plainly: the agent-side
keys are unchanged — read-history stubbing keys on the read's
URL, completion on URL → total. That stays sound because a
session pins ONE tier per URL per run, so a URL's chunks,
totals and end-of-text state never mix tiers; a leave-and-return
starts a fresh session and a fresh cursor, exactly as
ReadCursor.resetFor already treated a changed page. The
host harness pins the cross-tier behaviour at the string layer:
main-content and full-page history chunks stub identically.

ITEM 2 — DENSITY SETTLE. Before a session's first extraction,
the hands poll a quick text-length probe (the metrics walk over
the body; no string built) up to 3 times ~600 ms apart and
proceed when growth flattens (last-pair delta ≤ 32 chars) or
the budget expires — a stability signal where the v2.3 retry's
fixed 2.5 s guess used to be the only hydration handling. The
decision over a probe series is pure (Eyes.settleDecision,
host-tested: flat pairs, shrinkage, late flattening, budget
expiry, the empty page). A failed probe proceeds unsettled:
settling is polish, never a gate. The v2.3 thin-read retry is
unchanged as the second line for reads that still come back
thin.

ITEM 3 — PREVIEW PREFERS CONTENT. MAP_JS runs the light
candidate pass (cap 8) alongside the map; parseMap carries the
metrics; when the same pure pick finds a winner, MainActivity
fetches the winner's opening text (the walk's own cleaned
opening, via previewAtJs) and Perception.textPreview leads
with it instead of the page's first screenful. On the
weather-shaped fixture the standing preview was nav chrome;
it now opens with the forecast heading. Title, URL and the
element map — every input the recognition rules read — are
untouched; the harness pins identical isSearchResultsPage /
shouldAutoRead verdicts across wall-vs-content previews on the
DDG fixture, and that the prompt's Page-text line is what
changed.

ITEM 4 — LAUNCHER ICON (Ryan's ask; the A design is the pick).
The spectral ghost hand (glowing cyan-white open hand
dissolving into a ghost tail on deep navy — the ethereal
skin's palette, art 1600×1600) is wired as the launcher icon.
Masters of record (A the shipped design, B the saved alternate,
NOT shipped) live in v1/icon-source/ beside build_icon.py,
which generates: legacy ic_launcher.png at 48/72/96/144/192;
ic_launcher_round.png (art circle-cropped on the sampled
navy); adaptive-icon foregrounds (108dp canvases, the art at
75% centered on transparency, so launcher masks cannot clip
the fingertips or the tail); and mipmap-anydpi-v26 adaptive
XMLs whose background is @color/ic_launcher_background — the
navy sampled from the master's border ring by the generator
(#10123F measured). Manifest carries android:icon and
android:roundIcon. Verified in the gates: aapt2 badging shows
the icon resource at every density, the compiled manifest
carries icon=@mipmap/ic_launcher and
roundIcon=@mipmap/ic_launcher_round, and the packaged APK
lists all 15 mipmap PNGs + both adaptive XMLs.

PROOF SHAPE (the read-lab, ~/workspace/ghost-hands-mobile/
read-lab/): the production script strings were dumped from the
compiled Eyes class and exercised in jsdom against the real
fixtures — every dumped script also passes a real
`node --check`. On the DDG lite mirror: no candidate clears,
the full-page tier serves, and its text is BYTE-IDENTICAL to
the legacy extractor's (the page has no semantic chrome
elements for suppression to touch). On the real Wikipedia
Clinch article (fetched for the lab): the legacy <main> is the
pick and the main-content text is BYTE-IDENTICAL to legacy,
same word count, markers (337, Norris Dam, Melton Hill) in
chunk 1. On the weather-shaped fixture: the legacy chunk 1 is
all chrome with NO forecast text (markers at chars 2778/2871,
past the chunk), the pick is the forecast <section>, and its
chunk 1 carries the values (low near 43 at char 61, "Chance of
rain 10 percent" at 154); the preview likewise leads with the
forecast. On the saved weather.gov homepage shell: the
0.48-density nav-wrapper is rejected and a genuine content
column (the forecast lookup block) is the pick. The settle
probe demonstrably grows when a fixture hydrates.

FIXTURE HONESTY: weather.com itself would not serve its page
to curl (a 404 wall, ~94 KB, no forecast content), so the
weather-shaped fixture is SYNTHETIC — hand-built to the v2.1
field failure's shape (semantic chrome wall ahead of a buried
forecast block of <p> prose). The DDG mirror, the Clinch
article and the weather.gov homepage are real page HTML.

HONEST LIMITS. (a) Heuristic readability is not a guarantee: a
page whose prose lives outside the paragraph-ish tag set, or
whose content block is link-denser than 2/5, falls back — by
design, and the fallback is never worse in kind than the
pre-v2.8 walk. Nav built from <li> lists inflates paragraph-ish
length (menus are lists); the density cap is what rejects
those wrappers, and a wrapper under both caps with real prose
mixed in can still be picked — the weather.gov shell's pick
is a content column, not the forecast itself, because the
saved shell is mostly chrome around a lookup form. (b) The
candidate metrics are the walk's own approximation (per-node
whitespace-collapsed lengths summed); the exact total of a
served read still comes from the extraction itself. (c) JS
collection fidelity on live pages remains device-verified: the
lab ran the app's exact scripts in jsdom (which has no layout
— irrelevant to the visibility-blind walk, but the MAP
preview's innerText fallback and WebView rendering differences
are not exercised there). (d) The settle adds up to ~1.2 s
before a session's first read when a page keeps growing; the
thin retry's costs are unchanged. (e) This release does not
touch the grammar A/B or the v2.7 field test: the grammar
default stays OFF, the model defaults stay, and the crash
investigation's next data point is still a device run on those
defaults.

## v2.7.0 (Oct 25, 2026) — the Parity Fix

Three items from one field trail, everything else frozen —
INCLUDING the v2.6.1 grammar default (OFF) and the model defaults
(default Q5_K_M, Q4_K_M one chip away). **Host tests: 604 passed /
0 failed** (566 carried from v2.6.1, all green with ZERO fixture
changes, plus 38 new v2.7.0 checks). APK: 2,802,503 bytes, sha256
`aa2b55568ceea301e16b97c39cce5b54042acc13a8bb5441f3c6a212480bb240`,
versionCode 19 / versionName 2.7.0, signed with the preserved
product keystore (cert SHA-256 de6969f0…25cd71b), full arm64-v8a
lib set, no lambdas. Download (expires Oct 27, 2026, 04:59 UTC):
https://muse.ai/files/1450549544789833/1676220597540112/us19zpj3hcb3w2xg9tmul0av/ghost-hands.apk

ITEM 1 — THE DIAGNOSIS (the actual mechanism). The field trail
(trail-20261025-003150, v2.6.1, grammar off, Q5) sat on the
DuckDuckGo lite results page with an IDENTICAL page signature
(d2f2d2f4:1508) at every step, and BOTH taps — el 6 ("top") and
el 8 (the correct weather.com 10-day result) — died "element N
not on page", and the v2.0 identity heal rescued neither. The
map, the stash and the page all agreed; the heal itself was the
veto. Mechanism, proven three ways (source read, `node --check`
on the app's own emitted script strings, and a live Firefox run
of the app's exact MAP_JS → shapeMap → stashJs → identityJs →
identityScanJs → decideHeal pipeline against the real DDG lite
page):

1. HEAL_FUNCS_JS's collectNodes() builder carried ONE CLOSING
   BRACE TOO MANY: the ptrHarvest append segment ended
   `out.push(ex[j]);}`, closing the function early, so
   `return out;` landed outside it and the enclosing IIFE closed
   a brace early. Every script built on those helpers —
   identityJs, identityScanJs, tapScanJs, typeScanJs — has been
   SYNTACTICALLY INVALID JavaScript since v2.0 shipped. They
   could never execute anywhere; the host harness only ever
   tested the pure decision (decideHeal) with fabricated identity
   JSON, never the emitted script text.
2. On device, evaluateJavascript on a script with a syntax error
   yields "null". healDecision therefore always read the stash
   slot as unreadable (parseIdentity → null) and the scan as
   empty (parseIdentityScan → empty list), and decideHeal counted
   zero matches and returned NONE.
3. tap()/type() treat NONE as a refusal and return
   "error: element N not on page" WITHOUT ever calling tapJs/
   typeJs — so a perfectly good stash (the lab re-run stashed all
   40 elements and both field targets read back found:true with
   the exact perceived identities) was never tried. The v2.2 race
   retry papered over earlier instances by re-deciding into
   different actions; it cannot fire on an unchanged URL, so this
   run had no escape.

The brief's three prime suspects were checked in the code and
are NOT the mechanism: the shaped list's domIndex values are
recorded against the single raw candidate list and the stash
rebuilds from exactly those indices (verified end to end in the
lab: shaped #12 = raw candidate 10 = stash slot 12 = scan match
at 10); the v2.5 pointer harvest appends to the same list in
both MAP_JS and collectNodes; and the 600-candidate rescan cap
never came into play on a 50-candidate page.

THE FIX, three layers:
- The stray brace is removed; collectNodes returns inside its
  own body. Every JS string Eyes emits is now structurally
  validated by the host harness (string/comment/regex-aware
  balance scan): a malformed builder fails the tests instead of
  shipping silently. One validator check pins the exact
  regression shape (`out.push(ex[j]);return out;}`).
- decideHeal no longer vetoes on NO EVIDENCE: slot identity
  unreadable AND scan unavailable/empty now resolves to MATCH
  (act on the stash as perceived; if the slot is truly empty the
  plain tap reports "not on page" itself). A slot PROVEN to hold
  a different element still refuses, with or without a scan.
- identityJs treats a stashed node the page has since replaced
  (detached, `isConnected === false`) as not found, so the scan
  re-finds the live node instead of the tap landing on a dead
  node. Lab-proven: after a simulated re-render replaced the
  slot's node, the fixed chain returned {found:false}, the scan
  re-found the target at raw index 48 of 51, decideHeal ruled
  REFOUND, and tapScanJs acted on the live node ("ok: acted").
- Host parity regression: a modeled candidate flood (130
  identical boilerplate links ahead of the controls and results)
  flows through parseMap → shapeMap → stashJs → parseIdentityScan
  → decideHeal with the invariant asserted at each layer — the
  distinctive result link's number, stash slot and scan position
  all name the same raw candidate, an unmoved page heals MATCH,
  and a replaced slot heals REFOUND at the exact scan index
  tapScanJs then addresses.

ITEM 2 — REPEAT-SEARCH GUARD LEARNS q=. The v1.6 same-page
correction derived the current query only from Wikipedia's
search= parameter (or a "… - Search results" title), so on the
field page the planner re-searched the exact query already in
the DuckDuckGo URL's q= and the guard never fired. The
derivation now goes through RelevanceGuard.resultsPageQuery:
search= still wins where present (Wikipedia behaviour
unchanged), and on any page isSearchResultsPage recognises
(DuckDuckGo incl. html. lite, Google, Bing) the q= parameter is
consulted too; the "<query> at DuckDuckGo" title form also
fires. Host-tested: the field case verbatim fires; a different
query on the same page proceeds; a q= on an unrecognised page
derives nothing.

ITEM 3 — STASH-REFRESH RETRY. Sibling of the v2.2 race retry,
in the same branch: when a tap/type dies "element N not on
page" and the fresh perception shows the SAME URL and an
IDENTICAL signature as the deciding perception (i.e. NOT the
navigation race), the loop records a "stale_stash" trail event,
feeds "Step N: element map may be stale; refreshing and
deciding again", refunds the attempt's stuck credit, and
re-decides the same step against the refreshed perception —
cap 1 per step (Agent.STALE_RETRY_CAP); a second stale failure
stands as the ordinary result. The pure predicate
Agent.staleStashSuspect(perceivedSignature, currentSignature,
perceivedUrl, currentUrl, result) is host-tested against both
the field case (fires) and the v2.2 race case (does not fire —
the race path owns it).

HONEST LIMITS. (a) JS collection fidelity on live pages remains
device-verified: the parity proof ran the app's exact scripts
in desktop Firefox against a locally mirrored copy of the real
DDG lite page (the live page's CSP blocks script-tag injection
in a desktop browser; evaluateJavascript on device is not
subject to page CSP), and Chromium/WebView identity semantics
were not re-exercised on the phone. The first device run of
v2.7.0 is the real confirmation. (b) The A/B grammar question
is untouched by this release: the grammar default stays OFF,
the model default stays Q5, and the crash investigation's next
data point is still a device run on those defaults. (c) The
heal's identity semantics are unchanged (links by tag+name+href,
controls by name+tag+type); pages whose re-renders change a
link's name or href still refuse rather than guess.

## v2.6.1 (Oct 25, 2026) — the Kill-Switch hotfix

A surgical hotfix on top of v2.6.0: ONE item, everything else
byte-frozen. **Host tests: 566 passed / 0 failed** (542 carried
from v2.6.0, all green with ZERO fixture changes, plus 24 new
v2.6.1 checks). APK: 2,802,506 bytes, sha256
`e0c99bcd53ce23521f21e281ef61edb2783cfb105f68c7f783130600353ad7eb`,
versionCode 18 / versionName 2.6.1, signed with the preserved
product keystore (cert SHA-256 de6969f0…25cd71b), full arm64-v8a
lib set, no lambdas. Download (expires Oct 27, 2026, 04:23 UTC):
https://muse.ai/files/1450549544789833/4405186506408040/p6hxwtvlyzpad6zei4dikvdt/ghost-hands.apk

WHY. The app has now closed on Ryan's phone three times during
runs — twice on v2.4, once on the v2.5-era build (trail
20261025-001110) — after v1.0 through v2.3 ran a full day of
device runs with zero closures. The closures begin exactly with
v2.4, whose only brand-new NATIVE code path is the grammar
sampler in the shim. The phone itself stays alive through each
closure (Ryan messages from it afterwards), so this is app
process death. The exact defect line is NOT proven and this
hotfix does not go looking for it — and it does NOT claim to fix
the crash. What it ships is the containment that isolates the
cause: a default-off kill-switch that returns the engine to the
configuration with the full day of stable field history behind
it, plus the labelled switch that bisects the remaining suspects
on real hardware (grammar off + Q5 first; grammar off + Q4 if
closures persist; the v2.6 marker and per-run model + setting
records are the evidence set).

THE SWITCH. A user setting, "Output grammar lock"
(ModelStore.GRAMMAR_LABEL, shown verbatim in the model card:
"Output grammar lock (experimental — off is the stable
setting)"), sits beside v2.6's brain picker and persists the
same way — a string ("on" / "off", ModelStore.grammarPrefValue)
in the same SharedPreferences file under "grammar_on". It
DEFAULTS TO OFF for every install, upgrades included
(ModelStore.GRAMMAR_DEFAULT_ON; resolveGrammarOn turns only an
explicit saved "on" on — null, "off", and anything unrecognised
resolve off). MainActivity resolves it at launch and hands it
to the engine (ModelEngine.setGrammarOn; a fresh engine is off
before anyone sets anything) and to each Agent at construction.
NativeBridge.generate now takes the flag; in the shim, OFF means
llama_sampler_init_grammar is never called and the sampler chain
is byte-for-byte the pre-v2.4 greedy-only chain, while ON is
v2.4+ behaviour exactly. Result slot [7] keeps reporting per
call what actually happened — 0 on every call made with the
switch off. The switch can only change while no run is active
(mid-run taps are refused with a toast, mid-download taps are
inert — the brain chips' rule), and because the chain is built
per call, a toggle needs no model reload. On the trail, every
decide / stop / run_summary record now also carries
"grammar_setting" beside "model", following the v2.6 pattern
(via a new 7-arg Agent constructor; the 6-arg one delegates with
the setting off, so every existing construction — and every
existing test fixture — is untouched). With the switch off,
PlannerParse is again the only format defense, exactly as in
v1.0-v2.3.

HOST-TESTED: the resolver matrix (null / empty / "on" / case /
whitespace / "off" / "true" / garbage), the default, the
persist round trip, both label strings verbatim, the engine
field's default and setter plumbing, and stub-bodied runs whose
decide / stop / run_summary records must carry the setting the
Agent was handed, on and off. NOT host-executable: the native
branch itself — whether the shim honours the flag on-device is
device-verified only, and the trail is where the proof will
show: a v2.6.1 run with the switch untouched must show
"grammar_setting":false beside "grammar":false on every decide.

## v2.6.0 (Oct 25, 2026) — the Switchboard release

Four items, all Ryan's asks or direct consequences of his field
reports, on top of v2.5.0; everything else frozen. **Host tests:
542 passed / 0 failed** (493 carried from v2.5.0, all green with
ZERO fixture changes, plus 49 new v2.6 checks). APK: 2,802,506
bytes, sha256
`5c02f4fd7bd903227db6f2a9ee1f8c7ddc68e2b7dc65a3551617237cef82dba1`,
versionCode 17 / versionName 2.6.0, signed with the preserved
product keystore (cert SHA-256 de6969f0…25cd71b), full arm64-v8a
lib set, no lambdas. Download (expires Oct 27, 2026, 04:15 UTC):
https://muse.ai/files/1450549544789833/1703765567980581/xdamisfuaj3ayfabconzhekr/ghost-hands.apk

THE CRASH REPORT, STATED PLAINLY. Ryan reports the app "stopped
twice and closed out the app on me" during v2.4 weather runs,
with three live suspects — the new native grammar code, the Q5
model, a phone browning out at 12-15% battery — and no evidence
either way. This release does NOT claim to fix that crash. Items
1 and 4 are diagnostic instruments built for it: the switcher
lets a run go back to the pre-v2.4 quant and bisect the suspects
on real hardware, every decide now names its model on the
trail, and the marker turns any future close into recorded
evidence with the goal and the brain attached.

ITEM 1 — THE MODEL SWITCHER (ModelStore registry + MainActivity
picker + engine wiring). The app can now hold and use EITHER
quant of Qwen2.5-1.5B-Instruct. ModelStore carries a registry —
`Spec` (id, displayName, shortName, fileName, url, size,
sha256), `MODELS = { Q4, Q5 }`, `specFor` (trims, matches
case-insensitively, returns null for unknown ids — rejected,
never guessed), `resolveId` (a saved choice resolves to itself
or to `DEFAULT_ID`), plus the pure label helpers `gbLabel` and
`pickerLabel`, all host-tested (both pins exact, field for
field). The pins: Q4_K_M is the pre-v2.4 pin recovered from the
proof's MainActivity in this tree (file
qwen2.5-1.5b-instruct-q4_k_m.gguf, size 1117320736, sha256
6a1a2eb6…40e9407e, the URL the v1 app inherited unchanged);
Q5_K_M is the v2.4 pin, unchanged, and the legacy MODEL_*
constants remain as its values so every older reference keeps
its meaning. The default is Q5_K_M — an upgrade behaves exactly
as v2.4 did until the user says otherwise.

The picker lives in the model card as a BRAIN row of two chips
("Qwen2.5-1.5B Q4_K_M (faster, smaller)" / "Qwen2.5-1.5B
Q5_K_M (sharper, larger)" is the registry's naming; the chips
show the short form with state — active / chosen / on phone /
download size). The choice persists in SharedPreferences
("ghosthands" / "model_id") and is resolved at launch.
Switching is idle-only: the chips lock while a run OR a
download is in flight, and a tap during a run is refused with a
toast. A switch unloads the engine, then loads the chosen file
on a worker thread when it is on the phone, or runs the
existing verified download flow (size + sha256, Range resume)
for THAT model when it is not. Neither model's file is ever
deleted by switching. All file logic is per-spec
(fileFor / ready(spec) / partialBytes(spec) / download(spec,
…)); the engine's path always comes from the registry —
startAgent now loads the active file whenever the engine is
unloaded or holds a different path (new ModelEngine.loadedPath
()). The status line and the ready pill name the active model,
and the Agent takes the id at construction and records it as
"model" on every decide (next to the v2.4 "grammar" flag), the
stop record, and the run_summary — host-tested end to end with a
stub-bodied run whose records must name the id it was handed.

ITEM 2 — THE AGENT ACTIVE GLOW + PULSE (MainActivity UI). The
status pill now sits in a padded wrap whose background is a red
halo drawable (alpha 0 at rest). While a run is live, a
ValueAnimator breathes it on a 1.4 s cycle (halo alpha 0→115,
wrap scale 1.0→1.05, AccelerateDecelerate, framework-only — no
AndroidX, no other view animated, the v1.4 palette and
typography untouched). updatePill owns the pulse's life: it
starts it exactly when the pill is AGENT ACTIVE and stops it —
alpha back to 0, scale back to 1 — on every other state, and
onPause stops it too (onResume re-syncs through updatePill).
While STOPPING (item 3) the pill is steady amber with no pulse.
If the device has animations disabled system-wide the animator
never advances and the pill simply stays lit; the state is
still legible.

ITEM 3 — INSTANT PRESS ACKNOWLEDGEMENT (MainActivity +
stop path). Start and Stop now answer in the same frame as the
tap, on the UI thread, before any engine work: (a) START —
button latches disabled, pill flips to AGENT ACTIVE and the
pulse starts, the brain chips lock, one haptic tick, and the
feed gains "— run started" with the run header. (b) STOP — the
tap flips the pill to STOPPING (amber, steady), feeds "— stop
requested", ticks, and only then releases any pending approval;
the loop itself is untouched, so an in-flight native generation
may finish first — that honesty is the point — and the pill
settles to its resting state in the run thread's finally, with
the usual stop line and run summary. If a run ends on its own
while STOPPING shows, the finally's reset wins. Haptics go
through the framework Vibrator (VibrationEffect on API 26+, the
guarded legacy call below it — the build's one deprecation note)
inside a catch-Throwable guard: a missing vibrator or the
(new, normal) VIBRATE permission can never break the tap path.

ITEM 4 — THE UNCLEAN-SHUTDOWN MARKER (RunMarker + lifecycle).
At run start (goal accepted, marker written before the agent
thread launches) the app writes run-in-progress.json in its
files dir: goal, start timestamp, active model id — serialized
by the new pure RunMarker (the app's own Json writer/parser;
host-tested round trip, including quotes/backslashes/unicode,
and a refusal matrix: null, empty, non-JSON, non-object,
missing/non-string/empty goal all parse as no marker). The run
thread's finally deletes the marker on EVERY normal ending,
including a failed model load. At launch, a surviving marker
feeds one line — "Last run ended abruptly (app or phone died)
— goal: <goal>" — ahead of everything else, and is deleted. A
corrupt marker feeds the same line without the goal rather than
crashing the launch.

HONEST LIMITS. The switcher offers the two quants of the same
1.5B only; a different model family or size is a separate
decision Ryan has not made. The chips also lock during a
download (the brief demanded mid-run refusal; a mid-download
switch would make the in-flight download's target ambiguous,
so it waits the download out). The pulse and the haptics are
UI-thread cosmetics: they can prove a tap was received, they
cannot report an engine that died mid-call, and if the whole
process dies the animator dies with it, silently — which is
exactly the gap the marker exists to cover. The marker proves
an ending was abrupt, never why: a native crash, an Android
kill, and a dead battery all leave the same file, and the
trail + marker + active-model record together are the evidence
set, not a verdict. A death before Start is tapped, or during
the switch-load (not a run), leaves no marker, correctly —
there was no run to lose. The STOPPING state's honesty cuts
both ways: a generation in flight can hold it for the length of
one native call, and no UI change can shorten that.

## v2.5.0 (Oct 25, 2026) — the Detours release

Four approved items on top of the v2.4.0 Max package; everything
else frozen. **Host tests: 493 passed / 0 failed** (462 carried from
v2.4.0, all green unchanged — the prompt reorder forced **zero**
fixture updates, every v2.4 prompt pin still holds — plus 31 new
v2.5 checks). APK: 2,794,313 bytes, sha256
`0a77c8e3fa199af4fb6512285f9ed0803cc61f5695f639c7da347f1d4975f401`,
versionCode 16 / versionName 2.5.0, signed with the preserved
product keystore (cert SHA-256 de6969f0…25cd71b), full arm64-v8a
lib set, no lambdas.

ITEM 1 — THE DEAD-RESULT DETOUR (Agent + error-page recovery).
Field evidence (trail-20261024-234232, v2.3.0): the planner tapped
a forecast result out of the DuckDuckGo results, the destination
failed to load (chrome-error), the one recovery auto-reload
failed, and the run stopped — with a results page full of
alternatives sitting one Back away. The v1.6 error block's four
exhaustion paths (reload declined because this URL already spent
its one, reload threw, re-perceive threw, still an error page
after the reload) now converge on a single question, factored
pure and host-tested: `shouldDetour(failedUrl, lastResultsUrl,
detoursUsed, arrivedViaResultsLink)` — fire only when the agent
arrived at the dead page by tapping a link on a recognised
search-results page (DuckDuckGo / Google / Bing, the v2.1
detector), that results page's URL is on record, it is not itself
the page that failed, and the run has detours left
(`DETOUR_CAP = 2`). On a detour the agent records a trail
**detour** event (failed_url, results_url, detours_used), feeds
"Step N: result failed to load; back to results to pick
another", adds a history note naming the failed destination
("the result you opened (<url>) failed to load, so you went
back. Do not tap that result again; pick a different one."),
goes Back, and continues the SAME step deciding against the
results page it landed on. If the Back fails or lands on another
error page, the original honest v1.6 stop message returns
(showing the freshest network error). After two detours, or with
no results origin at all, today's behavior is unchanged.

The detour tracking lives in the loop's post-execute
bookkeeping: a successful tap on a link element while the
perception is a recognised results page records the departure
(lastResultsUrl + arrived flag); any other successful tap, goto,
back or search clears the flag (the agent is then somewhere a
detour has no claim on); reads, types, scrolls and waits leave
it alone. The arrival invariant holds by construction, verified
by inspection: the error block runs before the auto-read block,
the auto-read stands down on results pages, and when the step
continues it does so with the Back-landing perception — the
error page's URL never reaches the arrival/completion state, so
no guard can arm on a dead URL.

ITEM 2 — PROMPT LAYOUT REORDER (cache geometry). Through v2.4
the layout was contract → goal → page → preview → auto-read →
HISTORY → map → goal → banners → cue. On same-page steps the
native prefix cache (v1.5, which reuses the longest common
prefix with the previous prompt) diverged inside the history, so
the element map — ~600-900 est tokens — was re-evaluated every
step. The v2.5 layout is: contract (with the v2.4 few-shot
examples, untouched in the static prefix) → goal → page line →
element map → text preview → auto-read section → repeated goal
→ arrival banner → completion banner → history → cue. Every
line is the same information, only reordered (the host test
proves the step-3 line multisets of the two layouts are equal).
The banners keep their post-goal position in their group, each
exactly once; the cue stays last; the arrival banner's "its
text is above" stays literally true. The budget trimmer is
untouched — its stages operate on the same inputs and the
under-budget byte-identity gate still holds.

**Measured** (host test, fixed 3-step same-page read scenario —
Clinch-shaped article, 12-line map, auto-read section present,
step-2 vs step-3 prompts differing only in one history entry;
prefix measured in chars and converted with estTokens):

- v2.4 layout: longest common prefix = **597 est tokens** reused
- v2.5 layout: longest common prefix = **937 est tokens** reused

+340 est tokens of prompt the model no longer re-evaluates on
every same-page step, in this scenario. The old layout is frozen
as a test-local assembler in GovernorTest (never shipped in the
app) purely so the comparison stays honest.

ITEM 3 — THE WIDER CLICKABLE NET (Eyes). MAP_JS's candidate
selector gains `[role='link']` (the role basics were otherwise
already covered) and `[onclick]`, and a new cursor-pointer
harvest pass appends elements whose computed cursor is "pointer"
and that carry visible text — the clickable divs and cards real
sites navigate with. The harvest is capped at the first **12**
in document order (`MAX_POINTER_HARVEST`), skips elements inside
main-selector candidates and inside already-harvested elements
(no nested double-counts), and marks its candidates `ptr:1` in
the map JSON. A harvest element gets a normal ElementInfo with
its tag recorded truthfully ("div"), parses its flag in
elementFromMap, and in shaping ranks in the link tier (tier 4 —
even with a button role or a search-shaped name; it is
discovery, not a primary control). The v2.3 dedupe treats it as
the non-link it is (name+tag+type), and the heal identity scan
enumerates the same widened net with the same harvest appended,
so a harvest element heals by name+tag+type like any other
non-link. The visibility and size filters are the script's own
vis() gate, unchanged — a candidate it filters never reaches the
raw list. Host tests pin the shaping layer (the cap in document
order, the tier, the dedupe, the parseMap flag round-trip); the
JS itself is not host-executable — stated plainly, the
selector/harvest code is verified by its pinned fragments and by
the shaping contract, and its first real exercise is a device
run.

ITEM 4 — THE STOP-LINE RUN SUMMARY (Agent stop path). Every
stop — done, error, stuck, budget, user — now closes the run
with its own account of itself. The decide loop accumulates
total prompt tokens and total reused tokens from every decide's
engine stats (a race re-decide counts too: it really spent the
tokens again), and stop() records a trail **run_summary** event
(outcome, steps, wall-clock seconds from run start, prompt
tokens, reused tokens, reuse percent) and appends one feed line
after the stop line, in the shape:

`— run: done in 10 steps, 512 s, 61% of prompt reused`

The planner's done summary stays exactly as written — the run
summary is a separate line after it. The formatter is pure and
host-tested (`runSummaryLine` / `reusePercent`): the percent is
reused*100/prompt with integer division, zero prompt tokens read
as 0%, never a division by zero. New trail event types
**detour** and **run_summary** are registered in
Trail.EVENT_TYPES.

HONEST LIMITS. The detour only helps when a results page exists
to return to: a typed URL that dies, a start page that dies, or
a third dead result in one run still ends in the honest error
stop — by design, not by omission. The wider net adds map noise
on div-heavy pages despite the 12-cap: twelve extra link-tier
lines cost prompt budget and the 1.5B planner's attention, and a
pointer cursor is a hint, not a promise — some harvested divs
will do nothing when tapped; the cap keeps the cost bounded and
only a device run says whether the trade pays. The layout
reorder cannot help a first step on a brand-new page — there is
no previous prompt to share a prefix with; it pays only on
repeated-page work (reading, paging, iterating on one site),
which is where the field runs spend most steps. The run
summary's token totals are the engine's own accounting (the same
numbers the decide events carry); the wall-clock figure includes
perception and settle waits, not just model time; and the reuse
percent is the native cache's reuse claim as the shim reports
it (the v2.4 grammar-active honesty rule applies to it the same
way).

## v2.4.0 (Oct 24, 2026) — the 1.5B Max package

Ryan approved this package explicitly: five items that squeeze
the CURRENT 1.5B brain harder instead of replacing it — worked
examples and attention markers for its judgment, a grammar lock
for its output shape, a cleaner quant of the same weights, and
a contract trim that pays for the examples. On top of v2.3.0;
everything else frozen.

ITEM 1 — FEW-SHOT EXAMPLES (Agent.EXAMPLES_TEXT, appended to
the contract block). Two miniature worked examples in the
prompt's own "Next action JSON:" idiom, teaching the two
behaviours the guards correct most: (a) results discipline —
a results sketch whose links include "Clinch River" and
"Next page", answered with the tap on the match (the v1.9
field run paged away from that exact page twice before
recovering); (b) answer from text — a goal asking a length
and text carrying "Length 337 mi", answered done with the
value IN the summary (the v2.0 weather run's done restated
the goal and carried neither requested number). They sit at
the END of Agent.CONTRACT_BLOCK, entirely inside the static
prefix the native prefix cache reuses; every per-step section
follows them. Measured: 206 chars, 52 est tokens for both
(the budget was ≤ 220). Host-pinned: the built prompt starts
with the contract block, the examples follow the action
contract and precede the first per-step section, and a
max-size synthetic prompt still fits through the unchanged
trimmer with the examples intact.

ITEM 2 — GOAL-MATCH HIGHLIGHTING (Eyes.Perception.render(
String)). Link elements whose name matches a significant
goal token now carry " [goal match]" at the end of their map
line. The matcher is the guard family's own —
RelevanceGuard.significantTokens + nameMatches, the latter
relaxed from private to package-private so there is exactly
ONE implementation of "this link's name matches the goal",
used by the guards that judge taps and by the map that
steers them. Links only; inputs and buttons never marked.
The marker cannot alter element identity: it is appended at
render time, downstream of shapeMap/dedupe and of the heal
path, both of which compare ElementInfo fields, never render
output; Perception.signature() still renders goal-less, so
stuck detection is byte-untouched. A null goal or a page
with zero matches renders byte-identical to v2.3 (pinned).
Agent now renders the prompt map with render(goal) at all
three perceive sites.

ITEM 3 — GRAMMAR-LOCKED OUTPUT (native shim ghhands.c).
LANDED. The pinned llama.h (b11382) exposes
llama_sampler_init_grammar(vocab, grammar_str, grammar_root),
returning NULL on a parse failure; the shim builds a GBNF
grammar (GH_ACTION_GRAMMAR) accepting exactly one JSON
object of the shapes PlannerParse parses — the field set
derived from GhAction/PlannerParse, not invented: tap{el},
type{el,text}, goto{url}, scroll{dir optional}, back{},
wait{ms optional}, search{query}, read{}, done{summary
optional}, stuck{reason optional}, "action" first as the
contract writes it. Strings accept arbitrary text with
standard JSON escapes (the done summary is prose); el is
1-based in the grammar ([1-9][0-9]*), while range checks a
grammar cannot express (wait's clamp, the element's
existence) stay with PlannerParse, which is unchanged and
remains the second line of defense. The grammar sampler
sits ahead of the greedy sampler in the per-call chain
(greedy kept). The native result array grows to String[8]:
slot [7] reports, PER CALL, whether the grammar actually
initialised — if init ever returns NULL the chain degrades
to pre-v2.4 greedy-only and the slot reports 0, so the
trail (Agent records "grammar" on every decide event) never
claims a constraint that was not applied. The grammar
string itself was verified by decoding the C literals
exactly as the compiler does and reading the GBNF back
(it mirrors the pinned tree's own grammars/*.gbnf
conventions); the sampler's runtime behaviour is NOT
host-executable and its first live exercise is the next
device run, where the decide events' grammar flag is the
evidence.

ITEM 4 — Q5_K_M REQUANT (ModelStore). The pinned model is
now Qwen2.5-1.5B-Instruct Q5_K_M from the official
Qwen/Qwen2.5-1.5B-Instruct-GGUF repo, file
qwen2.5-1.5b-instruct-q5_k_m.gguf. The pin was measured in
the build environment from the downloaded bytes (downloaded
to ~/workspace/downloads/): MODEL_SIZE = 1,285,494,304,
MODEL_SHA256 = b46661073c18e5b56a41fa320975f866a00def1ff0
8feef4718e013258896f8c, the file's size identical before
and after hashing and its GGUF v3 header sane (339 tensors).
The existing size+sha256 verification and Range-resume flow
apply unchanged; the on-device filename changes with the
quant, so an upgraded install downloads the new file fresh
and the old Q4_K_M file is left alone, never deleted.
Host-pinned: filename, URL, exact size and hash, and the
hash's shape. The real verification happens on device at
download, as always.

ITEM 5 — CONTRACT TRIM (Agent.CONTRACT_TEXT). Removals,
each one line of reason:
- Search sentence: dropped "look up, or" — a third synonym
  for the same instruction (the guard's search-like test
  reads the goal's words, not this sentence).
- Search sentence: dropped "that is" ("something that is
  not already on this page") — filler.
- Link sentence: "a target named in the goal" -> "a goal
  term" — the guard family's own phrase for the same test
  (its history note reads "matches no goal term").
- Question sentence: "If the goal asks a question," ->
  "For a question goal," — same condition, fewer words; the
  protected tail ("then use read (again for more text if
  needed) before answering done") is verbatim.
- Title sentence: dropped "already" ("the page title
  already matches your target") — filler adverb; the
  chrome-tab rule after it is verbatim.
Considered and KEPT verbatim: every action's syntax line,
the done-with-values sentence, the featured/random-homepage
sentence, and the auto-read sentence (a guard's history
note refers back to it, and the harness pins its wording).
The arithmetic: v2.3's static prefix measured 292 est
tokens; the trimmed standing text is 279; with the 52-token
examples the block is 331 — within the item's bound
(v2.3 + 40 = 332), host-pinned.

FIXTURE ADJUSTMENTS (the only existing-test changes):
- The two banner-position checks (arrival, completion) now
  locate the cue with lastIndexOf("Next action JSON:"):
  Item 1 deliberately puts the cue idiom inside the
  examples, so the FIRST occurrence is no longer the step's
  cue; the last one is, which is what the checks mean.
- The host harness now also compiles ModelStore.java
  (android.jar on the compile classpath for its SystemClock
  import) so Item 4's pin is test-covered. Its fields are
  compile-time constants, inlined into the test class, so
  no Android class loads at test runtime.

Tests: host harness 462 passed, 0 failed (436 existing +
26 new, all foreground-verified): the examples (presence,
position, size, survival through the trimmer, the static-
prefix bound), the highlight matrix (match marked, non-
match unmarked, inputs never marked, byte-identity for no-
goal and zero-match pages), the grammar flag's Java surface
(slot 7 read honestly: "1" constrained, "0"/missing/null
not), Stats defaults, and the model pin (name, URL, exact
size + hash, hash shape).

Build: ./build-apk.sh green; apksigner signer cert unchanged
(de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b);
aapt2 badging com.ghosthands.app versionCode 15, versionName
2.4.0; full arm64-v8a lib set (libghhands, libllama, libggml,
libggml-base, libggml-cpu, libomp); lambda check: no lambdas;
delivered copy cmp-verified byte-identical to the build output.
APK: ~/workspace/your_files/ghost-hands.apk, 2,794,313 bytes,
sha256
52f4d60ba663c18553d06ba2d73b3717cbb5742bcae58118faf7fefd75285810.
Download: https://muse.ai/files/1450549544789833/2040310043348942/zu47keazgpbboi77me60ffid/ghost-hands.apk
(link expires Oct 27, 2026, 03:52 UTC).

HONEST LIMITS:
- Few-shot examples and goal-match markers STEER a 1.5B;
  they do not make it a 3B. They teach the two corrected
  behaviours by imitation and point attention at the links
  the guards will judge, but every semantic slip the guard
  family exists for remains possible, and the guards remain
  the enforcement.
- Q5_K_M vs Q4_K_M is a modest fidelity gain — less
  quantisation loss on the same weights — not a capability
  jump. Expect slightly cleaner outputs, not new abilities,
  at a slightly larger download (1,285,494,304 vs
  1,117,320,736 bytes).
- The grammar constrains SYNTAX only, never semantics: a
  well-formed tap on the wrong element is still perfectly
  grammatical. What it removes is the malformed-output
  class (the fail-closed unparseable path should simply
  stop firing); PlannerParse and the guards still own
  everything else. Its runtime behaviour is device-
  verified only — see Item 3 — and if the native grammar
  ever fails to initialise, generation falls back to
  unconstrained greedy with the trail flag saying so.
- The Q5_K_M file's first on-device download re-verifies
  size + sha256 on the phone, as every model pin has; the
  build-environment measurement above is not a substitute
  for that check, only its source.

## v2.3.0 (Oct 24, 2026) — the clearer eyes release

Improvement work on top of v2.2.0, three items, each grounded in
Oct 24 field evidence. Everything else frozen.

ITEM A — THIN-PAGE READ RETRY (WebHands.read + Eyes decisions).
Weather.com's 10-day page extracted only 2,390 chars, all
navigation skeleton, in two separate field runs: script-driven
pages hydrate late, or only when scrolled into view, so a single
extraction taken the moment the agent asks can see a fraction of
the text the page will shortly carry. Now, when a read's total
comes back below THIN_READ_THRESHOLD = 3000 chars for a URL not
yet retried this run: (1) wait ~2,500 ms and re-extract once,
keeping the better text; (2) if still below threshold, one gentle
scroll pass (down in ~4 viewport steps with short pauses to trip
lazy rendering, then back to the top) and a final extraction; the
best (largest) extraction wins. One retry per URL per run,
tracked in the read state (ReadCursor.thinRetriedUrls; WebHands
is created per run, so the set is per run). The read result
string appends the retry's honest account —
"(page rendered thin at first: 812 chars, 2390 after
settle+scroll)" when the page grew, "(page still thin after
retry: N chars)" when it did not. The pure decisions are Eyes'
(isThinRead / shouldThinRetry / pickBestExtraction — larger
total wins, a tie keeps the later, more-settled extraction /
thinRetryNote), host-tested; the waiting, scrolling and DOM
extraction in WebHands are not host-executable and are kept
minimal. Planner reads and the loop's auto-read share
WebHands.read(), so both get the improved extraction through
the same path — there is no separate auto-read retry.

ITEM B — LABELS THE PLANNER CAN SEE (Eyes.Perception.render +
WebHands.search). v2.2 extracted each input's explicitly
associated label (labelText) for the search-target decision but
never showed it: the planner saw a bare text box and had to
guess what it was for. (1) Map lines for INPUT elements now
carry the label in bracketed form — #N input "name"
[label: Local forecast by "City, St" or ZIP code] (the house
line is [N] <input> "name" type=.. [label: ...]) — via the pure
Eyes.mapLabelSuffix; inputs only, so prose pages' prompts do
not grow, and an input with a bracketed label no longer also
renders the compact 24-char label= form (the same words twice).
(2) When Hands.search executes through a lookup box, the result
string names the box: Eyes.searchTargetTier classifies the
winner on its own signals, Eyes.searchBoxText returns its
labelText (explicit-label tier) or formText (ambient tier), and
Eyes.formatSearchedViaBox composes
ok: searched via the page's box ("Local forecast by "City, St"
or ZIP code") for "Oakdale, Tennessee forecast" — so when the
follow-up page answers like a location lookup (a pick-a-place
list, a no-results complaint), the planner knows why. The
classic tier's result string and the DuckDuckGo fallback string
are byte-unchanged.

ITEM C — ELEMENT-MAP DEDUPE (Eyes.shapeMap). Pages repeat
identical chrome — header and footer nav, mirrored menus — and
every repeat ate one of the map's 40 slots (weather.gov's map
was 40 elements dominated by nav while its real controls were
few). After the tier sort and before the caps, later duplicates
drop (the pure Eyes.isDuplicateElement): two LINKS are
duplicates when name AND href match exactly, as extracted; two
NON-LINKS when name, tag and type all match — except INPUTS,
which must also agree on labelText, since two same-shaped boxes
labelled for different purposes are distinct controls. The
FIRST occurrence in shaped (tier/DOM) order survives; survivors
are numbered after dedupe, as before, so numbering stays tight
and the caps now budget distinct elements.

Tests: host harness 436 passed, 0 failed (408 existing + 28 new,
all foreground-verified): the thin decisions (the threshold,
fresh/retried/thick/null gating, best-extraction incl. the tie
rule, both note shapes — the grew-note pinned to the task's own
812 -> 2390 example), the map label (suffix for a labelled
input, empty for unlabelled / non-input / null, the rendered
line carrying the bracket exactly once with no label= twin, a
link keeping its compact label= form), the search naming (tier
classification and box text for labelled / ambient / classic /
none, the via-box string verbatim, and the real weather.gov
fixture's picked box naming itself), and dedupe (the duplicate
matrix for links, buttons and inputs incl. the label exception,
chrome collapse to first occurrences with tight numbering,
same-name different-href links surviving, same-shaped
differently-labelled inputs surviving).

Build: ./build-apk.sh green; apksigner signer cert unchanged
(de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b);
aapt2 badging com.ghosthands.app versionCode 14, versionName
2.3.0; full arm64-v8a lib set (libghhands, libllama, libggml,
libggml-base, libggml-cpu, libomp); lambda check: no lambdas;
delivered copy cmp-verified byte-identical to the build output.
APK: ~/workspace/your_files/ghost-hands.apk, 2,794,312 bytes,
sha256
20df2c058793ed1b714b8341cdfb2d788c0b3d008b59428aa668c2e0f286bfd8.
Download: https://muse.ai/files/1450549544789833/1394301459121125/hwzdncsctcvu8iqaran5zaoe/ghost-hands.apk
(link expires Oct 27, 2026, 03:39 UTC).

HONEST LIMITS:
- The thin-page retry cannot conjure text a page never puts in
  the DOM: canvas-drawn content, text locked inside closed
  shadow roots, and app-shell pages whose "text" lives in
  native components stay unreadable no matter how patiently the
  hands wait and scroll. The retry also costs real time on a
  genuinely thin page (~2.5 s wait plus a ~2.5 s scroll pass,
  once per URL per run) — the price of not believing a skeleton.
- Dedupe is exact-match only: near-duplicates survive — the
  same destination under an href differing by a tracking
  parameter, or the same words with different casing, are
  different strings and both keep their slots. Fuzzy matching
  could merge elements the planner must tell apart, so the
  map keeps the honest duplicates.
- Label display adds prompt lines only on form-bearing pages,
  but on such a page each labelled input's line grows by its
  label (up to the 120-char extraction cap, charged against the
  same 1500-char map budget — a form-dense page can push tail
  elements past the budget cut sooner; the budget's truncation
  marker says so when it happens, as before).
- The thin-retry's waiting/scrolling (Item A) and the
  extraction it re-runs are not host-executable; only the pure
  decisions over totals are host-tested. Its first live
  exercise is the next device run — a JS-heavy forecast page is
  the honest test of whether settle+scroll grows the text.
- The v2.1 done-grounding guard remains unexercised in the
  field (no weather run has reached a done proposal yet), as
  v2.2 recorded; nothing in this release changes that.

## v2.2.0 (Oct 24, 2026) — the second look release

THE SCORECARD FOR v2.1.0's WEATHER RETEST (trail-20261024-
231513, Ryan's phone; same weather.gov goal as v2.1's field
failure): Fix 1 (generalised results recognition) PROVEN
LIVE — step 3 shows NO auto-read on the DuckDuckGo page, and
the planner tapped result #12 (weather.com's 10-day forecast
for Oakdale) directly: a results page is a waypoint again.
Fix 2 (the done-grounding guard) was NEVER EXERCISED — the
run died before any done was proposed. Fix 3 (lookup-box
discovery) FAILED LIVE — step 1 fell back to DuckDuckGo
again, same "no search box on this page". The run then died
at step 5: weather.com's page extracted only 2,390 chars (a
navigation skeleton — its forecast modules yield no text to
the visibility-blind walk; a standing extraction limit), the
planner read it fully at step 4, auto-read fired LATE at
step 5 on the same URL (the page title settled only then),
and the prompt — section plus the history's copy of the same
text — overflowed the 2048 context: "model engine error:
ERROR: prompt too long for context". The v1.8 fast-stop
handled the death cleanly in one step. Step 2 also repeated,
identically to the first weather run, the departed-page tap:
step 1's search navigation landed during step 2's decide,
and the tap on weather.gov's SEARCH died "element 1 not on
page" — one step burned to a race, both times.

WHY FIX 3 MISSED (diagnosed firsthand against weather.gov's
real homepage HTML, fetched and inspected): the page has
THREE text inputs — (1) name="query", the usa.gov site-search
form; (2) id/name="inputstring" inside <form
name="getForecast" action="https://forecast.weather.gov/
zipcity.php">, preceded by <label for="inputstring">Local
forecast by "City, St" or ZIP code</label>; (3)
id="myfcst-location-input" in the MyForecast widget form.
TWO location-shaped text inputs exist, so the ambient
form-text rule most likely saw both qualify and its ambiguity
refusal fired — by design; it never guesses. The missed
signal: inputstring has an EXPLICIT label association whose
text carries the keywords — a stronger, unambiguous signal
than ambient form text. v2.2 keys on it.

FIX 1 — LABEL-ASSOCIATION PRIORITY (Eyes + pickSearchTarget):
MAP_JS (and the heal scan, for shape parity) now also
extracts, per input, the text of an explicitly associated
label — a <label for="its id">, a wrapping <label>, or an
aria-labelledby target — as ElementInfo.labelText (cap 120
chars; distinct from the 48-char `label` the map renders and
the classic rule reads). pickSearchTarget's tiers are now:
(a) the classic rule, unchanged; (b) the EXPLICIT-LABEL
rule: visible text-like inputs whose labelText mentions
search/location/city/zip/postal — exactly one picks it,
beating ANY number of ambient candidates (the page author
named this input's purpose); two or more fall through
without picking from this tier; (c) the v2.1 ambient
form-text rule, unchanged, ambiguity still refusing. In the
modeled real shape — query (no label), inputstring
(for-label with keywords), myfcst (ambient only) — the
chooser now picks inputstring, and the search runs through
the site's own forecast form to forecast.weather.gov's
zipcity page instead of detouring via DuckDuckGo.

FIX 2 — NAVIGATION-RACE RE-DECIDE (Agent loop): the
decide -> guard -> govern -> execute pipeline for a step now
runs in a retry loop (guard corrections continue the outer
step loop, as before). A pure predicate,
Agent.staleNavigationRace(perceivedUrl, currentUrl,
result) — the result is the stale-element shape AND the URL
has moved since the decision's perception — gates a pure
cap check, shouldRaceRetry (RACE_RETRY_CAP = 1 per step).
When a race is confirmed after an execute: the raced
attempt's result IS recorded on the trail (the hands did
move), but it earns no history entry, no action-state
updates, and its stuck-counter credit is refunded; a trail
"race" event (new event type) and the feed line "page
changed while deciding; re-perceiving and deciding again"
mark it; the loop re-perceives and re-decides the SAME step
number. If the retry's execute also returns the stale error,
the step completes with that error as an ordinary result.
The 40-100 s think makes this race structural on any slow
navigation; both weather runs paid exactly one step to it.

FIX 3 — REDUNDANT AUTO-READ SUPPRESSION + ESTIMATOR
HARDENING (Agent): (a) When auto-read would fire for a URL
already in readCompleteTotals — the planner has read the
whole page this run — the extraction AND the section are
skipped entirely (the pure gate is autoReadSectionFor, also
applied where the loop assembles the prompt's section, so
the ordering cannot matter). Arrival still arms (the guard
keys on autoReadUrl) and the completion banner still shows
(it is computed from readCompleteTotals): only the doubled
text is gone. (b) estTokens is density-aware: the base
chars/4 rate stands, but each line containing "http" is
charged at chars/3 (a surcharge of ceil(len/3)-ceil(len/4)
per such line) — URL text tokenizes far worse than prose,
and the flat rate is what waved the weather.com prompt
through to a real overflow. The budget's trim stages (iv)
and (v) now size their cuts from the token estimate rather
than raw length. PROMPT_TOKEN_BUDGET stays 1700. A prompt
whose lines carry no URLs estimates EXACTLY as before
(host-pinned); only URL-carrying lines moved.

Tests: host harness 408 passed, 0 failed (377 existing + 31
new, all foreground-verified): the label tier against the
REAL weather.gov shape modeled from the fetched HTML (picks
inputstring; the same page unlabelled refuses; two labelled
inputs refuse; classic still wins overall; a labelled input
beats an ambient-only one), the label-word predicate, the
JS shape + scan-parse carrying labelText; the race predicate
matrix (across-pages stale = race; same-page stale, other
errors, successes = not) and the cap (first race retries,
at-cap does not); the auto-read suppression gate (shows
before completion, suppressed after, hidden elsewhere); the
estimator (prose == the old formula exactly, a 60-char URL
line at chars/3, a mixed text surcharging only its URL line,
a prose-only Clinch-shaped prompt identical to before) and
its budget consequence (a 60-line URL-dense weather.com-
shaped prompt the OLD estimate waved through at 1551 is seen
at 1974 by the new one and trimmed to fit, map tail cut).
ONE EXISTING FIXTURE RE-SIZED, named here honestly: the
v1.8 stage-(ii) budget test's map went from 36 href lines to
26 — at 36, the density surcharge alone (~150 tokens)
exceeds the preview line whose drop the stage exists to
prove sufficient, so the fixture's premise ("overage smaller
than the preview") was unsatisfiable under the estimator
this release intentionally introduces. All four of its
assertions are unchanged; the comment in the test says the
same. (Precedent: v1.9 re-shaped five cases against its new
signature.)

Build: ./build-apk.sh green; apksigner signer cert unchanged
(de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b);
aapt2 badging com.ghosthands.app versionCode 13, versionName
2.2.0; full arm64-v8a lib set (libghhands, libllama, libggml,
libggml-base, libggml-cpu, libomp); lambda check: no lambdas;
delivered copy cmp-verified byte-identical to the build
output. APK: ~/workspace/your_files/ghost-hands.apk, 2,794,313
bytes, sha256
34a75c4b46c565c0745d8e5851d082c195273b2fe157f23638f360d76b3dd84c.
Download: https://muse.ai/files/1450549544789833/1471934998187844/2nostbbfplw02k3hp14shzqp/ghost-hands.apk
(link expires Oct 27, 2026, 03:31 UTC).

HONEST LIMITS:
- weather.com-class JS-heavy pages still extract thin: the
  retest's forecast page yielded 2,390 chars of navigation
  skeleton and no forecast values at all — no guard can ground
  an answer in text that is not there. The text-rich path for
  this goal never needed weather.com: weather.gov's own form
  submits to forecast.weather.gov/zipcity.php, a plain server-
  rendered page, and Fix 1 now routes the search there.
- The label-extraction JS (labx) is not host-executable; only
  the pure decisions over the extracted fields are host-tested,
  with the JS pinned by shape checks — the same standing limit
  as the heal scan and form-text extraction. Its first live
  exercise is the next device run.
- The race retry heals the SYMPTOM, not the slow-navigation
  cause: a page that races twice inside one step still burns
  the step, and the retry pass re-decides from the fresh
  perception without re-running arrival auto-read for the new
  page (the planner keeps its own read action there).
- The density-aware estimate is still an estimate: it charges
  whole "http"-carrying lines at chars/3, which overshoots for
  a mostly-prose line quoting one short URL and can still
  undershoot a pathological tokenization. The native side
  remains the final authority, and the v1.8 engine-error
  fast-stop remains the honest backstop — it is what ended
  the retest run cleanly.
- The done-grounding guard (v2.1 Fix 2) remains unexercised
  in the field: no weather run has reached a done proposal
  yet. It is host-tested only, as v2.1 recorded.

## v2.1.0 (Oct 24, 2026) — the answer discipline release

THE HONEST FIELD VERDICT: the first non-Wikipedia test FAILED
on answer grounding. v2.0.0, trail-20261024-230228 (Ryan's
phone), goal: "Find the forecast for Oakdale, Tennessee, and
tell me tonight's low temperature and the chance of rain.",
starting at weather.gov. Step 1: the generic search found NO
search box on weather.gov (its location lookup input is not
type=search and is not named like one) and fell back to
DuckDuckGo. Step 2: the planner tapped the old page's SEARCH
mid-navigation → "element 1 not on page" (the heal correctly
had nothing to re-find — a different page; the loop recovered).
Step 3: AUTO-READ fired on the DuckDuckGo results page and the
planner read it to end-of-text (3,768 chars of snippets) — the
results-page detection only knew Wikipedia shapes, so DDG was
treated as the arrived article. Step 4: the planner declared
DONE with the summary "Oakdale, Tennessee forecast provides
tonight's low temperature and chance of rain." — a restatement
of the goal containing NEITHER value. Ground truth that night:
low ~43-44F, clear, rain chance ~10% or less. The machinery
(recovery, completion guard, no re-read loop) all held; the
answer discipline did not. Three fixes, one per failure layer:

FIX 1 — GENERALISED SEARCH-RESULTS RECOGNITION
(RelevanceGuard.isSearchResultsPage): a page now also counts
as search results when ANY of: host html.duckduckgo.com with
a path starting /html/; host duckduckgo.com with a q= query
parameter; a title ending " at DuckDuckGo"; host
www.google.com or google.com with path /search and a q=
parameter; host www.bing.com or bing.com with path /search
and a q= parameter. The v1.6 rules (title "search results",
Special:Search path) are unchanged. Consequences, verified in
code, not just intended: auto-read cannot fire on a recognised
results page (shouldAutoRead already stood down on
isSearchResultsPage), so ARRIVAL cannot arm there (autoReadUrl
is only ever set by the auto-read block); and COMPLETION
cannot arm there either — the planner-read completion record
in Agent now also requires !isSearchResultsPage, since a
planner read of a results page to end-of-text was exactly how
the DDG snippets got crowned "fully read". A results page is a
waypoint again on every engine: the planner must tap through
to a real result.

FIX 2 — DONE-GROUNDING GUARD (RelevanceGuard.doneGrounding-
Reason + Agent wiring, standard guard machinery: trail
guard/correct, feed line "guard corrected done [summary
states no values -> not executed]", history note, no stuck
credit): when the goal requests number-shaped facts — goal
text carries a cue (case-insensitive): "temperature", "chance
of rain", "rain chance", "percent", "%", "price", "cost",
"how much", "how many", "how long", "how far", "when is",
"what date", "what time", "population", "distance"
(goalRequestsNumbers) — a proposed done is ACCEPTED only if
its summary is grounded (doneSummaryGrounded): it contains at
least one digit, OR it plainly states inability ("could not
find", "couldn't find", "not available", "unable to", "no
information", "not found"). Otherwise the done is corrected
(cap DONE_CORRECTION_CAP = 2 per run) with the note: your
summary gives none of the values the goal asks for — open the
most relevant result and read its actual values, then answer
done with the numbers, or say plainly that you could not find
them. After the cap, dones execute exactly as before — never a
trap. The check reads ONLY the done action's summary field.
Regression-pinned: the v1.9 Clinch River pass stays clean (the
goal is numeric — "how long" — and its summary carries 337).

FIX 3 — LOOKUP-BOX DISCOVERY (Hands.search path (b)): the
classic rule (type=search, or "search" in the input's own
name/label/placeholder) missed weather.gov because only the
FORM knows what its plain text input is for. MAP_JS now also
extracts each element's enclosing form's visible text
(ElementInfo.formText, capped at 200 chars: the form's
labels, legend, and plain text, skipping text inside
controls), and the target choice is factored purely in Eyes
(pickSearchTarget / isSearchInput / isTextLikeInput /
formSuggestsLookup / isLookupBox, host-tested): classic rule
first, exactly as before; otherwise a text-like input whose
form's text mentions "search", "location", "city", "zip" or
"postal" qualifies — but if TWO OR MORE newly qualify and
none qualified under the classic rule, the chooser refuses
(null) and the DuckDuckGo fallback runs as today. Ambiguity
is never guessed. Wikipedia path (a) and the fallback (c)
are unchanged, and the planner's query is typed through
unchanged — no rewriting it into a bare place name.

Tests: host harness 377 passed, 0 failed (338 existing + 39
new, all foreground-verified): every recognition rule
positive and negative (a normal article on duckduckgo.com
without q= does NOT match; a weather.gov forecast page does
NOT match; /search without q= does NOT match; Google /maps
with q= does NOT match), the no-auto-read consequence on the
DDG page with the arrival WOULD-fire sanity check beside it,
the done-guard matrix (numeric goal + valueless summary
fires — the field summary verbatim; digit summary silent;
inability summaries silent; non-numeric goal silent; non-done
action untouched; cap boundary at 2; the Clinch River
regression), the cue/grounding predicates, and the search-
target matrix (weather.gov shape discovered via its form;
classic rule wins by type and by placeholder; two broader
candidates refused; unrelated form text, checkbox and hidden
inputs never qualify; a 'location' form qualifies; the MAP_JS
formText shape).

Build: ./build-apk.sh green; apksigner signer cert unchanged
(de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b);
aapt2 badging com.ghosthands.app versionCode 12, versionName
2.1.0; full arm64-v8a lib set (libghhands, libllama, libggml,
libggml-base, libggml-cpu, libomp); lambda check: no lambdas.
APK: ~/workspace/your_files/ghost-hands.apk, 2,790,217 bytes,
sha256 3c36b3fc1e27f7e5877e4be1aebc34e20528d6d542a9b45a61ef0a8212e0bbb7.
Download: https://muse.ai/files/1450549544789833/1574746648016143/6pazqstmnll93ra7jrvefdkn/ghost-hands.apk
(link expires Oct 27, 2026, 03:12 UTC).

HONEST LIMITS:
- The done guard enforces only that SOME digit or an explicit
  inability is present in the summary. It cannot judge whether
  the number is the RIGHT one: a done claiming "low of 91
  degrees, 80% chance of rain" passes it untouched. Grounding
  is now required in shape; correctness remains the planner's
  (and the reader's) — the same limit v1.9 recorded for the
  read-after-complete correction.
- Results recognition covers the DuckDuckGo / Google / Bing
  shapes named above only. Other engines (Yahoo, Brave,
  Startpage, Ecosia...) and site-internal results pages with
  no distinctive shape are still recognised only by the v1.6
  title rule; one of those can still be auto-read as a false
  arrival.
- Form-text lookup discovery is heuristic: it reads the form's
  labels/legend/plain text, so a form that never says what its
  input is for stays undiscovered (the DuckDuckGo fallback
  carries the run, as it did in the field), and a broadly
  worded form could qualify an input that is not really a
  lookup box — mitigated, not eliminated, by the classic-rule
  priority and the refuse-ambiguity rule. The DOM/form-text
  extraction JS is not host-executable; only the pure
  decisions over the extracted fields are host-tested, with
  the JS pinned by a shape check. Its first live exercise is
  the next device run: weather.gov should now be searched via
  its own forecast form instead of the DuckDuckGo fallback.
- The done guard's cue list is a list: a numeric request
  phrased without any cue ("Tell me the low tonight and
  whether it rains") escapes it, and a non-numeric goal that
  happens to contain a cue word ("Find the Distance Learning
  page") is held to it for up to two corrections before the
  cap lets the run finish. Both are accepted costs of a
  deterministic rule over a 1.5B planner's judgment.

## v2.0.0 (Oct 24, 2026) — the self-healing hands release

THE MILESTONE: v1.9.0's field run (trail-20261024-224020, Ryan's
phone) is the app's FIRST FULL END-TO-END PASS. Search → article →
full 11,604-char read → the step-10 end-of-text guard correction
fired exactly as designed → the run ended: "Stopped (done): The
Clinch River is 337 miles long and is dammed by Norris Dam and
Melton Hill Dam." Correct and grounded ("Length 337 mi (542 km)"
sits in the extraction's opening infobox chunk). v2.0.0 changes no
behaviour that produced that pass; it fixes the two defects in the
run's messy middle.

DEFECT 1 — STALE ELEMENTS (steps 2 and 4 of the milestone run:
tap #11 "English" → "error: element 11 not on page"; tap #11
"Next page" → the same). Root cause: between perception and
execution sits a 40-100 s model generation. The hands address the
node stashed at the perceived index (window.__gh.els[n-1]), but
the live DOM churns during that generation window — lazy renders,
rotating chrome — so by tap time the stash slot can be empty or
hold a different node. v1 had no self-healing re-find, by design;
the tap simply died.

FIX 1 — IDENTITY-BASED RE-FIND AT EXECUTION (Eyes + WebHands):
- Identity is what ElementInfo already carries: for a link, name +
  href; for a control, name + tag + input type. At execution,
  WebHands re-reads the identity of the node NOW at the stash slot
  (new Eyes.identityJs) and compares it in Java
  (Eyes.identityMatches) against the element perceived in the
  same step (WebHands caches each perception's map).
- Match → act exactly as before (tapJs/typeJs untouched).
- Mismatch → a fresh identity scan of the live DOM (new
  Eyes.identityScanJs: MAP_JS's own selector, visibility filter and
  identity extraction, re-run with no stash side effects) and the
  pure decision Eyes.decideHeal: exactly one identity match → act
  on it via the new scan-act scripts (tapScanJs/typeScanJs) and
  report "ok: tapped #N (element moved; re-found as #M)" (type:
  "ok: typed into #N ..."), where M is the match's position in the
  fresh scan enumeration (scan position + 1) — the honest,
  checkable address of where the element was actually found.
- Zero or multiple matches → the original "error: element N not
  on page", byte-identical, so Agent-side handling of failures is
  completely unaffected. Success strings from a healed act are the
  only new planner-visible results; genuine action errors from a
  re-found node (e.g. a select without the requested option) pass
  through unchanged.

DEFECT 2 — PAGINATION WANDER (steps 3-4 of the milestone run: on
the results page the planner tapped "Next page" twice, paging
away from a page whose map — per the v1.8 run of the same query —
held the exact-match "Clinch River" result link. It recovered on
its own at step 5; nothing discouraged leaving.)

FIX 2 — RESULTS-PAGE PAGINATION GUARD (RelevanceGuard +
Agent, standard guard machinery: trail guard/correct event, feed
line, history note, no stuck credit): when the current page is a
search-results page (the existing isSearchResultsPage) AND the
current map contains a link whose name matches a significant goal
token (the guard family's significant-token name matching —
"Clinch River" for this goal), a proposed tap on a pagination
element (whole name, trimmed, case-insensitive: "Next page",
"Previous page", "Next »", "Next ›", "More results", or a bare
page number) is corrected with a note naming the match: a result
matching your goal is on THIS page (#N "Clinch River") — tap it
instead of paging away. Cap: 2 corrections per run
(RelevanceGuard.PAGINATION_CORRECTION_CAP); past the cap the
planner's paging executes. If NO goal-matching result link is in
the map the rule is silent and pagination proceeds untouched —
paging is legitimate when the results are poor.

Tests: host harness 338 passed, 0 failed (304 existing + 34 new,
all foreground-verified): identity matching (link name+href,
control name+tag+type, tag mismatch, empty slot), the heal
decision (stash MATCH wins even with duplicates in the scan,
REFOUND at the exact scan index from a replaced or emptied slot,
NONE, MULTIPLE refused, null perceived), the heal parsers, the
heal JS builders' shape, and the pagination guard (fires with a
matching result present and names it, silent when absent, silent
for non-pagination taps, silent off results pages, cap boundary at
2, bare page numbers, "More results", case-insensitivity, non-tap
actions untouched).

Build: ./build-apk.sh green; apksigner signer cert unchanged
(de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b);
aapt2 badging com.ghosthands.app versionCode 11, versionName
2.0.0; full arm64-v8a lib set (libghhands, libllama, libggml,
libggml-base, libggml-cpu, libomp); lambda check: no lambdas.
APK: ~/workspace/your_files/ghost-hands.apk, 2,790,215 bytes,
sha256 b59371a07b2a4cd6dae76b506d4f67aca0b9033694838548f74dfd6be9f235bb.
Download: https://muse.ai/files/1450549544789833/1506175774686158/3yw3kr3uqusf8riw6t3ebc5k/ghost-hands.apk
(link expires Oct 27, 2026, 02:58 UTC).

HONEST LIMITS:
- Re-find keys on name+href (links) / name+tag+type (controls)
  identity. A page with many identical links ("Read more" x20,
  same name, same href) produces MULTIPLE matches and healing
  refuses BY DESIGN — acting on a guessed twin is worse than the
  honest "not on page" failure, and the failure string the planner
  sees is then exactly v1's.
- The pagination guard only fires when a goal-matching result is
  PROVABLY in the current map; it never guesses that a better
  result exists elsewhere, and it stands down entirely off
  search-results pages.
- The JS DOM search itself is not host-executable; only the pure
  decisions (identity comparison, unique/none/multiple outcome,
  guard conditions) are host-tested, with the JS builders pinned
  by shape checks. The heal path's first live exercise is the next
  device run.

## v1.9.0 (Oct 24, 2026) — the finish line release

Field evidence (Ryan's real trail, v1.8.0, trail-20261024-222519,
the standing Clinch River goal — extraction fixed, navigation
perfect, one loop left):
- Steps 1-2: search ✓, tap #16 "Clinch River" ✓. Step 3: auto-read
  took chars 0-2400 of 11,604 (the full-text extraction works; the
  same article totalled 1,473 in v1.7); the planner's `read`
  continued at 2400-4800 through the shared cursor ✓. Steps 4-6:
  planner reads paged 4800-7200 (Norris Dam named in this chunk),
  7200-9600, 9600-11604 — the last carrying the result marker
  "; end of page text". THE ENTIRE ARTICLE READ.
- Step 7: the planner proposed `read` AGAIN. The cursor wrapped:
  "restarted at the top of the page; read chars 0-2400" — a wasted
  ~51 s step and ~2,400 chars of prompt to re-fetch text the
  planner already held. v1.8's read-after-complete correction did
  not fire: it keyed completion on the AUTO-READ alone, and this
  run's auto-read had stopped at 2,400 of 11,604. Completion was
  reached by planner reads, a path the rule could not see.

The fix (one gap, generalised — nothing else moved):
1. UNIFIED END-OF-TEXT STATE (Agent): readCompleteTotals, a
   per-URL map (URL -> extracted-text total chars), fed by BOTH
   read paths. The auto-read block records completion when its
   result reaches the end; a successful planner `read` execution
   does the same for the page it read. Detection is two pure
   helpers over the Eyes.formatReadChunk result the paths already
   share: Agent.readReachedEnd (the "; end of page text" marker)
   and Agent.readTotalChars (the T in "ok: read chars S-E of T").
   v1.8's single autoReadCompleteUrl slot and once-flag are gone;
   per-URL keying also retires v1.8's "a later partial auto-read
   clears the slot" behaviour — one page's record no longer
   depends on another page's auto-read.
2. END-OF-TEXT READ CORRECTION (RelevanceGuard
   .readAfterCompleteReason, same guard machinery and wiring
   position as v1.8 — after the arrival guard, before empty-map):
   while the agent is on a URL in the completion map, a proposed
   `read` is corrected (trail guard/correct, feed line "[page
   already fully read -> not executed]", history note carrying
   the totals). The helper's signature changed honestly:
   (action, currentUrl, completeUrl, completeTotalChars,
   correctionsSoFar) replaces (..., autoReadCompleteUrl,
   alreadyCorrected), and the once-per-run limit widened to a
   shared cap, READ_COMPLETE_CORRECTION_CAP = 3 per run — after
   the cap, reads execute again (no permanent trap). The reason
   now names what the planner holds: "you have read this entire
   page (chars 0-N of N) - reading again only repeats it from the
   top; answer {"action":"done"} from the text you have read".
   The v1.8 rule's behaviour is the special case where the map
   entry came from the auto-read.
3. COMPLETION BANNER (Agent.buildPrompt, new 6-arg form; the
   5-arg delegates with 0): when the CURRENT page's text is
   complete, one line joins the cue, after the repeated Goal line
   and after the arrival banner when both apply: "You have read
   this entire page. Answer done from it now." Threaded through
   buildPromptWithinBudget (new trailing parameter) so the banner
   is measured by the budget and, living in buildPrompt's fixed
   tail, never trimmed.

Tests: host harness 304 passed, 0 failed (290 v1.8 cases with
the 5 read-after-complete cases re-shaped against the new
signature — 7 checks: fires on the fully-read page, reason names
the totals, last-under-cap fires, at-cap silent, other actions /
different page / no completion quiet — plus 12 new: read-result
parsing x7 — mid-chunk not-ended but total parsed, final chunk
ended + total, planner-read completion arms the correction,
one-chunk page completes on first read, null/garbage safe — and
completion banner x5 — present, positioned after the repeated
goal before the cue, absent when incomplete, arrival banner
precedes it when both apply, under-budget byte-identity through
the budget builder).

Build: ./build-apk.sh green; signer cert de6969f0…25cd71b
(preserved keystore); badging versionCode 10 / versionName
1.9.0; 6 arm64 libs; no lambdas; delivered copy cmp-verified
byte-identical. APK sha256
05b85d8688e29ef4f0d260b7b12eb425fdb0e3d9b9fdfdde120c3ff8c029f2e3,
2,786,122 bytes, at ~/workspace/your_files/ghost-hands.apk.
Download: https://muse.ai/files/1450549544789833/1222497006774686/su9dsuqjaznxshcmp88tlqeq/ghost-hands.apk
(link expires Mon Oct 26, 2026, 10:37 PM ET).

Honest limits:
- The correction forces the done-or-leave choice; it cannot force
  a CORRECT summary. Answer quality remains the 1.5B planner's:
  the facts are all in its history (the infobox's "Length 337 mi
  (542 km)" rides in the extraction's opening chunk; Norris Dam
  is named mid-article), but if it answers from the lead alone it
  may say "more than 300 miles" rather than 337 — grounded, but
  looser than the goal asks.
- Completion is keyed on the extraction's own total: a page
  whose text grows between chunks (live feeds, lazy loading)
  can complete against a stale total, and the cursor's chunk
  offsets already assume a stable page (v1.2 limitation,
  unchanged). A re-read after the 3-correction cap is the
  escape hatch, as designed.
- The map is per run and per URL exactly as perceived: the same
  page under a different URL spelling (redirects, fragments) is
  a different entry, matching how auto-read and the arrival
  guard already key.
- On-device proof pending: Ryan installs v1.9.0 and reruns the
  Clinch River goal. Success = the step after the final chunk is
  a done (or one corrected re-read, then done) — no wrap to
  "read chars 0-2400" — with the answer drawn from the text read.

## v1.8.0 (Oct 24, 2026) — the context diet release

Field evidence (Ryan's real trail, v1.7.0, the standing Clinch River
goal — best navigation yet, killed by context overflow):
- Step 1 search ✓. Step 2: the planner tapped #16, whose resolved
  element was name "Clinch River", href /wiki/Clinch_River — the
  CORRECT result, one step faster than v1.6.
- Step 3 (on the article): auto-read fired ✓, but reported "read
  chars 0-1473 of 1473; end of page text" — the extraction returned
  only 1,473 chars TOTAL for a full Wikipedia article. The planner
  then chose read (allowed, sensible-looking); the shared cursor
  was already at the end, so it wrapped and re-read the SAME 1,473
  chars, and that chunk entered history at full READ_HISTORY_CHARS
  weight while the auto-read section carrying the IDENTICAL text
  was also in the prompt. The page text was carried twice.
- Step 4: generate() returned "ERROR: prompt too long for context"
  (n_ctx 2048 exceeded). PlannerParse failed on it → fail-closed
  deny (by design for gibberish) → steps 5-6 repeated the identical
  overflow → stop: "planner produced unparseable output 3 times".
  The arrival guard was never exercised; the run died of prompt
  bloat, not bad navigation.

Fixes (all five):
1. PROMPT BUDGET (Agent.buildPromptWithinBudget + estTokens):
   every decide-step prompt is measured with the project's chars/4
   estimate and must fit PROMPT_TOKEN_BUDGET = 1700 (2048 ctx −
   160 generation − margin). Over budget, it trims deterministically
   in this order, re-measuring after each step and stopping as soon
   as it fits: (i) stub duplicated read history (fix 2), (ii) drop
   the Page text preview, (iii) shorten history to the most recent
   3 entries, (iv) truncate the auto-read section's tail with a
   marker, (v) truncate the element map's tail at a line boundary
   with a marker. A last-resort clamp stubs both if a pathological
   prompt still overflows. The contract, the goal (either copy),
   the arrival banner and the cue are never trimmed; a prompt that
   fits untouched is byte-identical to v1.7 (host-tested).
2. READ-HISTORY DEDUPE: history entries now carry the URL of the
   page they were read from in a parallel list (addHistory is the
   only add path; trimHistory trims both). When the prompt shows an
   auto-read section for the current URL, a history entry that is a
   read result from that same page has its chunk replaced by
   "(page text shown above in the auto-read section; not repeated
   here)" — the step line and action JSON survive, so the planner
   still knows it read. Reads of other pages are untouched.
3. ENGINE-ERROR FAST STOP: a generate() result starting with
   "ERROR:" is now recognised (Agent.isEngineError) as an engine
   failure, not planner output: the decide event is recorded with
   the raw result and stats, and the run stops immediately with
   reason "error" and summary "model engine error: ERROR: ...".
   Genuine planner gibberish keeps the existing fail-closed path.
4. EXTRACTION FIX (Eyes.readTextJs): cause proven from code below.
   The extractor no longer uses innerText; it walks the DOM tree
   recursively collecting text nodes regardless of visibility
   (skipping script/style/noscript/iframe/svg subtrees, blocks and
   <br> become newlines, same whitespace cleanup as before), with
   the total capped at READ_TOTAL_CAP_CHARS = 60,000. Chunking
   pages through it exactly as before.
5. READ-AFTER-COMPLETE CORRECTION (RelevanceGuard
   .readAfterCompleteReason, agent-loop, once per run): if the
   auto-read of the page the agent is still on reached "end of
   page text", a planner read on that URL is corrected — the full
   text is already in the prompt; answer done from it. If the
   auto-read stopped mid-text, planner reads page through the rest
   normally and are never corrected by this rule.

Extraction diagnosis (cause, proven from code): Eyes.readTextJs
built the read text as `var t = root.innerText || root.textContent;`
(innerText first; the textContent fallback engages only when
innerText is empty). Per the HTML spec, innerText approximates the
RENDERED text: a subtree with display:none contributes nothing.
The article's DOM did contain the full text — the live article
(Course, History, Power industry, Ecology, Recreation, references;
infobox "Length 337 mi (542 km)") is many thousands of chars — but
an Android WebView gets Wikipedia's mobile presentation, whose
sections past the lead are collapsed (display:none) until tapped,
so <main>.innerText yields only chrome + hatnote + lead ≈ 1,473
chars, and the fallback never engages because innerText is
non-empty. The total is computed in-page (total: t.length), so no
Java cap explains the number either: Eyes.formatReadChunk only
formats the chunk it is handed, and parseRead passes the JS total
through. Host-verifiable evidence: regenerated read JS contains no
"innerText", walks "childNodes", and carries the 60000 cap
(host-tested). On-device verification: the next trail's read
events on the same article should show a total ≫ 1,473.

Host tests: 290 passed, 0 failed (244 existing + 46 new: token
estimate, engine-error decision, dedupe stubbing incl. other-page
and no-section cases, under-budget byte-identity, five budget
scenarios each proving its own trimming stage decides in order,
read-after-complete decision, read-JS shape).

Build: ./build-apk.sh green; signer cert de6969f0…25cd71b
(preserved keystore); badging versionCode 9 / versionName 1.8.0;
6 arm64 libs; no lambdas. APK sha256
642ec301a914d96c6ceaa911a206c51462396f3c6029a0f315bb99ce6eeb8015,
2,786,121 bytes, at ~/workspace/your_files/ghost-hands.apk.
Download: https://muse.ai/files/1450549544789833/1453058863631504/7qyw52rsm8b325opb651jas2/ghost-hands.apk
(link expires Mon Oct 26, 2026, 10:20 PM ET).

Honest limits:
- Budget trims lose the map's tail first on huge pages: stage (v)
  is the last resort precisely because cutting the map can hide
  the element the goal needs; on a page whose map alone exceeds
  the budget, later elements are invisible to the planner for that
  step (perception itself is untrimmed — the trail's perceive
  event still counts every element — and the next step
  re-perceives the full page).
- Full-text extraction makes each read chunk richer (real article
  body from the first chunk instead of lead-only), but reading a
  long article end-to-end still costs one step per ~2,400-char
  chunk; the auto-read + done path remains the fast route, and the
  budget now guarantees the prompt survives carrying it.
- The prompt budget is an ESTIMATE (chars/4, matching trail
  accounting); the native side remains the final authority — if a
  prompt still overflows, fix 3 now stops the run honestly in one
  step instead of burning three.
- On-device proof pending: Ryan installs v1.8.0 and reruns the
  Clinch River goal. Success = read events show totals in the tens
  of thousands, no context-overflow stop, the arrival guard holds
  on the article, and the run answers done with 337 mi / Norris
  Dam.

## v1.7.0 (Oct 24, 2026) — the commitment release

Field evidence (Ryan's real feed, v1.6.0, same Clinch River goal —
the closest run yet, thrown away at the last fence):
- Steps 1-4 worked: search, two taps on the results page, then
  tap #20 "Clinch River" → step 5: "Auto-read: Clinch River -
  Wikipedia". ARRIVAL + AUTO-READ BOTH CORRECT.
- Step 5's decision, WITH the article's auto-read text in its
  prompt: `search "Clinch River"` again. It EXECUTED — no guard
  applied: the current page was the article, not a results page
  (state-based and same-query repeat conditions silent), and the
  intervening taps had disarmed the history-based condition. The
  agent abandoned the page it had just read.
- Step 6 tapped the result "Clinch" (the disambiguation page),
  step 7 auto-read "Clinch - Wikipedia", step 7 proposed tap #20
  "Clinch River" — and the run STOPPED: "stuck: action repeated
  3 times: {"action":"tap","el":20}". Taps on element #20 on
  THREE DIFFERENT PAGES (results, results again after the
  re-search, the Clinch page) were counted as one repeated
  action: the repeat detector was page-blind.

Fixes (all deterministic, in the established guard style; the
governor and the planner contract are otherwise unchanged):

1. ARRIVAL ANSWER GUARD. New pure decision
   RelevanceGuard.arrivalLeaveReason (Agent wires it after the
   chrome-tab correction, before the empty-map correction).
   While the agent is still ON the URL auto-read fired for this
   run, a proposal that would LEAVE the page — `search`, `goto`,
   `back`, or a tap on an <a> element — is corrected through the
   existing guard machinery (trail guard/correct, feed line
   "[leaving arrived page -> not executed]", history note: you
   are on the target page and its opening text was auto-read
   above — do not leave; answer {"action":"done"} from that
   text, or use {"action":"read"} for more). Proceeding
   untouched: read, scroll, wait, done, stuck, type, and taps on
   non-link elements. Cap ARRIVAL_CORRECTION_CAP = 3 corrections
   PER RUN (not per signature — a planner abandoning its page can
   vary the leaving action's shape); after the cap the guard
   falls silent and proposals execute — no permanent trap. The
   guard disarms the moment the current URL is not the auto-read
   URL. Placed after chrome-tab so a page tab keeps its specific
   message on its first offence; a repeat tab tap (label already
   corrected there) is then caught here as the link tap it is.
2. PROMPT BANNER (the cheap salience half of fix 1). When
   buildPrompt's autoReadText is present, one line goes in
   immediately after the repeated Goal line, before
   "Next action JSON:": "You are ON the target page and its text
   is above. Answer done from it, or read for more." Absent
   whenever no auto-read section is in the prompt.
3. CONTEXT-AWARE REPEAT DETECTION. The "action repeated 3
   times" stuck counter no longer keys on the bare
   GhAction.signature(). New pure helper Agent.repeatKey:
   current perception URL + the action signature + (for
   tap/type) the resolved element's identity from the CURRENT
   perception — name + "@" + href — falling back to the bare
   element number ("#N") when the element cannot be resolved or
   carries neither a name nor an href. Element resolution moved
   up so it happens once, before the stuck check; every guard
   below uses the same resolved element as before. Same action
   JSON on a different page URL no longer accumulates; the same
   action on the same page at the same element counts exactly
   as before (the v1.1/v1.2 same-page repeat failures still
   stop). Counting placement and threshold (> 3) unchanged, and
   `read` stays exempt.
   SIGNATURE AUDIT (what else keys on the bare signature):
   the relevance guard's cap (guardCounts, 3 corrections per
   signature) still uses action.signature() — LEFT as-is: it
   only budgets how often that one guard may correct the same
   proposal shape inside its narrow pre-search / pre-arrival
   window; page-blindness there cannot stop a run, it can only
   stop correcting one. perception.signature() (the
   unchanged-map stuck check) is the page's own map hash —
   inherently page-scoped; untouched.

Tests: host harness 244 passed, 0 failed (220 v1-v1.6 cases +
24 new: arrival-guard decisions x14 — search / goto / back /
link tap corrected on the arrived page; non-link tap, read,
scroll, done, type proceed; not armed on another page; not
armed before any auto-read; an unresolvable tap is not provably
leaving; last-under-cap fires; at-cap silent — prompt banner
x3 — present with an auto-read section, positioned after the
repeated Goal and before the cue, absent without one —
repeat-key x7 — same page+action+element keys together, a
different page separates, a different element on the same page
separates, unidentifiable element falls back to its number,
the fallback still separates numbers, an identified element
differs from the bare number, non-element actions key on page
+ signature).

- APK: versionCode 8, versionName 1.7.0, same package/keystore
  (cert SHA-256 de6969f0...25cd71b, upgrades v1.6.0 in place,
  keeps the downloaded model).
  ~/workspace/your_files/ghost-hands.apk — 2,786,122 bytes,
  sha256 2de50a03f897bf73231fe65b515c1efa63e9430e50a2369515c79d1b98dd71ad
- Download link (expires Oct 27, 2026 01:52 UTC):
  https://muse.ai/files/1450549544789833/1377602364220249/t296emonc25hkdiqzgg6ubym/ghost-hands.apk

v1.7 limitations, honestly:
- The arrival guard is armed only while the agent remains on
  the (most recent) auto-read URL, and caps at 3 corrections
  per run. A planner that insists on leaving four times still
  leaves; the guard buys three explicit "you already have the
  text" corrections, not a lock.
- A goal that legitimately needs to leave an auto-read page —
  follow a citation to a second source, compare two pages —
  will spend corrections first (up to the cap) before it can
  go. Multi-page comparison goals remain a known weakness of
  the guard family as a whole.
- The guard keys on the single live autoReadUrl (the same URL
  whose section the prompt shows). Returning to an EARLIER
  auto-read page after a later one was read does not re-arm it,
  matching the prompt, which no longer shows that page's text.
- A link tap whose element cannot be resolved from the current
  perception is not provably a departure and is not corrected
  by this guard (the governor / empty-map / stuck logic remain
  the backstops, as before).
- repeatKey's element identity is name + href as perceived at
  proposal time; a page that re-renders between steps with the
  same element at a new number keys by identity (the point of
  the fix), while a genuinely different element that happens to
  share a name AND an href on the same page would share a key —
  that is the same target for the planner's purposes.
- Not yet run on a phone: the guard, banner and key are
  host-tested at the pure-helper level and compile-verified in
  the full build; the on-device proof is a field run that
  reaches an article and stays there.

## v1.6.0 (Oct 24, 2026) — the recovery release

Field evidence (Ryan's real trail, v1.5.0 run 2, Clinch River goal):
- The v1.5 prefix cache WORKS on device: steps 2-7 show
  reused_tokens 327/463/486/560/626/391 (65-91% of each prompt),
  prompt_ms down to 3.2-13.6 s against 46.7 s for a cold
  ~1000-token step. Step 1 of run 2 even reused 333 tokens cached
  from run 1 — the cache persists across runs in one app session.
- The run DIED OF A NETWORK ERROR, not planner failure. Step 1:
  the planner proposed `search "Clinch River"` while the WebView
  already sat on that query's results page (leftover from run 1;
  the fresh run's history was empty, so no repeat-guard fired).
  Executing it re-navigated and the load ABORTED:
  net::ERR_CONNECTION_ABORTED → chrome-error://chromewebdata/,
  "Webpage not available", 0 elements, for steps 2-8. The planner
  hallucinated taps on elements 1/2/3 ("error: element N not on
  page" x3), read the error page's text, and the stuck detector
  stopped the run at step 8. Nobody recovered the page.
- Step 1 cost 1833 prompt tokens / 87.5 s of prompt eval, ~600
  tokens of it the auto-read section — auto-read had fired on the
  SEARCH RESULTS page (title "Clinch River - Search results -
  Wikipedia" matches the goal tokens; the v1.3 limitation, now
  proven wasteful in the field).

Fixes (all deterministic, in the established guard style; the
governor and the planner contract are otherwise unchanged):

1. ERROR-PAGE RECOVERY (Agent loop, right after perceive, before
   auto-read / stuck / decide). New pure helper class ErrorPages:
   isErrorPage(url, title, textPreview) — true for a
   chrome-error:// URL, a net::ERR_ token in title or preview, or
   the error page's "Webpage not available" title; failedUrl()
   parses "The webpage at <url> could not be loaded" out of the
   preview (the perception's url field is the chrome-error
   placeholder, so the preview is the only place the real URL
   survives); netError() extracts the ERR_ token. On the FIRST
   sight of a given failed URL in a run: trail event "recover"
   (Trail.EVENT_TYPES gained it; fields url + failed_url +
   net_error), feed line "Page failed to load, reloading: <url>",
   then a NEW Hands method reload() (WebHands: loadUrl(failedUrl)
   via the gotoUrl path when the error page names one, else
   webView.reload(); both end in the same bounded settle wait
   gotoUrl uses), then a RE-PERCEIVE inside the same step (a
   second perceive event — honest: the first one really saw the
   error page) and the step continues with the fresh perception.
   If the reload throws, the re-perceive throws, or the fresh
   perception is STILL an error page, the run stops honestly:
   stop(step, "error", "page failed to load: <failedUrl> (<net
   error>)"). A failed URL seen again later in the run stops the
   same way — at most ONE auto-reload per distinct failed URL
   per run.
2. EMPTY-MAP GUARD. RelevanceGuard.emptyMapReason (pure): a
   proposed tap/type against a perception with 0 elements is
   corrected through the existing guard machinery (trail
   guard/correct, feed line, history note: the page shows no
   elements — it may have failed to load or still be loading;
   use goto/back/wait/read) and never executed. Cap: 3
   corrections PER RUN (RelevanceGuard.EMPTY_MAP_CORRECTION_CAP),
   not per signature — the field failure used a fresh element
   number each time. After the cap the proposal falls through
   and the governor / stuck logic owns it. (With fix 1, true
   error pages stop earlier; this covers empty maps from other
   causes — blank, still-loading, canvas-only pages.)
3. NO AUTO-READ ON SEARCH-RESULTS PAGES.
   RelevanceGuard.shouldAutoRead now returns false when
   isSearchResultsPage(title, url): the title contains "search
   results" (any case) or the URL path contains "Special:Search".
   A results page is a waypoint, not arrival; its ~240-char
   preview already carries the snippets. Article auto-read is
   unchanged.
4. SAME-QUERY SEARCH CORRECTION.
   RelevanceGuard.searchSamePageReason joins the repeat-search
   family (wired in Agent after the v1.2 state-based and v1.3
   history-based conditions, same guard machinery and history
   text): when the proposed `search` query equals the query the
   CURRENT page is already the results page for — the URL's
   search= parameter (URL-decoded, trimmed, case-insensitive) or
   a title of the form "<query> - Search results ..." — the
   search is corrected, even with a completely empty in-run
   history (the run-2 failure shape). The two older conditions
   work exactly as before.

Tests: host harness 220 passed, 0 failed (186 v1-v1.5 cases +
34 new: error-page detection x6 — chrome-error URL, net error in
preview, in title, error title alone, normal-page negative,
null negative — failed-URL parsing x3 — field-shaped preview,
absent phrase, null perception — net-error extraction x2,
isSearchResultsPage x3, auto-read exclusions x4 — results title,
Special:Search URL, question gating intact, article still fires —
queryFromResultsUrl x3 — '+' form, %20 form, absent param —
same-query correction x6 — URL match, case/padding variant,
title-form-only, different query negative, unrelated page
negative, non-search negative — empty-map decision x7 — tap and
type corrected, elements-present negative, read/goto never,
last-under-cap fires, at-cap silent).

- APK: versionCode 7, versionName 1.6.0, same package/keystore
  (cert SHA-256 de6969f0...25cd71b, upgrades v1.5.0 in place,
  keeps the downloaded model).
  ~/workspace/your_files/ghost-hands.apk — 2,782,025 bytes,
  sha256 aac5b575da310e2e2eedc0ad6b2be9456cef4621c3c265cc2a845323b3e9c8d5

v1.6 limitations, honestly:
- Recovery depends on the network actually coming back. One
  reload per failed URL per run; if the load aborted because
  connectivity is gone, the reload fails too and the run ends in
  the honest error stop — faster and without hallucinated taps,
  but still an end.
- A site that hard-blocks the WebView (bot walls, unsupported-
  browser pages that are not chrome-error pages) does not trip
  ErrorPages; the empty-map guard and the stuck detector are the
  backstops there, exactly as before.
- failedUrl parsing keys on Chrome's English error-page wording
  ("The webpage at ... could not be loaded"); a differently
  worded/localised error page yields null, and recovery falls
  back to a plain reload of the current URL.
- Error-page detection reads the perception's ~240-char text
  preview; an error page whose preview carries neither the
  net::ERR_ token nor the standard title (heavily customised
  builds) would be missed by fix 1 and left to fix 2.
- Not yet run on a phone: the recovery paths are host-tested at
  the pure-helper level and compile-verified in the full build;
  the on-device proof is a field run that hits (or doesn't) a
  load failure.

## v1.5.0 (Oct 24, 2026) — the speed release: prefix caching

Why: every agent step re-evaluated the ENTIRE prompt from scratch.
At the proof's measured ~20 tok/s prompt processing on the Moto G
Stylus 2025, a step's prompt cost 20-40 s of pure re-reading — most
of it the same static contract + goal (and, on same-page steps, the
same Page line, text preview and re-shown auto-read section) the
model had already decoded one step earlier. The shim cleared the KV
(llama_memory_clear) before every generate.

Design (native/shim/ghhands.c only; Java is observability):
- The handle caches the previous call's PROMPT token array. Its KV
  sits at positions [0, cache_len) of seq 0, followed by whatever
  that call generated.
- generate() tokenizes as before, then computes the longest common
  prefix (LCP) with the cached prompt. If a cache exists and
  LCP >= 64: the sequence's KV is trimmed back to exactly LCP and
  only the suffix tokens are decoded. Trim API, confirmed in the
  pinned header (ghost-os/spike-llama/src/include/llama.h, line
  761): `bool llama_memory_seq_rm(llama_memory_t mem,
  llama_seq_id seq_id, llama_pos p0, llama_pos p1)` — removes the
  sequence's tokens with positions in [p0, p1); p1 < 0 means
  [p0, inf). Called as seq_rm(mem, 0, reuse, -1).
- The final prompt token is ALWAYS re-decoded (reuse is capped at
  n_prompt - 1) so the greedy sampler sees fresh logits — this is
  also what an identical repeat prompt costs: 1 token evaluated.
- If seq_rm refuses the partial removal (returns false), the shim
  falls back to the pre-v1.5 path: full memory clear + full decode.
  If no/short cache (< 64 shared tokens), same full path directly.
- After a successful prompt decode, the cached buffer is replaced
  with the new prompt's tokens (a malloc failure just drops the
  cache; it never fails the call). On ANY decode failure (prompt or
  generation), the shim clears the memory, invalidates the cache,
  and returns exactly the pre-v1.5 ERROR strings.
- Result contract: String[5] -> String[7]. [1] still total prompt
  tokens; [2] prompt-eval ms now measures ONLY the suffix decode;
  new [5] = prompt tokens reused via cache, [6] = prompt tokens
  actually evaluated. Java reading only [0..4] is unaffected.

Java observability (the device-run proof path):
- ModelEngine captures each call's array into an immutable Stats
  (lastPromptTokens / lastReusedTokens / lastEvaluatedTokens /
  lastPromptMs / lastGenTokens / lastGenMs), exposed via lastStats().
  parseStats is pure and defensive (null/short/garbage -> zeros,
  never throws), so an older 5-field array parses with reused =
  evaluated = 0.
- Agent records prompt_tokens / reused_tokens / evaluated_tokens /
  prompt_ms on BOTH trail "decide" events (parsed and fail-closed)
  and appends " (model: N prompt tokens, R reused, M ms)" to the
  two feed lines that already report a decision (the govern line
  and the unparseable line). No new feed line per step.
- Prompt contents, agent decisions, governor, guards, eyes and
  hands are UNCHANGED — planner-visible behavior is identical to
  v1.4; only speed and stats moved.

Tests: host harness 186 passed, 0 failed (164 v1-v1.4 cases + 22
new: parseStats well-formed x6, legacy 5-field array x4, null array
x2, empty array x1, error-only array x1, garbage slots x6,
modelStatsLine format + null tolerance x2). The native cache logic
itself CANNOT execute on the host (arm64) — it is compile-verified
via build-apk.sh plus review; its on-device proof is the trail:
from step 2 of a run onward, "decide" events should show
reused_tokens > 0, growing on same-page steps.

Expected savings (ESTIMATES, to be proven by the trail numbers on
Ryan's phone, not benchmarks): at ~20 tok/s prompt processing each
reused token saves ~50 ms. The contract + goal prefix is roughly
500-700 tokens of a typical 800-1,200-token step prompt, so a
navigation step should reuse ~55-70% and cut prompt-eval time from
~40-60 s to roughly ~15-25 s; same-page steps also share the Page
line, preview and re-shown auto-read section, so reuse can reach
~80-90% there and prompt eval can drop to a few seconds. Step 1 of
every model session still pays the full prompt, and generation
time (~14 tok/s) is untouched.

Limitations:
- The cache is per loaded model/session: it lives in the native
  handle and resets on model reload or app restart. It also holds
  exactly ONE previous prompt — a prompt that diverges early (a
  navigation step whose new Page line starts near the top) reuses
  only what precedes the divergence (typically contract + goal).
- Cache validity is purely token-prefix equality; that is the
  correct criterion for KV reuse, but it means any early prompt
  change (a different goal, a changed first history line) forfeits
  everything after it for that step.
- Context stays 2048 tokens; the cache does not grow it. The
  auto-read section is still re-shown (and now mostly re-used, not
  re-paid) on same-page steps.
- NOT yet run on a phone: the speed claim is unproven until a
  device run's trail shows the reused_tokens numbers above.

- APK: versionCode 6, versionName 1.5.0, same package/keystore
  (cert SHA-256 de6969f0...25cd71b, upgrades v1.4.0 in place,
  keeps the downloaded model).
  ~/workspace/your_files/ghost-hands.apk — 2,782,026 bytes,
  sha256 4d77dd72bcd48cd471dd437d04bf51371481cdb41e944a1d8730b80719ebae1b
- Download link (expires Oct 27, 2026 01:05 UTC):
  https://muse.ai/files/1450549544789833/1675347891045422/6oytnx1frb6n9x9lzyy0vys8/ghost-hands.apk

## v1.4.0 (Oct 24, 2026) — the ethereal reskin

RESKIN ONLY, at Ryan's approval of a concept mockup he shared. No
behavior changed: governor, agent loop, model download/load,
eyes/hands, trail format, and every callback are byte-identical
v1.3. Only MainActivity's presentation and the manifest version
moved. File mtimes confirm no other source file was touched.

- Palette: background gradient #060B18 -> #04070F; cards #0A1220
  with a 1dp #24506E border (the faint cyan glow edge); accent
  cyan #38C6FF; red #FF4A4A reserved for STOP and stuck/denied/
  error; text #EAF6FF, secondary #8FA8BF. Status/navigation bar
  tinted to the background.
- Header: "Ghost Hands" in bold serif (system SERIF — the closest
  framework face to the concept's display type), subtitle
  "Ethereal on-device agent", and a status pill at the right:
  red "● AGENT ACTIVE" while a run is live, cyan "● DOWNLOADING"
  during the model fetch, cyan "● MODEL READY" when idle-ready,
  muted "● NO MODEL" otherwise. The pill derives from the same
  state (running / downloading / store.ready()) the detailed
  status line reports; the detailed line itself moved into the
  model card, text unchanged, above a cyan progress bar.
- Address bar: full-width rounded pill (dark fill, glow border),
  "⌖" glyph at the left, borderless URL field, and a solid cyan
  "GO" segment at the right end.
- WebView stage: inside a rounded card. Framework WebView content
  cannot be reliably corner-clipped, so the card's 5dp padding is
  the bezel: the page's square corners stay inside the card's
  rounded outline. setClipToOutline is also set; where the
  platform honours it the corners round, where it doesn't the
  bezel carries the look.
- Goal: a card labelled "PROMPT" (small-caps cyan, letter-spaced)
  with the borderless goal input and a "✦" glyph at the right.
- Buttons: "▶ START" solid cyan with dark text; "■ STOP" dark red
  fill, red border, red text. Below, quiet dark buttons "View
  Trail" / "Export Log" / "Copy Log" (Copy Log kept: it is the
  clipboard path Ryan pastes run logs from; the concept showed
  two, the app's third behavior was not dropped). Steps/Trail
  toggle survives as small chips in the log card header.
- Feed is now "TRAIL LOG": the same feed lines rendered as
  timeline rows — ring-badge glyph (✓ cyan completed step, ◆
  muted info, ⚑ amber guard correction / governor ask,
  ⚠ red stuck/denied/error), step text in near-white, the
  "-> result" line as muted sub-text under its step, Goal/Trail
  lines as sub-text under the run header. Identical text per
  event; only the arrangement changed. Trail view still shows
  the raw JSONL (monospace, pale cyan, selectable).
- Approval panel: dark card with a red border (was pale yellow),
  text near-white, Approve solid cyan, Deny in the STOP style.
- Tests: host harness 164 passed, 0 failed — identical to v1.3.0,
  as expected: MainActivity is not in the Android-free test set,
  and no tested class changed.
- APK: versionCode 5, versionName 1.4.0, same package/keystore
  (cert SHA-256 de6969f0...25cd71b, upgrades v1.3.0 in place,
  keeps the downloaded model).
  ~/workspace/your_files/ghost-hands.apk — 2,782,025 bytes,
  sha256 d99dc9b17689c283f8c9f86fa0c1975889bd9885eb2509a52e9b18cce986daf3
- Concept elements framework-only widgets could not match:
  the smoky ghost artwork and photographic backdrop (no image
  assets; gradient + type carry it), true rounded clipping of
  live WebView content (bezel instead), the pill's relative
  timestamps, and the concept's filled red STOP (kept as dark
  red fill + red text so the disabled state stays readable).
- NOT verified on a phone: the visual check is Ryan's device
  pass. Nothing here claims the look rendered as intended until
  he installs it.

## v1.3.0 (Oct 24, 2026) — the arrival release

Why: v1.2's field run (same Clinch River goal) got further and
failed later, in two new shapes:
- Step 2 executed the IDENTICAL search again — the v1.2
  repeat-search guard did not fire. Its condition derives "still
  on the results page" from agent state tied to the last
  execution's perceived page, and a perception that races the
  navigation can leave that picture stale.
- Steps 4-5, ON the article, tapped element #16 "Article" (the
  Wikipedia page tab) twice in a row — a no-op — instead of using
  read. The planner never recognised it had ARRIVED; the goal's
  words "open the article" kept pointing it at the tab.

Changes:

- AUTO-READ ON ARRIVAL. When a perception's title or URL matches
  a significant goal token (RelevanceGuard.pageArrived — the same
  token rule the v1.1 guard uses: >= 4 chars, stopwords removed,
  host tokens like "wikipedia" excluded) AND the goal is a
  question/information goal (RelevanceGuard.isQuestionGoal:
  "tell me" / what / how / which / who / when / ...), the agent
  loop performs one read extraction itself, before the planner
  decides anything that step. The chunk is injected into the
  prompt as a labelled section — "Page text (auto-read, chars
  X-Y of Z):" — via Agent.buildPrompt's new 5-arg form (the 4-arg
  form remains, delegating with no section). Recorded in the
  trail as a new event type "read" with phase "auto" (Trail
  EVENT_TYPES gained "read"), and shown in the feed as
  "Auto-read: <page title>". At most one extraction per distinct
  URL per run; the extraction goes through WebHands.read(), so
  it shares Eyes.ReadCursor with planner reads — a later
  planner `read` continues from the auto-read chunk. The chunk
  is re-shown in the prompt on every step while the agent stays
  on that URL (see limitations). Prompt rule addition: "If a
  Page text (auto-read) section appears below, answer from it;
  use read again only if the fact you need is not in it."
- HARDENED REPEAT-SEARCH GUARD (history-based, race-proof).
  RelevanceGuard.searchRepeatByHistoryReason corrects a proposed
  search when the immediately previous EXECUTED action was a
  successful `search` with the same query (case-insensitive,
  trimmed) and no tap/goto/read/type action has executed since —
  regardless of the currently perceived URL (the condition never
  consults a perception). It runs alongside the v1.2 state-based
  condition in Agent; either firing corrects the repeat through
  the same guard machinery (trail guard/correct event, feed line,
  history correction), still before the stuck counter.
- CHROME-TAB TRAP. Prompt line: "If the page title already
  matches your target, you are ON the article. Do not tap page
  tabs such as Article, Talk, Read, or Edit; use read." Plus a
  RelevanceGuard extension (chromeTabReason): on an arrived page
  (pageArrived's rule), a tap on an <a> whose whole trimmed name
  is one of Article / Talk / Read / Edit / View history /
  Contents (case-insensitive) is corrected — not executed — once
  per label per run (guard/correct trail event, feed line,
  history note: "that tab does not change the page; use read").
- Tests: host harness 164 passed, 0 failed (132 v1-v1.2 cases +
  32 new: isQuestionGoal x4, shouldAutoRead x5 — fires on arrived
  question goal, not on a non-question goal, not on a
  non-matching page, once per URL, still fires for a different
  arrived URL — autoReadSection framing x3, prompt auto-read
  section + answer-from-it rule + chrome-tab rule x4,
  history-based repeat-search guard x6 — including firing on
  execution history alone with the perceived URL irrelevant —
  chrome-tab correction x10 — fires for Article and Talk, once
  per label, quiet before arrival, ignores ordinary links,
  buttons, and non-tap actions, label normalisation x2).
- APK: versionCode 4, versionName 1.3.0, same package/keystore
  (cert SHA-256 de6969f0...25cd71b, so it upgrades v1.2.0 in
  place and keeps the downloaded model).
  ~/workspace/your_files/ghost-hands.apk — 2,777,930 bytes,
  sha256 6ec91954df98181f430c6f95ebab360426689d8012d394d3f0984a040e73cbad
- Download link (expires Oct 26, 2026 22:00 UTC):
  https://muse.ai/files/1450549544789833/1275892631359145/ckzn8vz0g9p985wnl411gv9p/ghost-hands.apk

v1.3 limitations, honestly:
- The arrival rule is title/URL tokens only, so a question goal
  can also auto-read a token-matching SEARCH RESULTS page (its
  title carries the target's name): one extraction there, once
  per URL. Usually harmless (result snippets), occasionally even
  sufficient — but it spends one of the run's reads off-article,
  and the article gets its own auto-read when reached. A target
  page whose title/URL contains NO significant goal token (a
  bare domain, an unrelated headline) gets no auto-read at all.
- The auto-read chunk is re-included in the prompt on every step
  while the agent remains on that URL: +~2400 chars (~600
  tokens, ~30 s of prompt processing at this phone's ~20 tok/s)
  per arrived-page step. The extraction happens once; the
  re-display is a deliberate prompt cost so a stray planner step
  before answering does not lose the facts.
- Auto-read is best-effort: an extraction exception is swallowed
  (no trail event, no stop) and the planner's own `read` action
  is unaffected.
- The history-based repeat-search guard disarms on the first
  executed tap/goto/read/type after the search — including a
  read — after which a determined re-search is the stuck
  counter's problem again, as in v1.2. It complements the v1.2
  state-based condition rather than replacing it.
- The chrome-tab correction fires once per label per run, and
  the label set is Wikipedia-shaped; other sites' page chrome
  ("Overview", "Reviews", ...) is not covered, and a second
  proposal of the same tab executes (a harmless no-op).
- No prefix caching (explicitly out of this round): prompts are
  still re-evaluated per step.

## v1.2.0 (Oct 24, 2026) — the reading release

Why: v1.1's field run fixed the opening move (step 1: search
"Clinch River" -> Wikipedia results page) and then died anyway: the
planner repeated the identical search twice more and the run stopped
as stuck (same action x3). Two defects underneath:

A. The results-page map hid the prize. On Special:Search, v1.1
   tiering put every form control — including the dozens of
   namespace/filter checkboxes — ahead of links, and the 40-element
   cap + ~1500-char render budget crowded the actual result links
   out of what the model could see. Re-searching was the only move
   left that made sense to it.
B. The agent could not read. Perception was only an interactive
   element map; no page text ever entered the loop, so a question
   goal ("how long is it, which dam is on it") was unanswerable even
   standing on the right page.

Changes:

- READ ACTION + PAGE TEXT. New action {"action":"read"} (no params;
  strict parsing unchanged), classified readonly -> allow by the
  Governor. Execution (MainActivity.WebHands.read) extracts readable
  text via JS injection — <main>, else <article>, else body
  innerText, whitespace-normalised — and returns one ~2400-char chunk
  per call from a per-page cursor (Eyes.ReadCursor): repeated reads
  continue where the last stopped, the result string reports
  "chars X-Y of Z; read again for more" (or "; end of page text"),
  a URL change restarts the cursor, and a read past the end wraps to
  the top and says so. Agent history carries read results at up to
  2600 chars (other results keep the 120-char truncation), so the
  text actually reaches the next prompt. Perception also carries a
  ~240-char page-text preview ("Page text: ..." in the prompt, and a
  text_preview field on the trail's perceive events) so the planner
  can tell what page it is on. Prompt rules: a question goal means
  open the target page, read (repeatedly if needed), then answer
  done — with the summary based only on text actually read.
  Consecutive reads are exempt from both stuck checks (the
  action-repeat counter and the unchanged-map counter): chunked
  reading is progress, not repetition. The 25-step budget still
  bounds a run.
- MAP REBALANCE. Shaping moved from the injected script into Java
  (Eyes.shapeMap) so the rules are host-testable: MAP_JS now returns
  up to 600 raw candidates in DOM order (nodes stashed on
  window.__gh.all) plus the text preview; Java tiers them (0 search,
  1 text inputs/textareas/selects, 2 buttons/submit, 3 checkbox/
  radio — DEMOTED from v1.1's tier 1 — 4 links), applies the caps,
  numbers the result, and the Activity re-stashes window.__gh.els
  from the shaped list's DOM indices (Eyes.stashJs), so hands still
  address exactly the numbers the planner saw. Caps: at most 6
  checkbox/radio elements survive; at most 18 non-link elements may
  precede the first link (excess controls nearest the link are
  dropped); total cap 40 and ~1500-char render budget unchanged.
- REPEAT-SEARCH CORRECTION + RESULTS GUIDANCE. After a search
  executes, its history entry carries a deterministic note: you are
  on the results page for "<query>" — tap the result link that
  matches the goal, or use read; do not search the same query again.
  If the planner still proposes the identical search (case-
  insensitive) while no navigating action (tap/type/goto/back) has
  executed since that search, the repeat is NOT executed:
  RelevanceGuard.searchRepeatReason drives the same guard machinery
  as v1.1 (trail event type "guard", outcome "correct", feed line,
  history correction, loop continues) and the check runs BEFORE the
  stuck counter, so a correction costs no stuck credit; guard
  corrections also reset the unchanged-map counter (a correction is
  not stagnation).
- Tests: host harness 132 passed, 0 failed (92 v1/v1.1 cases + 40
  new: read parse/classify/describe, prompt read contract + question
  rule + preview line x5, repeat-search guard x5, tier order x6,
  shaping order/numbering/stash-index x3, checkbox cap + link
  survival x2, pre-link cap x2, total cap x2, parseMap end-to-end
  shaping + preview x3, parseRead/formatReadChunk/ReadCursor x7).
- APK: versionCode 3, versionName 1.2.0, same package/keystore (cert
  SHA-256 de6969f0...25cd71b, so it upgrades v1.1.0 in place and
  keeps the downloaded model). ~/workspace/your_files/ghost-hands.apk
  — 2,773,834 bytes, sha256
  a6a3cce6b821747ea8399de34267719e7091c77559fa60cab13c45fa0a62e2eb
  (byte size coincides with v1.1.0's; the hash differs.)
- Download link (expires Oct 26, 2026 21:37 UTC):
  https://muse.ai/files/1450549544789833/1464935812198358/dve5p343iptf5euj13g52muz/ghost-hands.apk

v1.2 limitations, honestly:
- Read quality is extraction, not understanding: there is no
  readability-style content isolation. On article pages (Wikipedia)
  the first chunk is the lead + infobox, which is what question
  goals usually need. On portal/search/app pages the root element's
  text starts with navigation and chrome, and the fact the planner
  wants may sit several chunks in — each chunk costs a step and
  ~2400 chars of prompt history (a read-heavy run can carry up to
  five chunks ≈ 13k chars of history, so steps get slower as the
  run reads on).
- Chunk continuation assumes a stable page: the cursor is a
  character offset into a fresh extraction each call, so if the
  page's text changes between reads (live feeds, lazy loading,
  rotating banners), chunks can overlap or skip. A URL change is
  the only hard reset; the wrap-to-top behaviour on over-read is
  the recovery path.
- The pre-link control cap drops controls, not just deprioritises
  them: on a control-dense page with links (a big settings form
  with nav links), controls past the 18th before the first link
  are invisible to the planner for that step. Scrolling does not
  change the shaped map (visibility is DOM-based), so a dropped
  control stays dropped until the page itself changes.
- MAP_JS bounds raw candidates at 600 in DOM order; pages with
  more visible interactives than that never offer the tail to
  shaping at all. Tier parity between the collected fields and the
  Java heuristic is approximate (the haystack covers name, label,
  placeholder, name-attribute and id; a control identifiable as
  "search" only by its icon or CSS class still tiers as a plain
  control).
- The repeat-search correction stays armed across scroll/read/wait
  (same page) and disarms on the first navigating action; a planner
  determined to re-search after tapping anything is the stuck
  counter's problem again, as in v1.1.
- No prefix caching (explicitly out of this round): prompts are
  still re-evaluated per step, and read chunks make them longer.

## v1.1.0 (Oct 24, 2026) — the search release

Why: two different Wikipedia goals on v1.0.0 produced the IDENTICAL
wrong first move — tap #16 "War of the First Coalition" off the Main
Page, then wandering. The goal was in the prompt, but there was no
search action: the planner had to find a search box and coordinate
type+submit while a 40-link map dominated the prompt. v1.1 fixes the
reliability shape, not just the wording:

- Dedicated `search` action: {"action":"search","query":"..."}
  (PlannerParse requires a non-empty string query; strict
  single-object parsing unchanged). Governor classifies `search` as
  readonly -> allow under standard. Execution (MainActivity.WebHands):
  (a) Wikipedia host -> goto Special:Search on the CURRENT host with
  &fulltext=1; (b) else type into a likely search input from the map
  (type=search, or name/label/placeholder mentioning "search") and
  submit its own form (submit control if present, else Enter +
  requestSubmit/form.submit); (c) else goto DuckDuckGo HTML search.
- Eyes prioritization: candidates are tiered before numbering (DOM
  order kept within a tier) — tier 0 search inputs/controls, tier 1
  other inputs/textareas/selects, tier 2 buttons/submit controls,
  tier 3 links/clickables. window.__gh.els order == numbering.
  Rendered lines add label= / ph= context (<=24 chars) only when it
  carries information the name lacks; ~1500-char budget and 40-cap
  unchanged.
- Prompt: the search action is in the contract; rules added ("use
  search first" for search-shaped goals; never tap a content/article
  link unless its text matches a goal target; no featured/random
  homepage content); the goal is repeated immediately before
  "Next action JSON:" (small-model salience).
- Relevance guard (new, agent-loop, NOT the safety Governor): for a
  search-like goal, before any successful search and before arriving
  on a page whose title/URL matches a significant goal token (host
  tokens excluded, so "wikipedia" alone never counts as arrival), a
  proposed tap on an <a> whose visible name matches no significant
  goal token (>=4 chars, stopwords removed) and which is not a
  search/navigation control is NOT executed. Recorded as a trail
  event type "guard", outcome "correct", with the blocked action,
  element and reason; the planner's history gets a correction telling
  it to use `search`; the step feed shows the correction; the loop
  continues. Cap: 3 guard corrections per action signature, after
  which the existing stuck logic owns the repeat.
- Tests: host harness 92 passed, 0 failed (71 v1 cases + 21 new:
  search parse accept/reject x5, governor readonly/allow, describe,
  prompt contract x4, relevance guard x8).
- APK: versionCode 2, versionName 1.1.0, same package/keystore (cert
  SHA-256 de6969f0...25cd71b, so it upgrades v1.0.0 in place).
  ~/workspace/your_files/ghost-hands.apk — 2,773,834 bytes,
  sha256 9a63f130138f9c63b4dbabad12463a95b06803e1a86a6eac5b1acd6007ff49bf
- Download link (expires Oct 26, 2026 21:23 UTC):
  https://muse.ai/files/1450549544789833/1644782500572764/huvcnvaid1tu7jbij9l4qbk2/ghost-hands.apk

v1.1 limitations, honestly:
- Generic (non-Wikipedia) search path (b) is best-effort: it can only
  use a search box the eyes can see in the CURRENT map, and submission
  is synthetic (JS click / Enter events / form.submit), which pages
  with JS-driven search widgets, search-as-you-type UIs, or submit
  handlers that inspect event trust may ignore or mishandle. There is
  no verification that results actually loaded beyond the normal
  next-step perception; a failed site search surfaces as an unchanged
  or odd map for the planner to react to, not as a hard error.
- Path (c) changes the site context to DuckDuckGo — fine for lookup
  goals, wrong for "search within this site" goals on sites whose
  search box the eyes missed.
- The relevance guard is deliberately narrow: it only judges link
  taps on search-like goals before arrival. A wrong tap on a button,
  or any tap after arrival/search, is the Governor's and the
  planner's business, exactly as in v1.
- No prefix caching (explicitly out of this round): prompts are
  still re-evaluated per step.

- Package: com.ghosthands.app, versionName 1.0.0, min SDK 24, target 34.
- Keystore: ~/workspace/ghost-hands-mobile/app.keystore (alias
  ghosthands, pass ghosthands) — NEW for this app; PRESERVE it, later
  versions must be signed with the same key. Cert SHA-256:
  de6969f02188f03b78f1f56afcd231a367ae5264b2f77acea38fe746425cd71b
- APK: ~/workspace/your_files/ghost-hands.apk — 2,769,735 bytes,
  sha256 aa1beed950679e26836a7b1d0e3aec7216b331455055eae6bbbd7e23a8ac6f93
- Download link (expires Oct 26, 2026 21:05 UTC):
  https://muse.ai/files/1450549544789833/1398895571985436/o2wyqotjdykmc9einc8zyw55/ghost-hands.apk
- Model (downloaded by the app on first launch, NOT in the APK):
  qwen2.5-1.5b-instruct-q4_k_m.gguf, 1117320736 bytes, sha256
  6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e
- Native: libghhands.so is the proof shim with JNI symbols renamed to
  com.ghosthands.app (loadModel / generate / freeModel). Context 2048,
  n_threads = n_threads_batch = 4 (the proof's on-device setting),
  greedy sampling. Stripped lib sizes match the proof byte-for-byte.
- Governor: faithful Java port of governor.py + policies.py (all four
  packs; "standard" is the app default). Host harness
  test/GovernorTest.java: 71/71 cases pass (classification cases ported
  from tests/test_governor.py + planner fail-closed parsing cases).
- Rebuild: ./build-apk.sh (llama libs are reused from
  ../native/llama-build; rerun ../native/build-llama.sh if missing).
  Governor test: javac the Android-free classes + test/GovernorTest.java
  with the JDK17 javac (-encoding UTF-8), run GovernorTest.

## Known limitations (v1)

- Prompt processing is ~20 tok/s on the Moto G Stylus 2025, so each
  step costs roughly (prompt tokens / 20)s + a few seconds of
  generation. Prompts are kept lean (map capped at 40 elements /
  ~1500 chars, history = last 5 steps), but a step still takes on the
  order of 20-40s. The shim clears KV per call, so the prompt is
  re-evaluated every step (no prefix caching yet).
- WebView eyes use JS injection only (no CDP): perception covers the
  top document's visible interactive elements. Iframes, shadow DOM,
  canvas UIs, and pages that fight injection will read as sparse or
  empty maps; there is no coordinate-click fallback in v1.
- Downloads offered by pages are recorded in the trail but not saved.
- Tap on a <select> focuses it; choosing an option is a "type" action
  with the option's text (the planner is told nothing special — this
  can confuse small models).
- One WebView, one tab, no file upload (set_file has no WebView
  equivalent wired), no self-healing re-find (Python runner's heal)
  in v1: a failed action ends the run with an error stop.
- Approval timeouts follow the pack (standard = 600s); Stop also
  declines any pending approval.
