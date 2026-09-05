package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.RawInputEvent
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis

object SourceStateReducer {
    fun reduce(state: SourceGamepadState, event: RawInputEvent): SourceGamepadState {
        require(state.sourceId == event.sourceId)
        val current = state.gamepad
        val next = when (event) {
            is RawInputEvent.Button -> current.copy(
                pressedButtons = if (event.pressed) {
                    current.pressedButtons + event.control
                } else {
                    current.pressedButtons - event.control
                },
            )
            is RawInputEvent.Dpad -> current.copy(dpad = event.direction)
            is RawInputEvent.Axis -> when (event.axis) {
                VirtualAxis.LEFT_X -> current.copy(leftStickX = event.value.coerceIn(-1f, 1f))
                VirtualAxis.LEFT_Y -> current.copy(leftStickY = event.value.coerceIn(-1f, 1f))
                VirtualAxis.RIGHT_X -> current.copy(rightStickX = event.value.coerceIn(-1f, 1f))
                VirtualAxis.RIGHT_Y -> current.copy(rightStickY = event.value.coerceIn(-1f, 1f))
                VirtualAxis.LEFT_TRIGGER -> current.copy(leftTrigger = event.value.coerceIn(0f, 1f))
                VirtualAxis.RIGHT_TRIGGER -> current.copy(rightTrigger = event.value.coerceIn(0f, 1f))
            }
        }
        return state.copy(gamepad = next)
    }
}

class SourceStateRegistry {
    private val states = linkedMapOf<SourceId, SourceGamepadState>()

    fun apply(event: RawInputEvent): SourceGamepadState {
        val current = states[event.sourceId] ?: SourceGamepadState(event.sourceId)
        return SourceStateReducer.reduce(current, event).also { states[event.sourceId] = it }
    }

    fun remove(sourceId: SourceId) { states.remove(sourceId) }
    fun clear() { states.clear() }
    fun snapshots(): List<SourceGamepadState> = states.values.toList()
}
