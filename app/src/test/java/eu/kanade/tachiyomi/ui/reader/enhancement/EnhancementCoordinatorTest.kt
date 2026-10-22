package eu.kanade.tachiyomi.ui.reader.enhancement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnhancementCoordinatorTest {
    @Test
    fun `duplicate requests share one inference`() = runTest {
        var calls = 0
        val engine = object : EnhancementEngine {
            override val id = "counting"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                calls++
                return doubled(input)
            }
        }
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))
        val image = RasterImage(2, 2, IntArray(4))

        val first = coordinator.request(spec(), image)
        val second = coordinator.request(spec(), image)

        assertSame(first, second, "A rebind must not queue the same page twice")
        advanceUntilIdle()

        assertTrue(first.await() is EnhancementOutcome.Ready)
        assertEquals(1, calls)
    }

    @Test
    fun `releasing one duplicate keeps shared inference alive`() = runTest {
        var calls = 0
        val engine = object : EnhancementEngine {
            override val id = "shared"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                calls++
                return doubled(input)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = EnhancementCoordinator(engine, this, dispatcher)
        val page = spec()
        val image = RasterImage(2, 2, IntArray(4))

        val first = coordinator.request(page, image)
        val second = coordinator.request(page, image)
        coordinator.release(page, first)
        advanceUntilIdle()

        assertTrue(first.await() is EnhancementOutcome.Ready)
        assertTrue(second.await() is EnhancementOutcome.Ready)
        assertEquals(1, calls)
    }

    @Test
    fun `releasing the last user cancels abandoned work`() = runTest {
        var calls = 0
        val engine = object : EnhancementEngine {
            override val id = "abandoned"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                calls++
                return doubled(input)
            }
        }
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))
        val page = spec()
        val pending = coordinator.request(page, RasterImage(2, 2, IntArray(4)))

        coordinator.release(page, pending)
        advanceUntilIdle()

        assertTrue(pending.isCancelled)
        assertEquals(0, calls, "An abandoned queued page must not reach the engine")
    }

    @Test
    fun `new requests evict the oldest task when queue reaches its limit`() = runTest {
        val calls = mutableListOf<String>()
        val engine = object : EnhancementEngine {
            override val id = "bounded"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                calls += input.pixels.first().toString()
                return doubled(input)
            }
        }
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler), maxInflight = 3)
        val requests = (1..4).map { index ->
            coordinator.request(spec(index), RasterImage(1, 1, intArrayOf(index)))
        }

        advanceUntilIdle()

        assertTrue(requests.first().isCancelled, "The oldest page must yield to the current reading direction")
        assertEquals(listOf("2", "3", "4"), calls)
        requests.drop(1).forEach { assertTrue(it.await() is EnhancementOutcome.Ready) }
    }

    @Test
    fun `evicting a running task is observed at its next tile boundary`() = runTest {
        lateinit var coordinator: EnhancementCoordinator
        var crossedTileBoundary = false
        val engine = object : EnhancementEngine {
            override val id = "bounded-cooperative"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                if (input.pixels.first() == 1) {
                    coordinator.request(spec(4), RasterImage(1, 1, intArrayOf(4)))
                    options.checkCancellation()
                    crossedTileBoundary = true
                }
                return doubled(input)
            }
        }
        coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler), maxInflight = 3)
        val first = coordinator.request(spec(1), RasterImage(1, 1, intArrayOf(1)))
        coordinator.request(spec(2), RasterImage(1, 1, intArrayOf(2)))
        coordinator.request(spec(3), RasterImage(1, 1, intArrayOf(3)))

        advanceUntilIdle()

        assertTrue(first.isCancelled)
        assertEquals(false, crossedTileBoundary)
    }

    @Test
    fun `an evicted task cannot release a newer request for the same page`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = EnhancementCoordinator(BilinearEnhancementEngine(), this, dispatcher, maxInflight = 1)
        val image = RasterImage(2, 2, IntArray(4))
        val page = spec(1)

        val old = coordinator.request(page, image)
        coordinator.request(spec(2), image)
        val replacement = coordinator.request(page, image)
        coordinator.release(page, old)
        advanceUntilIdle()

        assertTrue(old.isCancelled)
        assertTrue(replacement.await() is EnhancementOutcome.Ready)
    }

    @Test
    fun `invalidate cancels pending work and advances the generation`() = runTest {
        var calls = 0
        val engine = object : EnhancementEngine {
            override val id = "counting"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                calls++
                return doubled(input)
            }
        }
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))
        val before = coordinator.currentGeneration()

        val pending = coordinator.request(spec(), RasterImage(2, 2, IntArray(4)))
        coordinator.invalidate()
        advanceUntilIdle()

        assertTrue(pending.isCancelled, "A page change must drop the queued result")
        assertEquals(0, calls, "Cancelled work must not reach the engine")
        assertEquals(before + 1, coordinator.currentGeneration())
    }

    @Test
    fun `a cancelled page can be requested again afterwards`() = runTest {
        val engine = BilinearEnhancementEngine()
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))
        val image = RasterImage(2, 2, IntArray(4))

        coordinator.request(spec(), image)
        coordinator.invalidate()

        val retried = coordinator.request(spec(), image)
        advanceUntilIdle()

        val outcome = retried.await()
        assertTrue(outcome is EnhancementOutcome.Ready)
        assertEquals(4, (outcome as EnhancementOutcome.Ready).image.width)
    }

    @Test
    fun `engine failure keeps the original page instead of crashing`() = runTest {
        val engine = object : EnhancementEngine {
            override val id = "broken"

            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage =
                throw IllegalStateException("model not installed")
        }
        val coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))

        val deferred = coordinator.request(spec(), RasterImage(2, 2, IntArray(4)))
        advanceUntilIdle()

        val outcome = deferred.await()
        assertTrue(outcome is EnhancementOutcome.Failed)
        assertEquals("model not installed", (outcome as EnhancementOutcome.Failed).reason)
    }

    @Test
    fun `retired model cancels queued work and refuses late requests`() = runTest {
        val coordinator = EnhancementCoordinator(BilinearEnhancementEngine(), this, StandardTestDispatcher(testScheduler))
        val image = RasterImage(2, 2, IntArray(4))
        val pending = coordinator.request(spec(), image)
        coordinator.dispose()
        advanceUntilIdle()
        assertTrue(pending.isCancelled)
        assertThrows(CancellationException::class.java) { coordinator.request(spec(), image) }
    }

    @Test
    fun `retiring model is observed at the next engine tile boundary`() = runTest {
        lateinit var coordinator: EnhancementCoordinator
        var reachedNextTile = false
        val engine = object : EnhancementEngine {
            override val id = "cooperative"
            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                coordinator.dispose()
                options.checkCancellation()
                reachedNextTile = true
                return doubled(input)
            }
        }
        coordinator = EnhancementCoordinator(engine, this, StandardTestDispatcher(testScheduler))
        val pending = coordinator.request(spec(), RasterImage(2, 2, IntArray(4)))
        advanceUntilIdle()
        assertTrue(pending.isCancelled)
        assertEquals(false, reachedNextTile)
    }

    @Test
    fun `destroying viewer scope cancels holder awaiting process wide inference`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val engine = object : EnhancementEngine {
            override val id = "exit-during-inference"
            override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
                viewerScope.cancel()
                return doubled(input)
            }
        }
        val coordinator = EnhancementCoordinator(engine, this, dispatcher)
        val holder = viewerScope.launch {
            coordinator.request(spec(), RasterImage(2, 2, IntArray(4))).await()
        }
        advanceUntilIdle()
        assertTrue(holder.isCancelled)
        coordinator.dispose()
    }

    private fun doubled(input: RasterImage) = RasterImage(
        width = input.width * 2,
        height = input.height * 2,
        pixels = IntArray(input.pixels.size * 4),
    )

    private fun spec(index: Int = 0): EnhancementSpec = EnhancementSpec(
        sourceSha256 = index.toString(16).last().toString().repeat(64),
        weightsSha256 = "b".repeat(64),
        engineVersion = "test-1",
        geometryIdentity = "raw",
    )
}
