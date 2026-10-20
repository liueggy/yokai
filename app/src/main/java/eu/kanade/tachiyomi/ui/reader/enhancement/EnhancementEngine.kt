package eu.kanade.tachiyomi.ui.reader.enhancement

/**
 * A local upscaler.
 *
 * Implementations own their native resources and are not safe to call concurrently: [EnhancementCoordinator]
 * guarantees a single worker thread. A real engine may take seconds and must be cancellable only
 * between tiles, which is why the signal has to stay cooperative.
 */
interface EnhancementEngine {
    /** Stable identifier persisted alongside cached output, e.g. `animevideov3-2x`. */
    val id: String

    /**
     * Blocking and expensive: only call from the enhancement worker.
     *
     * Throw to report failure. Never return a partially processed image: a half-enhanced page is
     * worse than the original, so callers treat any exception as "keep the source".
     */
    fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage
}
