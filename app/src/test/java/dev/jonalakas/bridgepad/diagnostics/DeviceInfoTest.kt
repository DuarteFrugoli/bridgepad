package dev.jonalakas.bridgepad.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    @Test
    fun displayModel_combinesManufacturerAndModel() {
        val info = DeviceInfo("Samsung", "Galaxy A35", "16", 36)

        assertEquals("Samsung Galaxy A35", info.displayModel)
    }

    @Test
    fun displayModel_usesKnownValueWhenOtherIsBlank() {
        val info = DeviceInfo("", "Galaxy A35", "16", 36)

        assertEquals("Galaxy A35", info.displayModel)
    }

    @Test
    fun displayModel_fallsBackWhenDeviceIsUnknown() {
        val info = DeviceInfo("", "", "16", 36)

        assertEquals("Unknown device", info.displayModel)
    }
}
