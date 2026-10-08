package dev.jonalakas.bridgepad.streaming

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the Quinn-based Android QUIC DATAGRAM diagnostic through JNI. */
class QuicMediaProbe(
    private val host: String,
    private val port: Int,
    private val durationSeconds: Int,
    private val onStatus: (MediaTransportProbeStatus) -> Unit,
) : AutoCloseable {
    private val stopping = AtomicBoolean(false)

    fun start() {
        onStatus(MediaTransportProbeStatus.Connecting)
        Thread(::runProbe, "BridgePad-media-quic-probe").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { QuicMediaNative.cancelProbe() }
        onStatus(MediaTransportProbeStatus.Stopped)
    }

    private fun runProbe() {
        try {
            require(port in 1..0xffff) { "Invalid QUIC port" }
            require(durationSeconds in 1..300) { "Invalid test duration" }
            val route = resolveRoute(host, port)
            onStatus(MediaTransportProbeStatus.Receiving(route.first, route.second))
            val encoded = QuicMediaNative.runProbe(host, port, durationSeconds)
            if (stopping.get()) return
            onStatus(
                MediaTransportProbeStatus.Completed(
                    parseQuicMediaProbeResult(encoded, route.second),
                ),
            )
        } catch (error: Throwable) {
            if (!stopping.get()) {
                val detail = error.cause?.message ?: error.message ?: "QUIC media probe failed"
                onStatus(MediaTransportProbeStatus.Failed(detail))
            }
        }
    }

    private fun resolveRoute(host: String, port: Int): Pair<String, String> {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port))
            val localAddress = socket.localAddress.hostAddress.orEmpty()
            val interfaceName = runCatching {
                NetworkInterface.getByInetAddress(socket.localAddress)?.displayName
            }.getOrNull().orEmpty().ifBlank { "unknown" }
            return localAddress to interfaceName
        }
    }

}

internal fun parseQuicMediaProbeResult(
    encoded: String,
    interfaceName: String,
): MediaTransportProbeResult {
    val fields = encoded.split('\t')
    if (fields.firstOrNull() == "ERR") {
        throw IllegalStateException(fields.getOrNull(1) ?: "native QUIC diagnostic failed")
    }
    require(fields.size == QUIC_RESULT_FIELD_COUNT && fields[0] == "OK") {
        "Invalid native QUIC diagnostic result"
    }
    return MediaTransportProbeResult(
        transport = MediaTransportKind.QUIC_DATAGRAM,
        localAddress = fields[1],
        interfaceName = interfaceName,
        receivedPackets = fields[2].toLong(),
        expectedPackets = fields[3].toLong(),
        completedFrames = fields[4].toLong(),
        expectedFrames = fields[5].toLong(),
        expiredFrames = fields[6].toLong(),
        duplicatePackets = fields[7].toLong(),
        rejectedPackets = fields[8].toLong(),
        receivedBytes = fields[9].toLong(),
        elapsedMillis = fields[10].toLong(),
        handshakeMillis = fields[11].toLong(),
        arrivalGapP95Micros = fields[12].toLong(),
        arrivalGapP99Micros = fields[13].toLong(),
        arrivalGapMaxMicros = fields[14].toLong(),
    )
}

private const val QUIC_RESULT_FIELD_COUNT = 15
