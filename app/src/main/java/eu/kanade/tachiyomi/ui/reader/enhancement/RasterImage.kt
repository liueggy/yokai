package eu.kanade.tachiyomi.ui.reader.enhancement

/**
 * Decoded page in its original orientation, packed ARGB as [android.graphics.Bitmap.getPixels] returns it.
 *
 * Deliberately not a Bitmap: the enhancement core has to run and be tested on a plain JVM, so the
 * Android graphics adapter stays at the reader boundary instead of leaking into the pipeline.
 */
class RasterImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Image must not be empty" }
        require(width.toLong() * height.toLong() == pixels.size.toLong()) {
            "Pixel buffer does not match ${width}x$height"
        }
    }
}

/**
 * Processing and transient scheduling options for one enhancement run.
 *
 * [priorityRegion] and [onProgress] only change when intermediate frames become visible. They must
 * stay out of [EnhancementSpec] because the final pixels and disk cache identity are unchanged.
 */
data class EnhancementOptions(
    val denoise: Int = 0,
    val quality: String = "balanced",
    val priorityRegion: EnhancementRegion? = null,
    val onProgress: ((RasterImage) -> Unit)? = null,
    val checkCancellation: () -> Unit = {},
)
