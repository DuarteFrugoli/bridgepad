package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeMediaAeadTest {
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `Rust golden sealed datagram round trips and rejects replay`() {
        val plaintext = sampleDatagram()
        val sender = UdpAeadSender(key, 0x0102_0304)
        val sealed = sender.seal(plaintext)
        assertArrayEquals(SEALED_VECTOR.hexToBytes(), sealed)

        val receiver = UdpAeadReceiver(key, 0x0102_0304)
        assertArrayEquals(plaintext, receiver.open(sealed))
        assertThrows(MediaSecurityException::class.java) { receiver.open(sealed) }
    }

    @Test
    fun `tampering does not consume the valid packet counter`() {
        val plaintext = sampleDatagram()
        val sealed = UdpAeadSender(key, 7).seal(plaintext)
        val tampered = sealed.copyOf().apply { this[BridgeMediaAead.HEADER_SIZE + 3] =
            (this[BridgeMediaAead.HEADER_SIZE + 3].toInt() xor 1).toByte() }
        val receiver = UdpAeadReceiver(key, 7)
        assertThrows(MediaSecurityException::class.java) { receiver.open(tampered) }
        assertArrayEquals(plaintext, receiver.open(sealed))
    }

    @Test
    fun `bounded replay window accepts out of order packet once`() {
        val plaintext = sampleDatagram()
        val sender = UdpAeadSender(key, 9)
        val packets = List(300) { sender.seal(plaintext) }
        val receiver = UdpAeadReceiver(key, 9)
        receiver.open(packets[299])
        receiver.open(packets[44])
        assertThrows(MediaSecurityException::class.java) { receiver.open(packets[43]) }
        assertThrows(MediaSecurityException::class.java) { receiver.open(packets[44]) }
    }

    private fun sampleDatagram(): ByteArray = BridgeMediaDatagramCodec.encode(
        MediaDatagram(
            MediaDatagramHeader(
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
            ),
            byteArrayOf(0xaa.toByte(), 0xbb.toByte()),
        ),
    )

    private fun String.hexToBytes(): ByteArray =
        replace("\n", "").replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val SEALED_VECTOR =
            "42504131010014000102030400000000000000006305906c16917949fbe297bb" +
                "b1c2b46af5195df9c1b31a4769d31a7b53a0f72da0958ce0a37a50bce77d" +
                "de3313c2f96f7355f0c2514f12fb33dd68c42e7fbe76262e4bcd87e2085a1" +
                "1c1"
    }
}
