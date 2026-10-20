# Local manga super-resolution integration plan

Status: planned; no inference implementation yet.
Base: 843a59b20e4edd26c545dc48989a00162ac65937
Branch: feature/local-super-resolution

## Scope
Offline reader enhancement; persistent enable switch; current-page original/enhanced preview. Never overwrite downloaded source images. Start with ARM64 debug build, coexisting with official release.

## Initial models
- Real-CUGAN Pro native 2x: no-denoise / conservative.
- Real-ESRGAN AnimeVideo-v3 native 2x: fast-mode candidate, performance unverified.
- waifu2x CUNet native 2x: no denoise / low denoise initially. Additional levels only after testing.
Use official/upstream-converted ncnn weights and model-specific inference pipelines. Pin source revisions, verify checksums, and audit code AND weight redistribution licenses before bundling. Do not imply arbitrary ncnn model compatibility.

## Architecture
Reader preferences/UI -> EnhancementCoordinator -> priority queue -> JNI engine adapters -> ncnn/Vulkan (CPU fallback where validated) -> atomic file cache -> reader image source.
- EnhancementModelRegistry: id, version, scale, denoise variants, file names, checksums, license, supported backend.
- Engine adapters: CuganEngine, AnimeVideoEngine, Waifu2xEngine; share device/lifecycle management, not model-specific preprocessing.
- Single inference worker; current page first; only prefetch next page after memory testing. Cancellation checked between tiles; late native completion must not update recycled views.
- State: Disabled / Queued / Processing / Ready / Failed. Display original immediately; publish enhanced result only if page and settings generation still match.
- Preserve zoom and normalized center on swap. Avoid recursive load callbacks and reapplying enhancement to enhanced output.

## Source integration
Inspect and adapt PagerPageHolder (including spread/split/background handling), WebtoonPageHolder, and common ReaderPageImageView. Common view is a display seam, not a sufficient source/cache identity by itself. Retain reopenable original sources without reading large compressed streams into heap. Animated sources bypass enhancement initially.
Settings entry: reader settings; implement localized labels with existing i18n resources and preference infrastructure.

## Preview
Dedicated current-page comparison sheet/view with synchronized zoom and original/enhanced toggle. Explicit run-preview action works even when auto enhancement is off. Full-page result where safe; oversized long strips use a labeled region preview, never silently claim full-page processing. Model/denoise changes invalidate only relevant result.

## Memory and storage
2x only for first release; TTA disabled. Start tile tuning at 128 with overlap/padding and model constraints; benchmark 64/128/256 rather than promise a fixed value. Tiling reduces intermediate tensors, NOT full output bitmap cost. Enforce decoded/output pixel budgets before allocation; use file-backed/tileable output or explicit bypass for oversized pages. Native allocation failure/device loss must return error; use CPU fallback only when verified and bounded.
Disk cache key: source-content digest + engine/model revision + scale + denoise + processing version. Atomic temp-write/rename; bounded LRU (initial proposed 512 MiB); clear-cache action; recover from corruption. No two models resident simultaneously.

## Delivery gates
1. Baseline upstream build; resolve ARM-host Android toolchain issues without unrelated source edits.
2. License/source audit, JNI/ncnn ARM64 smoke build; validate Vulkan support on device.
3. Real inference on same test inputs for all three models; record cold/warm latency, peak memory, output dimensions, visual seams and text/net-tone fidelity.
4. Integrate paged reader and enable switch; lifecycle/race/error regression tests.
5. Preview and model selection; then long-strip/spread handling.
6. Build signed debug APK; install and test without touching official app data; export patch/source snapshot and usage notes.

## Acceptance
- All three models execute real local inference with model identifier visible.
- Enhancement off restores original without re-download or data loss.
- Rapid page turns, settings changes, rotation/background and missing/corrupt model do not attach wrong image or crash.
- Cache hit avoids inference; cache keys distinguish variants; cache limit respected.
- Verify no seams, clipped edges, incorrect aspect ratio, or destroyed text on representative black/white, color and long-strip images.
- Publish measured latency/memory; no guaranteed real-time claim before device measurements.

## Sources
https://github.com/nihui/realcugan-ncnn-vulkan
https://github.com/xinntao/Real-ESRGAN
https://github.com/nihui/waifu2x-ncnn-vulkan
https://github.com/tumuyan/RealSR-NCNN-Android

## First-release decisions and implementation checklist
- Default OFF. Persist selected engine/denoise; preview alone does not enable automatic processing.
- Proposed controls: enable enhancement, model, conservative denoise preset, preview current page, clear enhanced cache. Do not expose unsupported scales or arbitrary model import in v1.
- Ship policy remains undecided until weight audit: bundled verified assets versus explicit model download. Reading and inference must work offline once assets are present; no image uploads.
- A missing model disables that engine with a visible explanation; never silently substitute another model.
- Queue deduplicates by cache key. Page unbind removes its consumer; settings change advances generation. A running native call may finish but must not publish to stale consumers.
- Original source remains authoritative. Disable, cancellation, corrupt output, low memory or unavailable backend leaves/restores original and exposes a non-blocking reason.

### Work packages (ordered)
1. Build report: upstream task exit code, host architecture, SDK/NDK and any environment-only workaround.
2. Asset audit: exact upstream commit, each weight URL/path and SHA-256, code license, weight license evidence, required notices; unresolved permission blocks redistribution.
3. Native spike: ARM64 ncnn and one engine, device backend discovery, tiny synthetic input with verified 2x dimensions, bounded error propagation. Then port remaining two adapters.
4. Shared Kotlin layer: settings, model registry, source identity, bounded queue, generation/consumer tokens, atomic cache and injectable engine interface.
5. Paged-reader wiring: source capture after original page transformations; original-first presentation; safe replacement preserving viewport. Verify spread/split ordering explicitly before enabling those modes.
6. Preview and webtoon wiring: explicit current-page action, model identity and progress/error states; oversized region labeled clearly.
7. Regression and device benchmark; APK only after build and runtime gates pass.

### Required evidence before release
- Test fixture matrix: low-resolution B/W text and screentones, color page, JPEG artifacts, double-page spread, narrow long strip, animation, corrupt file, oversized image.
- Concurrency tests: rapid bind/unbind, settings flip during inference, chapter change, duplicate requests, cache eviction while a result is displayed.
- Cache tests: variants never collide, interrupted write is ignored, corrupt entry falls back, output is not recursively enhanced.
- Benchmark table per model: input size, actual output size, backend, tile size, cold/warm milliseconds, peak process memory and visual review. These values are currently UNKNOWN.
- No absolute real-time latency promise. Choose recommended engine only after the same-input device comparison.


## Aidoku review follow-up
See aidoku-super-resolution-review.md for commit-pinned source findings and Android mapping. Preserve engine-required halo; add full geometry/model cache identity and input/output allocation budgets. Baseline failed on dependency TLS downloads, not feature source.
