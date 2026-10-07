package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeMediaControlCodecTest {
    @Test
    fun `Rust offer golden vector round trips`() {
        val packet = MediaControlPacket(header(), MediaControlMessage.Offer(offer()))
        val expected = OFFER_VECTOR.hexToBytes()
        assertArrayEquals(expected, BridgeMediaControlCodec.encode(packet))
        assertEquals(packet, BridgeMediaControlCodec.decode(expected))
    }

    @Test
    fun `newer minor version and trailing offer fields preserve known prefix`() {
        val bytes = BridgeMediaControlCodec.encode(
            MediaControlPacket(header(), MediaControlMessage.Offer(offer())),
        ).toMutableList()
        bytes[5] = 4
        bytes[24] = 0
        bytes[25] = 58
        bytes += 0xaa.toByte()
        bytes += 0xbb.toByte()
        val decoded = BridgeMediaControlCodec.decode(bytes.toByteArray())
        assertEquals(4, decoded.header.minorVersion)
        assertEquals(MediaControlMessage.Offer(offer()), decoded.message)
    }

    @Test
    fun `feedback stop and acknowledgement round trip`() {
        val feedback = MediaControlMessage.Feedback(
            MediaReceiverFeedback(
                streamId = 1, highestSequence = 2, lastCompleteFrame = 3,
                lastPresentedFrame = 3, receivedPackets = 100, lostPackets = 1,
                latePackets = 2, reorderedPackets = 3, fecRecoveredPackets = 0,
                receiveBitrateBitsPerSecond = 7_000_000, roundTripMicros = 2_000,
                jitterMicros = 500, assemblyMicros = 800, decodeMicros = 2_500,
                presentationMicros = 1_000, requestedBitrateBitsPerSecond = 6_000_000,
                receiverQueueDepth = 1, flags = MediaFeedbackFlags.REQUEST_KEYFRAME,
            ),
        )
        listOf(
            feedback,
            MediaControlMessage.Stop(MediaStopReason.USER_REQUEST, MediaStopScope.ALL),
            MediaControlMessage.StopAck,
        ).forEach { message ->
            val packet = MediaControlPacket(header(), message)
            assertEquals(packet, BridgeMediaControlCodec.decode(BridgeMediaControlCodec.encode(packet)))
        }
    }

    @Test
    fun `incompatible minor ranges and unknown feedback flags are rejected`() {
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaControlCodec.selectMinorVersion(0, 1, 2, 3)
        }
        assertEquals(2, BridgeMediaControlCodec.selectMinorVersion(0, 3, 1, 2))
        val invalid = MediaControlMessage.Feedback(
            MediaReceiverFeedback(
                streamId = 1, highestSequence = 0, lastCompleteFrame = 0,
                lastPresentedFrame = 0, receivedPackets = 0, lostPackets = 0,
                latePackets = 0, reorderedPackets = 0, fecRecoveredPackets = 0,
                receiveBitrateBitsPerSecond = 0, roundTripMicros = 0, jitterMicros = 0,
                assemblyMicros = 0, decodeMicros = 0, presentationMicros = 0,
                requestedBitrateBitsPerSecond = 0, receiverQueueDepth = 0, flags = 0x8000,
            ),
        )
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaControlCodec.encode(MediaControlPacket(header(), invalid))
        }
    }

    @Test
    fun `answer cannot select a codec outside the offer`() {
        val valid = MediaControlAnswer(
            selectedMinorVersion = 0,
            videoCodec = 1,
            audioCodec = 1,
            colorFormat = 1,
            videoProfile = 100,
            videoLevel = 31,
            audioChannels = 2,
            audioPacketDurationMillis = 10,
            width = 1_280,
            height = 720,
            framesPerSecond = 60,
            datagramSize = 1_200,
            targetBitrateBitsPerSecond = 8_000_000,
            minBitrateBitsPerSecond = 500_000,
            maxBitrateBitsPerSecond = 12_000_000,
            reorderWindowMicros = 10_000,
            capabilities = 0x0f,
            videoStreamId = 1,
            audioStreamId = 2,
            routeId = offer().routeId,
            clockOriginMicros = 0,
            keyEpoch = 1,
            security = MediaTransportSecurity.TRANSPORT_TLS13,
        )
        BridgeMediaControlCodec.validateAnswerForOffer(offer(), valid)
        assertThrows(MediaProtocolException::class.java) {
            BridgeMediaControlCodec.validateAnswerForOffer(offer(), valid.copy(videoCodec = 2))
        }
    }

    private fun header() = MediaControlHeader(
        minorVersion = 0,
        sessionId = 0x0102_0304_0506_0708,
        requestId = 0x1112_1314,
    )

    private fun offer() = MediaControlOffer(
        minimumMinorVersion = 0,
        maximumMinorVersion = 0,
        videoCodecs = MediaVideoCodecs.H264,
        audioCodecs = MediaAudioCodecs.OPUS,
        colorFormats = MediaColorFormats.BT709_SDR,
        maxWidth = 1_280,
        maxHeight = 720,
        maxFramesPerSecond = 60,
        maxDatagramSize = 1_200,
        minBitrateBitsPerSecond = 500_000,
        initialBitrateBitsPerSecond = 8_000_000,
        maxBitrateBitsPerSecond = 12_000_000,
        maxReorderMicros = 10_000,
        capabilities = 0x0f,
        routeId = 0x2122_2324_2526_2728,
        clockOriginMicros = 0x3132_3334_3536_3738,
        audioSampleRates = 1,
        audioPacketDurations = 7,
        maxAudioChannels = 2,
    )

    private fun String.hexToBytes(): ByteArray =
        replace("\n", "").replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val OFFER_VECTOR =
            "42504d430100011c0000000001020304050607081112131400380000" +
                "0000000100010001050002d0003c04b00007a120007a120000b71b00" +
                "000027100000000f2122232425262728313233343536373800010702"
    }
}
