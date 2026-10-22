package eu.kanade.tachiyomi.ui.reader.enhancement

/**
 * Visible source-image area, expressed as fractions of the complete source image.
 *
 * Normalized coordinates keep the viewport stable when the displayed bitmap and the raster passed
 * to the enhancement engine were decoded at different sizes.
 */
data class EnhancementRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Enhancement region coordinates must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "Enhancement region coordinates must be normalized to 0..1"
        }
        require(left < right && top < bottom) { "Enhancement region must not be empty" }
    }
}

internal data class EnhancementTile(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isPriority: Boolean,
)

/** Stable row-major order within the priority and non-priority groups. */
internal fun orderedEnhancementTiles(
    width: Int,
    height: Int,
    tileSize: Int,
    priorityRegion: EnhancementRegion?,
): List<EnhancementTile> {
    require(width > 0 && height > 0 && tileSize > 0)

    val priority = ArrayList<EnhancementTile>()
    val remaining = ArrayList<EnhancementTile>()
    var y = 0
    while (y < height) {
        val tileHeight = minOf(tileSize, height - y)
        var x = 0
        while (x < width) {
            val tileWidth = minOf(tileSize, width - x)
            val isPriority = priorityRegion?.intersects(x, y, tileWidth, tileHeight, width, height) == true
            val tile = EnhancementTile(x, y, tileWidth, tileHeight, isPriority)
            if (isPriority) priority += tile else remaining += tile
            x += tileWidth
        }
        y += tileHeight
    }
    return priority + remaining
}

private fun EnhancementRegion.intersects(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    imageWidth: Int,
    imageHeight: Int,
): Boolean {
    val normalizedLeft = x.toFloat() / imageWidth
    val normalizedTop = y.toFloat() / imageHeight
    val normalizedRight = (x + width).toFloat() / imageWidth
    val normalizedBottom = (y + height).toFloat() / imageHeight
    return normalizedLeft < right && normalizedRight > left &&
        normalizedTop < bottom && normalizedBottom > top
}
