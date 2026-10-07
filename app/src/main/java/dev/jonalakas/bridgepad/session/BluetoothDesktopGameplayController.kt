package dev.jonalakas.bridgepad.session

import android.content.Context
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadClient
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadRequest
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BluetoothDesktopGameplayController(
    private val context: Context,
    private val inputRouter: InputRouter,
    private val scope: CoroutineScope,
    private val outputSessionOwner: OutputSessionOwner,
) {
    private val mutableStatus = MutableStateFlow<BluetoothDesktopGamepadStatus>(
        BluetoothDesktopGamepadStatus.Stopped,
    )
    val status: StateFlow<BluetoothDesktopGamepadStatus> = mutableStatus.asStateFlow()
    private var client: BluetoothDesktopGamepadClient? = null
    private var inputSubscription: InputSubscription? = null
    private var auxiliaryInputJob: Job? = null
    private var captureMode: PhysicalCaptureMode? = null
    private var generation = 0L
    private var ownership: OutputSessionOwner.Lease? = null

    fun start(deviceAddress: String, physicalCaptureMode: PhysicalCaptureMode) {
        stopCurrent(immediate = true)
        val nextOwnership = outputSessionOwner.claim(OutputSessionOwner.Kind.BLUETOOTH_DESKTOP) {
            stopForOwnershipChange()
        }
        val currentGeneration = synchronized(this) { ++generation }
        val nextClient = BluetoothDesktopGamepadClient(
            context,
            BluetoothDesktopGamepadRequest(deviceAddress),
        ) { update -> handleStatus(currentGeneration, update) }
        synchronized(this) {
            ownership = nextOwnership
            client = nextClient
            captureMode = physicalCaptureMode
            subscribeToInput(nextClient, physicalCaptureMode)
            auxiliaryInputJob = scope.launch {
                while (isActive) {
                    inputRouter.peekPointer()?.let { pointer ->
                        inputRouter.acknowledgePointer(nextClient.sendPointer(pointer))
                    }
                    inputRouter.peekKeyboard()?.let { keyboard ->
                        inputRouter.acknowledgeKeyboard(nextClient.sendKeyboard(keyboard))
                    }
                    delay(AUXILIARY_INPUT_POLL_MILLIS)
                }
            }
        }
        nextClient.start()
    }

    @Synchronized
    fun updatePhysicalCapture(physicalCaptureMode: PhysicalCaptureMode) {
        val activeClient = client ?: return
        if (captureMode == physicalCaptureMode) return
        captureMode = physicalCaptureMode
        subscribeToInput(activeClient, physicalCaptureMode)
    }

    fun stop() {
        stopCurrent(immediate = false)
    }

    fun shutdown() {
        stopCurrent(immediate = true)
    }

    private fun stopCurrent(immediate: Boolean) {
        val activeClient = synchronized(this) {
            generation++
            inputSubscription?.cancel()
            inputSubscription = null
            auxiliaryInputJob?.cancel()
            auxiliaryInputJob = null
            inputRouter.clearPointer()
            inputRouter.clearKeyboard()
            captureMode = null
            val active = client
            client = null
            ownership?.release()
            ownership = null
            mutableStatus.value = BluetoothDesktopGamepadStatus.Stopped
            active
        }
        activeClient?.stopAndAwait(immediate)
    }

    @Synchronized
    private fun handleStatus(currentGeneration: Long, update: BluetoothDesktopGamepadStatus) {
        if (generation != currentGeneration) return
        mutableStatus.value = update
        if (update is BluetoothDesktopGamepadStatus.Failed ||
            update is BluetoothDesktopGamepadStatus.Stopped
        ) {
            inputSubscription?.cancel()
            inputSubscription = null
            auxiliaryInputJob?.cancel()
            auxiliaryInputJob = null
            inputRouter.clearPointer()
            inputRouter.clearKeyboard()
            client = null
            captureMode = null
            ownership?.release()
            ownership = null
        }
    }

    private fun stopForOwnershipChange() {
        stopCurrent(immediate = true)
    }

    private fun subscribeToInput(
        activeClient: BluetoothDesktopGamepadClient,
        physicalCaptureMode: PhysicalCaptureMode,
    ) {
        inputSubscription?.cancel()
        inputSubscription = inputRouter.observe(physicalCaptureMode) { routed ->
            activeClient.send(routed.gamepad)
        }
    }

    private companion object {
        const val AUXILIARY_INPUT_POLL_MILLIS = 10L
    }
}
