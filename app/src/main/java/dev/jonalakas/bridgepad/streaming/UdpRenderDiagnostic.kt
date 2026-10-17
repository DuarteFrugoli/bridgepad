package dev.jonalakas.bridgepad.streaming

enum class UdpRecoveryDiagnosticMode(val nativeFlag: Int) {
    STRICT(0),
    RELAXED(1),
}

data class UdpPlaybackDiagnosticConfig(
    val mode: UdpRecoveryDiagnosticMode,
    val dropEveryNFrames: Int = 0,
)

/** Measurements from frames actually rendered on the Android Surface. */
data class UdpRenderDiagnosticSnapshot(
    val renderedFrames: Long = 0,
    val measuredMillis: Long = 0,
    val gapP95Millis: Long = 0,
    val gapP99Millis: Long = 0,
    val longestGapMillis: Long = 0,
    val gapsOver33Millis: Long = 0,
    val gapsOver50Millis: Long = 0,
    val gapsOver100Millis: Long = 0,
    val injectedDrops: Long = 0,
    val decoderFlushes: Long = 0,
    val queueOverflows: Long = 0,
)

/** Bounded histogram avoids retaining a timestamp for every frame in long tests. */
internal class UdpRenderDiagnostic {
    private val lock = Any()
    private val gapHistogram = LongArray(1_002)
    private var firstRenderNanos = 0L
    private var lastRenderNanos = 0L
    private var renderedFrames = 0L
    private var validGaps = 0L
    private var longestGapNanos = 0L
    private var gapsOver33Millis = 0L
    private var gapsOver50Millis = 0L
    private var gapsOver100Millis = 0L
    private var injectedDrops = 0L
    private var decoderFlushes = 0L
    private var queueOverflows = 0L

    fun onFrameRendered(renderNanos: Long) = synchronized(lock) {
        if (renderNanos <= 0) return@synchronized
        if (firstRenderNanos == 0L) firstRenderNanos = renderNanos
        if (lastRenderNanos > 0 && renderNanos > lastRenderNanos) {
            val gap = renderNanos - lastRenderNanos
            gapHistogram[(gap / 1_000_000).coerceAtMost(1_001).toInt()]++
            validGaps++
            longestGapNanos = maxOf(longestGapNanos, gap)
            if (gap > 33_333_333) gapsOver33Millis++
            if (gap > 50_000_000) gapsOver50Millis++
            if (gap > 100_000_000) gapsOver100Millis++
        }
        lastRenderNanos = maxOf(lastRenderNanos, renderNanos)
        renderedFrames++
    }

    fun onInjectedDrop() = synchronized(lock) { injectedDrops++ }
    fun onDecoderFlush() = synchronized(lock) { decoderFlushes++ }
    fun onQueueOverflow() = synchronized(lock) { queueOverflows++ }

    fun snapshot(): UdpRenderDiagnosticSnapshot = synchronized(lock) {
        UdpRenderDiagnosticSnapshot(
            renderedFrames = renderedFrames,
            measuredMillis = if (firstRenderNanos > 0)
                (lastRenderNanos - firstRenderNanos) / 1_000_000 else 0,
            gapP95Millis = percentile(95),
            gapP99Millis = percentile(99),
            longestGapMillis = longestGapNanos / 1_000_000,
            gapsOver33Millis = gapsOver33Millis,
            gapsOver50Millis = gapsOver50Millis,
            gapsOver100Millis = gapsOver100Millis,
            injectedDrops = injectedDrops,
            decoderFlushes = decoderFlushes,
            queueOverflows = queueOverflows,
        )
    }

    private fun percentile(percent: Int): Long {
        if (validGaps == 0L) return 0
        val rank = (validGaps * percent + 99) / 100
        var seen = 0L
        for (millis in gapHistogram.indices) {
            seen += gapHistogram[millis]
            if (seen >= rank) return millis.toLong()
        }
        return 1_001
    }
}
