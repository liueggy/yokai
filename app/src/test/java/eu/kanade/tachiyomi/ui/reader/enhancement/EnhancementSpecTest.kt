package eu.kanade.tachiyomi.ui.reader.enhancement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnhancementSpecTest {
    private val spec = EnhancementSpec("a".repeat(64), "b".repeat(64), "anime-v1", "original")

    @Test
    fun `cache identity is deterministic and digest case independent`() {
        assertEquals(spec.cacheKey(), spec.copy().cacheKey())
        assertEquals(spec.cacheKey(), spec.copy(sourceSha256 = "A".repeat(64)).cacheKey())
        assertTrue(spec.cacheKey().matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `all processing changes invalidate cache`() {
        listOf(
            spec.copy(sourceSha256 = "c".repeat(64)),
            spec.copy(weightsSha256 = "c".repeat(64)),
            spec.copy(engineVersion = "anime-v2"),
            spec.copy(geometryIdentity = "split-left"),
            spec.copy(denoise = 1),
            spec.copy(quality = "best"),
            spec.copy(pipelineVersion = spec.pipelineVersion + 1),
        ).forEach { assertNotEquals(spec.cacheKey(), it.cacheKey()) }
        assertNotEquals(
            spec.copy(engineVersion = "a|b", geometryIdentity = "c").cacheKey(),
            spec.copy(engineVersion = "a", geometryIdentity = "b|c").cacheKey(),
        )
    }

    @Test
    fun `unsupported scale and malformed digests fail early`() {
        assertThrows(IllegalArgumentException::class.java) { spec.copy(scale = 4) }
        assertThrows(IllegalArgumentException::class.java) { spec.copy(weightsSha256 = "bad") }
    }

    @Test
    fun `budget rejects animations giant dimensions and invalid images`() {
        val budget = EnhancementBudget()
        assertTrue(budget.accepts(800, 1200))
        assertTrue(budget.accepts(2000, 2000))
        assertFalse(budget.accepts(2000, 2001))
        assertFalse(budget.accepts(800, 1200, animated = true))
        assertFalse(budget.accepts(0, 1200))
        assertFalse(budget.accepts(-1, 1200))
        assertFalse(budget.accepts(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(EnhancementBudget(maxImageBufferBytes = 35).accepts(1, 1))
        assertTrue(EnhancementBudget(maxImageBufferBytes = 36).accepts(1, 1))
    }
}
