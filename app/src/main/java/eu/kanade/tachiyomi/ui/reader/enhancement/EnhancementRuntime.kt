package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EnhancementBinding(val renderer: EnhancementPageRenderer, val model: EnhancementModel)

/** One resident model, selected explicitly in settings, with matching renderer/cache identity. */
object EnhancementRuntime {
    private const val CACHE_BYTES = 512L * 1024 * 1024
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "enhancement-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cachedKey: String? = null
    private var cachedBinding: EnhancementBinding? = null
    private var cachedEngine: EnhancementEngine? = null
    private var cachedCoordinator: EnhancementCoordinator? = null
    private var cache: EnhancementDiskCache? = null

    /** Resolve weights and renderer together so the displayed model and cache can never diverge. */
    fun binding(context: Context, preferredId: String): EnhancementBinding? = synchronized(this) {
        EnhancementTrace.initialize(context)
        val installed = EnhancementModelStore.selectedModel(context, preferredId) ?: return null
        EnhancementTrace.record("binding", "model" to installed.displayName, "id" to installed.id)
        val key = "${installed.file.absolutePath}:${installed.nativeScale}"
        cachedBinding?.let { if (cachedKey == key) return it }
        retireEngine()

        val engine = OnnxEnhancementEngine(installed.file.absolutePath, nativeScale = installed.nativeScale)
        val coordinator = EnhancementCoordinator(engine, scope, worker)
        val diskCache = cache ?: EnhancementDiskCache(
            File(context.applicationContext.filesDir, "enhancement-cache"), CACHE_BYTES,
        ).also { cache = it }
        val digest = installed.file.nameWithoutExtension
        val model = EnhancementModel(
            id = "onnx-2x",
            displayName = installed.displayName,
            version = digest.take(12),
            scale = 2,
            denoiseLevels = listOf(0),
            files = listOf(ModelFile(name = installed.file.name, sha256 = digest, sizeBytes = installed.file.length())),
            license = if (installed.isBundled) "BSD-3-Clause" else "user-supplied",
            baseUrls = listOf("https://local.invalid"),
            approxBytes = installed.file.length(),
        )
        EnhancementBinding(EnhancementPageRenderer(coordinator, diskCache), model).also {
            cachedEngine = engine
            cachedCoordinator = coordinator
            cachedBinding = it
            cachedKey = key
        }
    }

    /** A bounded detail sample, executed through the real selected model without a cache hit. */
    suspend fun preview(
        context: Context,
        source: EnhancementPreviewSource,
        preferredId: String,
    ): EnhancementPreviewResult? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val region = PreviewRegion.centre(bounds.outWidth, bounds.outHeight)
        val original = decodePreviewRegion(source.bytes, region, bounds.outWidth, bounds.outHeight)
            ?: return@withContext null
        val bytes = ByteArrayOutputStream().use { output ->
            check(original.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val binding = binding(context, preferredId) ?: return@withContext null
        val enhanced = binding.renderer.render(
            bytes, binding.model, binding.model.id,
            "${source.geometryIdentity}:preview:${region.left}:${region.top}",
            denoise = 0,
            bypassCache = true,
        ) ?: return@withContext null
        EnhancementPreviewResult(original, enhanced)
    }

    /** AVIF/other readable formats may lack BitmapRegionDecoder support on the device. */
    private fun decodePreviewRegion(bytes: ByteArray, region: PreviewRegion, width: Int, height: Int): Bitmap? {
        val tiled = runCatching {
            @Suppress("DEPRECATION")
            val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            try {
                decoder.decodeRegion(
                    Rect(region.left, region.top, region.left + region.width, region.top + region.height),
                    BitmapFactory.Options(),
                )
            } finally {
                decoder.recycle()
            }
        }.getOrNull()
        if (tiled != null) return tiled

        // A bounded full decode is the fallback, never an unbounded allocation for a long strip.
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > 1_000_000L) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val crop = PreviewRegion.centre(decoded.width, decoded.height)
        return try {
            Bitmap.createBitmap(decoded, crop.left, crop.top, crop.width, crop.height)
        } finally {
            if (crop.width != decoded.width || crop.height != decoded.height) decoded.recycle()
        }
    }

    fun invalidate() = synchronized(this) {
        retireEngine()
    }

    private fun retireEngine() {
        cachedCoordinator?.dispose()
        val retired = cachedEngine as? Closeable
        // A close must never race with a native inference call or block the settings UI.
        if (retired != null) scope.launch(worker) { runCatching { retired.close() } }
        cachedEngine = null
        cachedCoordinator = null
        cachedBinding = null
        cachedKey = null
    }
}
