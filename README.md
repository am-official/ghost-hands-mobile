# Ghost Hands Mobile

[![CI](https://github.com/littlestjames82-sys/ghost-hands-mobile/actions/workflows/ci.yml/badge.svg)](https://github.com/littlestjames82-sys/ghost-hands-mobile/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Release](https://img.shields.io/badge/release-v3.16.0-blue.svg)](CHANGELOG.md)
[![Host tests](https://img.shields.io/badge/host_tests-1%2C738-green.svg)](v1/BUILD_NOTES.md)

**A goal-driven web agent that runs entirely on your phone. No cloud brain, no account, no data leaving the device.**

Give it a goal in plain words ("find the length of the Clinch River", "watch this page and tell me when the price changes") and Ghost Hands plans, browses, reads, and acts on its own, using an on-device language model. Every consequential move is judged by a built-in governor before it runs, written to a provenance trail, and, when it matters, gated on your approval.

From [Ghost Developer Studio](https://github.com/littlestjames82-sys). MIT licensed. Sibling project: [Ghost Hands for desktop](https://github.com/littlestjames82-sys/ghost-hands) (Python, governed browser automation).

> Status (Oct 25, 2026): latest shipped release is **v3.16.0** (downloads, documents, page watch), and the source in this repo is synced to it. Nothing here is on the Play Store; builds are sideloaded APKs signed with the studio keystore. What is being built next is in `ROADMAP_EVERYTHING.md`.

## How it works

- **Brain on device.** Qwen2.5-1.5B-Instruct (GGUF, Q4_K_M or Q5_K_M) runs through a pinned llama.cpp build and a small JNI shim. The model downloads once on first launch (about 1 GB) and is never bundled in the APK.
- **Eyes.** A WebView stage plus JS perception builds a numbered element map of each page. Since v3.10 the planner usually receives only the delta from the last map (75% fewer map tokens on a stable page).
- **Hands.** A fixed action vocabulary: navigate, tap, type, scroll, read, search, choose, toggle, note, ask, open/switch/close tab, done. The planner replies in one JSON action at a time; parsing is fail-closed.
- **Governor.** Every action is classified before it executes (readonly / write / consequential), checked against the active policy pack, domain rules, and site grants, and recorded in the trail before it runs. Submits, purchases, and deletes ask first.
- **Trail.** Every run leaves a complete, inspectable record: decisions, actions, results, approvals, and corrections.

## Highlights by release

Full list in `CHANGELOG.md`.

- **v3.16 Downloads, Documents, Page Watch:** every download offer is judged by the Governor's own order (executable and archive shapes are consequential and ask first), goals can read local documents the way they read pages, and any page can be watched for changes.
- **v3.15 Multi-tab:** up to 3 tabs, per-tab perception and read state, done-grounding isolated per tab (a fact read in tab 1 does not ground an answer in tab 2; the scratchpad is the sanctioned bridge).
- **v3.14 Beyond the Browser (spike):** an accessibility-service feasibility probe, inert unless you enable it in Android Settings AND flip the experimental toggle. Verdict: GO-IF, readonly first.
- **v3.13 Trust Pack:** resume interrupted runs, a governor self-check in Diagnostics, and per-site remembered approvals you can revoke in Settings.
- **v3.12 Clarify + Retry:** the agent can ask you a clarifying question mid-run, and a stuck run offers one-tap strategy retries matched to the failure shape.
- **v3.11 Grammar Hunt:** root cause of the grammar-mode native crash found (a double token-accept in the shim) and fixed. Grammar stays default OFF until the on-device A/B is run.
- **v3.9 Forms Frontier:** selects, radio groups, checkboxes, dates, and multi-field forms as first-class perception and actions.
- **v3.0 to v3.8:** watched and scheduled checks, voice goals, planner styles (Careful / Fast / Thorough), the settings drawer and accordions, and 1,600+ host tests by v3.15.

Full per-release detail lives in `v1/BUILD_NOTES.md`.

## Permissions

Exactly four, and the set is pinned by a host test:

- `INTERNET` and `ACCESS_NETWORK_STATE`: browsing and model download.
- `RECORD_AUDIO`: voice goals only, requested at runtime on first mic tap.
- `VIBRATE`: approval and finish alerts.

No analytics, no trackers, no ads. The optional accessibility service (v3.14 spike) is a separate Android Settings enablement, not a permission, and ships default OFF behind a second in-app toggle.

## Building

The app builds without Gradle, using the Android SDK command-line tools directly (aapt2, javac, d8, zipalign, apksigner), the same recipe as the other Ghost studio apps.

```bash
cd v1
./run-host-tests.sh   # host harness: 1,738 checks at v3.16, must be green twice
./build-apk.sh        # produces ghost-hands.apk, signed with the preserved keystore
```

Requirements: JDK 17, Android SDK platform 34 and build-tools, and the pinned llama.cpp native build (see `native/` and `BUILD_NOTES.md` at the repo root for the proof build the product grew from).

Package: `com.ghosthands.app` · min SDK 24 · target SDK 34.

## Layout

- `v1/` : the product. Java sources in `v1/app/src/main/java/com/ghosthands/app/` (54 classes, framework-only, no AndroidX), host tests in `v1/test/`, native shim in `v1/native/shim/`.
- `app/`, `native/`, `build-apk.sh` at the root: the Oct 24 on-device proof the product was founded on. Kept for provenance.
- `parity-lab/`, `read-lab/`: development labs used to pin the perception JS against real pages.
- `ROADMAP_EVERYTHING.md`: the current release train (R1 to R14) with its status log. `CHANGELOG.md`: every release, one line each.

## Honest limits

- Steps are slow by cloud standards: prompt processing is roughly 20 tok/s on a Moto G Stylus 2025, so a step takes on the order of 20 to 40 seconds.
- Perception covers the top document's visible interactive elements. Iframes, shadow DOM, and canvas UIs read as sparse or empty maps.
- A completed weather answer on a current build, the grammar-ON device A/B, and several other field passes are still owed on a real device. Host-green is not device-proven, and this repo does not pretend otherwise.

## License

MIT. See `LICENSE`.
