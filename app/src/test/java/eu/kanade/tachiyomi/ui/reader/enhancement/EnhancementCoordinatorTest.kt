package eu.kanade.tachiyomi.ui.reader.enhancement

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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

    private fun doubled(input: RasterImage) = RasterImage(
        width = input.width * 2,
        height = input.height * 2,
        pixels = IntArray(input.pixels.size * 4),
    )

    private fun spec(): EnhancementSpec = EnhancementSpec(
        sourceSha256 = "a".repeat(64),
        weightsSha256 = "b".repeat(64),
        engineVersion = "test-1",
        geometryIdentity = "raw",
    )
}
