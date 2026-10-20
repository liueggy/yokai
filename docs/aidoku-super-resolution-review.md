# Aidoku super-resolution review
Reference: https://github.com/Aidoku/Aidoku
Commit: ceee58b29d4f48106cf63042ca0cb075a8ac22b4
Scope: source inspection, not an Aidoku runtime benchmark. No Swift implementation copied.

## Verified architecture
- ModelManager.swift: actor-managed installation and filename-keyed loaded-model cache. First use compiles/loads Core ML; selects ImageModel or MultiArrayModel by metadata. Installed models support local inference.
- UpscaleProcessor.swift: returns original for missing model, loading failure or nil processing output. Only processes height strictly below configured maximum. Default is disabled, maximum height 2000.
- ImageModel.swift: Vision image-model wrapper using scaleFill and pixel-buffer output; no custom multiarray tiling here.
- MultiArrayModel.swift: metadata defines tensor names/shape, blockSize(default 256), shrinkSize, scale(default 2), overlap and grayscale preservation. best uses configured overlap; fast sets optional overlap to zero. Overlapping path is used only for overlap > 0 AND shrinkSize == 0. Quality therefore need not change processing for every model.
- Overlapping path: stride blockSize-overlap, edge-clamped samples, reused input/conversion buffers, cosine-ramp horizontal and vertical blending, cancellation checks between tiles/rows. Row-bounded intermediate processing still allocates complete source/output images; not arbitrary-length streaming.
- Non-overlap/shrink path: AsyncStreams and CPU-count-sized input buffer pool. Its failed-prediction path can yield the input buffer instead of aborting the page. Do not transplant failure/concurrency behaviour without tests.
- Grayscale preservation maps a source mask to output and converts masked pixels to weighted luminance; candidate for reducing color artifacts in monochrome regions, not proof of improved text fidelity.
- ReaderPageView.swift and ReaderWebtoonPageNode.swift both use the processor. Crop precedes downsample OR upscale. Downsample takes precedence when both settings are enabled.
- ImageProcessingSettingsKey.swift includes crop/downsample/upscale/maxHeight/quality; page cache keys append it to page identity. This helper does not include model filename/version; do not assume it alone invalidates model changes safely.
- ReaderViewController.swift observes enable/quality and reloads. Checked image paths await processing before final display; this is not evidence of original-first progressive replacement or instantaneous inference.

## Model installation caveats
Model list URL is HTTP. Inspected ModelInfo/download code has version metadata but no explicit digest/signature validation. Single-file writes are atomic. Packages unzip to a temporary directory before deleting the previous installation and moving the new one; delete+move is not a transactional replacement with rollback.
Yokai should instead require HTTPS, trusted manifests/weight hashes, bounded downloads/extraction and validated safe replacement.

## Mapping to Yokai
1. Keep Kotlin coordinator plus separate JNI/ncnn/Vulkan engine adapters. Swift/Core ML/Vision cannot serve as Android runtime code.
2. Keep original-first display, generation-checked replacement, one worker, dedup/cancellation and shallow next-page prefetch. These are Yokai design choices, not claims verified about Aidoku.
3. Retain one active loaded engine, releasing on model switch or memory pressure. Measure cold and warm latency separately.
4. Never remove engine-required padding/halo in a fast mode. Optional extra blend overlap requires seam and latency testing.
5. Enforce input/output pixel and allocation budgets, not height alone. Initially skip oversized webtoon images; region previews require contextual padding.
6. Cache identity includes source digest, engine/model/weight versions, scale/denoise, optional blend quality, crop/split/rotation/downsample geometry and pipeline version.
7. Treat grayscale preservation as optional pending real-page tests. Any tile failure must discard partial output and retain the original page.
8. Audit all code and weight licences before distribution. Aidoku is GPL-3.0; this review does not authorize unrestricted implementation copying.

## Baseline build
/root/projects/build-logs/yokai-baseline.exit = 1. Maven Central SQLDelight 2.2.1 dialect JAR downloads failed with TLS handshake termination. The unmodified baseline is not build-verified; this is not an introduced super-resolution source error.

## Remaining work
Repair dependency access and rerun baseline; audit licences; build resident JNI smoke test; measure real-page cold/warm inference and peak memory; then integrate settings, cache, preview, paged and webtoon readers. No feature APK exists yet.
