package eu.kanade.tachiyomi.ui.reader.enhancement

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
        bypassCache: Boolean = false,
        priorityRegion: EnhancementRegion? = null,
        // The callback takes ownership of every bitmap, including when it is cancelled/rejected.
        onProgress: (suspend (Bitmap) -> Unit)? = null,
    ): Bitmap? {
        val startedAt = System.nanoTime()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pageBytes, 0, pageBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Reject before allocating: an oversized page would OOM instead of simply staying unenhanced.
        if (!budget.accepts(bounds.outWidth, bounds.outHeight)) {
            EnhancementTrace.record("budget_skip", "width" to bounds.outWidth, "height" to bounds.outHeight, "geometry" to geometryIdentity)
            return null
        }

        val spec = EnhancementSpec(
            sourceSha256 = sha256(pageBytes),
            weightsSha256 = model.files.first().sha256,
            engineVersion = engineVersion,
            geometryIdentity = geometryIdentity,
            denoise = denoise,
        )
        val key = spec.cacheKey()

        EnhancementTrace.record("request", "key" to key.take(8), "width" to bounds.outWidth, "height" to bounds.outHeight, "geometry" to geometryIdentity)
        if (!bypassCache) cache.read(key) { decode(it) }?.let {
            EnhancementTrace.record("cache_hit", "key" to key.take(8), "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000)
            return it
        }

        currentCoroutineContext().ensureActive()
        val source = decode(pageBytes) ?: return null
        val raster = try {
            source.toRasterImage()
        } finally {
            source.recycle()
        }
        val pending = coordinator.request(spec, raster, priorityRegion = priorityRegion, progressive = onProgress != null)
        val outcome = coroutineScope {
            val updates = if (onProgress != null) launch {
                coordinator.progress(spec, pending)?.filterNotNull()?.collect { partial ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = partial.toBitmap()
                    onProgress(bitmap)
                }
            } else null
            try {
                pending.await()
            } catch (cancellation: CancellationException) {
                EnhancementTrace.record("wait_cancelled", "key" to key.take(8))
                throw cancellation
            } finally {
                updates?.cancel()
                coordinator.release(spec, pending)
            }
        }

        val enhanced = (outcome as? EnhancementOutcome.Ready)?.image ?: run {
            EnhancementTrace.record("failed", "key" to key.take(8), "reason" to ((outcome as? EnhancementOutcome.Failed)?.reason ?: "unknown"))
            return null
        }
        currentCoroutineContext().ensureActive()
        val bitmap = enhanced.toBitmap()

        // Cache what was actually displayed, so a later visit skips inference entirely.
        try {
            currentCoroutineContext().ensureActive()
            runCatching {
                cache.put(key) { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            bitmap.recycle()
            throw cancelled
        }
        EnhancementTrace.record("ready", "key" to key.take(8), "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000)
        return bitmap
    }

    private fun decode(input: InputStream): Bitmap? = BitmapFactory.decodeStream(input)

    private fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
