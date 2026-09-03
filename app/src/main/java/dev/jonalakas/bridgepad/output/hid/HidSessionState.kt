package dev.jonalakas.bridgepad.output.hid

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

data class PairedHost(val address: String, val name: String)

data class HidSessionState(
    val status: HidSessionStatus = HidSessionStatus.IDLE,
    val sessionActive: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val pairedHosts: List<PairedHost> = emptyList(),
    val connectedHost: String? = null,
    val message: String = "Start the HID spike to check this device.",
    val feedbackLevel: HidFeedbackLevel = HidFeedbackLevel.INFO,
)

object HidSessionStore {
    private val mutableState = MutableStateFlow(HidSessionState())
    val state: StateFlow<HidSessionState> = mutableState.asStateFlow()

    fun update(transform: (HidSessionState) -> HidSessionState) {
        mutableState.update(transform)
    }
}
