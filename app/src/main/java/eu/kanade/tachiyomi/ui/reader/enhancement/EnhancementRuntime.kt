package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Process-wide enhancement wiring: one worker thread, one cache, one coordinator.
 *
 * The engine here is the built-in bilinear upscaler, not a model. That is deliberate: it lets the
 * whole chain (preference -> queue -> cache -> viewport-preserving swap) run and be judged on a real
 * device before any native code or weight download exists. A real engine replaces `engine` and the
 * placeholder model below without touching the call site.
 */
object EnhancementRuntime {
    /** Cache budget for enhanced pages; the source images stay untouched. */
    private const val CACHE_BYTES = 256L * 1024 * 1024

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

    @Volatile
    private var renderer: EnhancementPageRenderer? = null

    fun renderer(context: Context): EnhancementPageRenderer {
        renderer?.let { return it }
        return synchronized(this) {
            renderer ?: EnhancementPageRenderer(
                coordinator = EnhancementCoordinator(BilinearEnhancementEngine(), scope, worker),
                cache = EnhancementDiskCache(File(context.applicationContext.filesDir, "enhancement-cache"), CACHE_BYTES),
            ).also { renderer = it }
        }
    }

    /** Model to use for the current preference, falling back to the built-in engine. */
    fun modelFor(preferredId: String): EnhancementModel =
        EnhancementModelRegistry.find(preferredId) ?: builtinModel
}
