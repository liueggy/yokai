package eu.kanade.tachiyomi.ui.reader.enhancement

/**
 * A downloaded upscaler model.
 *
 * [files] are individual weight files rather than an archive: an archive would need extraction-time
 * path validation, and a model is only ever a handful of files.
 */
data class EnhancementModel(
    val id: String,
    val displayName: String,
    val version: String,
    val scale: Int,
    val denoiseLevels: List<Int>,
    val files: List<ModelFile>,
    val license: String,
    val baseUrls: List<String>,
    val approxBytes: Long,
) {
    init {
        require(id.matches(ID)) { "Invalid model id: $id" }
        require(version.isNotBlank())
        require(scale == 2) { "Only native 2x is supported" }
        require(files.isNotEmpty())
        require(baseUrls.isNotEmpty()) { "A model needs at least one download source" }
        // A missing mirror is a broken download, not a licence problem: fail loudly at declaration time.
        require(baseUrls.all { it.startsWith("https://") }) { "Model weights must be served over HTTPS" }
        require(denoiseLevels.all { it in 0..3 })
    }

    val licenseLabel: String get() = license

    private companion object {
        val ID = Regex("[a-z0-9][a-z0-9._-]*")
    }
}

data class ModelFile(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        // The name becomes a file name under the model directory: no separators, no "..".
        require(name.matches(NAME)) { "Invalid model file name: $name" }
        require(sha256.matches(SHA256)) { "Invalid sha256 for $name" }
        require(sizeBytes > 0)
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

/**
 * Built-in model catalogue.
 *
 * Intentionally empty: every entry needs a verified SHA-256 of the actual weight files and a settled
 * redistribution licence. Inventing a digest here would produce downloads that always fail the check,
 * or worse, a check that passes for the wrong bytes. Entries are added once the audit produces them.
 */
object EnhancementModelRegistry {
    val builtin: List<EnhancementModel> = emptyList()

    fun find(id: String): EnhancementModel? = builtin.firstOrNull { it.id == id }

    fun all(): List<EnhancementModel> = builtin
}
