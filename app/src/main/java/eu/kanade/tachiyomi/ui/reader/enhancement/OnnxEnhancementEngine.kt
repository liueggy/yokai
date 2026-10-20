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
    threads: Int = 4,
    /** Native output multiplier of the model, e.g. 4 for Real-ESRGAN animevideov3. */
    private val nativeScale: Int = 4,
    /** Multiplier the reader actually wants; the native output is averaged down to this. */
    private val targetScale: Int = 2,
) : EnhancementEngine, Closeable {

    override val id: String = "onnx-2x"

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession by lazy {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        environment.createSession(modelPath, options)
    }

    override fun enhance(input: RasterImage, options: EnhancementOptions): RasterImage {
        val width = input.width
        val height = input.height
        val outWidth = Math.multiplyExact(width, targetScale)
        val outHeight = Math.multiplyExact(height, targetScale)
        val output = IntArray(Math.multiplyExact(outWidth, outHeight))

        var y = 0
        while (y < height) {
            val tileHeight = minOf(tileSize, height - y)
            var x = 0
            while (x < width) {
                val tileWidth = minOf(tileSize, width - x)
                runTile(input, output, outWidth, x, y, tileWidth, tileHeight)
                x += tileWidth
            }
            y += tileHeight
        }

        return RasterImage(outWidth, outHeight, output)
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
        val channels = arrayOf(
            FloatArray(inputWidth * inputHeight),
            FloatArray(inputWidth * inputHeight),
            FloatArray(inputWidth * inputHeight),
        )

        for (row in 0 until inputHeight) {
            val sourceRow = (y0 + row) * source.width
            val targetRow = row * inputWidth
            for (column in 0 until inputWidth) {
                val pixel = source.pixels[sourceRow + x0 + column]
                channels[0][targetRow + column] = ((pixel shr 16) and 0xFF) / 255f
                channels[1][targetRow + column] = ((pixel shr 8) and 0xFF) / 255f
                channels[2][targetRow + column] = (pixel and 0xFF) / 255f
            }
        }

        // NCHW in one buffer: the tensor API wants a flat FloatBuffer with an explicit shape.
        val planar = FloatArray(3 * inputWidth * inputHeight)
        channels[0].copyInto(planar, 0)
        channels[1].copyInto(planar, inputWidth * inputHeight)
        channels[2].copyInto(planar, 2 * inputWidth * inputHeight)

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(planar),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong()),
        ).use { tensor ->
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val planes = result[0].value as Array<Array<Array<FloatArray>>>
                copyTile(
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

    /**
     * Averages the native output down to the requested scale and copies only the non-halo centre.
     *
     * Averaging rather than dropping pixels matters: a 4x model fed to a 2x reader would otherwise
     * waste three quarters of its work and still alias when the extra detail is discarded.
     */
    private fun copyTile(
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
            val blockRow = (haloTop + row / targetScale) * nativeScale
            val outRowOffset = (originY * targetScale + row) * outWidth
            for (column in 0 until tileOutWidth) {
                val blockColumn = (haloLeft + column / targetScale) * nativeScale
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

    override fun close() {
        runCatching { session.close() }
    }
}
