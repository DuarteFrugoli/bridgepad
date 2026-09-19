package dev.jonalakas.bridgepad.transport.bluetooth.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothDesktopProbeRequestTest {
    @Test
    fun defaultsMatchTheSharedBenchmarkProfile() {
        val request = BluetoothDesktopProbeRequest(
            deviceAddress = "00:11:22:33:44:55",
            transport = BluetoothDesktopTransport.RFCOMM,
        )

        assertEquals(1_000, request.sampleCount)
        assertEquals(125, request.targetRateHz)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRatesThatCannotRepresentTheSpike() {
        BluetoothDesktopProbeRequest(
            deviceAddress = "00:11:22:33:44:55",
            transport = BluetoothDesktopTransport.BLE_GATT,
            targetRateHz = 0,
        )
    }
}
