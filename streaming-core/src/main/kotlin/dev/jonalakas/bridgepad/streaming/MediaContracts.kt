package dev.jonalakas.bridgepad.streaming

/** Monotonic timestamps are relative to one stream and never use wall-clock time. */
fun interface MediaClock {
    fun nowMicros(): Long
}

enum class VideoCodec {
    RAW_RGB565,
    H264,
}

enum class AudioCodec {
    PCM_S16LE,
    OPUS,
}

data class VideoFormat(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val framesPerSecond: Int,
    val targetBitrateBitsPerSecond: Int,
) {
    init {
        require(width > 0 && height > 0)
        require(framesPerSecond > 0)
        require(targetBitrateBitsPerSecond > 0)
    }
}

data class CapturedVideoFrame(
    val frameId: Long,
    val presentationTimestampMicros: Long,
    val width: Int,
    val height: Int,
    val rgb888: ByteArray,
)

data class EncodedVideoFrame(
    val frameId: Long,
    val presentationTimestampMicros: Long,
    val format: VideoFormat,
    val keyframe: Boolean,
    val payload: ByteArray,
    val generationMicros: Long,
    val encodeMicros: Long,
)

data class DecodedVideoFrame(
    val frameId: Long,
    val presentationTimestampMicros: Long,
    val width: Int,
    val height: Int,
    val rgb565: ByteArray,
    val generationMicros: Long,
    val encodeMicros: Long,
    val transportMicros: Long,
    val decodeMicros: Long,
    val decodedAtNanos: Long,
)

data class MediaFeedback(
    val lastPresentedFrameId: Long,
    val lostFrames: Long,
    val receiveBitrateBitsPerSecond: Int,
    val decodeMicros: Long,
    val presentationMicros: Long,
    val requestedBitrateBitsPerSecond: Int,
    val keyframeRequested: Boolean,
)

data class MediaPipelineMetrics(
    val generationMicros: Long = 0,
    val encodeMicros: Long = 0,
    val networkEstimateMicros: Long = 0,
    val decodeMicros: Long = 0,
    val presentationMicros: Long = 0,
    val receivedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val receivedBytes: Long = 0,
)

interface VideoCaptureSource : AutoCloseable {
    fun capture(): CapturedVideoFrame
}

interface VideoEncoder : AutoCloseable {
    val outputFormat: VideoFormat
    fun encode(frame: CapturedVideoFrame, forceKeyframe: Boolean = false): EncodedVideoFrame
    fun setTargetBitrate(bitsPerSecond: Int)
}

interface MediaTransport : AutoCloseable {
    fun sendVideo(frame: EncodedVideoFrame)
    fun pollFeedback(): MediaFeedback?
}

interface VideoDecoder : AutoCloseable {
    fun decode(frame: EncodedVideoFrame): DecodedVideoFrame
}

interface AudioCaptureSource : AutoCloseable {
    fun read(target: ByteArray): Int
}

interface AudioEncoder : AutoCloseable {
    val codec: AudioCodec
    fun encode(pcm: ByteArray, presentationTimestampMicros: Long): ByteArray
}

interface AudioDecoder : AutoCloseable {
    val codec: AudioCodec
    fun decode(encoded: ByteArray, presentationTimestampMicros: Long): ByteArray
}

interface AudioRenderer : AutoCloseable {
    fun render(pcm: ByteArray, presentationTimestampMicros: Long)
}

/** Foundation decoder. Production video replaces this with Android MediaCodec. */
class RawRgb565Decoder(
    private val clockNanos: () -> Long = System::nanoTime,
) : VideoDecoder {
    override fun decode(frame: EncodedVideoFrame): DecodedVideoFrame {
        require(frame.format.codec == VideoCodec.RAW_RGB565)
        require(frame.payload.size == frame.format.width * frame.format.height * 2)
        val started = clockNanos()
        val copied = frame.payload.copyOf()
        val finished = clockNanos()
        return DecodedVideoFrame(
            frameId = frame.frameId,
            presentationTimestampMicros = frame.presentationTimestampMicros,
            width = frame.format.width,
            height = frame.format.height,
            rgb565 = copied,
            generationMicros = frame.generationMicros,
            encodeMicros = frame.encodeMicros,
            transportMicros = 0,
            decodeMicros = ((finished - started) / 1_000).coerceAtLeast(0),
            decodedAtNanos = finished,
        )
    }

    override fun close() = Unit
}
