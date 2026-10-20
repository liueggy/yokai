package eu.kanade.tachiyomi.ui.reader.enhancement

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EnhancementDiskCacheTest {
    @TempDir
    lateinit var temporary: Path

    private val key = "a".repeat(64)

    @Test
    fun `startup cleanup and invalidation preserve unrelated files`() {
        temporary.resolve("pending-12345.tmp").toFile().writeText("interrupted")
        temporary.resolve("pending-not-owned.tmp").toFile().writeText("keep")
        val cache = EnhancementDiskCache(temporary.toFile(), 12, 8)
        assertFalse(temporary.resolve("pending-12345.tmp").toFile().exists())
        assertEquals("keep", temporary.resolve("pending-not-owned.tmp").toFile().readText())
        cache.put(key) { it.write(byteArrayOf(1)) }
        cache.remove(key)
        assertNull(cache.read(key) { it.readBytes() })
        assertThrows(IllegalArgumentException::class.java) { cache.remove("../escape") }
    }

    @Test
    fun `only complete output is committed and failed replacement preserves old entry`() {
        val cache = EnhancementDiskCache(temporary.toFile(), 12, 8)
        assertNull(cache.read(key) { it.readBytes() })
        cache.put(key) { it.write("old".toByteArray()) }
        assertThrows(IllegalStateException::class.java) {
            cache.put(key) { it.write("partial".toByteArray()); error("tile failed") }
        }
        assertEquals("old", cache.read(key) { it.reader().readText() })
        assertFalse(temporary.toFile().listFiles()!!.any { it.extension == "tmp" })
        cache.put(key) { it.write("new".toByteArray()) }
        assertEquals("new", cache.read(key) { it.reader().readText() })
    }

    @Test
    fun `oversized empty and traversal writes are rejected`() {
        val cache = EnhancementDiskCache(temporary.toFile(), 12, 8)
        assertThrows(IllegalStateException::class.java) { cache.put(key) { it.write(ByteArray(9)) } }
        assertThrows(IllegalStateException::class.java) { cache.put(key) {} }
        assertThrows(IllegalArgumentException::class.java) { cache.put("../escape") {} }
        assertNull(cache.read(key) { it.readBytes() })
        assertEquals(0, temporary.toFile().listFiles()!!.size)
    }

    @Test
    fun `eviction respects byte budget and clear leaves unrelated files alone`() {
        val cache = EnhancementDiskCache(temporary.toFile(), 8, 8)
        cache.put(key) { it.write(ByteArray(8)) }
        temporary.resolve("$key.sr").toFile().setLastModified(1)
        val other = "b".repeat(64)
        cache.put(other) { it.write(ByteArray(8)) }
        assertNull(cache.read(key) { it.readBytes() })
        assertEquals(8, cache.read(other) { it.readBytes().size })
        temporary.resolve("unrelated.txt").toFile().writeText("keep")
        cache.clear()
        assertNull(cache.read(other) { it.readBytes() })
        assertEquals("keep", temporary.resolve("unrelated.txt").toFile().readText())
    }
}