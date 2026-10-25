# Contributing to Ghost Hands Mobile

Ghost Hands Mobile is a Ghost Developer Studio project. Contributions are
welcome. The bar is receipts: if a change claims a capability, the host
harness or a device trail proves it.

## Dev setup

There is no Gradle build. The app is framework-only Java (no AndroidX)
compiled directly with the Android SDK command-line tools.

You need: JDK 17, Android SDK platform 34 + build-tools, and the pinned
llama.cpp native build (see `v1/native/` and the root `BUILD_NOTES.md`).

```bash
git clone https://github.com/littlestjames82-sys/ghost-hands-mobile
cd ghost-hands-mobile/v1
./run-host-tests.sh   # host harness, must be green
./build-apk.sh        # builds ghost-hands.apk (needs the studio keystore
                      # for a signed, installable build)
```

## Verify before you send

- `./run-host-tests.sh` green, twice, in the foreground. At v3.16.0 that
  is 1,738 checks.
- If your change touches the planner's action grammar, the grammar parity
  oracle must stay at full marks (57/57 at v3.16.0).
- Device claims need a device trail. Host-green is not device-proven, and
  PR descriptions must say which one they are.

## The rules (non-negotiable)

- **Framework only.** No AndroidX, no third-party Java dependencies. If a
  feature seems to need a library, it gets designed until it doesn't.
- **The four permissions are the whole set.** INTERNET,
  ACCESS_NETWORK_STATE, RECORD_AUDIO, VIBRATE. Adding a permission is a
  release-level decision, never a drive-by, and the manifest is pinned by
  a host test.
- **Everything is governed.** A new action kind gets a Governor
  classification, a trail event registered in `Trail.EVENT_TYPES` first,
  and a fail-closed parser. Consequential behavior is approval-gated.
- **No secrets, ever.** Signing keystores, model files, and APKs are
  never committed (see `.gitignore`). The preserved keystore is what makes
  app updates installable; do not regenerate it.
- **Grammar stays default OFF** until the on-device A/B says otherwise.
- **Honest status.** Unproven things are called unproven, in the README,
  in build notes, and in your PR description.

## Publishing

Releases are maintainer-run: version bump, host suite green twice,
grammar oracle, APK built and badging-verified, a `BUILD_NOTES.md`
section, and a source push to this repo. Tags and GitHub Releases are a
separate maintainer decision; do not create them from a PR.

## Conduct

See `CODE_OF_CONDUCT.md`.
