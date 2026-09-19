package dev.jonalakas.bridgepad.session

import android.content.Context
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadClient
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadRequest
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothDesktopGameplayController(
    private val context: Context,
    private val inputRouter: InputRouter,
) {
    private val mutableStatus = MutableStateFlow<BluetoothDesktopGamepadStatus>(
        BluetoothDesktopGamepadStatus.Stopped,
    )
    val status: StateFlow<BluetoothDesktopGamepadStatus> = mutableStatus.asStateFlow()
    private var client: BluetoothDesktopGamepadClient? = null
    private var inputSubscription: InputSubscription? = null
    private var captureMode: PhysicalCaptureMode? = null
    private var generation = 0L

    @Synchronized
    fun start(deviceAddress: String, physicalCaptureMode: PhysicalCaptureMode) {
        stopCurrent(immediate = true)
        val currentGeneration = ++generation
        val nextClient = BluetoothDesktopGamepadClient(
            context,
            BluetoothDesktopGamepadRequest(deviceAddress),
        ) { update -> handleStatus(currentGeneration, update) }
        client = nextClient
        captureMode = physicalCaptureMode
        subscribeToInput(nextClient, physicalCaptureMode)
        nextClient.start()
    }

    @Synchronized
    fun updatePhysicalCapture(physicalCaptureMode: PhysicalCaptureMode) {
        val activeClient = client ?: return
        if (captureMode == physicalCaptureMode) return
        captureMode = physicalCaptureMode
        subscribeToInput(activeClient, physicalCaptureMode)
    }

    @Synchronized
    fun stop() {
        stopCurrent(immediate = false)
    }

    @Synchronized
    fun shutdown() {
        stopCurrent(immediate = true)
    }

    private fun stopCurrent(immediate: Boolean) {
        inputSubscription?.cancel()
        inputSubscription = null
        captureMode = null
        client?.let { active ->
            if (immediate) active.closeImmediately() else active.stop()
        }
        client = null
        mutableStatus.value = BluetoothDesktopGamepadStatus.Stopped
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
            client = null
            captureMode = null
        }
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
}
