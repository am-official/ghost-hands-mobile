# Ghost Hands Proof — build notes

Throwaway on-device proof APK (not the product). Built Oct 24, 2026.

- Package: com.ghosthands.proof, versionName 0.1-proof, min SDK 24, target 34.
- Keystore: ~/workspace/ghost-hands-mobile/proof.keystore (alias ghosthands,
  pass ghosthands) — NEW for this app; PRESERVE it, later versions must be
  signed with the same key. Cert SHA-256:
  0fdfbcb92bce1c21fad6c71c34519bd3035ab3deed9e098b7affc0e172e11972
- Engine: llama.cpp pinned b11382 (~/workspace/ghost-os/spike-llama/src),
  rebuilt fresh for arm64-v8a/android-24, Release, stripped
  (native/build-llama.sh; output native/llama-build/bin). Stripped sizes
  match the GhostIDE/ghostbot-proven set byte-for-byte.
- JNI shim: native/shim/ghproof.c — pure C over the llama.cpp C API.
  This pinned llama.h requires struct tags in C (llama_model etc.).
  libggml-cpu/libggml-base have libomp.so in DT_NEEDED: shipped from the
  NDK clang runtime (same as ghostbot).
- Model (downloaded by the app on first launch, NOT in the APK):
  qwen2.5-1.5b-instruct-q4_k_m.gguf from Qwen/Qwen2.5-1.5B-Instruct-GGUF,
  1117320736 bytes, sha256
  6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e
  (values hardcoded in MainActivity; local reference copy at
  ~/workspace/ghost-os/spike-llama/models/).
- Rebuild everything: native/build-llama.sh && ./build-apk.sh
- Deliverable: ~/workspace/your_files/ghost-hands-proof.apk
  (2,745,161 bytes, sha256
  917939f6d209436a323e5fe0fc5f5909ca103a865352f2bd16996c6d61806d14)
