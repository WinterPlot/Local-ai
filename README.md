# LocalAI

Native Android app that runs GGUF language models fully on-device with llama.cpp. No account, no API key, no network permission.

**Status: first scaffold, never compiled.** See docs/BUILD.md for what is verified and what is not.

## Get the APK (phone only)
1. Push this repo to GitHub (docs/BUILD.md).
2. Actions tab → "Android CI" → Run workflow (also runs on push to main).
3. Open the finished run → Artifacts → `LocalAI-debug-apk` → download, unzip, install (allow "install unknown apps").

## Use
Open the app → Models → Import a `.gguf` (file picker, no storage permission) → Load → chat.
Works offline. See docs/MODELS.md.

Third-party: llama.cpp (MIT), vendored snapshot in `engine/src/main/cpp/llama.cpp`.
