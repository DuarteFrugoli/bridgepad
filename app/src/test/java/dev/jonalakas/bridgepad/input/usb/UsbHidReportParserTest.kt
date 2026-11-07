package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import dev.jonalakas.bridgepad.output.hid.HidReportEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbHidReportParserTest {
    @Test
    fun readsUnsignedEightBitTriggerMaximaInsteadOfTreating255AsMinusOne() {
        val parser = UsbHidReportParser(triggerDescriptor(0, 0x25, 0xff, size = 8))
        val state = parser.decode(byteArrayOf(128.toByte(), 255.toByte()))
        assertEquals(128f / 255f, state.leftTrigger, 0.0001f)
        assertEquals(1f, state.rightTrigger, 0f)
        assertEquals(0f, parser.decode(byteArrayOf(0, 0)).leftTrigger, 0f)
    }

    @Test
    fun readsUnsignedSixteenBitTriggerMaxima() {
        val parser = UsbHidReportParser(triggerDescriptor(0, 0x26, 0xff, 0xff, size = 16))
        val state = parser.decode(byteArrayOf(255.toByte(), 255.toByte(), 0, 128.toByte()))
        assertEquals(1f, state.leftTrigger, 0f)
        assertEquals(32768f / 65535f, state.rightTrigger, 0.0001f)
    }

    @Test
    fun retainsSignedLogicalMaximaForNegativeRanges() {
        val parser = UsbHidReportParser(triggerDescriptor(0x80, 0x25, 0xff, size = 8))
        val state = parser.decode(byteArrayOf(128.toByte(), 255.toByte()))
        assertEquals(0f, state.leftTrigger, 0f)
        assertEquals(1f, state.rightTrigger, 0f)
    }

    @Test
    fun usbButtonTriggersCanBeMappedAndEncodedAsIndependentHidAxes() {
        val parser = UsbHidReportParser(
            byteArrayOf(
                0x05, 0x01, 0x09, 0x05, 0xa1.toByte(), 0x01,
                0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
                0x15, 0x00, 0x25, 0x01, 0x75, 0x01,
                0x95.toByte(), 0x10, 0x81.toByte(), 0x02, 0xc0.toByte(),
            ),
        )
        val mapping = GamepadMapping(
            buttons = emptyMap(),
            triggerButtons = mapOf(
                VirtualAxis.LEFT_TRIGGER to VirtualControl.START,
                VirtualAxis.RIGHT_TRIGGER to VirtualControl.SELECT,
            ),
        )
        val report = HidReportEncoder.encode(mapping.apply(parser.decode(byteArrayOf(0xc0.toByte(), 0))))
        assertEquals(0, report[0].toInt())
        assertEquals(255, report[7].toInt() and 0xff)
        assertEquals(255, report[8].toInt() and 0xff)
        assertEquals(VirtualGamepadState(), mapping.apply(parser.decode(byteArrayOf(0, 0))))
    }

    private fun triggerDescriptor(minimum: Int, vararg maximumItem: Int, size: Int): ByteArray =
        byteArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xa1.toByte(), 0x01,
            0x05, 0x02, 0x09, 0xc5.toByte(), 0x09, 0xc4.toByte(),
            0x15, minimum.toByte(),
        ) + maximumItem.map(Int::toByte).toByteArray() + byteArrayOf(
            0x75, size.toByte(), 0x95.toByte(), 0x02, 0x81.toByte(), 0x02, 0xc0.toByte(),
        )

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
