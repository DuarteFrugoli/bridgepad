package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import dev.jonalakas.bridgepad.protocol.BridgeProtocol
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
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
        val expectedFingerprint = decodeFingerprint(request.certificateSha256)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PinnedCertificateTrustManager(expectedFingerprint)), SecureRandom())

        val startedAt = System.nanoTime()
        val plainSocket = Socket()
        val socket = try {
            plainSocket.connect(
                InetSocketAddress(request.host.trim(), request.port),
                request.connectTimeoutMillis,
            )
            plainSocket.tcpNoDelay = true
            context.socketFactory.createSocket(
                plainSocket,
                request.host.trim(),
                request.port,
                true,
            ) as SSLSocket
        } catch (error: Exception) {
            plainSocket.close()
            throw error
        }
        socket.soTimeout = request.readTimeoutMillis
        val supported = socket.supportedProtocols.toSet()
        socket.enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
            .filter { it in supported }
            .toTypedArray()
        if (socket.enabledProtocols.isEmpty()) {
            socket.close()
            throw IllegalStateException("This device supports neither TLS 1.3 nor TLS 1.2")
        }

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
                val response = readPacket(input)
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

    private fun readPacket(input: DataInputStream): BridgePacket {
        val header = ByteArray(BridgeProtocol.HEADER_SIZE)
        input.readFully(header)
        val payloadLength =
            ((header[28].toInt() and 0xff) shl 8) or (header[29].toInt() and 0xff)
        if (payloadLength > BridgeProtocol.MAX_PAYLOAD_SIZE) {
            throw IllegalStateException("Desktop payload exceeds the v1 limit")
        }
        val packet = header.copyOf(BridgeProtocol.HEADER_SIZE + payloadLength)
        input.readFully(packet, BridgeProtocol.HEADER_SIZE, payloadLength)
        return BridgePacketCodec.decode(packet)
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

internal fun normalizeFingerprint(value: String): String {
    val normalized = value.filterNot { it == ':' || it == '-' || it.isWhitespace() }
    require(normalized.length == 64 && normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "certificate fingerprint must contain exactly 64 hexadecimal digits"
    }
    return normalized.uppercase()
}

private fun decodeFingerprint(value: String): ByteArray = normalizeFingerprint(value)
    .chunked(2)
    .map { it.toInt(16).toByte() }
    .toByteArray()

private class PinnedCertificateTrustManager(
    private val expectedSha256: ByteArray,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Desktop sent no certificate")
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(expectedSha256, actual)) {
            throw CertificateException("Desktop certificate fingerprint does not match")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificate validation is not supported by this probe")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun monotonicMicros(): Long = (System.nanoTime() and Long.MAX_VALUE) / 1_000

private fun elapsedMillis(startedAtNanos: Long): Double =
    (System.nanoTime() - startedAtNanos) / 1_000_000.0

private fun sleepPreciselyEnough(nanoseconds: Long) {
    val millis = nanoseconds / 1_000_000
    val nanos = (nanoseconds % 1_000_000).toInt()
    Thread.sleep(millis, nanos)
}
