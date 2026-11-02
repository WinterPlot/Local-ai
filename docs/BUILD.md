# Build

## Push from a phone (Termux)
```
git init -b main && git add . && git commit -m "LocalAI scaffold"
git remote add origin https://github.com/<you>/<repo>.git
git push -u origin main
```
Then run the "Android CI" workflow and download `LocalAI-debug-apk`.

## Pinned llama.cpp
`engine/src/main/cpp/llama.cpp` is a trimmed copy (ggml CPU, src, common, include, vendor) of the snapshot you supplied. CPU only: GPU backends were removed.

## Verification status
- Verified: `localai_jni.cpp` passes a g++ syntax check against the vendored llama.cpp headers (with a stub jni.h).
- NOT verified: Gradle/Kotlin compilation, CMake/NDK build, unit tests, lint, APK, running on a device. Expect to fix first-run CI errors; paste the failing log.
- minSdk 33 (`localai.minSdk` in gradle.properties) matches the llama.cpp Android example; lower values are unverified.
