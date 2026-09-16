package dev.jonalakas.bridgepad.transport.network

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

internal fun openPinnedTlsSocket(
    host: String,
    port: Int,
    certificateSha256: String,
    connectTimeoutMillis: Int,
    readTimeoutMillis: Int,
): SSLSocket {
    val expectedFingerprint = decodeFingerprint(certificateSha256)
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(PinnedCertificateTrustManager(expectedFingerprint)), SecureRandom())

    val normalizedHost = host.trim()
    val plainSocket = Socket()
    val socket = try {
        plainSocket.connect(InetSocketAddress(normalizedHost, port), connectTimeoutMillis)
        plainSocket.tcpNoDelay = true
        context.socketFactory.createSocket(plainSocket, normalizedHost, port, true) as SSLSocket
    } catch (error: Exception) {
        plainSocket.close()
        throw error
    }
    socket.soTimeout = readTimeoutMillis
    val supported = socket.supportedProtocols.toSet()
    socket.enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
        .filter { it in supported }
        .toTypedArray()
    if (socket.enabledProtocols.isEmpty()) {
        socket.close()
        throw IllegalStateException("This device supports neither TLS 1.3 nor TLS 1.2")
    }
    return socket
}

internal fun readBridgePacket(input: DataInputStream): BridgePacket {
    val header = ByteArray(BridgeProtocol.HEADER_SIZE)
    input.readFully(header)
    val payloadLength = ((header[28].toInt() and 0xff) shl 8) or (header[29].toInt() and 0xff)
    if (payloadLength > BridgeProtocol.MAX_PAYLOAD_SIZE) {
        throw IllegalStateException("Desktop payload exceeds the v1 limit")
    }
    val packet = header.copyOf(BridgeProtocol.HEADER_SIZE + payloadLength)
    input.readFully(packet, BridgeProtocol.HEADER_SIZE, payloadLength)
    return BridgePacketCodec.decode(packet)
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
        throw CertificateException("Client certificate validation is not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
