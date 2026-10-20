package eu.kanade.tachiyomi.ui.reader.enhancement

import android.graphics.Bitmap

/**
 * The only place the enhancement core touches Android graphics.
 *
 * Keeping the conversion here means the queue, cache and engines stay JVM-testable, and the reader
 * wiring has one obvious boundary to look at.
 */
fun Bitmap.toRasterImage(): RasterImage {
    if (isRecycled) error("Cannot read a recycled bitmap")
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RasterImage(width, height, pixels)
}

fun RasterImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
