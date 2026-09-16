package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.protocol.BridgeCapabilities
import dev.jonalakas.bridgepad.protocol.BridgeCapability
import dev.jonalakas.bridgepad.protocol.BridgeInputKind
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import dev.jonalakas.bridgepad.protocol.BridgeStopReason
import dev.jonalakas.bridgepad.core.ports.PointerReport
import java.io.DataInputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ArrayBlockingQueue
import javax.net.ssl.SSLSocket

data class NetworkGamepadRequest(
    val host: String,
    val port: Int = 39_393,
    val certificateSha256: String,
    val inputKind: BridgeInputKind = BridgeInputKind.AUTOMATIC,
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 2_000,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        require(connectTimeoutMillis > 0) { "connect timeout must be positive" }
        require(readTimeoutMillis > 0) { "read timeout must be positive" }
    }
}

sealed interface NetworkGamepadStatus {
    data object Connecting : NetworkGamepadStatus
    data object Active : NetworkGamepadStatus
    data object Stopped : NetworkGamepadStatus
    data class Failed(val detail: String) : NetworkGamepadStatus
}

/**
 * First playable network transport. Input updates are coalesced into one pending
 * complete snapshot, so rendering never waits for the network.
 */
class NetworkGamepadClient(
    private val request: NetworkGamepadRequest,
    private val onStatus: (NetworkGamepadStatus) -> Unit,
) {
    private val stopping = AtomicBoolean(false)
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
        if (!stopping.get()) pendingState.set(state)
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
        val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
        var sequence = 0L
        try {
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
                sequence = writePacket(
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
                onStatus(NetworkGamepadStatus.Active)

                var lastSent: VirtualGamepadState? = null
                var lastHeartbeatAt = System.nanoTime()
                while (!stopping.get()) {
                    val next = pendingState.getAndSet(null)
                    if (next != null && next != lastSent) {
                        sequence = writePacket(
                            connected,
                            sessionId,
                            sequence,
                            BridgeMessage.GamepadSnapshot(next),
                        )
                        lastSent = next
                    }
                    pendingPointers.poll()?.let { pointer ->
                        sequence = writePacket(
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
                        sequence = writePacket(
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

                sequence = writePacket(
                    connected,
                    sessionId,
                    sequence,
                    BridgeMessage.GamepadSnapshot(VirtualGamepadState()),
                )
                writePacket(
                    connected,
                    sessionId,
                    sequence,
                    BridgeMessage.SessionStop(BridgeStopReason.USER_REQUEST),
                )
            }
            onStatus(NetworkGamepadStatus.Stopped)
        } catch (error: Exception) {
            if (stopping.get()) {
                onStatus(NetworkGamepadStatus.Stopped)
            } else {
                onStatus(
                    NetworkGamepadStatus.Failed(
                        error.cause?.message ?: error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
        } finally {
            socket = null
        }
    }

    private fun writePacket(
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

    private companion object {
        const val HEARTBEAT_INTERVAL_NANOS = 500_000_000L
        const val IDLE_POLL_MILLIS = 4L
        const val POINTER_QUEUE_CAPACITY = 64
    }
}
