#!/bin/bash
# CI host-test recipe: the same compile + harness run as
# v1/run-host-tests.sh, with runner paths instead of the studio's
# local ones. The local script stays the developer entry point;
# this one exists so GitHub Actions can prove the suite green on
# a clean machine.
set -euo pipefail

PROJ="$GITHUB_WORKSPACE/v1"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AJAR="$SDK/platforms/android-34/android.jar"

if [ ! -f "$AJAR" ]; then
  echo "android-34 platform missing, installing via sdkmanager"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" >/dev/null
fi
ls -l "$AJAR"
java -version

OUT="$RUNNER_TEMP/hosttest"
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$PROJ"
javac -encoding UTF-8 -nowarn -cp "$AJAR" -d "$OUT" \
  app/src/main/java/com/ghosthands/app/*.java \
  test/GovernorTest.java
# args[0] = the shim path; the harness derives the v1 tree from it
# for its source-pin sections.
java -cp "$OUT:$AJAR" GovernorTest "$PROJ/native/shim/ghhands.c"
