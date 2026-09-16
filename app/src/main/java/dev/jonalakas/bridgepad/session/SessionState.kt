package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.localization.LocalizedMessage
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterId
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
    val destinationType: DestinationType? = null,
    val connectionMethod: ConnectionMethod? = null,
    val outputAdapterId: OutputAdapterId? = null,
    val bluetoothEnabled: Boolean = false,
    val pairedHosts: List<PairedHost> = emptyList(),
    val pairingModeActive: Boolean = false,
    val connectedHost: String? = null,
    val connectedHostAddress: String? = null,
    val canReconnect: Boolean = false,
    val physicalCaptureMode: PhysicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY,
    val directUsbActive: Boolean = false,
    val message: LocalizedMessage? = null,
    val feedbackLevel: FeedbackLevel = FeedbackLevel.INFO,
    val inputRateHz: Float = 0f,
    val outputRateHz: Float = 0f,
    val lastLatencyMs: Float? = null,
    val maxOutputDelayMs: Float = 0f,
)

object SessionStore {
    private val mutableState = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) {
        mutableState.update(transform)
    }
}

/** Removes Bluetooth-off feedback when current observations contradict that diagnosis. */
internal fun SessionState.reconcileBluetoothAvailability(
    enabled: Boolean,
    permissionGranted: Boolean = true,
): SessionState {
    val bluetoothOffNotice = message?.resourceId in BLUETOOTH_OFF_NOTICE_IDS
    val resolvedNotice = bluetoothOffNotice && (enabled || !permissionGranted)
    if (bluetoothEnabled == enabled && !resolvedNotice) return this
    return copy(
        bluetoothEnabled = enabled,
        message = if (resolvedNotice) null else message,
        feedbackLevel = if (resolvedNotice) FeedbackLevel.INFO else feedbackLevel,
    )
}

private val BLUETOOTH_OFF_NOTICE_IDS = setOf(
    R.string.bluetooth_required,
    R.string.hid_bluetooth_off,
)
