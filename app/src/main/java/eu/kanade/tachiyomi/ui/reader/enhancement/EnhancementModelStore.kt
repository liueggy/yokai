package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * ONNX models the user supplied, stored in app-private storage.
 *
 * Nothing is downloaded or redistributed: the file is copied from wherever the user picked it, then
 * named by its own SHA-256 so a truncated copy can never be picked up and re-importing is a no-op.
 */
data class InstalledEnhancementModel(
    val id: String,
    val displayName: String,
    val file: File,
    val nativeScale: Int,
    val isBundled: Boolean,
)

object EnhancementModelStore {
    private const val DIRECTORY = "enhancement-models"
    private const val EXTENSION = ".onnx"
    private const val MAX_BYTES = 512L * 1024 * 1024

    /**
     * Bundled fallback model: Real-ESRGAN animevideov3 (SRVGGNetCompact, BSD-3-Clause).
     *
     * Weights are about 2.5 MB, so shipping them costs little and the feature works out of the box.
     * The digest is the one published by the converter, and it is also the file name on disk.
     */
    private const val BUNDLED_ASSET = "models/realesr-animevideov3.onnx"
    private const val BUNDLED_SHA = "78baa685a1a92cac6e14ab2af2a8ad0ef56fa48629563b1414e3a7acc54d86e2"
    private const val BUNDLED_BYTES = 2_492_908L

    /** A stable selection is independent of file timestamps and survives process restarts. */
    fun models(context: Context): List<InstalledEnhancementModel> {
        ensureBundled(context)
        return installedModels(directory(context).listFiles())
    }

    internal fun installedModels(files: Array<File>?): List<InstalledEnhancementModel> =
        files.orEmpty()
            .filter { it.isFile && it.name.endsWith(EXTENSION) && it.length() > 0 }
            .mapNotNull { file ->
                if (!file.nameWithoutExtension.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                val bundled = file.nameWithoutExtension == BUNDLED_SHA
                val metadata = Properties().apply {
                    runCatching { metadataFile(file).inputStream().use { load(it) } }
                }
                val scale = if (bundled) 4 else metadata.getProperty("scale")?.toIntOrNull()
                if (scale != 2 && scale != 4) return@mapNotNull null
                InstalledEnhancementModel(
                    id = if (bundled) "bundled" else file.nameWithoutExtension,
                    displayName = if (bundled) "Real-ESRGAN" else metadata.getProperty("name", file.name),
                    file = file,
                    nativeScale = scale,
                    isBundled = bundled,
                )
            }
            .sortedWith(compareBy<InstalledEnhancementModel> { !it.isBundled }.thenBy { it.displayName.lowercase() }.thenBy { it.id })

    fun selectedModel(context: Context, preferredId: String): InstalledEnhancementModel? {
        return selectInstalledModel(models(context), preferredId)
    }

    internal fun selectInstalledModel(installed: List<InstalledEnhancementModel>, preferredId: String): InstalledEnhancementModel? =
        installed.firstOrNull { it.id == preferredId || it.file.nameWithoutExtension == preferredId }
            ?: installed.firstOrNull { it.isBundled }
            ?: installed.firstOrNull()

    fun modelFile(context: Context): File? = selectedModel(context, "bundled")?.file

    /** Copies the bundled model out of the APK once, verifying its size before it is used. */
    private fun ensureBundled(context: Context) {
        val directory = directory(context)
        val target = File(directory, "$BUNDLED_SHA.onnx")
        if (target.isFile && target.length() == BUNDLED_BYTES && sha256(target) == BUNDLED_SHA) return

        runCatching {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the model directory" }
            val temporary = File.createTempFile("bundled-", ".tmp", directory)
            context.assets.open(BUNDLED_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            val digest = sha256(temporary)
            if (temporary.length() != BUNDLED_BYTES || digest != BUNDLED_SHA) {
                temporary.delete()
                return
            }
            target.delete()
            check(temporary.renameTo(target)) { "Cannot store the bundled model" }
        }
    }

    /**
     * Copies [uri] into private storage and returns the stored file.
     *
     * The copy is hashed while it is written and then renamed, so an interrupted import leaves no
     * half-written model behind for the engine to load.
     */
    fun import(context: Context, uri: Uri): File {
        val directory = directory(context)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the model directory" }
        val temporary = File.createTempFile("import-", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Cannot read the selected file")
            stream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        require(total <= MAX_BYTES) { "Model file is larger than 512 MB" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(total > 0) { "The selected file is empty" }

            val scale = OnnxModelValidator.validate(temporary)
            val name = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } + EXTENSION
            val target = File(directory, name)
            if (target.exists()) {
                temporary.delete()
            } else {
                check(temporary.renameTo(target)) { "Cannot store the model" }
            }

            // Keep earlier imports so selecting another model is reversible.
            val displayName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "model.onnx"
            val metadata = Properties().apply {
                setProperty("name", displayName)
                setProperty("scale", scale.toString())
            }
            val metadataTarget = metadataFile(target)
            val pendingMetadata = File.createTempFile("metadata-", ".tmp", directory)
            try {
                pendingMetadata.outputStream().use { metadata.store(it, null) }
                check(pendingMetadata.renameTo(metadataTarget)) { "Cannot store model details" }
            } finally {
                pendingMetadata.delete()
            }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun metadataFile(model: File) = File(model.parentFile, "${model.nameWithoutExtension}.properties")

    fun clear(context: Context) {
        directory(context).deleteRecursively()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Files become selectable only after validation through the model picker. */
    private fun directory(context: Context): File =
        context.getExternalFilesDir(DIRECTORY) ?: File(context.applicationContext.filesDir, DIRECTORY)
}
