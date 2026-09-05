package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.localization.LocalizedMessage
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FeedbackLevel {
    INFO,
    WARNING,
    ERROR,
}

data class PairedHost(val address: String, val name: String)

data class SessionState(
    val status: SessionStatus = SessionStatus.IDLE,
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
    val feedbackLevel: FeedbackLevel = FeedbackLevel.INFO,
    val inputRateHz: Float = 0f,
    val outputRateHz: Float = 0f,
    val lastLatencyMs: Float? = null,
)

object SessionStore {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) {
        mutableState.update(transform)
    }
}
