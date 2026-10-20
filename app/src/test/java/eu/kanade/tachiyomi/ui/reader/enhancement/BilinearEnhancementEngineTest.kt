package eu.kanade.tachiyomi.ui.reader.enhancement

import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BilinearEnhancementEngineTest {
    private val engine = BilinearEnhancementEngine()

    @Test
    fun `2x doubles both dimensions and keeps a solid colour exact`() {
        val colour = 0xFF3366CC.toInt()
        val output = engine.enhance(
            RasterImage(width = 3, height = 5, pixels = IntArray(15) { colour }),
            EnhancementOptions(),
        )

        assertEquals(6, output.width)
        assertEquals(10, output.height)
        assertTrue(output.pixels.all { it == colour }, "Interpolating equal neighbours must not shift the colour")
    }

    @Test
    fun `single pixel source still produces a valid 2x image`() {
        val output = engine.enhance(
            RasterImage(width = 1, height = 1, pixels = intArrayOf(0xFF102030.toInt())),
            EnhancementOptions(),
        )

        assertEquals(2, output.width)
        assertEquals(2, output.height)
        assertTrue(output.pixels.all { it == 0xFF102030.toInt() })
    }

    @Test
    fun `horizontal ramp interpolates half steps and clamps at the edges`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val output = engine.enhance(
            RasterImage(width = 2, height = 1, pixels = intArrayOf(black, white)),
            EnhancementOptions(),
        )

        // Output x maps back to source -0.25, 0.25, 0.75, 1.25 and clamps outside the image.
        // A single source row becomes two identical output rows, so only the first is inspected.
        val red = output.pixels.take(output.width).map { (it shr 16) and 0xFF }
        assertEquals(listOf(0, (255 * 0.25f).roundToInt(), (255 * 0.75f).roundToInt(), 255), red)
        assertTrue(output.pixels.all { (it ushr 24) == 0xFF }, "Alpha must stay opaque")
    }
}
