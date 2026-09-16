package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.protocol.BridgeAuthentication
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import dev.jonalakas.bridgepad.protocol.PeerId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

data class NetworkCredentials(
    val clientPeerId: PeerId,
    val serverPeerId: PeerId,
    val sharedSecret: ByteArray,
) {
    init {
        require(sharedSecret.size == BridgeAuthentication.SHARED_SECRET_SIZE)
    }
}

data class NetworkPairingRequest(
    val host: String,
    val port: Int,
    val certificateSha256: String,
    val expectedServerPeerId: PeerId,
    val clientPeerId: PeerId,
    val clientName: String,
    val pairingCode: String,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 5_000,
)

data class NetworkPairingResult(
    val serverPeerId: PeerId,
    val serverName: String,
    val sharedSecret: ByteArray,
)

class PairingRejectedException(message: String) : SecurityException(message)
class NetworkAuthenticationException(message: String) : SecurityException(message)

object NetworkPairingClient {
    fun pair(request: NetworkPairingRequest): NetworkPairingResult {
        val code = normalizePairingCode(request.pairingCode)
        val clientNonce = secureBytes(BridgeAuthentication.NONCE_SIZE)
        openPinnedTlsSocket(
            request.host,
            request.port,
            request.certificateSha256,
            request.connectTimeoutMillis,
            request.readTimeoutMillis,
        ).use { socket ->
            socket.startHandshake()
            val input = DataInputStream(socket.inputStream)
            var sequence = writeBridgePacket(
                socket,
                sessionId = 0,
                sequence = 0,
                message = BridgeMessage.PairRequest(
                    peerId = request.clientPeerId,
                    peerName = request.clientName,
                    clientNonce = clientNonce,
                ),
            )
            val challenge = readBridgePacket(input).message as? BridgeMessage.PairChallenge
                ?: throw NetworkAuthenticationException("Desktop returned no pairing challenge")
            if (challenge.peerId != request.expectedServerPeerId) {
                throw NetworkAuthenticationException("Discovered desktop identity changed")
            }
            val key = derivePairingKey(code, challenge.salt, challenge.iterations)
            val commonTranscript = pairingTranscript(
                clientPeerId = request.clientPeerId,
                serverPeerId = challenge.peerId,
                clientNonce = clientNonce,
                serverNonce = challenge.serverNonce,
                fingerprint = decodeFingerprint(request.certificateSha256),
            )
            val expectedServerProof = hmacSha256(
                key,
                roleTranscript("bridgepad-pair-server-v1", commonTranscript),
            )
            if (!MessageDigest.isEqual(expectedServerProof, challenge.serverProof)) {
                throw PairingRejectedException("Desktop did not prove the pairing code")
            }
            val proof = hmacSha256(
                key,
                roleTranscript("bridgepad-pair-client-v1", commonTranscript),
            )
            writeBridgePacket(
                socket,
                sessionId = 0,
                sequence = sequence,
                message = BridgeMessage.PairProof(proof),
            )
            val result = readBridgePacket(input).message as? BridgeMessage.PairResult
                ?: throw NetworkAuthenticationException("Desktop returned no pairing result")
            if (!result.accepted) {
                throw PairingRejectedException(result.detail.ifBlank { "Pairing code was rejected" })
            }
            if (result.sharedSecret.size != BridgeAuthentication.SHARED_SECRET_SIZE) {
                throw NetworkAuthenticationException("Desktop returned an invalid shared secret")
            }
            return NetworkPairingResult(challenge.peerId, result.peerName, result.sharedSecret)
        }
    }
}

internal fun authenticateNetworkSession(
    socket: SSLSocket,
    input: DataInputStream,
    sessionId: Long,
    initialSequence: Long,
    certificateSha256: String,
    credentials: NetworkCredentials,
): Long {
    val clientNonce = secureBytes(BridgeAuthentication.NONCE_SIZE)
    var sequence = writeBridgePacket(
        socket,
        sessionId,
        initialSequence,
        BridgeMessage.AuthRequest(credentials.clientPeerId, clientNonce),
    )
    val challenge = readBridgePacket(input).message as? BridgeMessage.AuthChallenge
        ?: throw NetworkAuthenticationException("Desktop returned no authentication challenge")
    if (challenge.peerId != credentials.serverPeerId) {
        throw NetworkAuthenticationException("Trusted desktop identity changed")
    }
    val proof = hmacSha256(
        credentials.sharedSecret,
        authenticationTranscript(
            clientPeerId = credentials.clientPeerId,
            serverPeerId = challenge.peerId,
            clientNonce = clientNonce,
            serverNonce = challenge.serverNonce,
            fingerprint = decodeFingerprint(certificateSha256),
        ),
    )
    sequence = writeBridgePacket(
        socket,
        sessionId,
        sequence,
        BridgeMessage.AuthProof(proof),
    )
    val result = readBridgePacket(input).message as? BridgeMessage.AuthResult
        ?: throw NetworkAuthenticationException("Desktop returned no authentication result")
    if (!result.accepted) {
        throw NetworkAuthenticationException(result.detail.ifBlank { "Desktop rejected authentication" })
    }
    return sequence
}

internal fun writeBridgePacket(
    socket: SSLSocket,
    sessionId: Long,
    sequence: Long,
    message: BridgeMessage,
): Long {
    socket.outputStream.write(
        BridgePacketCodec.encode(
            BridgePacket(
                sessionId = sessionId,
                sequence = sequence,
                timestampMicros = (System.nanoTime() and Long.MAX_VALUE) / 1_000,
                message = message,
            ),
        ),
    )
    socket.outputStream.flush()
    return (sequence + 1) and 0xffff_ffffL
}

internal fun normalizePairingCode(value: String): String {
    val digits = value.filter(Char::isDigit)
    require(digits.length == BridgeAuthentication.PAIRING_CODE_DIGITS) {
        "Pairing code must contain ${BridgeAuthentication.PAIRING_CODE_DIGITS} digits"
    }
    return digits
}

internal fun derivePairingKey(code: String, salt: ByteArray, iterations: Int): ByteArray {
    require(salt.size == BridgeAuthentication.SALT_SIZE)
    require(iterations > 0)
    val specification = PBEKeySpec(
        code.toCharArray(),
        salt,
        iterations,
        BridgeAuthentication.PROOF_SIZE * 8,
    )
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(specification)
            .encoded
    } finally {
        specification.clearPassword()
    }
}

internal fun pairingTranscript(
    clientPeerId: PeerId,
    serverPeerId: PeerId,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    fingerprint: ByteArray,
): ByteArray = transcript(
    context = "bridgepad-pair-v1",
    clientPeerId = clientPeerId,
    serverPeerId = serverPeerId,
    clientNonce = clientNonce,
    serverNonce = serverNonce,
    fingerprint = fingerprint,
)

internal fun authenticationTranscript(
    clientPeerId: PeerId,
    serverPeerId: PeerId,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    fingerprint: ByteArray,
): ByteArray = transcript(
    context = "bridgepad-auth-v1",
    clientPeerId = clientPeerId,
    serverPeerId = serverPeerId,
    clientNonce = clientNonce,
    serverNonce = serverNonce,
    fingerprint = fingerprint,
)

private fun transcript(
    context: String,
    clientPeerId: PeerId,
    serverPeerId: PeerId,
    clientNonce: ByteArray,
    serverNonce: ByteArray,
    fingerprint: ByteArray,
): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        output.write(context.toByteArray(Charsets.US_ASCII))
        output.writeLong(clientPeerId.high)
        output.writeLong(clientPeerId.low)
        output.writeLong(serverPeerId.high)
        output.writeLong(serverPeerId.low)
        output.write(clientNonce)
        output.write(serverNonce)
        output.write(fingerprint)
    }
    bytes.toByteArray()
}

internal fun roleTranscript(context: String, transcript: ByteArray): ByteArray =
    context.toByteArray(Charsets.US_ASCII) + transcript

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

private fun secureBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)
