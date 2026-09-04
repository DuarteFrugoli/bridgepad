package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
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
)

object DirectUsbGamepadStore {
    private val mutableState = MutableStateFlow(DirectUsbState())
    val state = mutableState.asStateFlow()
    fun set(state: DirectUsbState) { mutableState.value = state }
    fun applyMapping(mapping: UsbGamepadMapping) {
        mutableState.value = mutableState.value.let { it.copy(gamepad = mapping.apply(it.rawGamepad)) }
    }
    fun clear() { mutableState.value = DirectUsbState() }
}
