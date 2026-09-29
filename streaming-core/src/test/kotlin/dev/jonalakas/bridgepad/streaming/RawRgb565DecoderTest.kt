package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RawRgb565DecoderTest {
    @Test
    fun `decoder validates and copies a complete frame`() {
        val format = VideoFormat(VideoCodec.RAW_RGB565, 2, 1, 30, 1_000_000)
        val payload = byteArrayOf(0, 1, 2, 3)
        val decoded = RawRgb565Decoder { 10_000 }.decode(
            EncodedVideoFrame(7, 9, format, true, payload, 11, 12),
        )

        payload[0] = 99
        assertEquals(7, decoded.frameId)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), decoded.rgb565)
    }
}
