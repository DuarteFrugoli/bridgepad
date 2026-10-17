package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Shared BPI1 envelope for playable gamepad input and the transport probe. */
internal class InputUdpSealer(key: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var counter = 0L

    init { require(key.size == 32) }

    fun seal(sessionId: Long, message: BridgeMessage): ByteArray {
        val sequence = counter
        check(sequence >= 0 && sequence < MAX_COUNTER) { "UDP input key exhausted" }
        val plaintext = BridgePacketCodec.encode(
            BridgePacket(
                sessionId = sessionId,
                sequence = sequence and 0xffff_ffffL,
                timestampMicros = (System.nanoTime() and Long.MAX_VALUE) / 1_000,
                message = message,
            ),
        )
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC).putLong(sequence).putInt(0).array()
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce(sequence)))
        cipher.updateAAD(header)
        val datagram = header + cipher.doFinal(plaintext)
        check(datagram.size <= MAX_DATAGRAM_SIZE) { "UDP input packet is too large" }
        counter = sequence + 1
        return datagram
    }
}

internal class InputUdpOpener(key: ByteArray) {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var latestCounter = -1L

    init { require(key.size == 32) }

    /** Returns null for malformed, unauthenticated, duplicate or stale packets. */
    fun open(datagram: ByteArray, length: Int): BridgePacket? {
        if (length !in (HEADER_SIZE + TAG_SIZE)..MAX_DATAGRAM_SIZE) return null
        val header = datagram.copyOfRange(0, HEADER_SIZE)
        val buffer = ByteBuffer.wrap(header)
        val magic = ByteArray(4).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) return null
        val counter = buffer.long
        if (counter <= latestCounter || buffer.int != 0) return null
        return runCatching {
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, nonce(counter)))
            cipher.updateAAD(header)
            val packet = BridgePacketCodec.decode(
                cipher.doFinal(datagram, HEADER_SIZE, length - HEADER_SIZE),
            )
            check(packet.sequence == (counter and 0xffff_ffffL))
            latestCounter = counter
            packet
        }.getOrNull()
    }
}

private val MAGIC = byteArrayOf('B'.code.toByte(), 'P'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
private const val HEADER_SIZE = 16
private const val TAG_SIZE = 16
private const val MAX_DATAGRAM_SIZE = 1_200
private const val MAX_COUNTER = 0x1_0000_0000L

private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
