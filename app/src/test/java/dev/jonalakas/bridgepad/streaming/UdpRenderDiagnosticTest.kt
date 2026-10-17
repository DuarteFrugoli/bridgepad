package dev.jonalakas.bridgepad.streaming

import org.junit.Assert.assertEquals
import org.junit.Test

class UdpRenderDiagnosticTest {
    @Test
    fun countsRenderedGapsAndDiagnosticEvents() {
        val diagnostic = UdpRenderDiagnostic()
        diagnostic.onFrameRendered(1_000_000_000)
        diagnostic.onFrameRendered(1_016_666_667)
        diagnostic.onFrameRendered(1_066_666_667)
        diagnostic.onFrameRendered(1_186_666_667)
        diagnostic.onInjectedDrop()
        diagnostic.onDecoderFlush()
        diagnostic.onQueueOverflow()

        val result = diagnostic.snapshot()
        assertEquals(4, result.renderedFrames)
        assertEquals(120, result.longestGapMillis)
        assertEquals(2, result.gapsOver33Millis)
        assertEquals(1, result.gapsOver50Millis)
        assertEquals(1, result.gapsOver100Millis)
        assertEquals(120, result.gapP95Millis)
        assertEquals(120, result.gapP99Millis)
        assertEquals(1, result.injectedDrops)
        assertEquals(1, result.decoderFlushes)
        assertEquals(1, result.queueOverflows)
    }

    @Test
    fun emptyAndOutOfOrderSamplesDoNotCreateFalseGaps() {
        val diagnostic = UdpRenderDiagnostic()
        assertEquals(0, diagnostic.snapshot().gapP99Millis)
        diagnostic.onFrameRendered(0)
        diagnostic.onFrameRendered(1_000_000_000)
        diagnostic.onFrameRendered(990_000_000)
        diagnostic.onFrameRendered(1_016_000_000)
        val result = diagnostic.snapshot()
        assertEquals(3, result.renderedFrames)
        assertEquals(16, result.gapP99Millis)
        assertEquals(0, result.gapsOver33Millis)
    }
}
