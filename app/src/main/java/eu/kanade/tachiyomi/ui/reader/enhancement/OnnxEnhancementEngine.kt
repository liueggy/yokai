package eu.kanade.tachiyomi.ui.reader.enhancement

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * 2x upscaler backed by an ONNX super-resolution model.
 *
 * The model contract is the common one used by Real-ESRGAN and waifu2x exports: a single float32
 * NCHW input in 0..1 RGB, and a single float32 output of twice the size.
 *
 * Inference is tiled. A webtoon strip can be several thousand pixels tall, and running it whole
 * would allocate an intermediate tensor several times the size of the image; tiles keep the peak
 * bounded regardless of page length. [halo] pixels of context are fed to the model on each side and
 * then discarded, because a tile edge without context produces a visible seam.
 */
class OnnxEnhancementEngine(
    private val modelPath: String,
    private val tileSize: Int = 256,
    private val halo: Int = 8,
    private val threads: Int = 4,
    /** Native output multiplier of the model, e.g. 4 for Real-ESRGAN animevideov3. */
    private val nativeScale: Int = 4,
    /** Multiplier the reader actually wants; the native output is averaged down to this. */
    private val targetScale: Int = 2,
) : EnhancementEngine, Closeable {

    init {
        require(tileSize > 0 && halo >= 0)
        require(targetScale > 0 && nativeScale >= targetScale && nativeScale % targetScale == 0)
    }

    override val id: String = "onnx-2x"

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val sessionDelegate = lazy {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelPath, options).also {
                EnhancementTrace.record("backend", "requested" to "CPU")
            }
        }
    }
    private val session: OrtSession by sessionDelegate

    override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
        options.checkCancellation()
        val width = input.width
        val height = input.height
        val outWidth = Math.multiplyExact(width, targetScale)
        val outHeight = Math.multiplyExact(height, targetScale)
        val output = IntArray(Math.multiplyExact(outWidth, outHeight))
        val onProgress = options.onProgress
        if (onProgress != null) {
            fillNearestNeighbor(input, output, outWidth)
        }

        val tiles = orderedEnhancementTiles(width, height, tileSize, options.priorityRegion)
        val priorityCount = tiles.count { it.isPriority }
        var lastProgressAt = 0L
        tiles.forEachIndexed { index, tile ->
            options.checkCancellation()
            runTile(input, output, outWidth, tile.x, tile.y, tile.width, tile.height)

            if (onProgress != null) {
                val completed = index + 1
                val priorityCompleted = priorityCount > 0 && completed == priorityCount
                val isFirstTile = completed == 1
                val isBatchBoundary = completed % PROGRESS_TILE_BATCH == 0
                val hasMoreTiles = completed < tiles.size
                if (hasMoreTiles && (isFirstTile || priorityCompleted || isBatchBoundary)) {
                    val now = System.nanoTime()
                    if (isFirstTile || now - lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
                        onProgress(RasterImage(outWidth, outHeight, output.copyOf()))
                        lastProgressAt = now
                    }
                }
            }
        }

        return RasterImage(outWidth, outHeight, output)
    }

    private fun fillNearestNeighbor(source: RasterImage, output: IntArray, outWidth: Int) {
        for (sourceY in 0 until source.height) {
            for (scaleY in 0 until targetScale) {
                val outputRow = (sourceY * targetScale + scaleY) * outWidth
                val sourceRow = sourceY * source.width
                for (sourceX in 0 until source.width) {
                    val pixel = source.pixels[sourceRow + sourceX]
                    val outputOffset = outputRow + sourceX * targetScale
                    for (scaleX in 0 until targetScale) {
                        output[outputOffset + scaleX] = pixel
                    }
                }
            }
        }
    }

    private fun runTile(
        source: RasterImage,
        output: IntArray,
        outWidth: Int,
        originX: Int,
        originY: Int,
        tileWidth: Int,
        tileHeight: Int,
    ) {
        // Expand by the halo, clamped to the image, and remember how much context was actually added.
        val x0 = (originX - halo).coerceAtLeast(0)
        val y0 = (originY - halo).coerceAtLeast(0)
        val x1 = (originX + tileWidth + halo).coerceAtMost(source.width)
        val y1 = (originY + tileHeight + halo).coerceAtMost(source.height)

        val inputWidth = x1 - x0
        val inputHeight = y1 - y0
        val planeSize = inputWidth * inputHeight
        val planar = FloatArray(3 * planeSize)
        for (row in 0 until inputHeight) {
            val sourceRow = (y0 + row) * source.width
            val targetRow = row * inputWidth
            for (column in 0 until inputWidth) {
                val pixel = source.pixels[sourceRow + x0 + column]
                val offset = targetRow + column
                planar[offset] = ((pixel shr 16) and 0xFF) / 255f
                planar[planeSize + offset] = ((pixel shr 8) and 0xFF) / 255f
                planar[2 * planeSize + offset] = (pixel and 0xFF) / 255f
            }
        }

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(planar),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
        ).use { tensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val planes = result[0].value as Array<Array<Array<FloatArray>>>
                copyEnhancedTile(
                    nativeScale = nativeScale,
                    targetScale = targetScale,
                    planes = planes[0],
                    output = output,
                    outWidth = outWidth,
                    originX = originX,
                    originY = originY,
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                    haloLeft = originX - x0,
                    haloTop = originY - y0,
                )
            }
        }
    }

    override fun close() {
        if (sessionDelegate.isInitialized()) session.close()
    }

    private companion object {
        const val PROGRESS_TILE_BATCH = 8
        const val PROGRESS_INTERVAL_NANOS = 1_000_000_000L
    }
}
