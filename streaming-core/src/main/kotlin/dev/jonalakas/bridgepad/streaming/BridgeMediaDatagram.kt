package dev.jonalakas.bridgepad.streaming

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaPacketKind(val code: Int) {
    VIDEO(1),
    AUDIO(2),
    ;

    companion object {
        fun fromCode(code: Int): MediaPacketKind = entries.firstOrNull { it.code == code }
            ?: throw MediaProtocolException("Unknown media packet kind: $code")
    }
}

object MediaPacketFlags {
    const val NONE = 0
    const val KEYFRAME = 1 shl 0
    const val CONFIG = 1 shl 1
    const val FRAME_START = 1 shl 2
    const val FRAME_END = 1 shl 3
    const val FEC_REPAIR = 1 shl 4
    const val DISCONTINUITY = 1 shl 5
    const val KNOWN = KEYFRAME or CONFIG or FRAME_START or FRAME_END or FEC_REPAIR or DISCONTINUITY
}

data class MediaDatagramHeader(
    val kind: MediaPacketKind,
    val flags: Int,
    val sessionId: Long,
    val streamId: Long,
    val sequence: Long,
    val frameId: Long,
    val presentationTimestampMicros: Long,
    val framePacketIndex: Int,
    val framePacketCount: Int,
    val originalFrameBytes: Long,
    val fecBlockIndex: Int = 0,
    val fecShardIndex: Int = 0,
    val fecSourceCount: Int = 0,
    val fecRepairCount: Int = 0,
)

data class MediaDatagram(
    val header: MediaDatagramHeader,
    val payload: ByteArray,
)

class MediaProtocolException(message: String) : IllegalArgumentException(message)

object BridgeMediaDatagramCodec {
    private const val MAGIC = 0x42504d31 // BPM1
    private const val MAJOR_VERSION = 1
    private const val MINOR_VERSION = 0
    const val HEADER_SIZE = 56
    const val MAX_DATAGRAM_SIZE = 1_200
    const val MAX_PAYLOAD_SIZE = MAX_DATAGRAM_SIZE - HEADER_SIZE
    const val MAX_FRAME_BYTES = 16 * 1_024 * 1_024L
    const val REPAIR_PACKET_INDEX = 0xffff

    fun encode(datagram: MediaDatagram): ByteArray {
        validate(datagram.header, datagram.payload.size)
        return ByteBuffer.allocate(HEADER_SIZE + datagram.payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(MAGIC)
                put(MAJOR_VERSION.toByte())
                put(MINOR_VERSION.toByte())
                put(datagram.header.kind.code.toByte())
                put(HEADER_SIZE.toByte())
                putShort(datagram.header.flags.toShort())
                putShort(0)
                putLong(datagram.header.sessionId)
                putInt(datagram.header.streamId.toInt())
                putInt(datagram.header.sequence.toInt())
                putInt(datagram.header.frameId.toInt())
                putLong(datagram.header.presentationTimestampMicros)
                putShort(datagram.header.framePacketIndex.toShort())
                putShort(datagram.header.framePacketCount.toShort())
                putInt(datagram.header.originalFrameBytes.toInt())
                putShort(datagram.header.fecBlockIndex.toShort())
                putShort(datagram.header.fecShardIndex.toShort())
                put(datagram.header.fecSourceCount.toByte())
                put(datagram.header.fecRepairCount.toByte())
                putShort(0)
                put(datagram.payload)
            }
            .array()
    }

    fun decode(bytes: ByteArray): MediaDatagram {
        if (bytes.size < HEADER_SIZE) {
            throw MediaProtocolException("Media datagram is shorter than $HEADER_SIZE bytes")
        }
        if (bytes.size > MAX_DATAGRAM_SIZE) {
            throw MediaProtocolException("Media datagram exceeds $MAX_DATAGRAM_SIZE bytes")
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC) throw MediaProtocolException("Invalid media datagram magic")
        val major = input.get().toInt() and 0xff
        val minor = input.get().toInt() and 0xff
        if (major != MAJOR_VERSION || minor != MINOR_VERSION) {
            throw MediaProtocolException("Unsupported media protocol version: $major.$minor")
        }
        val kind = MediaPacketKind.fromCode(input.get().toInt() and 0xff)
        val headerSize = input.get().toInt() and 0xff
        if (headerSize != HEADER_SIZE) {
            throw MediaProtocolException("Unsupported media header size: $headerSize")
        }
        val flags = input.short.toInt() and 0xffff
        if (flags and MediaPacketFlags.KNOWN.inv() != 0) {
            throw MediaProtocolException("Unknown media flags: $flags")
        }
        if (input.short.toInt() != 0) throw MediaProtocolException("Reserved media field is not zero")
        val header = MediaDatagramHeader(
            kind = kind,
            flags = flags,
            sessionId = input.long,
            streamId = input.int.toLong() and 0xffff_ffffL,
            sequence = input.int.toLong() and 0xffff_ffffL,
            frameId = input.int.toLong() and 0xffff_ffffL,
            presentationTimestampMicros = input.long,
            framePacketIndex = input.short.toInt() and 0xffff,
            framePacketCount = input.short.toInt() and 0xffff,
            originalFrameBytes = input.int.toLong() and 0xffff_ffffL,
            fecBlockIndex = input.short.toInt() and 0xffff,
            fecShardIndex = input.short.toInt() and 0xffff,
            fecSourceCount = input.get().toInt() and 0xff,
            fecRepairCount = input.get().toInt() and 0xff,
        )
        if (input.short.toInt() != 0) throw MediaProtocolException("Reserved media field is not zero")
        val payload = ByteArray(input.remaining())
        input.get(payload)
        validate(header, payload.size)
        return MediaDatagram(header, payload)
    }

    private fun validate(header: MediaDatagramHeader, payloadSize: Int) {
        if (payloadSize <= 0) throw MediaProtocolException("Media payload must not be empty")
        if (HEADER_SIZE + payloadSize > MAX_DATAGRAM_SIZE) {
            throw MediaProtocolException("Media datagram exceeds $MAX_DATAGRAM_SIZE bytes")
        }
        if (header.sessionId <= 0) throw MediaProtocolException("Session ID must be positive")
        requireUnsigned32(header.streamId, "stream ID")
        if (header.streamId == 0L) throw MediaProtocolException("Stream ID must not be zero")
        requireUnsigned32(header.sequence, "sequence")
        requireUnsigned32(header.frameId, "frame ID")
        if (header.presentationTimestampMicros < 0) {
            throw MediaProtocolException("Timestamp must not be negative")
        }
        if (header.framePacketCount !in 1..0xffff) {
            throw MediaProtocolException("Frame packet count is invalid")
        }
        requireUnsigned16(header.framePacketIndex, "frame packet index")
        if (header.originalFrameBytes !in 1..MAX_FRAME_BYTES) {
            throw MediaProtocolException("Original frame size is invalid")
        }
        requireUnsigned16(header.fecBlockIndex, "FEC block index")
        requireUnsigned16(header.fecShardIndex, "FEC shard index")
        requireUnsigned8(header.fecSourceCount, "FEC source count")
        requireUnsigned8(header.fecRepairCount, "FEC repair count")
        if (header.flags and MediaPacketFlags.KNOWN.inv() != 0) {
            throw MediaProtocolException("Unknown media flags: ${header.flags}")
        }

        val repair = header.flags and MediaPacketFlags.FEC_REPAIR != 0
        if (repair) {
            if (header.framePacketIndex != REPAIR_PACKET_INDEX) {
                throw MediaProtocolException("FEC repair must use the repair packet index")
            }
        } else if (header.framePacketIndex >= header.framePacketCount) {
            throw MediaProtocolException("Frame packet index is outside the frame")
        }
        val start = header.flags and MediaPacketFlags.FRAME_START != 0
        val end = header.flags and MediaPacketFlags.FRAME_END != 0
        if (repair && (start || end)) {
            throw MediaProtocolException("FEC repair cannot mark frame boundaries")
        }
        if (!repair && (start != (header.framePacketIndex == 0) ||
                end != (header.framePacketIndex + 1 == header.framePacketCount))
        ) {
            throw MediaProtocolException("Frame boundary flags do not match the packet index")
        }

        val fecDisabled = header.fecSourceCount == 0 && header.fecRepairCount == 0
        if (fecDisabled) {
            if (repair || header.fecBlockIndex != 0 || header.fecShardIndex != 0) {
                throw MediaProtocolException("FEC metadata is inconsistent")
            }
        } else {
            val shardCount = header.fecSourceCount + header.fecRepairCount
            if (header.fecSourceCount == 0 || header.fecRepairCount == 0 ||
                header.fecShardIndex >= shardCount ||
                repair != (header.fecShardIndex >= header.fecSourceCount)
            ) {
                throw MediaProtocolException("FEC metadata is inconsistent")
            }
        }
    }

    private fun requireUnsigned32(value: Long, label: String) {
        if (value !in 0..0xffff_ffffL) throw MediaProtocolException("$label must fit in u32")
    }

    private fun requireUnsigned16(value: Int, label: String) {
        if (value !in 0..0xffff) throw MediaProtocolException("$label must fit in u16")
    }

    private fun requireUnsigned8(value: Int, label: String) {
        if (value !in 0..0xff) throw MediaProtocolException("$label must fit in u8")
    }
}
