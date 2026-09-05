package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import dev.jonalakas.bridgepad.localization.LocalizedMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DirectUsbState(
    val active: Boolean = false,
    val deviceName: String? = null,
    val deviceKey: String? = null,
    val rawGamepad: VirtualGamepadState = VirtualGamepadState(),
    val gamepad: VirtualGamepadState = VirtualGamepadState(),
    val inputEventCount: Long = 0,
    val lastInputTimestampNanos: Long? = null,
    val statusMessage: LocalizedMessage? = null,
    val statusIsError: Boolean = false,
    val permissionPending: Boolean = false,
)

object DirectUsbGamepadStore {
    private val mutableState = MutableStateFlow(DirectUsbState())
    val state = mutableState.asStateFlow()
    fun set(state: DirectUsbState) { mutableState.value = state }
    fun update(transform: (DirectUsbState) -> DirectUsbState) {
        mutableState.value = transform(mutableState.value)
    }
    fun applyMapping(mapping: GamepadMapping) {
        mutableState.value = mutableState.value.let { it.copy(gamepad = mapping.apply(it.rawGamepad)) }
    }
    fun clear() { mutableState.value = DirectUsbState() }
}
