package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuicMediaProbeTest {
    @Test
    fun `native result preserves transport measurements`() {
        val result = parseQuicMediaProbeResult(
            "OK\t192.168.15.3\t4800\t4800\t298\t300\t2\t11\t0\t5268600\t4984\t8\t8680\t11220\t15004",
            "wlan0",
        )

        assertEquals(MediaTransportKind.QUIC_DATAGRAM, result.transport)
        assertEquals("192.168.15.3", result.localAddress)
        assertEquals("wlan0", result.interfaceName)
        assertEquals(4_800L, result.receivedPackets)
        assertEquals(298L, result.completedFrames)
        assertEquals(2L, result.expiredFrames)
        assertEquals(11L, result.duplicatePackets)
        assertEquals(8L, result.handshakeMillis)
        assertEquals(15_004L, result.arrivalGapMaxMicros)
    }

    @Test
    fun `native error remains visible to the diagnostic screen`() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseQuicMediaProbeResult("ERR\tconnection timed out", "wlan0")
        }

        assertEquals("connection timed out", error.message)
    }
}
