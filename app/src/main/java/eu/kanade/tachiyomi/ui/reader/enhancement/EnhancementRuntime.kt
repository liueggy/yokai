package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Process-wide enhancement wiring: one worker thread, one cache, one coordinator.
 *
 * The engine is chosen from what is installed: a user-supplied ONNX model when present, otherwise
 * the built-in bilinear upscaler. Swapping engines rebuilds the renderer, because a resident model
 * must be released before another one is loaded.
 */
object EnhancementRuntime {
    /** Cache budget for enhanced pages; the source images stay untouched. */
    private const val CACHE_BYTES = 512L * 1024 * 1024

    /**
     * Stand-in descriptor for the built-in engine.
     *
     * It has no weights, so the digest is a constant that only participates in the cache key. It is
     * never downloaded: [baseUrls] exists solely to satisfy the model contract.
     */
    val builtinModel = EnhancementModel(
        id = "bilinear",
        displayName = "Bilinear 2x",
        version = "1",
        scale = 2,
        denoiseLevels = listOf(0),
        files = listOf(ModelFile(name = "builtin", sha256 = "0".repeat(64), sizeBytes = 1)),
        license = "Apache-2.0",
        baseUrls = listOf("https://builtin.invalid"),
        approxBytes = 0,
    )

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "enhancement-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cachedKey: String? = null
    private var cachedRenderer: EnhancementPageRenderer? = null
    private var cachedEngine: EnhancementEngine? = null
    private var cachedModel: EnhancementModel? = null

    /** Model to identify results with: the installed ONNX model, or the built-in upscaler. */
    fun modelFor(context: Context, preferredId: String): EnhancementModel = synchronized(this) {
        EnhancementModelRegistry.find(preferredId)?.let { return it }
        val file = EnhancementModelStore.modelFile(context)
        (if (file == null) builtinModel else onnxModel(file)).also { cachedModel = it }
    }

    fun renderer(context: Context): EnhancementPageRenderer = synchronized(this) {
        val file = EnhancementModelStore.modelFile(context)
        val key = file?.absolutePath ?: "builtin"
        cachedRenderer?.let { if (cachedKey == key) return it }

        // A resident model must be released before another engine is created.
        (cachedEngine as? Closeable)?.let { runCatching { it.close() } }

        val engine: EnhancementEngine = if (file != null) {
            OnnxEnhancementEngine(file.absolutePath)
        } else {
            BilinearEnhancementEngine()
        }

        EnhancementPageRenderer(
            coordinator = EnhancementCoordinator(engine, scope, worker),
            cache = EnhancementDiskCache(
                File(context.applicationContext.filesDir, "enhancement-cache"),
                CACHE_BYTES,
            ),
        ).also {
            cachedEngine = engine
            cachedRenderer = it
            cachedKey = key
        }
    }

    /** Called after importing or deleting a model so the next page uses the new one. */
    fun invalidate() = synchronized(this) {
        cachedRenderer = null
        cachedKey = null
        (cachedEngine as? Closeable)?.let { runCatching { it.close() } }
        cachedEngine = null
    }

    /**
     * Descriptor for a user-supplied model. The file name is its SHA-256, so it doubles as the weight
     * digest that keeps cached results from one model apart from another.
     */
    private fun onnxModel(file: File): EnhancementModel {
        val digest = file.nameWithoutExtension
        return EnhancementModel(
            id = "onnx-2x",
            displayName = "ONNX model",
            version = digest.take(12),
            scale = 2,
            denoiseLevels = listOf(0),
            files = listOf(ModelFile(name = "$digest.onnx", sha256 = digest, sizeBytes = file.length())),
            license = "user-supplied",
            baseUrls = listOf("https://local.invalid"),
            approxBytes = file.length(),
        )
    }
}
