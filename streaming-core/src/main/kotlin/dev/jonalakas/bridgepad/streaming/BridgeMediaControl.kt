package dev.jonalakas.bridgepad.streaming

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object MediaVideoCodecs {
    const val H264 = 1 shl 0
    const val HEVC = 1 shl 1
    const val AV1 = 1 shl 2
}

object MediaAudioCodecs {
    const val OPUS = 1 shl 0
}

object MediaColorFormats {
    const val BT709_SDR = 1 shl 0
    const val HDR10 = 1 shl 1
}

object MediaCapabilities {
    const val LOW_LATENCY_DECODE = 1 shl 0
    const val REED_SOLOMON_FEC = 1 shl 1
    const val DEADLINE_RETRANSMIT = 1 shl 2
    const val HARDWARE_DECODE = 1 shl 3
}

object MediaFeedbackFlags {
    const val REQUEST_KEYFRAME = 1 shl 0
    const val CONGESTED = 1 shl 1
    const val DECODER_STARVED = 1 shl 2
    const val KNOWN = REQUEST_KEYFRAME or CONGESTED or DECODER_STARVED
}

data class MediaControlHeader(
    val minorVersion: Int,
    val sessionId: Long,
    val requestId: Long,
)

data class MediaControlOffer(
    val minimumMinorVersion: Int,
    val maximumMinorVersion: Int,
    val videoCodecs: Int,
    val audioCodecs: Int,
    val colorFormats: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFramesPerSecond: Int,
    val maxDatagramSize: Int,
    val minBitrateBitsPerSecond: Long,
    val initialBitrateBitsPerSecond: Long,
    val maxBitrateBitsPerSecond: Long,
    val maxReorderMicros: Long,
    val capabilities: Long,
    val routeId: Long,
    val clockOriginMicros: Long,
    val audioSampleRates: Int,
    val audioPacketDurations: Int,
    val maxAudioChannels: Int,
)

enum class MediaTransportSecurity(val code: Int) {
    TRANSPORT_TLS13(1),
    SESSION_AEAD(2),
    ;

    companion object {
        fun fromCode(code: Int): MediaTransportSecurity = entries.firstOrNull { it.code == code }
            ?: throw MediaProtocolException("Unknown media security mode: $code")
    }
}

data class MediaControlAnswer(
    val selectedMinorVersion: Int,
    val videoCodec: Int,
    val audioCodec: Int,
    val colorFormat: Int,
    val videoProfile: Int,
    val videoLevel: Int,
    val audioChannels: Int,
    val audioPacketDurationMillis: Int,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val datagramSize: Int,
    val targetBitrateBitsPerSecond: Long,
    val minBitrateBitsPerSecond: Long,
    val maxBitrateBitsPerSecond: Long,
    val reorderWindowMicros: Long,
    val capabilities: Long,
    val videoStreamId: Long,
    val audioStreamId: Long,
    val routeId: Long,
    val clockOriginMicros: Long,
    val keyEpoch: Long,
    val security: MediaTransportSecurity,
)

data class MediaReceiverFeedback(
    val streamId: Long,
    val highestSequence: Long,
    val lastCompleteFrame: Long,
    val lastPresentedFrame: Long,
    val receivedPackets: Long,
    val lostPackets: Long,
    val latePackets: Long,
    val reorderedPackets: Long,
    val fecRecoveredPackets: Long,
    val receiveBitrateBitsPerSecond: Long,
    val roundTripMicros: Long,
    val jitterMicros: Long,
    val assemblyMicros: Long,
    val decodeMicros: Long,
    val presentationMicros: Long,
    val requestedBitrateBitsPerSecond: Long,
    val receiverQueueDepth: Int,
    val flags: Int,
)

data class MediaKeyframeRequest(
    val streamId: Long,
    val lastGoodFrame: Long,
    val reason: Int,
)

enum class MediaStopReason(val code: Int) {
    USER_REQUEST(0),
    SOURCE_ENDED(1),
    TRANSPORT_LOST(2),
    PROTOCOL_ERROR(3),
    REPLACED_BY_NEW_SESSION(4),
    ;

    companion object {
        fun fromCode(code: Int): MediaStopReason = entries.firstOrNull { it.code == code }
            ?: throw MediaProtocolException("Unknown media stop reason: $code")
    }
}

enum class MediaStopScope(val code: Int) {
    ALL(0),
    VIDEO(1),
    AUDIO(2),
    ;

    companion object {
        fun fromCode(code: Int): MediaStopScope = entries.firstOrNull { it.code == code }
            ?: throw MediaProtocolException("Unknown media stop scope: $code")
    }
}

sealed interface MediaControlMessage {
    data class Offer(val value: MediaControlOffer) : MediaControlMessage
    data class Answer(val value: MediaControlAnswer) : MediaControlMessage
    data class Feedback(val value: MediaReceiverFeedback) : MediaControlMessage
    data class KeyframeRequest(val value: MediaKeyframeRequest) : MediaControlMessage
    data class Stop(val reason: MediaStopReason, val scope: MediaStopScope) : MediaControlMessage
    data object StopAck : MediaControlMessage
}

data class MediaControlPacket(
    val header: MediaControlHeader,
    val message: MediaControlMessage,
)

object BridgeMediaControlCodec {
    private const val MAGIC = 0x42504d43 // BPMC
    private const val MAJOR_VERSION = 1
    const val CURRENT_MINOR_VERSION = 0
    const val HEADER_SIZE = 28
    const val MAX_PAYLOAD_SIZE = 4_096

    fun encode(packet: MediaControlPacket): ByteArray {
        validateHeader(packet.header)
        validateMessage(packet.message)
        val payload = encodePayload(packet.message)
        if (payload.size > MAX_PAYLOAD_SIZE) {
            throw MediaProtocolException("Media control payload exceeds $MAX_PAYLOAD_SIZE bytes")
        }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(MAGIC)
                put(MAJOR_VERSION.toByte())
                put(packet.header.minorVersion.toByte())
                put(messageKind(packet.message).toByte())
                put(HEADER_SIZE.toByte())
                putShort(0)
                putShort(0)
                putLong(packet.header.sessionId)
                putInt(packet.header.requestId.toInt())
                putShort(payload.size.toShort())
                putShort(0)
                put(payload)
            }
            .array()
    }

    fun decode(bytes: ByteArray): MediaControlPacket {
        if (bytes.size < HEADER_SIZE) throw MediaProtocolException("Media control message is truncated")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC) throw MediaProtocolException("Invalid media control magic")
        val major = input.u8()
        if (major != MAJOR_VERSION) throw MediaProtocolException("Unsupported media major version: $major")
        val minor = input.u8()
        val kind = input.u8()
        val headerSize = input.u8()
        if (headerSize < HEADER_SIZE || headerSize > bytes.size) {
            throw MediaProtocolException("Invalid media control header size: $headerSize")
        }
        if (input.u16() != 0 || input.u16() != 0) {
            throw MediaProtocolException("Unsupported media control flags or reserved field")
        }
        val header = MediaControlHeader(
            minorVersion = minor,
            sessionId = input.long,
            requestId = input.u32(),
        )
        val payloadSize = input.u16()
        if (input.u16() != 0) throw MediaProtocolException("Reserved media control field is not zero")
        validateHeader(header)
        if (payloadSize > MAX_PAYLOAD_SIZE || bytes.size != headerSize + payloadSize) {
            throw MediaProtocolException("Invalid media control payload length")
        }
        input.position(headerSize)
        val payload = ByteArray(payloadSize)
        input.get(payload)
        val message = decodePayload(kind, payload)
        validateMessage(message)
        return MediaControlPacket(header, message)
    }

    fun selectMinorVersion(
        localMinimum: Int,
        localMaximum: Int,
        remoteMinimum: Int,
        remoteMaximum: Int,
    ): Int {
        val minimum = maxOf(localMinimum, remoteMinimum)
        val maximum = minOf(localMaximum, remoteMaximum)
        if (minimum > maximum) throw MediaProtocolException("Media minor versions do not overlap")
        return maximum
    }

    fun validateAnswerForOffer(offer: MediaControlOffer, answer: MediaControlAnswer) {
        validateOffer(offer)
        validateAnswer(answer)
        val videoBit = selectedBit(answer.videoCodec)
        val colorBit = selectedBit(answer.colorFormat)
        val audioBit = if (answer.audioCodec == 0) 0 else selectedBit(answer.audioCodec)
        val audioDurationBit = when (answer.audioPacketDurationMillis) {
            0 -> if (answer.audioCodec == 0) 0 else throw MediaProtocolException("Invalid audio mode")
            5 -> 1
            10 -> 1 shl 1
            20 -> 1 shl 2
            else -> throw MediaProtocolException("Invalid audio packet duration")
        }
        if (answer.selectedMinorVersion !in offer.minimumMinorVersion..offer.maximumMinorVersion ||
            offer.videoCodecs and videoBit == 0 || offer.colorFormats and colorBit == 0 ||
            offer.audioCodecs and audioBit != audioBit ||
            offer.audioPacketDurations and audioDurationBit != audioDurationBit ||
            answer.audioChannels > offer.maxAudioChannels || answer.width > offer.maxWidth ||
            answer.height > offer.maxHeight || answer.framesPerSecond > offer.maxFramesPerSecond ||
            answer.datagramSize > offer.maxDatagramSize ||
            answer.minBitrateBitsPerSecond < offer.minBitrateBitsPerSecond ||
            answer.maxBitrateBitsPerSecond > offer.maxBitrateBitsPerSecond ||
            answer.reorderWindowMicros > offer.maxReorderMicros ||
            answer.capabilities and offer.capabilities.inv() != 0L || answer.routeId != offer.routeId
        ) {
            throw MediaProtocolException("Media answer selected an unoffered value")
        }
    }

    private fun encodePayload(message: MediaControlMessage): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                when (message) {
                    is MediaControlMessage.Offer -> output.writeOffer(message.value)
                    is MediaControlMessage.Answer -> output.writeAnswer(message.value)
                    is MediaControlMessage.Feedback -> output.writeFeedback(message.value)
                    is MediaControlMessage.KeyframeRequest -> {
                        output.writeInt(message.value.streamId.toInt())
                        output.writeInt(message.value.lastGoodFrame.toInt())
                        output.writeByte(message.value.reason)
                        output.write(byteArrayOf(0, 0, 0))
                    }
                    is MediaControlMessage.Stop -> {
                        output.writeByte(message.reason.code)
                        output.writeByte(message.scope.code)
                        output.writeShort(0)
                    }
                    MediaControlMessage.StopAck -> Unit
                }
            }
            bytes.toByteArray()
        }

    private fun decodePayload(kind: Int, payload: ByteArray): MediaControlMessage {
        val input = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return when (kind) {
            1 -> MediaControlMessage.Offer(input.readOffer())
            2 -> MediaControlMessage.Answer(input.readAnswer())
            3 -> MediaControlMessage.Feedback(input.readFeedback())
            4 -> {
                input.requirePrefix(12)
                val value = MediaKeyframeRequest(input.u32(), input.u32(), input.u8())
                if (input.u8() != 0 || input.u8() != 0 || input.u8() != 0) {
                    throw MediaProtocolException("Reserved keyframe fields are not zero")
                }
                MediaControlMessage.KeyframeRequest(value)
            }
            5 -> {
                input.requirePrefix(4)
                val reason = MediaStopReason.fromCode(input.u8())
                val scope = MediaStopScope.fromCode(input.u8())
                if (input.u16() != 0) throw MediaProtocolException("Reserved stop field is not zero")
                MediaControlMessage.Stop(reason, scope)
            }
            6 -> MediaControlMessage.StopAck
            else -> throw MediaProtocolException("Unknown media control message: $kind")
        }
    }

    private fun DataOutputStream.writeOffer(value: MediaControlOffer) {
        writeByte(value.minimumMinorVersion)
        writeByte(value.maximumMinorVersion)
        writeShort(value.videoCodecs)
        writeShort(value.audioCodecs)
        writeShort(value.colorFormats)
        writeShort(value.maxWidth)
        writeShort(value.maxHeight)
        writeShort(value.maxFramesPerSecond)
        writeShort(value.maxDatagramSize)
        writeInt(value.minBitrateBitsPerSecond.toInt())
        writeInt(value.initialBitrateBitsPerSecond.toInt())
        writeInt(value.maxBitrateBitsPerSecond.toInt())
        writeInt(value.maxReorderMicros.toInt())
        writeInt(value.capabilities.toInt())
        writeLong(value.routeId)
        writeLong(value.clockOriginMicros)
        writeShort(value.audioSampleRates)
        writeByte(value.audioPacketDurations)
        writeByte(value.maxAudioChannels)
    }

    private fun DataOutputStream.writeAnswer(value: MediaControlAnswer) {
        write(
            byteArrayOf(
                value.selectedMinorVersion.toByte(), value.videoCodec.toByte(),
                value.audioCodec.toByte(), value.colorFormat.toByte(), value.videoProfile.toByte(),
                value.videoLevel.toByte(), value.audioChannels.toByte(),
                value.audioPacketDurationMillis.toByte(),
            ),
        )
        writeShort(value.width)
        writeShort(value.height)
        writeShort(value.framesPerSecond)
        writeShort(value.datagramSize)
        writeInt(value.targetBitrateBitsPerSecond.toInt())
        writeInt(value.minBitrateBitsPerSecond.toInt())
        writeInt(value.maxBitrateBitsPerSecond.toInt())
        writeInt(value.reorderWindowMicros.toInt())
        writeInt(value.capabilities.toInt())
        writeInt(value.videoStreamId.toInt())
        writeInt(value.audioStreamId.toInt())
        writeLong(value.routeId)
        writeLong(value.clockOriginMicros)
        writeInt(value.keyEpoch.toInt())
        writeByte(value.security.code)
        write(byteArrayOf(0, 0, 0))
    }

    private fun DataOutputStream.writeFeedback(value: MediaReceiverFeedback) {
        listOf(
            value.streamId, value.highestSequence, value.lastCompleteFrame,
            value.lastPresentedFrame, value.receivedPackets, value.lostPackets,
            value.latePackets, value.reorderedPackets, value.fecRecoveredPackets,
            value.receiveBitrateBitsPerSecond, value.roundTripMicros, value.jitterMicros,
            value.assemblyMicros, value.decodeMicros, value.presentationMicros,
            value.requestedBitrateBitsPerSecond,
        ).forEach { writeInt(it.toInt()) }
        writeShort(value.receiverQueueDepth)
        writeShort(value.flags)
    }

    private fun ByteBuffer.readOffer(): MediaControlOffer {
        requirePrefix(56)
        return MediaControlOffer(
            minimumMinorVersion = u8(), maximumMinorVersion = u8(), videoCodecs = u16(),
            audioCodecs = u16(), colorFormats = u16(), maxWidth = u16(), maxHeight = u16(),
            maxFramesPerSecond = u16(), maxDatagramSize = u16(),
            minBitrateBitsPerSecond = u32(), initialBitrateBitsPerSecond = u32(),
            maxBitrateBitsPerSecond = u32(), maxReorderMicros = u32(), capabilities = u32(),
            routeId = long, clockOriginMicros = long, audioSampleRates = u16(),
            audioPacketDurations = u8(), maxAudioChannels = u8(),
        )
    }

    private fun ByteBuffer.readAnswer(): MediaControlAnswer {
        requirePrefix(68)
        val result = MediaControlAnswer(
            selectedMinorVersion = u8(), videoCodec = u8(), audioCodec = u8(),
            colorFormat = u8(), videoProfile = u8(), videoLevel = u8(), audioChannels = u8(),
            audioPacketDurationMillis = u8(), width = u16(), height = u16(),
            framesPerSecond = u16(), datagramSize = u16(), targetBitrateBitsPerSecond = u32(),
            minBitrateBitsPerSecond = u32(), maxBitrateBitsPerSecond = u32(),
            reorderWindowMicros = u32(), capabilities = u32(), videoStreamId = u32(),
            audioStreamId = u32(), routeId = long, clockOriginMicros = long, keyEpoch = u32(),
            security = MediaTransportSecurity.fromCode(u8()),
        )
        if (u8() != 0 || u8() != 0 || u8() != 0) {
            throw MediaProtocolException("Reserved answer fields are not zero")
        }
        return result
    }

    private fun ByteBuffer.readFeedback(): MediaReceiverFeedback {
        requirePrefix(68)
        return MediaReceiverFeedback(
            streamId = u32(), highestSequence = u32(), lastCompleteFrame = u32(),
            lastPresentedFrame = u32(), receivedPackets = u32(), lostPackets = u32(),
            latePackets = u32(), reorderedPackets = u32(), fecRecoveredPackets = u32(),
            receiveBitrateBitsPerSecond = u32(), roundTripMicros = u32(), jitterMicros = u32(),
            assemblyMicros = u32(), decodeMicros = u32(), presentationMicros = u32(),
            requestedBitrateBitsPerSecond = u32(), receiverQueueDepth = u16(), flags = u16(),
        )
    }

    private fun validateHeader(header: MediaControlHeader) {
        if (header.minorVersion !in 0..0xff) throw MediaProtocolException("Minor version must fit in u8")
        if (header.sessionId <= 0) throw MediaProtocolException("Media session ID must be positive")
        requireU32(header.requestId, "request ID")
    }

    private fun validateMessage(message: MediaControlMessage) {
        when (message) {
            is MediaControlMessage.Offer -> validateOffer(message.value)
            is MediaControlMessage.Answer -> validateAnswer(message.value)
            is MediaControlMessage.Feedback -> {
                listOf(
                    message.value.streamId, message.value.highestSequence,
                    message.value.lastCompleteFrame, message.value.lastPresentedFrame,
                    message.value.receivedPackets, message.value.lostPackets,
                    message.value.latePackets, message.value.reorderedPackets,
                    message.value.fecRecoveredPackets,
                    message.value.receiveBitrateBitsPerSecond, message.value.roundTripMicros,
                    message.value.jitterMicros, message.value.assemblyMicros,
                    message.value.decodeMicros, message.value.presentationMicros,
                    message.value.requestedBitrateBitsPerSecond,
                ).forEach { requireU32(it, "feedback field") }
                requireU16(message.value.receiverQueueDepth, "receiver queue depth")
                requireU16(message.value.flags, "feedback flags")
                if (message.value.streamId == 0L || message.value.flags and MediaFeedbackFlags.KNOWN.inv() != 0) {
                    throw MediaProtocolException("Invalid receiver feedback")
                }
            }
            is MediaControlMessage.KeyframeRequest -> {
                requireU32(message.value.streamId, "stream ID")
                requireU32(message.value.lastGoodFrame, "last good frame")
                requireU8(message.value.reason, "keyframe reason")
                if (message.value.streamId == 0L) throw MediaProtocolException("Stream ID must not be zero")
            }
            is MediaControlMessage.Stop, MediaControlMessage.StopAck -> Unit
        }
    }

    private fun validateOffer(value: MediaControlOffer) {
        if (value.minimumMinorVersion !in 0..0xff || value.maximumMinorVersion !in 0..0xff ||
            value.minimumMinorVersion > value.maximumMinorVersion || value.videoCodecs == 0 ||
            value.colorFormats == 0 || value.maxWidth <= 0 || value.maxHeight <= 0 ||
            value.maxFramesPerSecond <= 0 || value.maxDatagramSize !in 576..1_200 ||
            value.minBitrateBitsPerSecond <= 0 ||
            value.minBitrateBitsPerSecond > value.initialBitrateBitsPerSecond ||
            value.initialBitrateBitsPerSecond > value.maxBitrateBitsPerSecond ||
            value.routeId <= 0 || value.clockOriginMicros < 0 ||
            value.audioCodecs != 0 && (value.audioSampleRates == 0 ||
                value.audioPacketDurations == 0 || value.maxAudioChannels == 0) ||
            value.audioCodecs == 0 && (value.audioSampleRates != 0 ||
                value.audioPacketDurations != 0 || value.maxAudioChannels != 0)
        ) {
            throw MediaProtocolException("Invalid media offer")
        }
        listOf(
            value.minBitrateBitsPerSecond, value.initialBitrateBitsPerSecond,
            value.maxBitrateBitsPerSecond, value.maxReorderMicros, value.capabilities,
        ).forEach { requireU32(it, "offer field") }
        listOf(
            value.videoCodecs, value.audioCodecs, value.colorFormats, value.maxWidth,
            value.maxHeight, value.maxFramesPerSecond, value.maxDatagramSize,
            value.audioSampleRates,
        ).forEach { requireU16(it, "offer field") }
        requireU8(value.audioPacketDurations, "audio packet durations")
        requireU8(value.maxAudioChannels, "maximum audio channels")
    }

    private fun validateAnswer(value: MediaControlAnswer) {
        if (value.selectedMinorVersion !in 0..CURRENT_MINOR_VERSION || value.videoCodec == 0 ||
            value.colorFormat == 0 || value.width <= 0 || value.height <= 0 ||
            value.framesPerSecond <= 0 || value.datagramSize !in 576..1_200 ||
            value.minBitrateBitsPerSecond <= 0 ||
            value.minBitrateBitsPerSecond > value.targetBitrateBitsPerSecond ||
            value.targetBitrateBitsPerSecond > value.maxBitrateBitsPerSecond ||
            value.videoStreamId == 0L || value.routeId <= 0 || value.clockOriginMicros < 0 ||
            value.audioCodec != 0 && (value.audioStreamId == 0L || value.audioChannels == 0 ||
                value.audioPacketDurationMillis == 0) ||
            value.audioCodec == 0 && (value.audioStreamId != 0L || value.audioChannels != 0 ||
                value.audioPacketDurationMillis != 0)
        ) {
            throw MediaProtocolException("Invalid media answer")
        }
        listOf(
            value.targetBitrateBitsPerSecond, value.minBitrateBitsPerSecond,
            value.maxBitrateBitsPerSecond, value.reorderWindowMicros, value.capabilities,
            value.videoStreamId, value.audioStreamId, value.keyEpoch,
        ).forEach { requireU32(it, "answer field") }
        listOf(
            value.width, value.height, value.framesPerSecond, value.datagramSize,
        ).forEach { requireU16(it, "answer field") }
        listOf(
            value.selectedMinorVersion, value.videoCodec, value.audioCodec, value.colorFormat,
            value.videoProfile, value.videoLevel, value.audioChannels,
            value.audioPacketDurationMillis,
        ).forEach { requireU8(it, "answer field") }
    }

    private fun messageKind(message: MediaControlMessage): Int = when (message) {
        is MediaControlMessage.Offer -> 1
        is MediaControlMessage.Answer -> 2
        is MediaControlMessage.Feedback -> 3
        is MediaControlMessage.KeyframeRequest -> 4
        is MediaControlMessage.Stop -> 5
        MediaControlMessage.StopAck -> 6
    }

    private fun ByteBuffer.requirePrefix(size: Int) {
        if (remaining() < size) throw MediaProtocolException("Media control payload is truncated")
    }

    private fun ByteBuffer.u8(): Int = get().toInt() and 0xff
    private fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xffff_ffffL

    private fun requireU32(value: Long, label: String) {
        if (value !in 0..0xffff_ffffL) throw MediaProtocolException("$label must fit in u32")
    }

    private fun requireU8(value: Int, label: String) {
        if (value !in 0..0xff) throw MediaProtocolException("$label must fit in u8")
    }

    private fun requireU16(value: Int, label: String) {
        if (value !in 0..0xffff) throw MediaProtocolException("$label must fit in u16")
    }

    private fun selectedBit(value: Int): Int {
        if (value !in 1..16) throw MediaProtocolException("Selected media code is invalid")
        return 1 shl (value - 1)
    }
}
