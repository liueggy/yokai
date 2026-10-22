package eu.kanade.tachiyomi.ui.reader.enhancement

import android.graphics.Bitmap
import okio.BufferedSource

data class EnhancementPreviewSource(val bytes: ByteArray, val geometryIdentity: String) {
    companion object {
        /** Bound encoded preview input before creating a second compressed copy. */
        fun read(source: BufferedSource, geometryIdentity: String): EnhancementPreviewSource? {
            if (source.request(32L * 1024 * 1024 + 1)) return null
            return EnhancementPreviewSource(source.readByteArray(), geometryIdentity)
        }
    }
}

data class EnhancementPreviewResult(val original: Bitmap, val enhanced: Bitmap)

internal data class PreviewRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    companion object {
        fun centre(width: Int, height: Int): PreviewRegion {
            require(width > 0 && height > 0)
            val cropWidth = minOf(width, 256)
            val cropHeight = minOf(height, 256)
            return PreviewRegion((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
        }
    }
}
