package eu.kanade.tachiyomi.ui.reader.enhancement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnhancedTileCopyTest {
    @Test
    fun `4x model preserves each distinct 2x output sample`() {
        val output = IntArray(4)
        val plane = Array(4) { y -> FloatArray(4) { x -> (y / 2 * 2 + x / 2) / 3f } }
        copyEnhancedTile(4, 2, arrayOf(plane, plane, plane), output, 2, 0, 0, 1, 1, 0, 0)
        assertEquals(listOf(0, 85, 170, 255), output.map { it and 0xFF })
    }

    @Test
    fun `native 2x keeps every pixel with no repeated samples`() {
        val output = IntArray(4)
        val plane = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 0f))
        copyEnhancedTile(2, 2, arrayOf(plane, plane, plane), output, 2, 0, 0, 1, 1, 0, 0)
        assertEquals(listOf(0, 255, 255, 0), output.map { it and 0xFF })
    }

    @Test
    fun `halo is cropped and partial tile is placed at its output origin`() {
        val output = IntArray(24) { 0xFF123456.toInt() }
        val plane = Array(8) { y -> FloatArray(8) { x -> if (y >= 4 && x >= 4) 1f else 0f } }
        copyEnhancedTile(4, 2, arrayOf(plane, plane, plane), output, 6, 2, 1, 1, 1, 1, 1)
        val changed = setOf(16, 17, 22, 23)
        output.forEachIndexed { index, pixel ->
            assertEquals(if (index in changed) -1 else 0xFF123456.toInt(), pixel)
        }
    }

    @Test
    fun `downsampling averages all native samples`() {
        val output = IntArray(4)
        val plane = Array(4) { FloatArray(4) { x -> if (x % 2 == 0) 0f else 1f } }
        copyEnhancedTile(4, 2, arrayOf(plane, plane, plane), output, 2, 0, 0, 1, 1, 0, 0)
        assertEquals(listOf(127, 127, 127, 127), output.map { it and 0xFF })
    }
}
