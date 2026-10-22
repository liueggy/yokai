package eu.kanade.tachiyomi.ui.reader.enhancement

/**
 * Averages the native output down to the requested scale and copies only the non-halo centre.
 *
 * Averaging rather than dropping pixels matters: a 4x model fed to a 2x reader would otherwise
 * waste three quarters of its work and still alias when the extra detail is discarded.
 */
internal fun copyEnhancedTile(
    nativeScale: Int,
    targetScale: Int,
    planes: Array<Array<FloatArray>>,
    output: IntArray,
    outWidth: Int,
    originX: Int,
    originY: Int,
    tileWidth: Int,
    tileHeight: Int,
    haloLeft: Int,
    haloTop: Int,
) {
    val block = nativeScale / targetScale
    val samples = (block * block).toFloat()
    val tileOutHeight = tileHeight * targetScale
    val tileOutWidth = tileWidth * targetScale
    for (row in 0 until tileOutHeight) {
        val blockRow = haloTop * nativeScale + row * block
        val outRowOffset = (originY * targetScale + row) * outWidth
        for (column in 0 until tileOutWidth) {
            val blockColumn = haloLeft * nativeScale + column * block
            var red = 0f
            var green = 0f
            var blue = 0f
            for (offsetY in 0 until block) {
                val redPlane = planes[0][blockRow + offsetY]
                val greenPlane = planes[1][blockRow + offsetY]
                val bluePlane = planes[2][blockRow + offsetY]
                for (offsetX in 0 until block) {
                    red += redPlane[blockColumn + offsetX]
                    green += greenPlane[blockColumn + offsetX]
                    blue += bluePlane[blockColumn + offsetX]
                }
            }
            val r = (red / samples).coerceIn(0f, 1f).let { (it * 255f).toInt() }
            val g = (green / samples).coerceIn(0f, 1f).let { (it * 255f).toInt() }
            val b = (blue / samples).coerceIn(0f, 1f).let { (it * 255f).toInt() }
            output[outRowOffset + originX * targetScale + column] =
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

