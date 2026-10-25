#!/bin/bash
# Ghost Hands Mobile v1 APK -- house manual recipe (aapt2 + javac + d8 +
# zipalign + apksigner; sandbox Gradle has no network).
# Framework-only Java: NO AndroidX, NO Kotlin, NO lambdas.
set -e
WS=$HOME/workspace
BT=$WS/android-sdk/build-tools/34.0.0
AJAR=$WS/android-sdk/platforms/android-34/android.jar
JDK=$WS/jdk17
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
NDK=$WS/downloads/android-ndk-r27
LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64
CLANG=$LLVM/bin/aarch64-linux-android24-clang
LLAMA_SRC=$WS/ghost-os/spike-llama/src
LLAMA_BIN=$WS/ghost-hands-mobile/native/llama-build/bin

PROJ=$WS/ghost-hands-mobile/v1
SRC=$PROJ/app/src/main
OUT=$PROJ/out
KS=$WS/ghost-hands-mobile/app.keystore

if [ ! -f "$LLAMA_BIN/libllama.so" ]; then
  echo "llama.cpp Android build missing; run the proof's native/build-llama.sh first" >&2
  exit 1
fi

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/libs" "$OUT/apkroot/lib/arm64-v8a"

echo '[0/9] keystore (preserved across versions)'
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" \
    -alias ghosthands -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass ghosthands -keypass ghosthands \
    -dname "CN=Ghost Hands, OU=Ghost Developer Studio, O=Ghost Developer Studio, C=US"
fi

echo '[1/9] compiling JNI shim (pure C over the llama.cpp C API)'
"$CLANG" -shared -fPIC -O3 -fvisibility=hidden \
  -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" \
  "$PROJ/native/shim/ghhands.c" \
  -o "$OUT/libs/libghhands.so" \
  "$LLAMA_BIN/libllama.so" -llog -lm

echo '[2/9] staging + stripping native libs'
cp "$LLAMA_BIN/libllama.so" "$LLAMA_BIN/libggml.so" \
   "$LLAMA_BIN/libggml-cpu.so" "$LLAMA_BIN/libggml-base.so" "$OUT/libs/"
# libomp.so is a DT_NEEDED of the ggml libs; ship the NDK clang runtime.
cp "$LLVM/lib/clang/18/lib/linux/aarch64/libomp.so" "$OUT/libs/"
"$LLVM/bin/llvm-strip" "$OUT/libs/"*.so
cp "$OUT/libs/"*.so "$OUT/apkroot/lib/arm64-v8a/"
ls -l "$OUT/libs"

echo '[3/9] compiling resources'
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled_res.zip"

echo '[4/9] linking'
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  "$OUT/compiled_res.zip"

echo '[5/9] compiling java'
find "$OUT/gen" "$SRC/java" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo '[5b] lambda check (no LambdaMetafactory in any class)'
if grep -rl "LambdaMetafactory" "$OUT/classes" >/dev/null 2>&1; then
  echo "LAMBDA FOUND in compiled classes" >&2; exit 1
fi
echo "no lambdas"

echo '[6/9] dexing'
"$BT/d8" --min-api 24 --output "$OUT" \
  $(find "$OUT/classes" -name '*.class' | tr '\n' ' ')

echo '[7/9] adding dex + native libs'
cd "$OUT" && "$JDK/bin/jar" uf base.apk classes.dex && cd - >/dev/null
cd "$OUT/apkroot" && "$JDK/bin/jar" uf "$OUT/base.apk" lib && cd - >/dev/null

echo '[8/9] zipalign + sign'
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/ghost-hands-unsigned.apk"
"$BT/apksigner" sign --ks "$KS" \
  --ks-pass pass:ghosthands --key-pass pass:ghosthands \
  --out "$PROJ/ghost-hands.apk" "$OUT/ghost-hands-unsigned.apk"

echo '[9/9] verify'
"$BT/apksigner" verify --print-certs "$PROJ/ghost-hands.apk" | head -3
unzip -l "$PROJ/ghost-hands.apk" | grep -E 'lib/arm64-v8a|classes.dex|AndroidManifest'
ls -l "$PROJ/ghost-hands.apk"
cp "$PROJ/ghost-hands.apk" "$WS/your_files/ghost-hands.apk"
echo BUILD-OK
