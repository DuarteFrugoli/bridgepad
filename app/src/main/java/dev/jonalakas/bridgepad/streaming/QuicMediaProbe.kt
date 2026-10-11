package dev.jonalakas.bridgepad.streaming

import java.util.concurrent.atomic.AtomicBoolean

/** Runs the Quinn-based Android QUIC DATAGRAM diagnostic through JNI. */
class QuicMediaProbe(
    private val host: String,
    private val port: Int,
    private val durationSeconds: Int,
    private val onStatus: (MediaTransportProbeStatus) -> Unit,
) : AutoCloseable {
    private val operationId = NativeMediaProbeSupport.nextOperationId()
    private val stopping = AtomicBoolean(false)

    fun start() {
        onStatus(MediaTransportProbeStatus.Connecting)
        Thread(::runProbe, "BridgePad-media-quic-probe").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { MediaTransportNative.cancelProbe(operationId) }
        onStatus(MediaTransportProbeStatus.Stopped)
    }

    private fun runProbe() {
        try {
            require(port in 1..0xffff) { "Invalid QUIC port" }
            require(durationSeconds in 1..300) { "Invalid test duration" }
            val route = NativeMediaProbeSupport.resolveRoute(host, port)
            onStatus(MediaTransportProbeStatus.Receiving(route.first, route.second))
            val encoded = MediaTransportNative.runQuicProbe(
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
                        MediaTransportKind.QUIC_DATAGRAM,
                    ),
                ),
            )
        } catch (error: Throwable) {
            if (!stopping.get()) {
                val detail = error.cause?.message ?: error.message ?: "QUIC media probe failed"
                onStatus(MediaTransportProbeStatus.Failed(detail))
            }
        }
    }

}

internal fun parseQuicMediaProbeResult(
    encoded: String,
    interfaceName: String,
): MediaTransportProbeResult {
    return parseNativeMediaProbeResult(
        encoded,
        interfaceName,
        MediaTransportKind.QUIC_DATAGRAM,
    )
}
