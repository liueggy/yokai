# Implementation progress

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
