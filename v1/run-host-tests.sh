#!/bin/bash
# Host-test recipe (per BUILD_NOTES): javac the app classes +
# test/GovernorTest.java with JDK17 against the android-34
# android.jar (four classes import android types; the pure code
# paths the harness drives never call them), then run.
# Usage: ./run-host-tests.sh [outdir]
set -e
PROJ=$HOME/workspace/ghost-hands-mobile/v1
JDK=$HOME/workspace/jdk17
AJAR=$HOME/workspace/android-sdk/platforms/android-34/android.jar
OUT=${1:-$PROJ/out-hosttest}
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$PROJ"
$JDK/bin/javac -encoding UTF-8 -nowarn -cp "$AJAR" -d "$OUT" \
  app/src/main/java/com/ghosthands/app/*.java \
  test/GovernorTest.java
# args[0] = the shim path; the harness derives the v1 tree from it
# for its source-pin sections (skipped silently without it).
$JDK/bin/java -cp "$OUT:$AJAR" GovernorTest "$PROJ/native/shim/ghhands.c"
