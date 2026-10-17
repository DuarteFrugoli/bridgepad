package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeMessage
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

enum class InputComparisonPhase { CONNECTING, TCP_TLS, UDP_AEAD }

data class InputTransportMeasurements(
    val attempted: Int,
    val received: Int,
    val p50Micros: Long,
    val p95Micros: Long,
    val p99Micros: Long,
    val maxMicros: Long,
    val over16Millis: Int,
    val over33Millis: Int,
    val over50Millis: Int,
    val elapsedMillis: Long,
) {
    val lost: Int get() = attempted - received
}

data class InputComparisonResult(
    val route: String,
    val tcp: InputTransportMeasurements,
    val udp: InputTransportMeasurements,
    val udpStoppedEarly: Boolean,
)

/** Paired transport echo; no virtual controller is created or input injected. */
class NetworkInputComparisonClient(private val request: NetworkGamepadRequest) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    @Volatile private var tls: SSLSocket? = null
    @Volatile private var datagram: DatagramSocket? = null

    fun run(onProgress: (InputComparisonPhase, Int) -> Unit): InputComparisonResult {
        requireNotNull(request.credentials) { "A paired Desktop is required for input comparison" }
        var lastError: Exception? = null
        request.endpointHosts.forEach { host ->
            if (stopped.get()) error("Input comparison stopped")
            try {
                return runWithHost(host, onProgress)
            } catch (error: Exception) {
                lastError = error
                closeSockets()
            }
        }
        throw lastError ?: IllegalStateException("No Desktop endpoint is available")
    }

    private fun runWithHost(
        host: String,
        onProgress: (InputComparisonPhase, Int) -> Unit,
    ): InputComparisonResult {
        onProgress(InputComparisonPhase.CONNECTING, 0)
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        val connected = openPinnedTlsSocket(
            host, request.port, request.certificateSha256,
            request.connectTimeoutMillis, request.readTimeoutMillis,
        )
        tls = connected
        connected.use { socket ->
            socket.startHandshake()
            if (request.requiredLocalRoute?.invoke(socket.localAddress) == false) {
                throw NoRouteToHostException("The selected Wi-Fi or USB route is unavailable")
            }
            val input = DataInputStream(socket.inputStream)
            sequence = authenticateNetworkSession(
                socket, input, sessionId, sequence, request.certificateSha256,
                requireNotNull(request.credentials),
            )
            val tcp = measureTcp(socket, input, sessionId, sequence, onProgress)
            sequence = tcp.second
            sequence = writeBridgePacket(socket, sessionId, sequence, BridgeMessage.InputProbeOffer)
            val readyPacket = readBridgePacket(input)
            check(readyPacket.sessionId == sessionId)
            val ready = readyPacket.message as? BridgeMessage.InputProbeReady
                ?: error("Desktop does not support the input comparison test")
            val udpSocket = DatagramSocket(null).apply {
                bind(InetSocketAddress(socket.localAddress, 0))
                connect(socket.inetAddress, ready.port)
            }
            datagram = udpSocket
            udpSocket.use { udp ->
                val udpResult = measureUdp(
                    udp, sessionId, ready.uplinkKey, ready.downlinkKey, onProgress,
                )
                return InputComparisonResult(
                    route = socket.localAddress.hostAddress ?: host,
                    tcp = tcp.first,
                    udp = udpResult.first,
                    udpStoppedEarly = udpResult.second,
                )
            }
        }
    }

    private fun measureTcp(
        socket: SSLSocket,
        input: DataInputStream,
        sessionId: Long,
        startSequence: Long,
        onProgress: (InputComparisonPhase, Int) -> Unit,
    ): Pair<InputTransportMeasurements, Long> {
        var sequence = startSequence
        val latencies = ArrayList<Long>(SAMPLES)
        val phaseStarted = System.nanoTime()
        repeat(SAMPLES) { index ->
            check(!stopped.get()) { "Input comparison stopped" }
            val started = System.nanoTime()
            sequence = writeBridgePacket(socket, sessionId, sequence, BridgeMessage.Ping(index.toLong()))
            val response = readBridgePacket(input)
            check(response.sessionId == sessionId &&
                (response.message as? BridgeMessage.Pong)?.nonce == index.toLong()) {
                "Desktop returned an unexpected TCP probe response"
            }
            latencies += (System.nanoTime() - started) / 1_000L
            if (index % 25 == 0) onProgress(InputComparisonPhase.TCP_TLS, index + 1)
            pace(started)
        }
        return measurements(SAMPLES, latencies, phaseStarted) to sequence
    }

    private fun measureUdp(
        socket: DatagramSocket,
        sessionId: Long,
        uplinkKey: ByteArray,
        downlinkKey: ByteArray,
        onProgress: (InputComparisonPhase, Int) -> Unit,
    ): Pair<InputTransportMeasurements, Boolean> {
        val sealer = InputUdpSealer(uplinkKey)
        val opener = InputUdpOpener(downlinkKey)
        val latencies = ArrayList<Long>(SAMPLES)
        val buffer = ByteArray(1_200)
        val phaseStarted = System.nanoTime()
        var attempted = 0
        var consecutiveLosses = 0
        var stoppedEarly = false
        repeat(SAMPLES) { index ->
            if (stoppedEarly) return@repeat
            check(!stopped.get()) { "Input comparison stopped" }
            val started = System.nanoTime()
            val bytes = sealer.seal(sessionId, BridgeMessage.Ping(index.toLong()))
            socket.send(DatagramPacket(bytes, bytes.size))
            attempted++
            val deadline = started + UDP_TIMEOUT_NANOS
            var matched = false
            while (!matched && !stopped.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                socket.soTimeout = (remaining / 1_000_000L).toInt().coerceAtLeast(1)
                val response = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    break
                }
                val decoded = opener.open(buffer, response.length)
                if (decoded?.sessionId == sessionId &&
                    (decoded.message as? BridgeMessage.Pong)?.nonce == index.toLong()) {
                    latencies += (System.nanoTime() - started) / 1_000L
                    matched = true
                }
            }
            consecutiveLosses = if (matched) 0 else consecutiveLosses + 1
            if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) stoppedEarly = true
            if (index % 25 == 0 || stoppedEarly) {
                onProgress(InputComparisonPhase.UDP_AEAD, attempted)
            }
            pace(started)
        }
        return measurements(attempted, latencies, phaseStarted) to stoppedEarly
    }

    private fun measurements(
        attempted: Int,
        latencies: List<Long>,
        started: Long,
    ): InputTransportMeasurements {
        val sorted = latencies.sorted()
        fun percentile(percent: Int): Long = if (sorted.isEmpty()) 0 else
            sorted[((sorted.size - 1) * percent / 100).coerceIn(sorted.indices)]
        return InputTransportMeasurements(
            attempted = attempted,
            received = sorted.size,
            p50Micros = percentile(50),
            p95Micros = percentile(95),
            p99Micros = percentile(99),
            maxMicros = sorted.lastOrNull() ?: 0,
            over16Millis = sorted.count { it > 16_000 },
            over33Millis = sorted.count { it > 33_000 },
            over50Millis = sorted.count { it > 50_000 },
            elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
        )
    }

    private fun pace(started: Long) {
        val remaining = SAMPLE_INTERVAL_NANOS - (System.nanoTime() - started)
        if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
    }

    private fun closeSockets() {
        runCatching { datagram?.close() }
        runCatching { tls?.close() }
        datagram = null
        tls = null
    }

    override fun close() {
        stopped.set(true)
        closeSockets()
    }

    private companion object {
        const val SAMPLES = 300
        const val SAMPLE_INTERVAL_NANOS = 8_000_000L
        const val UDP_TIMEOUT_NANOS = 80_000_000L
        const val MAX_CONSECUTIVE_LOSSES = 10
    }
}
