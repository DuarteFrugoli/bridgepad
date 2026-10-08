package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFrameAssemblerTest {
    @Test
    fun `reordered packets form one complete frame`() {
        val packets = packets(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5))
        val assembler = MediaFrameAssembler(7, 9, deadlineMicros = 10_000, maxInFlightFrames = 2)
        assertNull(assembler.push(packets[2], 100))
        assertNull(assembler.push(packets[0], 200))
        val frame = assembler.push(packets[1], 300)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frame?.payload)
        assertEquals(1, assembler.metrics.completedFrames)
    }

    @Test
    fun `duplicate and expired frames remain bounded`() {
        val packets = packets(byteArrayOf(1), byteArrayOf(2))
        val assembler = MediaFrameAssembler(7, 9, deadlineMicros = 1_000, maxInFlightFrames = 1)
        assertNull(assembler.push(packets[0], 100))
        assertNull(assembler.push(packets[0], 200))
        assembler.expire(1_100)
        assertEquals(1, assembler.metrics.duplicatePackets)
        assertEquals(1, assembler.metrics.expiredFrames)
        assertNull(assembler.push(packets[1], 1_200))
        assembler.expire(2_200)
        assertEquals(2, assembler.metrics.duplicatePackets)
        assertEquals(1, assembler.metrics.expiredFrames)
    }

    @Test
    fun `late duplicate cannot resurrect a completed frame`() {
        val packets = packets(byteArrayOf(1), byteArrayOf(2))
        val assembler = MediaFrameAssembler(7, 9, deadlineMicros = 1_000, maxInFlightFrames = 1)
        assertNull(assembler.push(packets[0], 100))
        assembler.push(packets[1], 200)
        assertNull(assembler.push(packets[0], 300))
        assembler.expire(1_300)
        assertEquals(1, assembler.metrics.completedFrames)
        assertEquals(1, assembler.metrics.duplicatePackets)
        assertEquals(0, assembler.metrics.expiredFrames)
    }

    private fun packets(vararg payloads: ByteArray): List<ByteArray> {
        val size = payloads.sumOf(ByteArray::size)
        return payloads.mapIndexed { index, payload ->
            var flags = MediaPacketFlags.KEYFRAME
            if (index == 0) flags = flags or MediaPacketFlags.FRAME_START
            if (index == payloads.lastIndex) flags = flags or MediaPacketFlags.FRAME_END
            BridgeMediaDatagramCodec.encode(
                MediaDatagram(
                    MediaDatagramHeader(
                        kind = MediaPacketKind.VIDEO,
                        flags = flags,
                        sessionId = 7,
                        streamId = 9,
                        sequence = index.toLong(),
                        frameId = 1,
                        presentationTimestampMicros = 1_000,
                        framePacketIndex = index,
                        framePacketCount = payloads.size,
                        originalFrameBytes = size.toLong(),
                    ),
                    payload,
                ),
            )
        }
    }
}
