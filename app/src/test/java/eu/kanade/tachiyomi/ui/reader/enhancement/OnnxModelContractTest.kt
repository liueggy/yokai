package eu.kanade.tachiyomi.ui.reader.enhancement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OnnxModelContractTest {
    @Test
    fun `supports dynamic RGB tiles with 2x or 4x native output`() {
        OnnxModelContract.validateInput(longArrayOf(1, 3, -1, -1))
        OnnxModelContract.validateInput(longArrayOf(-1, 3, -1, -1))
        assertEquals(2, OnnxModelContract.outputScale(longArrayOf(1, 3, 64, 48), 24, 32))
        assertEquals(4, OnnxModelContract.outputScale(longArrayOf(1, 3, 128, 96), 24, 32))
    }

    @Test
    fun `rejects fixed shape and channel layouts the reader cannot tile`() {
        listOf(longArrayOf(1, 3, 64, 64), longArrayOf(1, -1, -1, 3), longArrayOf(1, 1, -1, -1), longArrayOf(3, -1, -1)).forEach { shape ->
            assertThrows(IllegalArgumentException::class.java) { OnnxModelContract.validateInput(shape) }
        }
    }

    @Test
    fun `rejects mismatched dimensions unsupported scale and non RGB output`() {
        listOf(longArrayOf(1, 3, 48, 48), longArrayOf(1, 3, 64, 49), longArrayOf(1, 3, 96, 72), longArrayOf(1, 1, 64, 48)).forEach { shape ->
            assertThrows(IllegalArgumentException::class.java) { OnnxModelContract.outputScale(shape, 24, 32) }
        }
    }
}
