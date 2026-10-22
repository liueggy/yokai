package eu.kanade.tachiyomi.ui.reader.enhancement

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EnhancementModelStoreTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `catalog keeps all validated imports and their original names and scales`() {
        val digest = "b".repeat(64)
        File(directory, "$digest.onnx").writeText("model")
        File(directory, "$digest.properties").writeText("name=My model.onnx\nscale=2\n")
        File(directory, "${"c".repeat(64)}.onnx").writeText("pending import")
        File(directory, "invalid.onnx").writeText("not imported through picker")
        val catalog = EnhancementModelStore.installedModels(directory.listFiles())
        assertEquals(1, catalog.size)
        assertEquals(digest, catalog.single().id)
        assertEquals("My model.onnx", catalog.single().displayName)
        assertEquals(2, catalog.single().nativeScale)
        File(directory, "${"c".repeat(64)}.properties").writeText("name=Second.onnx\nscale=4\n")
        assertEquals(2, EnhancementModelStore.installedModels(directory.listFiles()).size)
    }

    private val bundled = InstalledEnhancementModel("bundled", "Real-ESRGAN", File("${"a".repeat(64)}.onnx"), 4, true)
    private val first = InstalledEnhancementModel("b".repeat(64), "first.onnx", File("${"b".repeat(64)}.onnx"), 2, false)
    private val second = InstalledEnhancementModel("c".repeat(64), "second.onnx", File("${"c".repeat(64)}.onnx"), 4, false)

    @Test
    fun `explicit selection wins regardless of list order or other imports`() {
        val models = listOf(bundled, first, second)
        assertEquals(first, EnhancementModelStore.selectInstalledModel(models, first.id))
        assertEquals(first, EnhancementModelStore.selectInstalledModel(models.reversed(), first.id))
        assertEquals(second, EnhancementModelStore.selectInstalledModel(models, second.id))
        assertEquals(bundled, EnhancementModelStore.selectInstalledModel(models, "bundled"))
    }

    @Test
    fun `missing selection falls back to bundled without deleting imports`() {
        val models = listOf(first, bundled, second)
        assertEquals(bundled, EnhancementModelStore.selectInstalledModel(models, "missing"))
        assertEquals(bundled, EnhancementModelStore.selectInstalledModel(models, ""))
        assertEquals(3, models.size)
        assertNull(EnhancementModelStore.selectInstalledModel(emptyList(), "bundled"))
    }

    @Test
    fun `importing the bundled asset resolves its digest to the bundled entry`() {
        assertEquals(bundled, EnhancementModelStore.selectInstalledModel(listOf(bundled, first), "a".repeat(64)))
    }
}
