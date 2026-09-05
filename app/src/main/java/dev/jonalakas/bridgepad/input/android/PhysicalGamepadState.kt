package dev.jonalakas.bridgepad.input.android

import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

data class PhysicalGamepadInfo(
    val deviceId: Int,
    val sourceId: SourceId,
    val name: String,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val axes: List<String>,
) {
    val mappingKey: String
        get() = "android:${sourceId.value}"
}

data class AxisDiagnostic(
    val rawValue: Float,
    val normalizedValue: Float,
)

data class PhysicalGamepadState(
    val devices: List<PhysicalGamepadInfo> = emptyList(),
    val sourceStates: Map<SourceId, VirtualGamepadState> = emptyMap(),
    val rawSourceStates: Map<SourceId, VirtualGamepadState> = emptyMap(),
    val axisDiagnostics: Map<String, AxisDiagnostic> = emptyMap(),
    val lastRawEvent: String = "Connect a USB gamepad and press a control.",
    val inputEventCount: Long = 0,
    val lastInputTimestampNanos: Long? = null,
)

object PhysicalGamepadStore {
    private val mutableState = MutableStateFlow(PhysicalGamepadState())
    private val mutableUpdates = MutableSharedFlow<PhysicalGamepadState>(extraBufferCapacity = 64)
    val state: StateFlow<PhysicalGamepadState> = mutableState.asStateFlow()
    val updates: SharedFlow<PhysicalGamepadState> = mutableUpdates.asSharedFlow()

    fun set(value: PhysicalGamepadState) {
        mutableState.value = value
        mutableUpdates.tryEmit(value)
    }
}
