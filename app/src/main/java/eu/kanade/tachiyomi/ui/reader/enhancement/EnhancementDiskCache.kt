package eu.kanade.tachiyomi.ui.reader.enhancement

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Single-process cache. Use one instance per directory. Outputs are opaque encoded bytes. */
class EnhancementDiskCache(
    private val directory: File,
    private val maxBytes: Long = 512L * 1024 * 1024,
    private val maxEntryBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxBytes > 0 && maxEntryBytes > 0 && maxEntryBytes <= maxBytes)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create enhancement cache" }
        // Construction requires exclusive ownership of this directory.
        directory.listFiles()?.filter { it.isFile && it.name.matches(PENDING_NAME) }
            ?.forEach { it.delete() }
        trim()
    }

    /** Consumer runs under the cache lock so eviction cannot remove its input. */
    @Synchronized
    fun <T> read(key: String, consume: (InputStream) -> T): T? {
        val file = entry(key)
        if (!file.isFile) return null
        if (file.length() !in 1..maxEntryBytes) {
            file.delete()
            return null
        }
        return file.inputStream().use { input ->
            consume(input).also { file.setLastModified(System.currentTimeMillis()) }
        }
    }

    /** Call on an IO worker. Producer exceptions (including cancellation) leave no new entry. */
    @Synchronized
    fun put(key: String, produce: (java.io.OutputStream) -> Unit) {
        val destination = entry(key)
        val temporary = File.createTempFile("pending-", ".tmp", directory)
        try {
            temporary.outputStream().use { fileOutput ->
                var written = 0L
                val bounded = object : java.io.OutputStream() {
                    override fun write(value: Int) {
                        check(written < maxEntryBytes) { "Enhancement cache entry too large" }
                        fileOutput.write(value)
                        written++
                    }

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        check(length.toLong() <= maxEntryBytes - written) { "Enhancement cache entry too large" }
                        fileOutput.write(bytes, offset, length)
                        written += length
                    }
                }
                produce(bounded)
                check(written > 0) { "Empty enhancement output" }
                fileOutput.fd.sync()
            }
            commit(temporary, destination)
            destination.setLastModified(System.currentTimeMillis())
            trim()
        } finally {
            temporary.delete()
        }
    }

    /**
     * Atomic replacement. File.renameTo cannot overwrite on Windows, so the failure to replace an
     * existing entry would abort every cache refresh on that platform.
     */
    private fun commit(temporary: File, destination: File) {
        val source = temporary.toPath()
        val target = destination.toPath()
        try {
            try {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (exception: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Cannot commit enhancement output", exception)
        }
    }

    /** Call when the image decoder rejects cached output. */
    @Synchronized
    fun remove(key: String): Boolean = entry(key).let { !it.exists() || it.delete() }

    @Synchronized
    fun clear() {
        directory.listFiles()?.filter { it.name.matches(ENTRY_NAME) }?.forEach { it.delete() }
    }

    private fun entry(key: String): File {
        require(key.matches(KEY)) { "Invalid cache key" }
        return File(directory, "$key.sr")
    }

    private fun trim() {
        val entries = directory.listFiles()?.filter { it.isFile && it.name.matches(ENTRY_NAME) }
            ?.sortedBy { it.lastModified() } ?: return
        var total = entries.sumOf { it.length() }
        for (file in entries) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private companion object {
        val PENDING_NAME = Regex("pending-[0-9]+[.]tmp")
        val KEY = Regex("[0-9a-f]{64}")
        val ENTRY_NAME = Regex("[0-9a-f]{64}\\.sr")
    }
}
