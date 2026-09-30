package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeCapabilities
import dev.jonalakas.bridgepad.protocol.BridgeCapability
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgeVideoCodec
import dev.jonalakas.bridgepad.streaming.VideoCodec
import dev.jonalakas.bridgepad.streaming.VideoFormat
import java.io.DataInputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

data class NetworkMediaRequest(
    val host: String,
    val alternateHosts: List<String> = emptyList(),
    val port: Int = 39_394,
    val certificateSha256: String,
    val credentials: NetworkCredentials,
    val maxWidth: Int = 1280,
    val maxHeight: Int = 720,
    val maxFramesPerSecond: Int = 60,
    val maxBitrateBitsPerSecond: Long = 8_000_000,
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

data class WebRtcMediaNegotiation(
    val answerSdp: String,
    val format: VideoFormat,
    val roundTripMicros: Long,
)

data class NetworkMediaPreparation(
    val format: VideoFormat,
    val roundTripMicros: Long,
)

/**
 * Authenticated signalling for WebRTC. Video packets never cross this TLS
 * socket; after SDP exchange they travel independently through ICE/DTLS/SRTP.
 */
class NetworkMediaSignalingClient(private val request: NetworkMediaRequest) : AutoCloseable {
    private val stopping = AtomicBoolean(false)
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var active: ActiveSignaling? = null

    fun prepare(): NetworkMediaPreparation {
        check(active == null) { "Media signalling is already prepared" }
        var failure: Throwable? = null
        request.endpointHosts.forEach { host ->
            if (stopping.get()) error("Media signalling stopped")
            try {
                return prepareWithHost(host)
            } catch (error: Throwable) {
                failure = error
                runCatching { socket?.close() }
                socket = null
                active = null
            }
        }
        throw failure ?: IllegalStateException("No media endpoint is available")
    }

    private fun prepareWithHost(host: String): NetworkMediaPreparation {
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        val connected = openPinnedTlsSocket(
            host,
            request.port,
            request.certificateSha256,
            request.connectTimeoutMillis,
            15_000,
        )
        socket = connected
        connected.startHandshake()
        val input = DataInputStream(connected.inputStream)
        sequence = authenticateNetworkSession(
            connected,
            input,
            sessionId,
            sequence,
            request.certificateSha256,
            request.credentials,
        )
        val probeStarted = System.nanoTime()
        val nonce = probeStarted and Long.MAX_VALUE
        sequence = writeBridgePacket(connected, sessionId, sequence, BridgeMessage.Ping(nonce))
        val pong = readBridgePacket(input).message as? BridgeMessage.Pong
            ?: error("Desktop did not answer the media clock probe")
        check(pong.nonce == nonce) { "Desktop returned another clock probe" }
        val roundTripMicros = (System.nanoTime() - probeStarted) / 1_000

        sequence = writeBridgePacket(
            connected,
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
        check(answer.codec == BridgeVideoCodec.H264) { "Desktop did not select H.264" }

        val preparation = NetworkMediaPreparation(
            format = VideoFormat(
                codec = VideoCodec.H264,
                width = answer.width,
                height = answer.height,
                framesPerSecond = answer.framesPerSecond,
                targetBitrateBitsPerSecond = answer.targetBitrateBitsPerSecond.toInt(),
            ),
            roundTripMicros = roundTripMicros,
        )
        active = ActiveSignaling(
            socket = connected,
            input = input,
            sessionId = sessionId,
            sequence = sequence,
            preparation = preparation,
        )
        return preparation
    }

    fun exchangeOffer(offerSdp: String): WebRtcMediaNegotiation {
        val current = checkNotNull(active) { "Media signalling was not prepared" }
        current.sequence = writeBridgePacket(
            current.socket,
            current.sessionId,
            current.sequence,
            BridgeMessage.WebRtcOffer(offerSdp),
        )
        val webRtcAnswer = readBridgePacket(current.input).message as? BridgeMessage.WebRtcAnswer
            ?: error("Desktop did not return a WebRTC answer")
        return WebRtcMediaNegotiation(
            answerSdp = webRtcAnswer.sdp,
            format = current.preparation.format,
            roundTripMicros = current.preparation.roundTripMicros,
        )
    }

    fun negotiate(offerSdp: String): WebRtcMediaNegotiation {
        prepare()
        return exchangeOffer(offerSdp)
    }

    override fun close() {
        stopping.set(true)
        runCatching { socket?.close() }
        socket = null
        active = null
    }

    private data class ActiveSignaling(
        val socket: SSLSocket,
        val input: DataInputStream,
        val sessionId: Long,
        var sequence: Long,
        val preparation: NetworkMediaPreparation,
    )
}

sealed interface NetworkMediaStatus {
    data object Idle : NetworkMediaStatus
    data object Connecting : NetworkMediaStatus
    data class Active(val format: VideoFormat) : NetworkMediaStatus
    data object Stopped : NetworkMediaStatus
    data class Failed(val detail: String) : NetworkMediaStatus
}
