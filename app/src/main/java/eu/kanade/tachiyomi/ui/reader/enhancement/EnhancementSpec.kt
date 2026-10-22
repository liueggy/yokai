package eu.kanade.tachiyomi.ui.reader.enhancement

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Immutable pixel-processing identity; display-only preferences do not belong here. */
data class EnhancementSpec(
    val sourceSha256: String,
    val weightsSha256: String,
    val engineVersion: String,
    val geometryIdentity: String,
    val denoise: Int = 0,
    val quality: String = "balanced",
    val scale: Int = 2,
    val pipelineVersion: Int = 2,
) {
    init {
        require(sourceSha256.matches(SHA256))
        require(weightsSha256.matches(SHA256))
        require(engineVersion.isNotBlank())
        require(geometryIdentity.isNotBlank())
        require(quality.isNotBlank())
        require(scale == 2) { "Only native 2x is supported" }
        require(pipelineVersion > 0)
    }

    /** Length-prefixed fields prevent delimiter collisions and ambiguous cache keys. */
    fun cacheKey(): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            listOf(
                "yokai-local-sr", pipelineVersion.toString(), sourceSha256.lowercase(),
                weightsSha256.lowercase(), engineVersion, geometryIdentity,
                denoise.toString(), quality, scale.toString(),
            ).forEach { field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

/** Conservative image-buffer admission only; native working memory needs a separate budget. */
class EnhancementBudget(
    private val maxInputPixels: Long = 4_000_000,
    private val maxOutputPixels: Long = 16_000_000,
    private val maxImageBufferBytes: Long = 160L * 1024 * 1024,
) {
    init {
        require(maxInputPixels > 0 && maxOutputPixels > 0 && maxImageBufferBytes > 0)
    }

    fun accepts(width: Int, height: Int, animated: Boolean = false): Boolean {
        if (animated || width <= 0 || height <= 0) return false
        // Division checks precede multiplication, including for malicious image dimensions.
        val input = width.toLong() * height.toLong()
        if (input > maxInputPixels || input > maxOutputPixels / 4) return false
        if (width > Int.MAX_VALUE / 2 || height > Int.MAX_VALUE / 2) return false
        // Source RGBA + output RGBA + one output-sized replacement/copy buffer.
        return input <= maxImageBufferBytes / 36
    }
}
