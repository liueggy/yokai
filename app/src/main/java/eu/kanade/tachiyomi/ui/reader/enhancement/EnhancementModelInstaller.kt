package eu.kanade.tachiyomi.ui.reader.enhancement

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and installs model weights.
 *
 * Guarantees, in order of importance:
 * - a partially downloaded or tampered file never reaches the installed directory;
 * - a failed replacement leaves the previous version usable;
 * - the reader can keep using the old model while a new one installs.
 *
 * Blocking: call from an IO dispatcher.
 */
class EnhancementModelInstaller(
    private val modelsRoot: File,
    private val timeoutMillis: Int = 30_000,
) {
    /** Installed directory for [model], or null when absent or incomplete. */
    fun installed(model: EnhancementModel): File? {
        val directory = File(modelsRoot, "${model.id}/${model.version}")
        if (!directory.isDirectory) return null
        val complete = model.files.all { file ->
            val installed = File(directory, file.name)
            installed.isFile && installed.length() == file.sizeBytes
        }
        return directory.takeIf { complete }
    }

    /**
     * Downloads every file of [model] into a staging directory, verifies it, then swaps it in.
     * [onProgress] receives bytes copied and total bytes, and may be called from the calling thread.
     */
    fun install(model: EnhancementModel, onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }): File {
        val staging = File(modelsRoot, ".staging-${model.id}-${System.nanoTime()}")
        check(staging.mkdirs()) { "Cannot create staging directory for ${model.id}" }

        try {
            val total = model.files.sumOf { it.sizeBytes }
            var copied = 0L
            for (file in model.files) {
                val target = File(staging, file.name)
                download(file, model.baseUrls, target) { bytes -> onProgress(copied + bytes, total) }
                require(sha256(target) == file.sha256.lowercase()) {
                    "Checksum mismatch for ${file.name}: refusing to install"
                }
                copied += file.sizeBytes
                onProgress(copied, total)
            }
            return commit(model, staging)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun remove(model: EnhancementModel) {
        File(modelsRoot, model.id).deleteRecursively()
    }

    private fun commit(model: EnhancementModel, staging: File): File {
        val target = File(modelsRoot, "${model.id}/${model.version}")
        target.parentFile?.mkdirs()

        // Move the old version aside first: rename onto an existing directory fails on every platform,
        // and a half-deleted install is worse than a stale one.
        val retired = File(target.parentFile, ".${model.version}.retired-${System.nanoTime()}")
        if (target.exists() && !target.renameTo(retired)) {
            staging.deleteRecursively()
            error("Cannot replace installed model ${model.id}")
        }
        if (!staging.renameTo(target)) {
            retired.renameTo(target)
            staging.deleteRecursively()
            error("Cannot commit model ${model.id}")
        }
        retired.deleteRecursively()
        return target
    }

    /** Tries each mirror in order; only a fully downloaded and size-checked file is kept. */
    private fun download(file: ModelFile, baseUrls: List<String>, target: File, onBytes: (Long) -> Unit) {
        var lastError: Exception? = null
        for (base in baseUrls) {
            val url = "${base.trimEnd('/')}/${file.name}"
            try {
                copy(url, file.sizeBytes, target, onBytes)
                return
            } catch (error: Exception) {
                lastError = error
                target.delete()
            }
        }
        throw IOException("Cannot download ${file.name} from any mirror", lastError)
    }

    private fun copy(url: String, expectedBytes: Long, target: File, onBytes: (Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            val length = connection.contentLengthLong
            if (length > 0 && length != expectedBytes) {
                throw IOException("Expected $expectedBytes bytes for $url but server reports $length")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    copyBounded(input, output, expectedBytes, onBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, expectedBytes: Long, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            copied += read
            // A server sending more than declared would otherwise fill the disk before the hash check.
            if (copied > expectedBytes) throw IOException("Download exceeded declared size")
            output.write(buffer, 0, read)
            onBytes(copied)
        }
        if (copied != expectedBytes) throw IOException("Truncated download: $copied of $expectedBytes bytes")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
