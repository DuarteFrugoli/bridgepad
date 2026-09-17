package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHidEncoderTest {
    @Test
    fun textProducesPressAndReleaseReports() {
        val reports = KeyboardHidEncoder.encode(KeyboardInput.Text("aA1!"))

        assertEquals(8, reports.size)
        assertArrayEquals(byteArrayOf(0, 0, 0x04, 0, 0, 0, 0, 0), reports[0])
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), reports[2])
        assertArrayEquals(byteArrayOf(0, 0, 0x1E, 0, 0, 0, 0, 0), reports[4])
        assertArrayEquals(byteArrayOf(0x02, 0, 0x1E, 0, 0, 0, 0, 0), reports[6])
        assertArrayEquals(ByteArray(8), reports.last())
    }

    @Test
    fun specialKeyProducesPressAndReleaseReports() {
        val reports = KeyboardHidEncoder.encode(KeyboardInput.Key(KeyboardKey.BACKSPACE))

        assertEquals(2, reports.size)
        assertEquals(0x2A, reports.first()[2].toInt())
        assertArrayEquals(ByteArray(8), reports.last())
    }
}
