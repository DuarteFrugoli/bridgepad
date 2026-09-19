package dev.jonalakas.bridgepad.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgePacketStreamTest {
    @Test
    fun `reads consecutive variable-length packets from one stream`() {
        val first = BridgePacket(7, 1, 10, BridgeMessage.Ping(11))
        val second = BridgePacket(7, 2, 20, BridgeMessage.Status(BridgeStatusCode.READY, "play"))
        val output = ByteArrayOutputStream()

        BridgePacketStream.write(output, first)
        BridgePacketStream.write(output, second)

        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        assertEquals(first, BridgePacketStream.read(input))
        assertEquals(second, BridgePacketStream.read(input))
    }
}
