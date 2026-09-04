package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidReportEncoderTest {
    @Test
    fun neutralReportHasKnownSizeAndBytes() {
        val report = HidReportEncoder.encode(VirtualGamepadState())

        assertEquals(HidReportEncoder.REPORT_SIZE, report.size)
        assertArrayEquals(byteArrayOf(0, 0, 8, 0, 0, 0, 0, 0, 0), report)
    }

    @Test
    fun everyDpadDirectionMapsToExpectedHatValue() {
        DpadDirection.entries.forEachIndexed { index, direction ->
            val expected = if (direction == DpadDirection.NEUTRAL) 8 else index
            assertEquals(direction.name, expected.toByte(), HidReportEncoder.encode(
                VirtualGamepadState(dpad = direction),
            )[2])
        }
    }

    @Test
    fun encodesButtonsSticksAndTriggersAtExtremes() {
        val report = HidReportEncoder.encode(
            VirtualGamepadState(
                pressedButtons = setOf(VirtualControl.FACE_SOUTH, VirtualControl.EXTRA_6),
                leftStickX = -1f,
                leftStickY = 1f,
                rightStickX = -2f,
                rightStickY = 2f,
                leftTrigger = -1f,
                rightTrigger = 1f,
            ),
        )

        assertArrayEquals(
            byteArrayOf(1, 128.toByte(), 8, -127, 127, -127, 127, 0, 255.toByte()),
            report,
        )
    }
}
