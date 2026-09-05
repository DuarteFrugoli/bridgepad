package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.localization.LocalizedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class HidSessionStatus {
    IDLE,
    STARTING,
    REGISTERING,
    READY,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class HidFeedbackLevel {
    INFO,
    WARNING,
    ERROR,
}

enum class PhysicalCaptureMode { COMPATIBILITY, BACKGROUND_USB }

data class PairedHost(val address: String, val name: String)

data class HidSessionState(
    val status: HidSessionStatus = HidSessionStatus.IDLE,
    val sessionActive: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val pairedHosts: List<PairedHost> = emptyList(),
    val pairingModeActive: Boolean = false,
    val connectedHost: String? = null,
    val connectedHostAddress: String? = null,
    val canReconnect: Boolean = false,
    val physicalCaptureMode: PhysicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY,
    val directUsbActive: Boolean = false,
    val touchInputSelected: Boolean = true,
    val message: LocalizedMessage? = null,
    val feedbackLevel: HidFeedbackLevel = HidFeedbackLevel.INFO,
    val inputRateHz: Float = 0f,
    val outputRateHz: Float = 0f,
    val lastLatencyMs: Float? = null,
)

object HidSessionStore {
    private val mutableState = MutableStateFlow(HidSessionState())
    val state: StateFlow<HidSessionState> = mutableState.asStateFlow()

    fun update(transform: (HidSessionState) -> HidSessionState) {
        mutableState.update(transform)
    }
}
