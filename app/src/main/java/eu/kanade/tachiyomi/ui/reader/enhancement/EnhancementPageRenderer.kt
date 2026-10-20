package eu.kanade.tachiyomi.ui.reader.enhancement

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Turns one compressed page into one enhanced bitmap, reusing cached output when possible.
 *
 * Deliberately free of reader classes: the holder supplies bytes and receives a bitmap, so the whole
 * decode/budget/cache/engine path can be exercised without a View.
 */
class EnhancementPageRenderer(
    private val coordinator: EnhancementCoordinator,
    private val cache: EnhancementDiskCache,
    private val budget: EnhancementBudget = EnhancementBudget(),
) {
    /**
     * Returns the enhanced page, or null when the page must stay as it is.
     *
     * Null is the normal answer for an oversized page, an animated source, a missing model or a
     * cancelled request: the caller keeps showing the original and never needs an error UI.
     */
    suspend fun render(
        pageBytes: ByteArray,
        model: EnhancementModel,
        engineVersion: String,
        geometryIdentity: String,
        denoise: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Reject before allocating: an oversized page would OOM instead of simply staying unenhanced.
        if (!budget.accepts(bounds.outWidth, bounds.outHeight)) return null

        val spec = EnhancementSpec(
            sourceSha256 = sha256(pageBytes),
            weightsSha256 = model.files.first().sha256,
            engineVersion = engineVersion,
            geometryIdentity = geometryIdentity,
            denoise = denoise,
        )
        val key = spec.cacheKey()

        cache.read(key) { decode(it) }?.let { return it }

        val source = decode(pageBytes) ?: return null
        val outcome = try {
            coordinator.request(spec, source.toRasterImage()).await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        }

        val enhanced = (outcome as? EnhancementOutcome.Ready)?.image ?: return null
        val bitmap = enhanced.toBitmap()

        // Cache what was actually displayed, so a later visit skips inference entirely.
        runCatching {
            cache.put(key) { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        }
        return bitmap
    }

    private fun decode(input: InputStream): Bitmap? = BitmapFactory.decodeStream(input)

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
