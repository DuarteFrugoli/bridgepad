package dev.jonalakas.bridgepad.streaming

import java.util.concurrent.atomic.AtomicBoolean

enum class MediaTransportKind {
    UDP_AEAD,
    QUIC_DATAGRAM,
}

sealed interface MediaTransportProbeStatus {
    data object Idle : MediaTransportProbeStatus
    data object Connecting : MediaTransportProbeStatus
    data class Receiving(val localAddress: String, val interfaceName: String) : MediaTransportProbeStatus
    data class Completed(val result: MediaTransportProbeResult) : MediaTransportProbeStatus
    data class Failed(val detail: String) : MediaTransportProbeStatus
    data object Stopped : MediaTransportProbeStatus
}

data class MediaTransportProbeResult(
    val transport: MediaTransportKind,
    val localAddress: String,
    val interfaceName: String,
    val receivedPackets: Long,
    val expectedPackets: Long,
    val completedFrames: Long,
    val expectedFrames: Long,
    val expiredFrames: Long,
    val duplicatePackets: Long,
    val rejectedPackets: Long,
    val receivedBytes: Long,
    val elapsedMillis: Long,
    val handshakeMillis: Long?,
    val arrivalGapP95Micros: Long,
    val arrivalGapP99Micros: Long,
    val arrivalGapMaxMicros: Long,
)

/** Runs the native Rust UDP+AEAD receiver used to compare Media v1 transports. */
class UdpAeadMediaProbe(
    private val host: String,
    private val port: Int,
    private val durationSeconds: Int,
    private val onStatus: (MediaTransportProbeStatus) -> Unit,
) : AutoCloseable {
    private val operationId = NativeMediaProbeSupport.nextOperationId()
    private val stopping = AtomicBoolean(false)

    fun start() {
        onStatus(MediaTransportProbeStatus.Connecting)
        Thread(::runProbe, "BridgePad-media-udp-native-probe").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { MediaTransportNative.cancelProbe(operationId) }
        onStatus(MediaTransportProbeStatus.Stopped)
    }

    private fun runProbe() {
        try {
            require(port in 1..0xffff) { "Invalid UDP port" }
            require(durationSeconds in 1..300) { "Invalid test duration" }
            val route = NativeMediaProbeSupport.resolveRoute(host, port)
            onStatus(MediaTransportProbeStatus.Receiving(route.first, route.second))
            val encoded = MediaTransportNative.runUdpAeadProbe(
                operationId,
                host,
                route.second,
                port,
                durationSeconds,
            )
            if (stopping.get()) return
            onStatus(
                MediaTransportProbeStatus.Completed(
                    parseNativeMediaProbeResult(
                        encoded,
                        route.second,
                        MediaTransportKind.UDP_AEAD,
                    ),
                ),
            )
        } catch (error: Throwable) {
            if (!stopping.get()) {
                val detail = error.cause?.message ?: error.message ?: "UDP media probe failed"
                onStatus(MediaTransportProbeStatus.Failed(detail))
            }
        }
    }
}
