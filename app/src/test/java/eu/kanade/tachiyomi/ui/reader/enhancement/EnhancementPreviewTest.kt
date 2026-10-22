package eu.kanade.tachiyomi.ui.reader.enhancement

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnhancementPreviewTest {
    @Test
    fun `detail crop stays bounded in portrait landscape spread and long strip pages`() {
        listOf(800 to 1200, 1600 to 1000, 2400 to 1600, 800 to 20000).forEach { (width, height) ->
            val region = PreviewRegion.centre(width, height)
            assertEquals(256, region.width)
            assertEquals(256, region.height)
            assertEquals((width - region.width) / 2, region.left)
            assertEquals((height - region.height) / 2, region.top)
        }
    }

    @Test
    fun `small sources use their real bounds without upscaling before model input`() {
        assertEquals(PreviewRegion(0, 0, 1, 1), PreviewRegion.centre(1, 1))
        assertEquals(PreviewRegion(0, 72, 100, 256), PreviewRegion.centre(100, 400))
        assertThrows(IllegalArgumentException::class.java) { PreviewRegion.centre(0, 400) }
    }

    @Test
    fun `preview keeps transformed source bytes and geometry identity`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val preview = EnhancementPreviewSource.read(Buffer().write(bytes), "page:split-right")!!
        assertArrayEquals(bytes, preview.bytes)
        assertEquals("page:split-right", preview.geometryIdentity)
    }

    @Test
    fun `oversized encoded source is rejected before another byte array is copied`() {
        val oversized = Buffer().write(ByteArray(32 * 1024 * 1024 + 1))
        assertNull(EnhancementPreviewSource.read(oversized, "webtoon"))
        assertEquals(32L * 1024 * 1024 + 1, oversized.size)
    }
}
