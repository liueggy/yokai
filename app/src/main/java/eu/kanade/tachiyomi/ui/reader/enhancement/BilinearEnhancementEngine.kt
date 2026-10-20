package eu.kanade.tachiyomi.ui.reader.enhancement

import kotlin.math.roundToInt

/**
 * Plain bilinear 2x upscaler.
 *
 * Not an inference engine: it exists so the queue, cache, cancellation and reader wiring can be
 * built and tested before any native code exists, and it stays as the fallback when a model is
 * missing or a device cannot run one.
 */
class BilinearEnhancementEngine : EnhancementEngine {
    override val id: String = "bilinear-2x"

    override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
        val sourceWidth = input.width
        val sourceHeight = input.height
        val outWidth = Math.multiplyExact(sourceWidth, 2)
        val outHeight = Math.multiplyExact(sourceHeight, 2)
        val source = input.pixels
        val output = IntArray(Math.multiplyExact(outWidth, outHeight))

        val maxX = (sourceWidth - 1).toFloat()
        val maxY = (sourceHeight - 1).toFloat()

        for (y in 0 until outHeight) {
            // Map the centre of the output pixel back into source space.
            val sourceY = ((y + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxY)
            val y0 = sourceY.toInt()
            val y1 = minOf(y0 + 1, sourceHeight - 1)
            val fy = sourceY - y0
            val row0 = y0 * sourceWidth
            val row1 = y1 * sourceWidth
            val outRow = y * outWidth

            for (x in 0 until outWidth) {
                val sourceX = ((x + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxX)
                val x0 = sourceX.toInt()
                val x1 = minOf(x0 + 1, sourceWidth - 1)
                val fx = sourceX - x0

                output[outRow + x] = blend(
                    topLeft = source[row0 + x0],
                    topRight = source[row0 + x1],
                    bottomLeft = source[row1 + x0],
                    bottomRight = source[row1 + x1],
                    fx = fx,
                    fy = fy,
                )
            }
        }

        return RasterImage(outWidth, outHeight, output)
    }

    private fun blend(topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int, fx: Float, fy: Float): Int {
        var result = 0
        for (shift in CHANNEL_SHIFTS) {
            val top = lerp((topLeft ushr shift) and 0xFF, (topRight ushr shift) and 0xFF, fx)
            val bottom = lerp((bottomLeft ushr shift) and 0xFF, (bottomRight ushr shift) and 0xFF, fx)
            val channel = lerp(top, bottom, fy).roundToInt().coerceIn(0, 255)
            result = result or (channel shl shift)
        }
        return result
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Float = start + (end - start) * fraction

    /** Second pass interpolates two already blended channels, so both ends are fractional. */
    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

    private companion object {
        val CHANNEL_SHIFTS = intArrayOf(0, 8, 16, 24)
    }
}
