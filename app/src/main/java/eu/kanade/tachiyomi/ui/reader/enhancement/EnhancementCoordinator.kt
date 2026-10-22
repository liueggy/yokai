package eu.kanade.tachiyomi.ui.reader.enhancement

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * - no more than [maxInflight] current or nearby pages can occupy the worker;
 * - [invalidate] bumps the generation and cancels everything pending after a chapter or settings change.
 *
 * A cancelled native call may still run to completion, but its result is discarded by the cancelled job,
 * and callers additionally compare [currentGeneration] before publishing anything on screen.
 */
class EnhancementCoordinator(
    private val engine: EnhancementEngine,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val maxInflight: Int = 3,
) {
    init {
        require(maxInflight > 0) { "At least one enhancement task must be allowed" }
    }

    private data class InflightRequest(
        val deferred: Deferred<EnhancementOutcome>,
        val progress: MutableStateFlow<RasterImage?>?,
        var users: Int,
    )

    private val lock = Any()
    private val inflight = LinkedHashMap<String, InflightRequest>()
    private var generation = 0L
    private var disposed = false

    /** Monotonic token callers capture before a request and re-check before touching a view. */
    fun currentGeneration(): Long = synchronized(lock) { generation }

    /**
     * Queues [input] for enhancement, or returns the job already running for the same [spec].
     *
     * Every call must be paired with [release] in a finally block. The returned job can also be
     * cancelled by [invalidate] or queue eviction, so callers treat cancellation as normal and
     * keep showing the original page.
     */
    fun request(
        spec: EnhancementSpec,
        input: RasterImage,
        priorityRegion: EnhancementRegion? = null,
        progressive: Boolean = false,
    ): Deferred<EnhancementOutcome> {
        var evicted: InflightRequest? = null
        val deferred = synchronized(lock) {
            if (disposed) throw CancellationException("Enhancement engine was replaced")
            val key = spec.cacheKey()
            inflight[key]?.let { request ->
                request.users++
                return request.deferred
            }

            if (inflight.size >= maxInflight) {
                val oldest = inflight.entries.first()
                inflight.remove(oldest.key)
                evicted = oldest.value
                EnhancementTrace.record("queue_evicted", "key" to oldest.key.take(8))
            }

            val issued = generation
            val queuedAt = System.nanoTime()
            val progress = if (progressive) MutableStateFlow<RasterImage?>(null) else null
            EnhancementTrace.record("queued", "key" to key.take(8), "pending" to inflight.size + 1)
            val created = scope.async(worker) {
                EnhancementTrace.record("started", "key" to key.take(8), "queueMs" to (System.nanoTime() - queuedAt) / 1_000_000)
                val startedAt = System.nanoTime()
                try {
                    compute(spec, issued, input, priorityRegion, progress)
                } finally {
                    progress?.value = null
                    EnhancementTrace.record("compute_end", "key" to key.take(8), "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000)
                }
            }
            val request = InflightRequest(created, progress, users = 1)
            inflight[key] = request
            created.invokeOnCompletion {
                request.progress?.value = null
                synchronized(lock) {
                    if (inflight[key] === request) inflight.remove(key)
                }
            }
            created
        }

        // Cancellation is cooperative at engine tile boundaries. Do it outside the lock so a
        // completion callback can remove itself without extending the critical section.
        evicted?.progress?.value = null
        evicted?.deferred?.cancel(CancellationException("Superseded by a newer enhancement request"))
        return deferred
    }

    /** Returns progressive snapshots only while [deferred] still owns [spec]'s in-flight slot. */
    fun progress(
        spec: EnhancementSpec,
        deferred: Deferred<EnhancementOutcome>,
    ): StateFlow<RasterImage?>? = synchronized(lock) {
        inflight[spec.cacheKey()]
            ?.takeIf { it.deferred === deferred }
            ?.progress
            ?.asStateFlow()
    }

    /**
     * Releases one caller's interest in [spec]. The shared task remains alive while another holder
     * is still awaiting it; once nobody needs it, queued or running work is cancelled immediately.
     */
    fun release(spec: EnhancementSpec, deferred: Deferred<EnhancementOutcome>) {
        val key = spec.cacheKey()
        val abandoned = synchronized(lock) {
            val request = inflight[key] ?: return
            // An evicted request may finish after the same page was queued again. Its finally block
            // must not release the replacement request that now owns this cache key.
            if (request.deferred !== deferred) return
            check(request.users > 0) { "Enhancement request released too many times" }
            request.users--
            if (request.users == 0) {
                inflight.remove(key)
                request.progress?.value = null
                request.deferred.takeUnless { it.isCompleted }
            } else {
                null
            }
        }
        abandoned?.cancel(CancellationException("No reader is waiting for this enhancement"))
    }

    /** Drops every pending result. Call on chapter or enhancement settings changes. */
    fun invalidate() {
        val cancelled = synchronized(lock) {
            generation++
            val running = inflight.values.map {
                it.progress?.value = null
                it.deferred
            }
            inflight.clear()
            running
        }
        cancelled.forEach { it.cancel() }
    }

    /** Retired engines must reject requests captured by a holder before a model switch. */
    fun dispose() {
        synchronized(lock) { disposed = true }
        invalidate()
    }

    private suspend fun compute(
        spec: EnhancementSpec,
        issued: Long,
        input: RasterImage,
        priorityRegion: EnhancementRegion?,
        progress: MutableStateFlow<RasterImage?>?,
    ): EnhancementOutcome {
        // A request queued before an invalidate must not start expensive work at all.
        if (!isCurrent(issued)) throw CancellationException("Enhancement request was invalidated")

        val context = currentCoroutineContext()
        val options = EnhancementOptions(
            denoise = spec.denoise, quality = spec.quality,
            priorityRegion = priorityRegion,
            onProgress = progress?.let { state ->
                { image -> state.value = image }
            },
            checkCancellation = { context.ensureActive() },
        )
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
