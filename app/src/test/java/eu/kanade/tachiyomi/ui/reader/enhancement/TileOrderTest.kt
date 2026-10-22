package eu.kanade.tachiyomi.ui.reader.enhancement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileOrderTest {
    @Test
    fun `visible tiles are processed before the rest`() {
        val tiles = orderedEnhancementTiles(
            width = 512,
            height = 512,
            tileSize = 256,
            priorityRegion = EnhancementRegion(0.5f, 0.5f, 1f, 1f),
        )

        assertEquals(
            listOf(
                256 to 256,
                0 to 0,
                256 to 0,
                0 to 256,
            ),
            tiles.map { it.x to it.y },
        )
        assertTrue(tiles.first().isPriority)
        assertTrue(tiles.drop(1).none { it.isPriority })
    }

    @Test
    fun `tile order stays row major inside each group`() {
        val tiles = orderedEnhancementTiles(
            width = 700,
            height = 300,
            tileSize = 256,
            priorityRegion = EnhancementRegion(0.3f, 0f, 1f, 0.5f),
        )

        assertEquals(
            listOf(
                0 to 0,
                256 to 0,
                512 to 0,
                0 to 256,
                256 to 256,
                512 to 256,
            ),
            tiles.map { it.x to it.y },
        )
        assertEquals(3, tiles.count { it.isPriority })
    }

    @Test
    fun `missing visible region preserves normal row major order`() {
        val tiles = orderedEnhancementTiles(300, 300, 256, priorityRegion = null)

        assertEquals(
            listOf(0 to 0, 256 to 0, 0 to 256, 256 to 256),
            tiles.map { it.x to it.y },
        )
        assertTrue(tiles.none { it.isPriority })
    }

    @Test
    fun `visible region rejects non normalized coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            EnhancementRegion(-0.1f, 0f, 1f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnhancementRegion(0.5f, 0f, 0.5f, 1f)
        }
    }
}
