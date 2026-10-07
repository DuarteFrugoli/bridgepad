package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeMediaDatagramCodecTest {
    @Test
    fun `golden Rust video datagram round trips`() {
        val datagram = MediaDatagram(sampleHeader(), byteArrayOf(0xaa.toByte(), 0xbb.toByte()))
        val expected = HEX_VECTOR.hexToBytes()
        assertArrayEquals(expected, BridgeMediaDatagramCodec.encode(datagram))

        val decoded = BridgeMediaDatagramCodec.decode(expected)
        assertEquals(datagram.header, decoded.header)
        assertArrayEquals(datagram.payload, decoded.payload)
    }

    @Test
    fun `maximum datagram is accepted`() {
        val payload = ByteArray(BridgeMediaDatagramCodec.MAX_PAYLOAD_SIZE) { 0x5a }
        val header = sampleHeader().copy(
            flags = MediaPacketFlags.KEYFRAME or MediaPacketFlags.FRAME_START or
                MediaPacketFlags.FRAME_END,
            framePacketCount = 1,
            originalFrameBytes = payload.size.toLong(),
        )
        val encoded = BridgeMediaDatagramCodec.encode(MediaDatagram(header, payload))
        assertEquals(BridgeMediaDatagramCodec.MAX_DATAGRAM_SIZE, encoded.size)
        assertArrayEquals(payload, BridgeMediaDatagramCodec.decode(encoded).payload)
    }

    @Test
    fun `unknown flags and malformed boundaries are rejected`() {
        val encoded = BridgeMediaDatagramCodec.encode(MediaDatagram(sampleHeader(), byteArrayOf(1)))
        encoded[8] = 0x80.toByte()
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaDatagramCodec.decode(encoded)
        }
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaDatagramCodec.encode(
                MediaDatagram(
                    sampleHeader().copy(flags = MediaPacketFlags.KEYFRAME),
                    byteArrayOf(1),
                ),
            )
        }
    }

    @Test
    fun `repair shard uses explicit FEC metadata`() {
        val header = sampleHeader().copy(
            flags = MediaPacketFlags.KEYFRAME or MediaPacketFlags.FEC_REPAIR,
            framePacketIndex = BridgeMediaDatagramCodec.REPAIR_PACKET_INDEX,
            fecShardIndex = 4,
            fecSourceCount = 4,
            fecRepairCount = 2,
        )
        val encoded = BridgeMediaDatagramCodec.encode(MediaDatagram(header, byteArrayOf(9, 9)))
        assertEquals(header, BridgeMediaDatagramCodec.decode(encoded).header)
    }

    @Test
    fun `negative signed wire values are rejected`() {
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaDatagramCodec.encode(
                MediaDatagram(sampleHeader().copy(sessionId = Long.MIN_VALUE), byteArrayOf(1)),
            )
        }
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaDatagramCodec.encode(
                MediaDatagram(
                    sampleHeader().copy(presentationTimestampMicros = Long.MIN_VALUE),
                    byteArrayOf(1),
                ),
            )
        }
    }

    private fun sampleHeader() = MediaDatagramHeader(
        kind = MediaPacketKind.VIDEO,
        flags = MediaPacketFlags.KEYFRAME or MediaPacketFlags.FRAME_START,
        sessionId = 0x0102_0304_0506_0708,
        streamId = 0x1112_1314,
        sequence = 0x2122_2324,
        frameId = 0x3132_3334,
        presentationTimestampMicros = 0x4142_4344_4546_4748,
        framePacketIndex = 0,
        framePacketCount = 2,
        originalFrameBytes = 4,
    )

    private fun String.hexToBytes(): ByteArray =
        replace("\n", "").replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val HEX_VECTOR =
            "42504d3101000138000500000102030405060708111213142122232431323334" +
                "414243444546474800000002000000040000000000000000aabb"
    }
}
