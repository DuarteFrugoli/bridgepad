package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import dev.jonalakas.bridgepad.localization.LocalizedMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

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
    private val updateChannel = Channel<DirectUsbState>(Channel.UNLIMITED)
    val state = mutableState.asStateFlow()
    val updates: Flow<DirectUsbState> = updateChannel.receiveAsFlow()
    fun set(state: DirectUsbState) {
        mutableState.value = state
        check(updateChannel.trySend(state).isSuccess) { "Direct USB input channel is unavailable." }
    }
    fun update(transform: (DirectUsbState) -> DirectUsbState) {
        set(transform(mutableState.value))
    }
    fun applyMapping(mapping: GamepadMapping) {
        set(mutableState.value.let { it.copy(gamepad = mapping.apply(it.rawGamepad)) })
    }
    fun clear() { set(DirectUsbState()) }
}
