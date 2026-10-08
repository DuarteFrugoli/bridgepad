package dev.jonalakas.bridgepad.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.util.BitSet
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaSecurityException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Authenticated envelope used only by the raw UDP transport candidate.
 * QUIC already authenticates its datagrams and must not use this envelope.
 */
object BridgeMediaAead {
    private const val MAGIC = 0x42504131 // BPA1
    private const val MAJOR_VERSION = 1
    private const val MINOR_VERSION = 0
    const val HEADER_SIZE = 20
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32
    const val MAX_SEALED_DATAGRAM_SIZE = BridgeMediaDatagramCodec.MAX_DATAGRAM_SIZE
    const val MAX_PLAINTEXT_SIZE = MAX_SEALED_DATAGRAM_SIZE - HEADER_SIZE - TAG_SIZE
    const val REPLAY_WINDOW_SIZE = 256
    const val MAX_PACKETS_PER_KEY = 0xffff_ffffL

    internal fun header(keyEpoch: Long, packetCounter: Long): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .put(MAJOR_VERSION.toByte())
            .put(MINOR_VERSION.toByte())
            .put(HEADER_SIZE.toByte())
            .put(0)
            .putInt(keyEpoch.toInt())
            .putLong(packetCounter)
            .array()

    internal fun nonce(keyEpoch: Long, packetCounter: Long): ByteArray =
        ByteBuffer.allocate(12)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(keyEpoch.toInt())
            .putLong(packetCounter)
            .array()

    internal fun parseHeader(sealed: ByteArray): Header {
        val minimumSize = HEADER_SIZE + TAG_SIZE + BridgeMediaDatagramCodec.HEADER_SIZE + 1
        if (sealed.size < minimumSize) {
            throw MediaSecurityException("Sealed media datagram is too short: ${sealed.size}")
        }
        if (sealed.size > MAX_SEALED_DATAGRAM_SIZE) {
            throw MediaSecurityException("Sealed media datagram exceeds $MAX_SEALED_DATAGRAM_SIZE bytes")
        }
        val input = ByteBuffer.wrap(sealed).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC) throw MediaSecurityException("Invalid sealed media magic")
        val major = input.get().toInt() and 0xff
        val minor = input.get().toInt() and 0xff
        if (major != MAJOR_VERSION || minor != MINOR_VERSION) {
            throw MediaSecurityException("Unsupported sealed media version: $major.$minor")
        }
        val headerSize = input.get().toInt() and 0xff
        if (headerSize != HEADER_SIZE) {
            throw MediaSecurityException("Unsupported sealed media header size: $headerSize")
        }
        val flags = input.get().toInt() and 0xff
        if (flags != 0) throw MediaSecurityException("Unknown sealed media flags: $flags")
        return Header(
            keyEpoch = input.int.toLong() and 0xffff_ffffL,
            packetCounter = input.long,
        ).also {
            if (it.packetCounter !in 0..MAX_PACKETS_PER_KEY) {
                throw MediaSecurityException("Packet counter is outside the active key epoch")
            }
        }
    }

    internal data class Header(val keyEpoch: Long, val packetCounter: Long)
}

class UdpAeadSender(
    key: ByteArray,
    private val keyEpoch: Long,
) {
    private val secretKey = validatedKey(key)
    private var nextPacketCounter = 0L

    init {
        requireEpoch(keyEpoch)
    }

    fun seal(plaintext: ByteArray): ByteArray {
        BridgeMediaDatagramCodec.decode(plaintext)
        if (plaintext.size > BridgeMediaAead.MAX_PLAINTEXT_SIZE) {
            throw MediaSecurityException("Media plaintext exceeds ${BridgeMediaAead.MAX_PLAINTEXT_SIZE} bytes")
        }
        if (nextPacketCounter > BridgeMediaAead.MAX_PACKETS_PER_KEY) {
            throw MediaSecurityException("Media traffic key packet counter is exhausted")
        }
        val counter = nextPacketCounter
        val header = BridgeMediaAead.header(keyEpoch, counter)
        val encrypted = cipher(Cipher.ENCRYPT_MODE, secretKey, keyEpoch, counter, header)
            .doFinal(plaintext)
        nextPacketCounter = counter + 1
        return header + encrypted
    }
}

class UdpAeadReceiver(
    key: ByteArray,
    private val keyEpoch: Long,
) {
    private val secretKey = validatedKey(key)
    private val replayWindow = ReplayWindow()

    init {
        requireEpoch(keyEpoch)
    }

    fun open(sealed: ByteArray): ByteArray {
        val header = BridgeMediaAead.parseHeader(sealed)
        if (header.keyEpoch != keyEpoch) {
            throw MediaSecurityException(
                "Unexpected media key epoch ${header.keyEpoch}; expected $keyEpoch",
            )
        }
        if (!replayWindow.mayAccept(header.packetCounter)) {
            throw MediaSecurityException("Replayed or stale media packet: ${header.packetCounter}")
        }
        val associatedData = sealed.copyOfRange(0, BridgeMediaAead.HEADER_SIZE)
        val ciphertext = sealed.copyOfRange(BridgeMediaAead.HEADER_SIZE, sealed.size)
        val plaintext = try {
            cipher(
                Cipher.DECRYPT_MODE,
                secretKey,
                header.keyEpoch,
                header.packetCounter,
                associatedData,
            ).doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw MediaSecurityException("Media datagram authentication failed", error)
        }
        BridgeMediaDatagramCodec.decode(plaintext)
        replayWindow.accept(header.packetCounter)
        return plaintext
    }
}

private class ReplayWindow {
    private var highest: Long? = null
    private var received = BitSet(BridgeMediaAead.REPLAY_WINDOW_SIZE)

    fun mayAccept(counter: Long): Boolean {
        val currentHighest = highest ?: return true
        if (counter > currentHighest) return true
        val distance = currentHighest - counter
        return distance < BridgeMediaAead.REPLAY_WINDOW_SIZE && !received[distance.toInt()]
    }

    fun accept(counter: Long) {
        val currentHighest = highest
        if (currentHighest == null) {
            highest = counter
            received.set(0)
            return
        }
        if (counter > currentHighest) {
            shift((counter - currentHighest).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            highest = counter
            received.set(0)
        } else {
            received.set((currentHighest - counter).toInt())
        }
    }

    private fun shift(distance: Int) {
        if (distance >= BridgeMediaAead.REPLAY_WINDOW_SIZE) {
            received.clear()
            return
        }
        val shifted = BitSet(BridgeMediaAead.REPLAY_WINDOW_SIZE)
        var bit = received.nextSetBit(0)
        while (bit >= 0) {
            val destination = bit + distance
            if (destination < BridgeMediaAead.REPLAY_WINDOW_SIZE) shifted.set(destination)
            bit = received.nextSetBit(bit + 1)
        }
        received = shifted
    }
}

private fun validatedKey(key: ByteArray): SecretKeySpec {
    if (key.size != BridgeMediaAead.KEY_SIZE) {
        throw MediaSecurityException("Media traffic key must contain ${BridgeMediaAead.KEY_SIZE} bytes")
    }
    return SecretKeySpec(key.copyOf(), "AES")
}

private fun requireEpoch(keyEpoch: Long) {
    if (keyEpoch !in 1..0xffff_ffffL) {
        throw MediaSecurityException("Media key epoch must be a non-zero u32")
    }
}

private fun cipher(
    mode: Int,
    key: SecretKeySpec,
    keyEpoch: Long,
    packetCounter: Long,
    associatedData: ByteArray,
): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
    init(mode, key, GCMParameterSpec(BridgeMediaAead.TAG_SIZE * 8, BridgeMediaAead.nonce(keyEpoch, packetCounter)))
    updateAAD(associatedData)
}
