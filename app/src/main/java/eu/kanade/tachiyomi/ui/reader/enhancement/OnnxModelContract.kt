package eu.kanade.tachiyomi.ui.reader.enhancement

/** Supported reader contract: dynamic NCHW RGB, float32 in 0..1, native 2x or 4x. */
internal object OnnxModelContract {
    fun validateInput(shape: LongArray) {
        require(shape.size == 4 && (shape[0] == 1L || shape[0] < 0) && shape[1] == 3L)
        require(shape[2] < 0 && shape[3] < 0) { "The model must accept dynamic page tiles" }
    }

    fun outputScale(shape: LongArray, width: Int, height: Int): Int {
        require(shape.size == 4 && shape[0] == 1L && shape[1] == 3L)
        val scale = shape[3] / width
        require(scale == 2L || scale == 4L) { "Only 2x and 4x models are supported" }
        require(shape[2] == height * scale && shape[3] == width * scale)
        return scale.toInt()
    }
}
