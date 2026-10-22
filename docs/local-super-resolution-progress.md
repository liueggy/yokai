# Implementation progress

## 2026-10-21 — Comparison models and preview duration

- Imported and device-tested two additional models: RealESRGAN-General-x4v3 and RealESRGAN-Anime-6B. Pinned export sources, licenses, hashes, validation and device results are documented in `model-comparison.md`. Both passed dynamic RGB float32 ONNX inference checks; phone-side checksums match the published values. Bundled model remains available.
- Test dialog shows elapsed seconds after successful enhancement, cleared on model change/new run/cancel/failure. Monotonic timing encloses actual preview processing and excludes source retrieval. Normal reading canvas remains unchanged. Display explains local detail timing includes model loading and is not a full-page benchmark.
- Phone previews succeeded for both imports: one-shot Anime 6B 8.53s and General v3 1.89s on the same current-page crop. These are not controlled performance benchmarks. Current selected model is General v3.
- 33 enhancement tests, assembleStandardDebug, apksigner and zipalign passed. Installed updated APK. Log `build/device-check/model-timing-build.log`.

## 2026-10-21 — LeakCanary ReaderActivity retention repair

- Retrieved the actual device LeakCanary database (one application leak). Signature a76c7d919a2522cd5dd2601d2b8ec6fa9633dd00 identifies EnhancementRuntime.cachedCoordinator -> inflight Deferred -> awaiting render continuation -> WebtoonPageHolder -> detached view -> destroyed ReaderActivity.
- Pager and webtoon page jobs now use their viewer-owned coroutine scope rather than independent MainScope instances. Viewer destruction cancels suspended holder jobs. ReaderActivity destruction also invalidates the enhancement runtime, cancelling queued work and retiring the native engine. Decoded temporary source bitmaps are recycled immediately after raster conversion.
- Regression test cancels viewer scope during inference and verifies the awaiting holder job is cancelled. Focused enhancement tests and assembleStandardDebug passed (`build/device-check/leak-fix-final.log`); updated APK installed on the connected phone. Original historical LeakCanary report is preserved. Repeated real-device exit/heap analysis is still needed before claiming no leak remains.

## 2026-10-21 — Android 16 device preview validation

- Connected ARM64 PGT-AN10 via adb; installed debug package separately from the stable app, preserving stable data.
- Reproduced current-page preview failure in long-strip reading. Private debug diagnostic captured `IOException: Image format not supported` from BitmapRegionDecoder before inference. The original page remained readable.
- Added a bounded BitmapFactory fallback when region decoding is unavailable, sampling full decode to approximately one million pixels before taking a center detail crop. Diagnostic stack traces remain private to debug builds; the reading canvas contains no parameters.
- Focused enhancement tests (32) and assembleStandardDebug passed (`build/device-check/format-fix.log`). Updated APK installed successfully. On-device test then generated the real enhanced preview and original/enhanced toggles. Evidence: `build/device-check/fixed-preview.png` and `fixed-original.png`.
- Confirmed long-strip preview on this device; the full five-mode matrix and full-page performance remain pending. Sampled fallback previews do not establish full-resolution reconstruction quality.

## 2026-10-20 — In-reader controls and current-page model test

- Reading settings > General now has a 阅读画面增强 entry opening enable/model controls and a current-page detail preview. Original/enhanced results share a zoomable view and can be toggled. Test bypasses the disk cache and exposes running, cancel, unsupported and failure states in Chinese. It does not imply full-page success: a centered source crop up to 256 pixels is tested.
- Preview reads the transformed original through the same pager merge/split and webtoon split/stack paths used for display. Encoded preview input is bounded to 32 MiB and region decoding avoids decoding an entire long strip just for the comparison.
- All explicit reading modes implement the shared preview/refresh hooks: left-to-right, right-to-left and vertical pager; long strip and continuous vertical webtoon. Settings changes refresh existing pages; returning from full model settings also rechecks preferences. Webtoon refresh includes cached pages so old models are not retained when scrolling backwards.
- Preview jobs cancel when the dialog closes; uncached inference cancellation propagates to the coordinator, and ONNX checks cancellation between tiles. Added tests for crop bounds across portrait/spread/long-strip images, exact transformed preview bytes, encoded size admission and cancellation at a tile boundary.
- Verification: focused enhancement unit tests and `assembleStandardDebug` succeeded with JDK 21; log `build/reader-preview-final.log`. Final ARM64 APK passed apksigner/zipalign checks. No Android device is connected, so interactive preview, scroll/zoom retention and the five-mode device matrix remain unverified. Existing animation/large-page fallback rules remain.

## 2026-10-20 — Chinese UI and selectable models

- Added Simplified/Traditional Chinese enhancement labels, model picker and import status messages. The first launch without an explicit language choice uses Simplified Chinese; later manual language changes remain available.
- Reader image views force diagnostic overlays off, including when old debug preferences are restored. Debug preferences also default to false.
- Settings > Reader now lists the bundled model and every validated import. Import uses the system document picker, runs off the UI thread, preserves original display names and previous imports, and selects the imported model after validation. The control is disabled while importing; failures use localized guidance.
- ONNX imports must be float32 RGB NCHW with dynamic tile dimensions and 2x/4x output. Two probe sizes verify shape, multiplier and finite output before installation. This does not imply support for arbitrary ONNX networks, fixed-size models or other formats. Only validated imports are exposed in the picker.
- Runtime resolves model weights and the renderer together. Selection is persisted by model identity, cache identity includes the selected weights, and switching retires the coordinator and closes native sessions on the inference worker. No enhancement parameters are added to the reading canvas.
- Final verification: JDK 21 Gradle `:app:testStandardDebugUnitTest --tests '*enhancement*' :app:assembleStandardDebug` succeeded; 27 enhancement tests, 0 failures/errors. Log: `build/enhancement-chinese-release-check.log`. The final ARM64 APK passed apksigner and zipalign checks; AAPT2 confirmed 阅读画面增强 / 增强模型 / 导入模型 in packaged resources.
- Current ARM64 APK SHA-256: `f2a43dddf39bfbc52a58a83a325afc25e6c4de9132cb1024349c7d3bb2b9155e` (supersedes the APK hash in the historical note below).
- No connected Android device or installed emulator is available. Document-picker interaction, language startup and visual/performance behaviour still require device validation. Existing D8/Kotlin metadata warnings remain despite successful builds.

## 2026-10-20 — Windows handoff: real model output repair

This is the current status; the implementation-stage notes below are historical.

- Reader integration and a bundled Real-ESRGAN animevideov3 ONNX model already exist. Both pager and webtoon run local inference and replace the displayed static page; the feature is disabled by default in Settings > Reader > Enhance page quality.
- Fixed native 4x-to-reader-2x downsampling. Previously adjacent output pixels reused one native block, losing model detail; output coordinates now advance through every native averaging block. Extracted the pixel copier and added four regression tests for 4x/2x sampling, averaging, halo cropping and output placement.
- Pipeline identity is now version 2 so incorrect cached output is recomputed. The cache identity test now checks a relative version change.
- Imported weights take priority over the bundled fallback even when that fallback is newly extracted. Imports preserve the bundled fallback; bundled extraction checks the published asset SHA-256. Two selection regression tests cover these cases.
- Reader jobs are cancelled when loading another image, detaching the pager, or rebinding/recycling a webtoon holder. UI publication rechecks the enable preference and attachment. Disabled enhancement no longer copies encoded page bytes.
- Verified with JDK 21: `:app:testStandardDebugUnitTest --tests '*enhancement*' :app:assembleStandardDebug` completed successfully; 21 enhancement tests, zero failures/errors. Log: `build/enhancement-final.log`.
- Desktop ONNX Runtime smoke test executed the actual bundled model: float RGB NCHW input `[1,3,32,32]` produced finite `[1,3,128,128]` output. This is model execution evidence, not Android-device performance evidence.
- ARM64 debug APK: `app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk`; model asset digest and presence of ARM64 ONNX native runtime were independently checked in the final ZIP. APK SHA-256: `d9b0b1a92f8514336167c416b6cdb0c4f6044a6238216576da0aefe4f5fbb10a`.
- Remaining validation: no adb device is connected. Real-device visual quality, latency, peak memory and long-strip behaviour are unverified. Existing D8/Kotlin metadata and deprecation warnings remain despite successful packaging. Oversized pages still fall back to the original under the existing budget; enhancement is not guaranteed for every page. Full encoded-byte capture when enabled, cooperative cancellation inside inference, model import UI and enhancement status UI remain follow-up work.

## 2026-10-20 — initial implementation slice

- Retried the unmodified application baseline using Java 17. Log: `/root/projects/build-logs/yokai-baseline-retry.log`; completion marker: matching `.exit` file. No success is claimed until that marker exists and equals zero.
- Repaired four missing SQLDelight 2.2.1 dialect artifacts (3-38, 3-33, 3-30, 3-24) in the local Gradle cache using Maven Central original artifacts and verified their published SHA-256 values. No repository mirrors or project dependency declarations were changed. Repair script: `/root/projects/build-logs/repair-sqldelight-cache.py`.
- Added `EnhancementSpec`: length-prefixed SHA-256 cache identity covering source and weights digests, engine, explicit geometry identity, quality, denoise, native 2x scale, and pipeline version. Geometry identity must be supplied by the eventual holder/coordinator and include all pixel transformations; the identity object does not discover transformations automatically.
- Added `EnhancementBudget`: rejects animation, invalid dimensions, output dimension overflow, and input/output/buffer over-budget requests before decoding. This only budgets image buffers, NOT total native/Vulkan memory, and must not be represented as an OOM guarantee.
- Added JUnit Jupiter tests for identity invalidation, delimiter collision avoidance, validation, and budget boundaries. Tests have been written but have NOT yet been executed.

The two new Kotlin files are not connected to the reader and do not enable enhancement. JNI, resident model, downloads, cache storage, settings, preview, and both reader modes remain unimplemented. No feature APK exists.

## Next actions

1. Inspect baseline retry completion; if it succeeds, run `:app:testStandardDebugUnitTest --tests '*EnhancementSpecTest'` and Kotlin formatting checks. If dependency resolution fails again, preserve exact error and repair only verified artifacts rather than silently changing dependencies.
2. Finish code and model-weight license audit before bundling third-party code or weights.
3. Add ARM64 native build and resident JNI/ncnn smoke test; measure cold/hot inference and native memory separately.
4. Implement model installation, atomic bounded disk cache, single-worker coordinator, cancellation and binding-generation checks, then reader UI integration.

## Second implementation slice

- Previous baseline retry exited 1: Maven-provided AAPT2 could not start on ARM64. Confirmed SDK 36 AAPT2 is ARM64 and runs; restarted build with command-line android.aapt2FromMavenOverride (no project configuration change). Log: /root/projects/build-logs/yokai-arm64-build.log. Full build success is not yet established. This run includes new Kotlin files and is not a pristine baseline.
- Added single-process EnhancementDiskCache: bounded writes, fsync before same-directory rename, failed replacement preserves old entry, byte-budget eviction, validated keys, and lock-scoped reads. Default 512 MiB total and 64 MiB per entry. Crash-orphan temporary-file cleanup and encoded image validation remain integration work.
- Compiled all enhancement sources and tests directly with cached Kotlin compiler 2.3.10 and ran JUnit Platform 1.11.4: 7 tests started, 7 passed. This is standalone JVM validation, not Android/Gradle integration validation.
- Native/JNI and reader integration remain unimplemented.

## Toolchain follow-up

- Previous ARM64 AAPT2 override passed resource processing, but Java 17 javac rejected FastScroller dependency bytecode version 65 (Java 21).
- Started assembleStandardDebug plus enhancement Gradle tests using JDK 21 with the same ARM64 AAPT2 override. Project Java/Kotlin output target remains 17; using a newer compiler does not itself change that target.
- Log: `/root/projects/build-logs/yokai-jdk21-build.log`; completion code: corresponding `.exit` file. Do not claim success without checking the exit code and test reports.
- Attempted to collect commit-pinned third-party LICENSE evidence via GitHub. Requests timed out for ncnn, realcugan and waifu2x; `nihui/realesrgan-ncnn-vulkan` returned 404 and its canonical repository must be verified. Audit remains incomplete; no third-party code or model weights have been added by this step.

## Cache recovery follow-up

- Cache construction now cleans owned pending numeric temporary files and enforces the disk budget. Requires a single instance owning its directory.
- Added explicit remove(key) for future decoder-detected corrupt entries. Encoded-image validation itself is not yet implemented.
- Independent Kotlin/JUnit rerun: 8 tests passed, 0 failed; compiler and runner both exited 0. This is not Android integration validation.
