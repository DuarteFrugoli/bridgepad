package dev.jonalakas.bridgepad.input.android

import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PhysicalGamepadInfo(
    val deviceId: Int,
    val sourceId: SourceId,
    val name: String,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val axes: List<String>,
)

data class AxisDiagnostic(
    val rawValue: Float,
    val normalizedValue: Float,
)

data class PhysicalGamepadState(
    val devices: List<PhysicalGamepadInfo> = emptyList(),
    val sourceStates: Map<SourceId, VirtualGamepadState> = emptyMap(),
    val axisDiagnostics: Map<String, AxisDiagnostic> = emptyMap(),
    val lastRawEvent: String = "Connect a USB gamepad and press a control.",
)

object PhysicalGamepadStore {
    private val mutableState = MutableStateFlow(PhysicalGamepadState())
    val state: StateFlow<PhysicalGamepadState> = mutableState.asStateFlow()

    fun set(value: PhysicalGamepadState) {
        mutableState.value = value
    }
}
