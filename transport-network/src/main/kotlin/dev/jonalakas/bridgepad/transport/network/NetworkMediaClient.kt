package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeCapabilities
import dev.jonalakas.bridgepad.protocol.BridgeCapability
import dev.jonalakas.bridgepad.protocol.BridgeMediaStopReason
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgeVideoCodec
import dev.jonalakas.bridgepad.streaming.DecodedVideoFrame
import dev.jonalakas.bridgepad.streaming.EncodedVideoFrame
import dev.jonalakas.bridgepad.streaming.MediaPipelineMetrics
import dev.jonalakas.bridgepad.streaming.RawRgb565Decoder
import dev.jonalakas.bridgepad.streaming.VideoCodec
import dev.jonalakas.bridgepad.streaming.VideoFormat
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

data class NetworkMediaRequest(
    val host: String,
    val alternateHosts: List<String> = emptyList(),
    val port: Int = 39_393,
    val certificateSha256: String,
    val credentials: NetworkCredentials,
    val maxWidth: Int = 320,
    val maxHeight: Int = 180,
    val maxFramesPerSecond: Int = 20,
    val maxBitrateBitsPerSecond: Long = 24_000_000,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 3_000,
) {
    init {
        require(host.isNotBlank())
        require(port in 1..65_535)
        require(maxWidth in 1..0xffff && maxHeight in 1..0xffff)
        require(maxFramesPerSecond in 1..0xffff)
        require(maxBitrateBitsPerSecond in 1..0xffff_ffffL)
    }

    internal val endpointHosts: List<String>
        get() = (listOf(host) + alternateHosts).filter(String::isNotBlank).distinct()
}

sealed interface NetworkMediaStatus {
    data object Idle : NetworkMediaStatus
    data object Connecting : NetworkMediaStatus
    data class Active(val format: VideoFormat) : NetworkMediaStatus
    data object Stopped : NetworkMediaStatus
    data class Failed(val detail: String) : NetworkMediaStatus
}

/**
 * A media-only TLS connection. It has its own socket, reader and decoder queue,
 * so slow video cannot block the gamepad, pointer or keyboard connection.
 */
class NetworkMediaClient(
    private val request: NetworkMediaRequest,
    private val onStatus: (NetworkMediaStatus) -> Unit,
    private val onFrame: (DecodedVideoFrame) -> Unit,
    private val onMetrics: (MediaPipelineMetrics) -> Unit,
) {
    private data class QueuedFrame(
        val frame: EncodedVideoFrame,
        val transportMicros: Long,
    )

    private val stopping = AtomicBoolean(false)
    private val decodeQueue = ArrayBlockingQueue<QueuedFrame>(2)
    private val receivedFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val receivedBytes = AtomicLong(0)
    private val lastMetrics = AtomicReference(MediaPipelineMetrics())
    private val presentedFrames = AtomicReference<Map<Long, Long>>(emptyMap())
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var started = false

    fun start() {
        check(!started) { "A media client can only be started once" }
        started = true
        onStatus(NetworkMediaStatus.Connecting)
        Thread(::decodeLoop, "BridgePad-media-decoder").apply { isDaemon = true }.start()
        Thread(::networkLoop, "BridgePad-media-network").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopping.set(true)
        runCatching {
            socket?.let { connected ->
                writeBridgePacket(
                    connected,
                    sessionId = 0,
                    sequence = 0,
                    message = BridgeMessage.MediaStop(BridgeMediaStopReason.USER_REQUEST),
                )
            }
        }
        runCatching { socket?.close() }
    }

    fun markPresented(frameId: Long) {
        val decodedAt = presentedFrames.get()[frameId] ?: return
        val presentationMicros = ((System.nanoTime() - decodedAt) / 1_000).coerceAtLeast(0)
        updateMetrics(lastMetrics.get().copy(presentationMicros = presentationMicros))
        presentedFrames.updateAndGet { current -> current - frameId }
    }

    fun metrics(): MediaPipelineMetrics = lastMetrics.get()

    private fun networkLoop() {
        var failure: Throwable? = null
        for (host in request.endpointHosts) {
            if (stopping.get()) break
            try {
                runConnected(host)
                failure = null
                break
            } catch (error: Throwable) {
                if (stopping.get()) break
                failure = error
            }
        }
        socket = null
        if (stopping.get()) onStatus(NetworkMediaStatus.Stopped)
        else onStatus(NetworkMediaStatus.Failed(failure?.message ?: "Media connection ended"))
    }

    private fun runConnected(host: String) {
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        val connected = openPinnedTlsSocket(
            host,
            request.port,
            request.certificateSha256,
            request.connectTimeoutMillis,
            request.readTimeoutMillis,
        )
        socket = connected
        connected.use { tls ->
            tls.startHandshake()
            val input = DataInputStream(tls.inputStream)
            sequence = authenticateNetworkSession(
                tls,
                input,
                sessionId,
                sequence,
                request.certificateSha256,
                request.credentials,
            )
            val pingStarted = System.nanoTime()
            val pingNonce = pingStarted and Long.MAX_VALUE
            sequence = writeBridgePacket(tls, sessionId, sequence, BridgeMessage.Ping(pingNonce))
            val pong = readBridgePacket(input).message as? BridgeMessage.Pong
                ?: error("Desktop did not answer media clock probe")
            check(pong.nonce == pingNonce) { "Desktop returned another clock probe" }
            val oneWayNetworkEstimateMicros = (System.nanoTime() - pingStarted) / 2_000

            sequence = writeBridgePacket(
                tls,
                sessionId,
                sequence,
                BridgeMessage.MediaOffer(
                    requestedCapabilities = BridgeCapabilities.of(BridgeCapability.VIDEO),
                    maxWidth = request.maxWidth,
                    maxHeight = request.maxHeight,
                    maxFramesPerSecond = request.maxFramesPerSecond,
                    maxBitrateBitsPerSecond = request.maxBitrateBitsPerSecond,
                ),
            )
            val answer = readBridgePacket(input).message as? BridgeMessage.MediaAnswer
                ?: error("Desktop did not negotiate a media stream")
            check(BridgeCapability.VIDEO in answer.enabledCapabilities)
            check(answer.codec == BridgeVideoCodec.RAW_RGB565) {
                "Synthetic client only supports RGB565"
            }
            val format = VideoFormat(
                codec = VideoCodec.RAW_RGB565,
                width = answer.width,
                height = answer.height,
                framesPerSecond = answer.framesPerSecond,
                targetBitrateBitsPerSecond = answer.targetBitrateBitsPerSecond.toInt(),
            )
            onStatus(NetworkMediaStatus.Active(format))
            val assembler = VideoFrameAssembler(format)
            while (!stopping.get()) {
                val packet = readBridgePacket(input)
                val chunk = packet.message as? BridgeMessage.VideoChunk ?: continue
                receivedBytes.addAndGet(chunk.data.size.toLong())
                assembler.accept(chunk)?.let { assembled ->
                    val queued = QueuedFrame(
                        frame = assembled.frame,
                        transportMicros = oneWayNetworkEstimateMicros + assembled.assemblyMicros,
                    )
                    if (!decodeQueue.offer(queued)) {
                        decodeQueue.poll()
                        droppedFrames.incrementAndGet()
                        decodeQueue.offer(queued)
                    }
                    receivedFrames.incrementAndGet()
                }
            }
        }
    }

    private fun decodeLoop() {
        RawRgb565Decoder().use { decoder ->
            while (!stopping.get() || decodeQueue.isNotEmpty()) {
                val queued = decodeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val decoded = decoder.decode(queued.frame).copy(transportMicros = queued.transportMicros)
                presentedFrames.set(mapOf(decoded.frameId to decoded.decodedAtNanos))
                updateMetrics(
                    MediaPipelineMetrics(
                        generationMicros = decoded.generationMicros,
                        encodeMicros = decoded.encodeMicros,
                        networkEstimateMicros = decoded.transportMicros,
                        decodeMicros = decoded.decodeMicros,
                        presentationMicros = lastMetrics.get().presentationMicros,
                        receivedFrames = receivedFrames.get(),
                        droppedFrames = droppedFrames.get(),
                        receivedBytes = receivedBytes.get(),
                    ),
                )
                onFrame(decoded)
            }
        }
    }

    private fun updateMetrics(metrics: MediaPipelineMetrics) {
        lastMetrics.set(metrics)
        onMetrics(metrics)
    }
}

internal data class AssembledFrame(
    val frame: EncodedVideoFrame,
    val assemblyMicros: Long,
)

internal class VideoFrameAssembler(private val format: VideoFormat) {
    private var frameId = -1L
    private var nextChunkIndex = 0
    private var chunkCount = 0
    private var totalBytes = 0
    private var startedAtNanos = 0L
    private var template: BridgeMessage.VideoChunk? = null
    private var bytes = ByteArrayOutputStream()

    fun accept(chunk: BridgeMessage.VideoChunk): AssembledFrame? {
        if (chunk.frameId != frameId) begin(chunk)
        if (chunk.chunkIndex != nextChunkIndex || chunk.chunkCount != chunkCount) {
            begin(chunk)
        }
        if (chunk.chunkIndex != nextChunkIndex) return null
        bytes.write(chunk.data)
        nextChunkIndex++
        if (nextChunkIndex != chunkCount) return null
        val payload = bytes.toByteArray()
        check(payload.size == totalBytes) { "Synthetic frame length changed in transit" }
        val first = requireNotNull(template)
        val result = AssembledFrame(
            frame = EncodedVideoFrame(
                frameId = first.frameId,
                presentationTimestampMicros = first.presentationTimestampMicros,
                format = format,
                keyframe = first.keyframe,
                payload = payload,
                generationMicros = first.generationMicros,
                encodeMicros = first.encodeMicros,
            ),
            assemblyMicros = (System.nanoTime() - startedAtNanos) / 1_000,
        )
        frameId = -1
        return result
    }

    private fun begin(chunk: BridgeMessage.VideoChunk) {
        frameId = chunk.frameId
        nextChunkIndex = 0
        chunkCount = chunk.chunkCount
        totalBytes = chunk.totalFrameBytes.toInt()
        require(totalBytes == format.width * format.height * 2) {
            "Synthetic frame does not match negotiated dimensions"
        }
        startedAtNanos = System.nanoTime()
        template = chunk
        bytes = ByteArrayOutputStream(totalBytes)
    }
}
