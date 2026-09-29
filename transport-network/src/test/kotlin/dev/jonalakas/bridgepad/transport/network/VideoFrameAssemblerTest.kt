package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.streaming.VideoCodec
import dev.jonalakas.bridgepad.streaming.VideoFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFrameAssemblerTest {
    private val format = VideoFormat(VideoCodec.RAW_RGB565, 2, 1, 20, 1_000_000)

    @Test
    fun `assembles ordered chunks into exactly one frame`() {
        val assembler = VideoFrameAssembler(format)

        assertNull(assembler.accept(chunk(frameId = 4, index = 0, data = byteArrayOf(1, 2))))
        val frame = requireNotNull(
            assembler.accept(chunk(frameId = 4, index = 1, data = byteArrayOf(3, 4))),
        )

        assertEquals(4, frame.frame.frameId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.frame.payload)
    }

    @Test
    fun `new frame discards an incomplete older frame`() {
        val assembler = VideoFrameAssembler(format)

        assertNull(assembler.accept(chunk(frameId = 4, index = 0, data = byteArrayOf(9, 9))))
        assertNull(assembler.accept(chunk(frameId = 5, index = 0, data = byteArrayOf(1, 2))))
        val frame = requireNotNull(
            assembler.accept(chunk(frameId = 5, index = 1, data = byteArrayOf(3, 4))),
        )

        assertEquals(5, frame.frame.frameId)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.frame.payload)
    }

    private fun chunk(frameId: Long, index: Int, data: ByteArray) = BridgeMessage.VideoChunk(
        frameId = frameId,
        presentationTimestampMicros = 100,
        keyframe = true,
        chunkIndex = index,
        chunkCount = 2,
        totalFrameBytes = 4,
        generationMicros = 10,
        encodeMicros = 20,
        data = data,
    )
}
