package dev.jonalakas.bridgepad.transport.usb.accessory

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAccessoryProbeConstantsTest {
    @Test
    fun `probe uses the standard BridgePad diagnostic cadence`() {
        assertEquals(250, USB_ACCESSORY_SAMPLE_COUNT)
        assertEquals(125, USB_ACCESSORY_RATE_HZ)
    }
}
