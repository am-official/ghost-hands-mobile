#!/bin/bash
# Rebuild pinned llama.cpp (b11382) for arm64-v8a/android-24, Release.
set -e
NDK=$HOME/workspace/downloads/android-ndk-r27
SRC=$HOME/workspace/ghost-os/spike-llama/src
BUILD=$HOME/workspace/ghost-hands-mobile/native/llama-build
rm -rf "$BUILD"; mkdir -p "$BUILD"
cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_COMMON=OFF \
  -DGGML_BUILD_EXAMPLES=OFF -DGGML_BUILD_TESTS=OFF
cmake --build "$BUILD" --target llama -j2
ls -l "$BUILD/bin"
echo LLAMA-BUILD-OK
