package eu.kanade.tachiyomi.ui.reader.enhancement

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

sealed interface EnhancementOutcome {
    data class Ready(val spec: EnhancementSpec, val image: RasterImage) : EnhancementOutcome

    data class Failed(val spec: EnhancementSpec, val reason: String) : EnhancementOutcome
}

/**
 * Serialises enhancement work and keeps stale results away from the reader.
 *
 * Rules this class enforces:
 * - one inference at a time, because a resident model is not re-entrant and two running models do not fit in memory;
 * - identical requests share one job, so a rebind or a duplicate prefetch cannot double the work;
 * - [invalidate] bumps the generation and cancels everything pending, which is what a page change,
 *   a settings change or a recycled view must call.
 *
 * A cancelled native call may still run to completion, but its result is discarded by the cancelled job,
 * and callers additionally compare [currentGeneration] before publishing anything on screen.
 */
class EnhancementCoordinator(
    private val engine: EnhancementEngine,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
) {
    private val lock = Any()
    private val inflight = LinkedHashMap<String, Deferred<EnhancementOutcome>>()
    private var generation = 0L

    /** Monotonic token callers capture before a request and re-check before touching a view. */
    fun currentGeneration(): Long = synchronized(lock) { generation }

    /**
     * Queues [input] for enhancement, or returns the job already running for the same [spec].
     *
     * The returned job is cancelled by [invalidate], so a caller must treat cancellation as normal
     * and fall back to the original page.
     */
    fun request(spec: EnhancementSpec, input: RasterImage): Deferred<EnhancementOutcome> = synchronized(lock) {
        val key = spec.cacheKey()
        inflight[key]?.let { return it }

        val issued = generation
        val deferred = scope.async(worker) { compute(spec, issued, input) }
        inflight[key] = deferred
        deferred.invokeOnCompletion {
            synchronized(lock) {
                if (inflight[key] === deferred) inflight.remove(key)
            }
        }
        deferred
    }

    /** Drops every pending result. Call on chapter change, page rebind, view recycling or settings change. */
    fun invalidate() {
        val cancelled = synchronized(lock) {
            generation++
            val running = inflight.values.toList()
            inflight.clear()
            running
        }
        cancelled.forEach { it.cancel() }
    }

    private fun compute(spec: EnhancementSpec, issued: Long, input: RasterImage): EnhancementOutcome {
        // A request queued before an invalidate must not start expensive work at all.
        if (!isCurrent(issued)) throw CancellationException("Enhancement request was invalidated")

        val options = EnhancementOptions(denoise = spec.denoise, quality = spec.quality)
        val image = try {
            engine.enhance(input, options)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // A missing model, an unsupported backend or a native allocation failure all land here.
            // Keep the original page and surface the reason instead of crashing the reader.
            return EnhancementOutcome.Failed(spec, error.message ?: error.javaClass.simpleName)
        }

        if (!isCurrent(issued)) throw CancellationException("Enhancement finished after invalidation")
        return EnhancementOutcome.Ready(spec, image)
    }

    private fun isCurrent(issued: Long): Boolean = synchronized(lock) { issued == generation }
}
