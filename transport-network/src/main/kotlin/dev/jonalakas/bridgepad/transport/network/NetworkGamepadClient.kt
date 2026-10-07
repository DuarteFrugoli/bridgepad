package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

data class NetworkGamepadRequest(
    val host: String,
    val alternateHosts: List<String> = emptyList(),
    val port: Int = 39_393,
    val certificateSha256: String,
    val inputKind: BridgeInputKind = BridgeInputKind.AUTOMATIC,
    val credentials: NetworkCredentials? = null,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 2_000,
    val reconnectAttempts: Int? = 3,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(alternateHosts.none(String::isBlank)) { "alternate hosts must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "read timeout must be positive" }
        require(reconnectAttempts == null || reconnectAttempts in 0..10) {
            "reconnectAttempts must be null or between 0 and 10"
        }
    }

    internal val endpointHosts: List<String>
        get() = (listOf(host) + alternateHosts).distinct()
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
    data class Reconnecting(val attempt: Int, val maximumAttempts: Int?) : NetworkGamepadStatus
    data object Active : NetworkGamepadStatus
    data object Stopped : NetworkGamepadStatus
    data class Failed(val reason: NetworkFailureReason, val detail: String) : NetworkGamepadStatus
}

data class NetworkInputDiagnostics(
    val acceptedPointerReports: Long,
    val pointerBackpressureCount: Long,
    val pendingPointerReports: Int,
    val acceptedKeyboardInputs: Long,
    val keyboardBackpressureCount: Long,
    val pendingKeyboardInputs: Int,
)

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
    private val pendingGamepadTransitions =
        ArrayBlockingQueue<PendingGamepadTransition>(GAMEPAD_TRANSITION_QUEUE_CAPACITY)
    private val pendingPointers = ArrayBlockingQueue<PointerReport>(POINTER_QUEUE_CAPACITY)
    private val pendingKeyboard = ArrayBlockingQueue<KeyboardInput>(KEYBOARD_QUEUE_CAPACITY)
    private val acceptedPointerReports = AtomicLong(0)
    private val pointerBackpressureCount = AtomicLong(0)
    private val acceptedKeyboardInputs = AtomicLong(0)
    private val keyboardBackpressureCount = AtomicLong(0)
    private val endpointHosts = AtomicReference(request.endpointHosts)
    @Volatile
    private var socket: SSLSocket? = null
    @Volatile
    private var started = false
    @Volatile
    private var worker: Thread? = null

    fun start() {
        check(!started) { "A network gamepad client can only be started once" }
        started = true
        onStatus(NetworkGamepadStatus.Connecting)
        worker = Thread(::runSession, "BridgePad-network-gamepad").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun send(state: VirtualGamepadState) {
        if (!stopping.get()) {
            val previous = latestState.getAndSet(state)
            if (hasDiscreteTransition(previous, state)) {
                val transition = PendingGamepadTransition(state, System.nanoTime())
                if (!pendingGamepadTransitions.offer(transition)) {
                    // Never build a delayed command replay. The periodic full
                    // snapshot below will still converge to the newest state.
                    pendingGamepadTransitions.clear()
                    pendingGamepadTransitions.offer(transition)
                }
            }
        }
    }

    /**
     * Returns true only after the report has been accepted by the bounded
     * network queue. A false result lets the producer retain and coalesce the
     * report instead of silently losing a click or relative movement.
     */
    fun sendPointer(report: PointerReport): Boolean {
        if (stopping.get()) return false
        val accepted = pendingPointers.offer(report)
        if (accepted) acceptedPointerReports.incrementAndGet()
        else pointerBackpressureCount.incrementAndGet()
        return accepted
    }

    fun sendKeyboard(input: KeyboardInput): Boolean {
        if (stopping.get()) return false
        val accepted = pendingKeyboard.offer(input)
        if (accepted) acceptedKeyboardInputs.incrementAndGet()
        else keyboardBackpressureCount.incrementAndGet()
        return accepted
    }

    fun updateEndpoints(hosts: List<String>) {
        val normalized = hosts.filter(String::isNotBlank).distinct()
        if (normalized.isNotEmpty()) endpointHosts.set(normalized)
    }

    fun inputDiagnostics(): NetworkInputDiagnostics = NetworkInputDiagnostics(
        acceptedPointerReports = acceptedPointerReports.get(),
        pointerBackpressureCount = pointerBackpressureCount.get(),
        pendingPointerReports = pendingPointers.size,
        acceptedKeyboardInputs = acceptedKeyboardInputs.get(),
        keyboardBackpressureCount = keyboardBackpressureCount.get(),
        pendingKeyboardInputs = pendingKeyboard.size,
    )

    fun stop() {
        stopping.set(true)
    }

    fun closeImmediately() {
        stopping.set(true)
        runCatching { socket?.close() }
    }

    fun stopAndAwait(immediate: Boolean, timeoutMillis: Long = STOP_TIMEOUT_MILLIS): Boolean {
        stopping.set(true)
        if (immediate) runCatching { socket?.close() }
        val activeWorker = worker ?: return true
        if (activeWorker === Thread.currentThread()) return false
        activeWorker.join(timeoutMillis)
        if (activeWorker.isAlive) {
            runCatching { socket?.close() }
            activeWorker.join(FORCED_STOP_TIMEOUT_MILLIS)
        }
        return !activeWorker.isAlive
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
                val maximumAttempts = request.reconnectAttempts
                if (!retryable || maximumAttempts != null && reconnectAttempt >= maximumAttempts) {
                    onStatus(failure)
                    return
                }
                reconnectAttempt++
                onStatus(
                    NetworkGamepadStatus.Reconnecting(
                        attempt = reconnectAttempt,
                        maximumAttempts = maximumAttempts,
                    ),
                )
                pendingGamepadTransitions.clear()
                pendingPointers.clear()
                pendingKeyboard.clear()
                waitBeforeReconnect(
                    RECONNECT_DELAYS_MILLIS[
                        (reconnectAttempt - 1).coerceAtMost(RECONNECT_DELAYS_MILLIS.lastIndex)
                    ],
                )
            }
        }
        onStatus(NetworkGamepadStatus.Stopped)
    }

    private fun waitBeforeReconnect(delayMillis: Long) {
        val deadline = System.nanoTime() + delayMillis * 1_000_000L
        while (!stopping.get()) {
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMillis <= 0) return
            Thread.sleep(remainingMillis.coerceAtMost(STOP_POLL_MILLIS))
        }
    }

    private fun runConnectedSession() {
        var lastFailure: Exception? = null
        endpointHosts.get().forEach { host ->
            try {
                runConnectedSession(host)
                return
            } catch (failure: Exception) {
                if (stopping.get()) throw failure
                lastFailure = failure
                pendingGamepadTransitions.clear()
                pendingPointers.clear()
                pendingKeyboard.clear()
            }
        }
        throw lastFailure ?: IllegalStateException("No network endpoint is available")
    }

    private fun runConnectedSession(host: String) {
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        val tlsSocket = openPinnedTlsSocket(
            host,
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
                    BridgeCapabilities.of(
                        BridgeCapability.GAMEPAD,
                        BridgeCapability.POINTER,
                        BridgeCapability.KEYBOARD,
                    ),
                ),
            )
            val ready = readBridgePacket(input).message as? BridgeMessage.SessionReady
                ?: error("Desktop did not accept the gamepad session")
            check(BridgeCapability.GAMEPAD in ready.enabledCapabilities) {
                "Desktop did not enable gamepad input"
            }
            hasBeenActive.set(true)
            onStatus(NetworkGamepadStatus.Active)

            var nextGamepadReportAt = 0L
            var lastHeartbeatAt = System.nanoTime()
            val pendingHeartbeatNonce = AtomicLong(NO_HEARTBEAT)
            val pendingHeartbeatSince = AtomicLong(0L)
            val heartbeatFailure = AtomicReference<Exception?>(null)
            val heartbeatReader = Thread(
                {
                    while (!stopping.get() && !connected.isClosed) {
                        try {
                            val pongPacket = readBridgePacket(input)
                            val pong = pongPacket.message as? BridgeMessage.Pong
                                ?: error("Desktop sent an unexpected control response")
                            check(pongPacket.sessionId == sessionId) {
                                "Desktop returned a heartbeat for another session"
                            }
                            val expectedNonce = pendingHeartbeatNonce.get()
                            check(expectedNonce != NO_HEARTBEAT && pong.nonce == expectedNonce) {
                                "Desktop changed the heartbeat nonce"
                            }
                            pendingHeartbeatNonce.compareAndSet(expectedNonce, NO_HEARTBEAT)
                        } catch (_: SocketTimeoutException) {
                            // Read timeouts only wake this monitor so it can observe shutdown.
                        } catch (error: Exception) {
                            if (!stopping.get() && !connected.isClosed) {
                                heartbeatFailure.compareAndSet(null, error)
                                runCatching { connected.close() }
                            }
                            return@Thread
                        }
                    }
                },
                "BridgePad-network-heartbeat",
            ).apply {
                isDaemon = true
                start()
            }
            while (!stopping.get()) {
                val loopStartedAt = System.nanoTime()
                heartbeatFailure.get()?.let { throw it }
                var sentInput = false
                if (loopStartedAt >= nextGamepadReportAt) {
                    val next = pollFreshGamepadTransition(loopStartedAt) ?: latestState.get()
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.GamepadSnapshot(next),
                    )
                    nextGamepadReportAt = loopStartedAt + GAMEPAD_REPORT_INTERVAL_NANOS
                    sentInput = true
                }
                pendingPointers.poll()?.let { pointer ->
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.PointerFrame(pointer),
                    )
                    sentInput = true
                }
                pendingKeyboard.poll()?.let { keyboard ->
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.KeyboardFrame(keyboard),
                    )
                    sentInput = true
                }
                val now = System.nanoTime()
                val awaitingHeartbeat = pendingHeartbeatNonce.get() != NO_HEARTBEAT
                if (awaitingHeartbeat &&
                    now - pendingHeartbeatSince.get() >= request.readTimeoutMillis * 1_000_000L
                ) {
                    throw SocketTimeoutException("Desktop heartbeat timed out")
                }
                if (!awaitingHeartbeat && now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_NANOS) {
                    val nonce = now and Long.MAX_VALUE
                    pendingHeartbeatNonce.set(nonce)
                    pendingHeartbeatSince.set(now)
                    sequence = writeBridgePacket(
                        connected,
                        sessionId,
                        sequence,
                        BridgeMessage.Ping(nonce),
                    )
                    lastHeartbeatAt = now
                }
                if (!sentInput) Thread.sleep(IDLE_POLL_MILLIS)
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
            runCatching { connected.close() }
            heartbeatReader.join(HEARTBEAT_READER_STOP_TIMEOUT_MILLIS)
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

    private fun pollFreshGamepadTransition(nowNanos: Long): VirtualGamepadState? {
        while (true) {
            val transition = pendingGamepadTransitions.poll() ?: return null
            if (nowNanos - transition.createdAtNanos <= GAMEPAD_TRANSITION_TTL_NANOS) {
                return transition.state
            }
        }
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_NANOS = 500_000_000L
        const val GAMEPAD_REPORT_INTERVAL_NANOS = 8_000_000L
        const val GAMEPAD_TRANSITION_TTL_NANOS = 100_000_000L
        const val GAMEPAD_TRANSITION_QUEUE_CAPACITY = 64
        const val IDLE_POLL_MILLIS = 4L
        // Keep at most a short burst. A large relative-pointer backlog feels
        // like input lag after a temporary Wi-Fi stall.
        const val POINTER_QUEUE_CAPACITY = 8
        const val KEYBOARD_QUEUE_CAPACITY = 128
        const val STOP_POLL_MILLIS = 100L
        const val STOP_TIMEOUT_MILLIS = 1_000L
        const val FORCED_STOP_TIMEOUT_MILLIS = 500L
        const val HEARTBEAT_READER_STOP_TIMEOUT_MILLIS = 500L
        const val NO_HEARTBEAT = -1L
        val RECONNECT_DELAYS_MILLIS = longArrayOf(250, 500, 1_000, 1_500, 2_000)
    }
}

private data class PendingGamepadTransition(
    val state: VirtualGamepadState,
    val createdAtNanos: Long,
)

internal fun hasDiscreteTransition(
    previous: VirtualGamepadState,
    current: VirtualGamepadState,
): Boolean = previous.pressedButtons != current.pressedButtons ||
    previous.dpad != current.dpad ||
    previous.leftTrigger.isPressed() != current.leftTrigger.isPressed() ||
    previous.rightTrigger.isPressed() != current.rightTrigger.isPressed()

private fun Float.isPressed(): Boolean = this > 0.01f

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
