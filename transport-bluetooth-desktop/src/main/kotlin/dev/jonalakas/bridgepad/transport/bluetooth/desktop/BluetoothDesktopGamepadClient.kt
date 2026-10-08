package dev.jonalakas.bridgepad.transport.bluetooth.desktop

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.output.OutputScheduler
import dev.jonalakas.bridgepad.protocol.BridgeCapabilities
import dev.jonalakas.bridgepad.protocol.BridgeCapability
import dev.jonalakas.bridgepad.protocol.BridgeInputKind
import dev.jonalakas.bridgepad.protocol.BridgeInputTiming
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketStream
import dev.jonalakas.bridgepad.protocol.BridgeStopReason
import java.io.DataInputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

data class BluetoothDesktopGamepadRequest(
    val deviceAddress: String,
    val inputKind: BridgeInputKind = BridgeInputKind.AUTOMATIC,
    val reconnectAttempts: Int = 3,
) {
    init {
        require(deviceAddress.isNotBlank())
        require(reconnectAttempts in 0..10)
    }
}

sealed interface BluetoothDesktopGamepadStatus {
    data object Connecting : BluetoothDesktopGamepadStatus
    data class Reconnecting(val attempt: Int, val maximumAttempts: Int) : BluetoothDesktopGamepadStatus
    data object Active : BluetoothDesktopGamepadStatus
    data object Stopped : BluetoothDesktopGamepadStatus
    data class Failed(val detail: String) : BluetoothDesktopGamepadStatus
}

internal class ReconnectAttemptBudget(
    private val maximumAttempts: Int,
    private val stableConnectionNanos: Long = DEFAULT_STABLE_CONNECTION_NANOS,
) {
    private var attempts = 0
    private var connectedAtNanos: Long? = null

    init {
        require(maximumAttempts >= 0)
        require(stableConnectionNanos > 0)
    }

    fun connected(nowNanos: Long) {
        connectedAtNanos = nowNanos
    }

    fun nextAttemptAfterDisconnect(nowNanos: Long): Int? {
        connectedAtNanos?.let { connectedAt ->
            if (nowNanos - connectedAt >= stableConnectionNanos) attempts = 0
        }
        connectedAtNanos = null
        if (attempts >= maximumAttempts) return null
        attempts += 1
        return attempts
    }

    private companion object {
        const val DEFAULT_STABLE_CONNECTION_NANOS = 5_000_000_000L
    }
}

/** First playable RFCOMM adapter. It remains diagnostic until product trust is added. */
@SuppressLint("MissingPermission")
class BluetoothDesktopGamepadClient(
    context: Context,
    private val request: BluetoothDesktopGamepadRequest,
    private val onStatus: (BluetoothDesktopGamepadStatus) -> Unit,
) {
    private val adapter = context.applicationContext
        .getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?: error("Bluetooth is not available on this device")
    private val stopping = AtomicBoolean(false)
    private val scheduler = OutputScheduler(
        reportRateHz = REPORT_RATE_HZ,
        // Keep held state comfortably inside the protocol watchdog window
        // instead of inheriting OutputScheduler's slower generic default.
        keepaliveIntervalMillis = BridgeInputTiming.GAMEPAD_KEEPALIVE_INTERVAL_MILLIS,
        maximumPendingTransitions = GAMEPAD_TRANSITION_QUEUE_CAPACITY,
    )
    private val pendingPointers = ArrayBlockingQueue<PointerReport>(POINTER_QUEUE_CAPACITY)
    private val pendingKeyboard = ArrayBlockingQueue<KeyboardInput>(KEYBOARD_QUEUE_CAPACITY)
    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var started = false
    @Volatile
    private var worker: Thread? = null

    fun start() {
        check(!started) { "A Bluetooth Desktop client can only be started once" }
        started = true
        onStatus(BluetoothDesktopGamepadStatus.Connecting)
        worker = Thread(::runSession, "BridgePad-bluetooth-desktop-gamepad").apply {
            isDaemon = true
            start()
        }
    }

    fun send(state: VirtualGamepadState) {
        if (!stopping.get()) scheduler.submit(state)
    }

    fun sendKeyboard(input: KeyboardInput): Boolean =
        !stopping.get() && pendingKeyboard.offer(input)

    fun sendPointer(report: PointerReport): Boolean =
        !stopping.get() && pendingPointers.offer(report)

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
        val reconnectBudget = ReconnectAttemptBudget(request.reconnectAttempts)
        while (!stopping.get()) {
            try {
                runConnectedSession {
                    reconnectBudget.connected(System.nanoTime())
                }
                onStatus(BluetoothDesktopGamepadStatus.Stopped)
                return
            } catch (error: Exception) {
                socket = null
                if (stopping.get()) {
                    onStatus(BluetoothDesktopGamepadStatus.Stopped)
                    return
                }
                val reconnectAttempt = reconnectBudget.nextAttemptAfterDisconnect(System.nanoTime())
                if (reconnectAttempt == null) {
                    onStatus(
                        BluetoothDesktopGamepadStatus.Failed(
                            "BridgePad Desktop did not keep the Bluetooth session available",
                        ),
                    )
                    return
                }
                pendingPointers.clear()
                pendingKeyboard.clear()
                scheduler.discardPendingTransitions()
                onStatus(
                    BluetoothDesktopGamepadStatus.Reconnecting(
                        reconnectAttempt,
                        request.reconnectAttempts,
                    ),
                )
                Thread.sleep(RECONNECT_DELAYS_MILLIS[(reconnectAttempt - 1).coerceAtMost(2)])
            }
        }
        onStatus(BluetoothDesktopGamepadStatus.Stopped)
    }

    private fun runConnectedSession(onConnected: () -> Unit) {
        check(adapter.isEnabled) { "Bluetooth is turned off" }
        val device = adapter.getRemoteDevice(request.deviceAddress)
        check(device.bondState == BluetoothDevice.BOND_BONDED) {
            "The selected computer is not paired with this phone"
        }
        val connected = device.createRfcommSocketToServiceRecord(RFCOMM_SERVICE_UUID)
        socket = connected
        connected.connect()
        connected.use { link ->
            if (stopping.get()) return@use
            val input = DataInputStream(link.inputStream)
            val output = link.outputStream
            val sessionId = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
            var sequence = 0L

            sequence = writePacket(
                output,
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
            val ready = BridgePacketStream.read(input).message as? BridgeMessage.SessionReady
                ?: error("BridgePad Desktop did not accept the Bluetooth gamepad session")
            check(BridgeCapability.GAMEPAD in ready.enabledCapabilities) {
                "BridgePad Desktop did not enable gamepad input"
            }
            check(BridgeCapability.KEYBOARD in ready.enabledCapabilities) {
                "BridgePad Desktop did not enable keyboard input"
            }
            check(BridgeCapability.POINTER in ready.enabledCapabilities) {
                "BridgePad Desktop did not enable pointer input"
            }
            onConnected()
            onStatus(BluetoothDesktopGamepadStatus.Active)

            while (!stopping.get()) {
                val pointer = pendingPointers.poll()
                if (pointer != null) {
                    sequence = writePacket(
                        output,
                        sessionId,
                        sequence,
                        BridgeMessage.PointerFrame(pointer),
                    )
                }
                val now = System.nanoTime()
                val state = scheduler.poll(now)
                if (state != null) {
                    val sent = runCatching {
                        sequence = writePacket(
                            output,
                            sessionId,
                            sequence,
                            BridgeMessage.GamepadSnapshot(state),
                        )
                    }.isSuccess
                    scheduler.complete(state, sent, System.nanoTime())
                    if (!sent) error("Bluetooth gamepad write failed")
                }
                pendingKeyboard.poll()?.let { keyboard ->
                    sequence = writePacket(
                        output,
                        sessionId,
                        sequence,
                        BridgeMessage.KeyboardFrame(keyboard),
                    )
                }
                if (
                    pointer == null &&
                    state == null &&
                    pendingPointers.isEmpty() &&
                    pendingKeyboard.isEmpty()
                ) {
                    Thread.sleep(IDLE_POLL_MILLIS)
                }
            }

            val neutral = scheduler.stop()
            runCatching {
                sequence = writePacket(
                    output,
                    sessionId,
                    sequence,
                    BridgeMessage.GamepadSnapshot(neutral),
                )
                writePacket(
                    output,
                    sessionId,
                    sequence,
                    BridgeMessage.SessionStop(BridgeStopReason.USER_REQUEST),
                )
            }
        }
        socket = null
    }

    private fun writePacket(
        output: java.io.OutputStream,
        sessionId: Long,
        sequence: Long,
        message: BridgeMessage,
    ): Long {
        BridgePacketStream.write(
            output,
            BridgePacket(
                sessionId = sessionId,
                sequence = sequence,
                timestampMicros = (System.nanoTime() and Long.MAX_VALUE) / 1_000,
                message = message,
            ),
        )
        return (sequence + 1) and 0xffff_ffffL
    }

    private companion object {
        const val REPORT_RATE_HZ = 125
        const val IDLE_POLL_MILLIS = 1L
        // Absorb short RFCOMM delivery stalls without rejecting pointer samples.
        const val POINTER_QUEUE_CAPACITY = 64
        const val KEYBOARD_QUEUE_CAPACITY = 64
        const val GAMEPAD_TRANSITION_QUEUE_CAPACITY = 8
        const val STOP_TIMEOUT_MILLIS = 1_000L
        const val FORCED_STOP_TIMEOUT_MILLIS = 500L
        val RECONNECT_DELAYS_MILLIS = longArrayOf(500, 1_000, 2_000)
        val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("7a1b8d5f-6c24-4e71-9f52-a4b8d9c30101")
    }
}
