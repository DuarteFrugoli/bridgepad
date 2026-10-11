package dev.jonalakas.bridgepad.streaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import dev.jonalakas.bridgepad.transport.network.NetworkAuthenticationException
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaSignalingClient
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** QUIC DATAGRAM media with hardware H.264 decoding and system Opus audio. */
class AndroidQuicMediaClient(
    private val requestProvider: () -> NetworkMediaRequest?,
    surface: Surface,
    private val onStatus: (NetworkMediaStatus) -> Unit,
) : AutoCloseable, QuicFrameCallback {
    private val stopping = AtomicBoolean(false)
    private val runId = nextRunId.incrementAndGet()
    private val videoQueue = ArrayBlockingQueue<VideoFrame>(8)
    private val audioQueue = ArrayBlockingQueue<AudioFrame>(16)
    private val needKeyframe = AtomicBoolean(true)
    @Volatile private var videoSurface: Surface? = surface
    @Volatile private var signaling: NetworkMediaSignalingClient? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var videoWorker: Thread? = null
    @Volatile private var audioWorker: Thread? = null

    fun start() {
        onStatus(NetworkMediaStatus.Connecting)
        worker = Thread(::runSession, "BridgePad-quic-media").apply {
            isDaemon = true
            start()
        }
    }

    /** A display rotation replaces the Surface, but must not restart QUIC or audio. */
    fun setVideoSurface(surface: Surface?) {
        if (videoSurface === surface) return
        videoSurface = surface
    }

    fun detachVideoSurface(surface: Surface) {
        if (videoSurface === surface) setVideoSurface(null)
    }

    private fun runSession() {
        try {
            val localSignaling = prepareSignalingOnCurrentRoute()
            val negotiation = localSignaling.startQuic()
            videoWorker = Thread(
                { decodeVideo(negotiation.format) },
                "BridgePad-h264-decode",
            ).apply { isDaemon = true; start() }
            audioWorker = Thread(::decodeAudio, "BridgePad-opus-decode").apply {
                isDaemon = true
                start()
            }
            if (stopping.get()) return
            val result = MediaTransportNative.runQuicPlayable(
                runId,
                negotiation.host,
                negotiation.port,
                negotiation.certificateSha256,
                negotiation.token,
                negotiation.sessionId,
                this,
            )
            if (!stopping.get() && result != "OK") {
                onStatus(NetworkMediaStatus.Failed(result.removePrefix("ERR\t")))
            }
        } catch (error: Throwable) {
            if (!stopping.get()) {
                onStatus(NetworkMediaStatus.Failed(error.message ?: "QUIC media session failed"))
            }
        } finally {
            stopping.set(true)
            videoWorker?.interrupt()
            audioWorker?.interrupt()
            signaling?.close()
            signaling = null
        }
    }

    private fun prepareSignalingOnCurrentRoute(): NetworkMediaSignalingClient {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var lastFailure: Throwable? = null
        while (!stopping.get() && System.nanoTime() < deadline) {
            val request = requestProvider()
            if (request != null) {
                val candidate = NetworkMediaSignalingClient(request)
                signaling = candidate
                try {
                    candidate.prepare()
                    return candidate
                } catch (error: Throwable) {
                    candidate.close()
                    if (signaling === candidate) signaling = null
                    if (!error.isTransientRouteFailure()) throw error
                    lastFailure = error
                }
            }
            Thread.sleep(500)
        }
        throw lastFailure ?: IllegalStateException("Desktop is unavailable on the current route")
    }

    private fun Throwable.isTransientRouteFailure(): Boolean {
        val causes = generateSequence(this) { it.cause }.toList()
        if (causes.any { it is CertificateException || it is NetworkAuthenticationException }) {
            return false
        }
        return causes.any { it is IOException }
    }

    override fun onVideoFrame(annexB: ByteArray, presentationMicros: Long, keyframe: Boolean) {
        if (stopping.get() || (needKeyframe.get() && !keyframe)) return
        if (keyframe) needKeyframe.set(false)
        val frame = VideoFrame(annexB, presentationMicros, keyframe)
        if (!videoQueue.offer(frame)) {
            videoQueue.clear()
            needKeyframe.set(!keyframe)
            if (keyframe) videoQueue.offer(frame)
        }
    }

    override fun onAudioFrame(opus: ByteArray, presentationMicros: Long) {
        if (stopping.get()) return
        if (!audioQueue.offer(AudioFrame(opus, presentationMicros))) {
            audioQueue.poll()
            audioQueue.offer(AudioFrame(opus, presentationMicros))
        }
    }

    private fun decodeVideo(format: VideoFormat) {
        var firstFrame = true
        while (!stopping.get()) {
            val initialSurface = videoSurface
            if (initialSurface == null) {
                try {
                    Thread.sleep(10)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            var outputSurface = initialSurface
            var restartDecoder = false
            val codec = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (error: Throwable) {
                fail("H.264: ${error.message}")
                return
            }
            try {
                codec.configure(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, format.width, format.height),
                    initialSurface,
                    null,
                    0,
                )
                codec.start()
                val info = MediaCodec.BufferInfo()
                while (!stopping.get()) {
                    val target = videoSurface
                    if (target != null && target !== outputSurface) {
                        try {
                            codec.setOutputSurface(target)
                            outputSurface = target
                        } catch (_: Throwable) {
                            // Some hardware decoders cannot switch Surfaces while running.
                            restartDecoder = true
                            break
                        }
                    }
                    val frame = videoQueue.poll(10, TimeUnit.MILLISECONDS)
                    if (frame != null) {
                        val inputIndex = codec.dequeueInputBuffer(5_000)
                        if (inputIndex >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(inputIndex))
                            if (frame.bytes.size <= input.capacity()) {
                                input.clear()
                                input.put(frame.bytes)
                                codec.queueInputBuffer(
                                    inputIndex, 0, frame.bytes.size,
                                    frame.presentationMicros.coerceAtLeast(0), 0,
                                )
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                                needKeyframe.set(true)
                            }
                        }
                    }
                    while (!stopping.get()) {
                        val outputIndex = codec.dequeueOutputBuffer(info, 0)
                        if (outputIndex < 0) break
                        codec.releaseOutputBuffer(outputIndex, videoSurface === outputSurface)
                        if (firstFrame && info.size > 0) {
                            firstFrame = false
                            onStatus(NetworkMediaStatus.Active(format))
                        }
                    }
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Throwable) {
                if (videoSurface === outputSurface && !stopping.get()) {
                    fail("H.264: ${error.message}")
                    return
                }
                restartDecoder = true
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            if (restartDecoder && !stopping.get()) {
                videoQueue.clear()
                needKeyframe.set(true)
            }
        }
    }

    private fun decodeAudio() {
        val codec = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        } catch (error: Throwable) {
            fail("Opus: ${error.message}")
            return
        }
        var track: AudioTrack? = null
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHeader()))
            format.setByteBuffer("csd-1", nativeLongBuffer(0))
            format.setByteBuffer("csd-2", nativeLongBuffer(0))
            codec.configure(format, null, null, 0)
            codec.start()
            val minBuffer = AudioTrack.getMinBufferSize(
                48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "No stereo audio output is available" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, 16_384))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val info = MediaCodec.BufferInfo()
            while (!stopping.get()) {
                val frame = audioQueue.poll(10, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val inputIndex = codec.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        input.put(frame.bytes)
                        codec.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.presentationMicros, 0)
                    }
                }
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex < 0) break
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        output.get(pcm)
                        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            fail("Opus: ${error.message}")
        } finally {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            track?.release()
            runCatching { codec.stop() }
            codec.release()
        }
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        MediaTransportNative.cancelPlayable(runId)
        signaling?.close()
        videoWorker?.interrupt()
        audioWorker?.interrupt()
    }

    private fun fail(detail: String) {
        if (stopping.compareAndSet(false, true)) {
            onStatus(NetworkMediaStatus.Failed(detail))
            MediaTransportNative.cancelPlayable(runId)
            signaling?.close()
        }
    }

    private data class VideoFrame(val bytes: ByteArray, val presentationMicros: Long, val keyframe: Boolean)
    private data class AudioFrame(val bytes: ByteArray, val presentationMicros: Long)

    companion object {
        private val nextRunId = AtomicLong(System.currentTimeMillis().coerceAtLeast(1))

        private fun nativeLongBuffer(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply {
                putLong(value)
                flip()
            }

        private fun opusHeader(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII))
            .put(1)
            .put(2)
            .putShort(0)
            .putInt(48_000)
            .putShort(0)
            .put(0)
            .array()
    }
}
