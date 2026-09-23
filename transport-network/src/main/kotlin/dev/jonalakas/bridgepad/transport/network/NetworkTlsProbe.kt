package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import java.io.DataInputStream
import java.security.SecureRandom
import kotlin.math.ceil

data class NetworkProbeRequest(
    val host: String,
    val port: Int = 39_393,
    val certificateSha256: String,
    val samples: Int = 250,
    val rateHz: Int = 125,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 5_000,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(samples in 2..10_000) { "samples must be between 2 and 10000" }
        require(rateHz in 1..1_000) { "rateHz must be between 1 and 1000" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "read timeout must be positive" }
    }
}

data class NetworkProbeResult(
    val localAddress: String,
    val remoteAddress: String,
    val tlsVersion: String,
    val cipherSuite: String,
    val connectAndHandshakeMillis: Double,
    val rttP50Millis: Double,
    val rttP95Millis: Double,
    val rttP99Millis: Double,
    val samples: Int,
)

object NetworkTlsProbe {
    fun run(request: NetworkProbeRequest): NetworkProbeResult {
        val startedAt = System.nanoTime()
        val socket = openPinnedTlsSocket(
            request.host,
            request.port,
            request.certificateSha256,
            request.connectTimeoutMillis,
            request.readTimeoutMillis,
        )

        socket.use { tlsSocket ->
            tlsSocket.startHandshake()
            val handshakeMillis = elapsedMillis(startedAt)
            val nonce = SecureRandom().nextLong()
            val rtts = ArrayList<Long>(request.samples)
            val intervalNanos = 1_000_000_000L / request.rateHz
            val input = DataInputStream(tlsSocket.inputStream)

            repeat(request.samples) { index ->
                val sampleStartedAt = System.nanoTime()
                val packet = BridgePacket(
                    sessionId = 1,
                    sequence = index.toLong(),
                    timestampMicros = monotonicMicros(),
                    message = BridgeMessage.Ping(nonce xor index.toLong()),
                )
                tlsSocket.outputStream.write(BridgePacketCodec.encode(packet))
                tlsSocket.outputStream.flush()
                val response = readBridgePacket(input)
                val pong = response.message as? BridgeMessage.Pong
                    ?: throw IllegalStateException("Desktop returned ${response.message.type}, expected Pong")
                check(response.sessionId == packet.sessionId) { "Desktop changed the session ID" }
                check(response.sequence == packet.sequence) { "Desktop changed the packet sequence" }
                check(pong.nonce == (nonce xor index.toLong())) { "Desktop changed the Ping nonce" }
                val elapsed = System.nanoTime() - sampleStartedAt
                rtts += elapsed
                val remaining = intervalNanos - elapsed
                if (remaining > 0) sleepPreciselyEnough(remaining)
            }

            val summary = summarizeNanoseconds(rtts)
            return NetworkProbeResult(
                localAddress = tlsSocket.localAddress.hostAddress.orEmpty(),
                remoteAddress = tlsSocket.inetAddress.hostAddress.orEmpty(),
                tlsVersion = tlsSocket.session.protocol,
                cipherSuite = tlsSocket.session.cipherSuite,
                connectAndHandshakeMillis = handshakeMillis,
                rttP50Millis = summary.p50,
                rttP95Millis = summary.p95,
                rttP99Millis = summary.p99,
                samples = request.samples,
            )
        }
    }

}

internal data class LatencySummary(val p50: Double, val p95: Double, val p99: Double)

internal fun summarizeNanoseconds(samples: List<Long>): LatencySummary {
    require(samples.isNotEmpty()) { "at least one sample is required" }
    val sorted = samples.sorted()
    fun percentile(value: Int): Double {
        val index = ceil((sorted.size - 1) * (value / 100.0)).toInt()
        return sorted[index] / 1_000_000.0
    }
    return LatencySummary(percentile(50), percentile(95), percentile(99))
}

private fun monotonicMicros(): Long = (System.nanoTime() and Long.MAX_VALUE) / 1_000

private fun elapsedMillis(startedAtNanos: Long): Double =
    (System.nanoTime() - startedAtNanos) / 1_000_000.0

private fun sleepPreciselyEnough(nanoseconds: Long) {
    val millis = nanoseconds / 1_000_000
    val nanos = (nanoseconds % 1_000_000).toInt()
    Thread.sleep(millis, nanos)
}
