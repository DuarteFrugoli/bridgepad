package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHidReportParserTest {
    @Test
    fun acceptsGamepadApplicationDescriptor() {
        val parser = UsbHidReportParser(
            byteArrayOf(
                0x05, 0x01,
                0x09, 0x05,
                0xa1.toByte(), 0x01,
                0x05, 0x09,
                0x09, 0x01,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0xc0.toByte(),
            ),
        )

        assertTrue(VirtualControl.FACE_SOUTH in parser.decode(byteArrayOf(1)).pressedButtons)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsKeyboardApplicationDescriptor() {
        UsbHidReportParser(
            byteArrayOf(
                0x05, 0x01,
                0x09, 0x06,
                0xa1.toByte(), 0x01,
                0x05, 0x07,
                0x09, 0x04,
                0x15, 0x00,
                0x25, 0x01,
                0x75, 0x01,
                0x95.toByte(), 0x01,
                0x81.toByte(), 0x02,
                0xc0.toByte(),
            ),
        )
    }
}
