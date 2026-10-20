package eu.kanade.tachiyomi.ui.reader.enhancement

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * ONNX models the user supplied, stored in app-private storage.
 *
 * Nothing is downloaded or redistributed: the file is copied from wherever the user picked it, then
 * named by its own SHA-256 so a truncated copy can never be picked up and re-importing is a no-op.
 */
object EnhancementModelStore {
    private const val DIRECTORY = "enhancement-models"
    private const val EXTENSION = ".onnx"
    private const val MAX_BYTES = 512L * 1024 * 1024

    /**
     * Bundled fallback model: Real-ESRGAN animevideov3 (SRVGGNetCompact, BSD-3-Clause).
     *
     * Weights are 1.2 MB, so shipping them costs little and the feature works out of the box.
     * The digest is the one published by the converter, and it is also the file name on disk.
     */
    private const val BUNDLED_ASSET = "models/realesr-animevideov3.onnx"
    private const val BUNDLED_SHA = "78baa685a1a92cac6e14ab2af2a8ad0ef56fa48629563b1414e3a7acc54d86e2"
    private const val BUNDLED_BYTES = 2_492_908L

    /** The most recently imported model, or null when none was imported yet. */
    fun modelFile(context: Context): File? {
        ensureBundled(context)
        return directory(context)
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXTENSION) && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Copies the bundled model out of the APK once, verifying its size before it is used. */
    private fun ensureBundled(context: Context) {
        val directory = directory(context)
        val target = File(directory, "$BUNDLED_SHA.onnx")
        if (target.isFile && target.length() == BUNDLED_BYTES) return

        runCatching {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the model directory" }
            val temporary = File.createTempFile("bundled-", ".tmp", directory)
            context.assets.open(BUNDLED_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() != BUNDLED_BYTES) {
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

            val name = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } + EXTENSION
            val target = File(directory, name)
            if (target.exists()) {
                temporary.delete()
            } else {
                check(temporary.renameTo(target)) { "Cannot store the model" }
            }

            // Only one model is resident at a time: the newest import wins.
            directory.listFiles()
                ?.filter { it != target && it.name.endsWith(EXTENSION) }
                ?.forEach { it.delete() }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun clear(context: Context) {
        directory(context).deleteRecursively()
    }

    /**
     * App-scoped external storage, so a model can also be pushed with adb before any picker exists:
     * `adb push model.onnx /sdcard/Android/data/<package>/files/enhancement-models/`.
     * Falls back to internal storage when external is unavailable.
     */
    private fun directory(context: Context): File =
        context.getExternalFilesDir(DIRECTORY) ?: File(context.applicationContext.filesDir, DIRECTORY)
}
