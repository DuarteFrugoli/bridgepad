package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.core.ports.KeyboardModifier
import dev.jonalakas.bridgepad.core.ports.PointerReport
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

    @Test
    fun shortcutPressesAndReleasesEveryModifierAtomically() {
        val reports = KeyboardHidEncoder.encode(
            KeyboardInput.Shortcut(setOf(KeyboardModifier.META), KeyboardKey.TAB),
        )

        assertEquals(2, reports.size)
        assertArrayEquals(byteArrayOf(0x08, 0, 0x2B, 0, 0, 0, 0, 0), reports.first())
        assertArrayEquals(ByteArray(8), reports.last())
    }

    @Test
    fun reverseWindowShortcutCombinesAltAndShift() {
        val reports = KeyboardHidEncoder.encode(
            KeyboardInput.Shortcut(
                setOf(KeyboardModifier.ALT, KeyboardModifier.SHIFT),
                KeyboardKey.TAB,
            ),
        )

        assertEquals(0x06, reports.first()[0].toInt())
        assertEquals(0x2B, reports.first()[2].toInt())
        assertArrayEquals(ByteArray(8), reports.last())
    }

    @Test
    fun windowNavigationKeysUseStandardHidUsages() {
        assertEquals(
            0x50,
            KeyboardHidEncoder.encode(KeyboardInput.Key(KeyboardKey.LEFT)).first()[2].toInt(),
        )
        assertEquals(
            0x4F,
            KeyboardHidEncoder.encode(KeyboardInput.Key(KeyboardKey.RIGHT)).first()[2].toInt(),
        )
        assertEquals(
            0x10,
            KeyboardHidEncoder.encode(KeyboardInput.Key(KeyboardKey.M)).first()[2].toInt(),
        )
    }

    @Test
    fun pinchZoomWrapsMouseWheelBetweenControlPressAndRelease() {
        val reports = GenericCompositeHidProfile.encodePointer(PointerReport(zoomY = 2))

        assertEquals(3, reports.size)
        assertEquals(GamepadHidDescriptor.KEYBOARD_REPORT_ID, reports[0].id)
        assertEquals(0x01, reports[0].payload[0].toInt())
        assertEquals(GamepadHidDescriptor.MOUSE_REPORT_ID, reports[1].id)
        assertEquals(2, reports[1].payload[3].toInt())
        assertArrayEquals(ByteArray(8), reports[2].payload)
    }
}
