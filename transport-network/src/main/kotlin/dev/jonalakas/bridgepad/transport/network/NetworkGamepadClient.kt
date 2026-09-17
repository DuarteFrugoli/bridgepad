package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.protocol.BridgeCapabilities
import dev.jonalakas.bridgepad.protocol.BridgeCapability
import dev.jonalakas.bridgepad.protocol.BridgeInputKind
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgeStopReason
import java.io.DataInputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

data class NetworkGamepadRequest(
    val host: String,
    val port: Int = 39_393,
    val certificateSha256: String,
    val inputKind: BridgeInputKind = BridgeInputKind.AUTOMATIC,
    val credentials: NetworkCredentials? = null,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 2_000,
    val reconnectAttempts: Int = 3,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "read timeout must be positive" }
        require(reconnectAttempts in 0..10) { "reconnectAttempts must be between 0 and 10" }
    }
}

enum class NetworkFailureReason {
    DESKTOP_UNAVAILABLE,
    CERTIFICATE_CHANGED,
    AUTHENTICATION_REJECTED,
    CONNECTION_LOST,
    PROTOCOL_ERROR,
    UNKNOWN,
}

sealed interface NetworkGamepadStatus {
    data object Connecting : NetworkGamepadStatus
    data class Reconnecting(val attempt: Int, val maximumAttempts: Int) : NetworkGamepadStatus
    data object Active : NetworkGamepadStatus
    data object Stopped : NetworkGamepadStatus
    data class Failed(val reason: NetworkFailureReason, val detail: String) : NetworkGamepadStatus
}

/**
 * Bounded sender shared by diagnostic and paired product sessions. Complete
 * gamepad state is retained across reconnects; relative pointer deltas are not
 * replayed because doing so would move the cursor twice.
 */
class NetworkGamepadClient(
    private val request: NetworkGamepadRequest,
    private val onStatus: (NetworkGamepadStatus) -> Unit,
) {
    private val stopping = AtomicBoolean(false)
    private val hasBeenActive = AtomicBoolean(false)
    private val latestState = AtomicReference(VirtualGamepadState())
    private val pendingState = AtomicReference<VirtualGamepadState?>(VirtualGamepadState())
    private val pendingPointers = ArrayBlockingQueue<PointerReport>(POINTER_QUEUE_CAPACITY)
    @Volatile
    private var socket: SSLSocket? = null
    @Volatile
    private var started = false

    fun start() {
        check(!started) { "A network gamepad client can only be started once" }
        started = true
        onStatus(NetworkGamepadStatus.Connecting)
        Thread(::runSession, "BridgePad-network-gamepad").apply {
            isDaemon = true
            start()
        }
    }

    fun send(state: VirtualGamepadState) {
        if (!stopping.get()) {
            latestState.set(state)
            pendingState.set(state)
        }
    }

    fun sendPointer(report: PointerReport) {
        if (stopping.get()) return
        if (!pendingPointers.offer(report)) {
            pendingPointers.poll()
            pendingPointers.offer(report)
        }
    }

    fun stop() {
        stopping.set(true)
    }

    fun closeImmediately() {
        stopping.set(true)
        runCatching { socket?.close() }
    }

    private fun runSession() {
        var reconnectAttempt = 0
        while (!stopping.get()) {
            try {
                runConnectedSession()
                onStatus(NetworkGamepadStatus.Stopped)
                return
            } catch (error: Exception) {
                socket = null
                if (stopping.get()) {
                    onStatus(NetworkGamepadStatus.Stopped)
                    return
                }
                val failure = normalizeFailureAfterActiveSession(
                    classifyFailure(error),
                    hasBeenActive.get(),
                )
                val retryable = failure.reason in setOf(
                    NetworkFailureReason.DESKTOP_UNAVAILABLE,
                    NetworkFailureReason.CONNECTION_LOST,
                    NetworkFailureReason.UNKNOWN,
                )
                if (!retryable || reconnectAttempt >= request.reconnectAttempts) {
                    onStatus(failure)
                    return
                }
                reconnectAttempt++
                onStatus(
                    NetworkGamepadStatus.Reconnecting(
                        attempt = reconnectAttempt,
                        maximumAttempts = request.reconnectAttempts,
                    ),
                )
                pendingState.set(latestState.get())
                pendingPointers.clear()
                Thread.sleep(RECONNECT_DELAYS_MILLIS[(reconnectAttempt - 1).coerceAtMost(2)])
            }
        }
        onStatus(NetworkGamepadStatus.Stopped)
    }

    private fun runConnectedSession() {
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        val tlsSocket = openPinnedTlsSocket(
            request.host,
            request.port,
            request.certificateSha256,
            request.connectTimeoutMillis,
            request.readTimeoutMillis,
        )
        socket = tlsSocket
        tlsSocket.use { connected ->
            connected.startHandshake()
            if (stopping.get()) return@use
            val input = DataInputStream(connected.inputStream)
            request.credentials?.let { credentials ->
                sequence = authenticateNetworkSession(
                    socket = connected,
                    input = input,
                    sessionId = sessionId,
                    initialSequence = sequence,
                    certificateSha256 = request.certificateSha256,
                    credentials = credentials,
                )
            }
            sequence = writeBridgePacket(
                connected,
                sessionId,
                sequence,
                BridgeMessage.SessionStart(
                    request.inputKind,
                    BridgeCapabilities.of(BridgeCapability.GAMEPAD, BridgeCapability.POINTER),
                ),
            )
            val ready = readBridgePacket(input).message as? BridgeMessage.SessionReady
                ?: error("Desktop did not accept the gamepad session")
            check(BridgeCapability.GAMEPAD in ready.enabledCapabilities) {
                "Desktop did not enable gamepad input"
            }
            hasBeenActive.set(true)
            onStatus(NetworkGamepadStatus.Active)

            var lastSent: VirtualGamepadState? = null
            var lastHeartbeatAt = System.nanoTime()
            while (!stopping.get()) {
                val next = pendingState.getAndSet(null)
                if (next != null && next != lastSent) {
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.GamepadSnapshot(next),
                    )
                    lastSent = next
                }
                pendingPointers.poll()?.let { pointer ->
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.PointerFrame(pointer),
                    )
                }
                val now = System.nanoTime()
                if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_NANOS) {
                    val nonce = now and Long.MAX_VALUE
                    val pingSequence = sequence
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.Ping(nonce),
                    )
                    val pongPacket = readBridgePacket(input)
                    val pong = pongPacket.message as? BridgeMessage.Pong
                        ?: error("Desktop did not answer the heartbeat")
                    check(pongPacket.sessionId == sessionId && pongPacket.sequence == pingSequence) {
                        "Desktop returned a heartbeat for another session"
                    }
                    check(pong.nonce == nonce) { "Desktop changed the heartbeat nonce" }
                    lastHeartbeatAt = System.nanoTime()
                }
                if (next == null) Thread.sleep(IDLE_POLL_MILLIS)
            }

            sequence = writeBridgePacket(
                connected,
                sessionId,
                sequence,
                BridgeMessage.GamepadSnapshot(VirtualGamepadState()),
            )
            writeBridgePacket(
                connected,
                sessionId,
                sequence,
                BridgeMessage.SessionStop(BridgeStopReason.USER_REQUEST),
            )
        }
        socket = null
    }

    private fun classifyFailure(error: Exception): NetworkGamepadStatus.Failed {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        val reason = when {
            chain.any { it is PairingRejectedException || it is NetworkAuthenticationException } ->
                NetworkFailureReason.AUTHENTICATION_REJECTED
            chain.any { it is CertificateException } ||
                chain.filterIsInstance<SSLHandshakeException>().any {
                    it.message?.contains("fingerprint", ignoreCase = true) == true
                } -> NetworkFailureReason.CERTIFICATE_CHANGED
            chain.any { it is ConnectException || it is NoRouteToHostException } ->
                NetworkFailureReason.DESKTOP_UNAVAILABLE
            chain.any { it is SocketTimeoutException } -> NetworkFailureReason.CONNECTION_LOST
            chain.any { it is IllegalArgumentException || it is IllegalStateException } ->
                NetworkFailureReason.PROTOCOL_ERROR
            else -> NetworkFailureReason.UNKNOWN
        }
        return NetworkGamepadStatus.Failed(
            reason = reason,
            detail = error.cause?.message ?: error.message ?: error.javaClass.simpleName,
        )
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_NANOS = 500_000_000L
        const val IDLE_POLL_MILLIS = 4L
        const val POINTER_QUEUE_CAPACITY = 64
        val RECONNECT_DELAYS_MILLIS = longArrayOf(500, 1_000, 2_000)
    }
}

internal fun normalizeFailureAfterActiveSession(
    failure: NetworkGamepadStatus.Failed,
    hasBeenActive: Boolean,
): NetworkGamepadStatus.Failed {
    if (!hasBeenActive) return failure
    return if (failure.reason in setOf(
            NetworkFailureReason.DESKTOP_UNAVAILABLE,
            NetworkFailureReason.CONNECTION_LOST,
            NetworkFailureReason.UNKNOWN,
        )
    ) {
        failure.copy(reason = NetworkFailureReason.CONNECTION_LOST)
    } else {
        failure
    }
}
