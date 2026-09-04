package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState

data class InputOwnership(
    val axes: Map<VirtualAxis, SourceId> = emptyMap(),
    val dpad: SourceId? = null,
)

object InputMerger {
    fun merge(
        sources: Collection<SourceGamepadState>,
        ownership: InputOwnership = InputOwnership(),
    ): VirtualGamepadState {
        val byId = sources.associateBy(SourceGamepadState::sourceId)
        val buttons = sources.flatMapTo(mutableSetOf()) { it.gamepad.pressedButtons }

        fun axisValue(axis: VirtualAxis): Float = ownership.axes[axis]
            ?.let(byId::get)
            ?.gamepad
            ?.valueOf(axis)
            ?: 0f

        return VirtualGamepadState(
            pressedButtons = buttons,
            dpad = ownership.dpad?.let(byId::get)?.gamepad?.dpad
                ?: dev.jonalakas.bridgepad.core.gamepad.DpadDirection.NEUTRAL,
            leftStickX = axisValue(VirtualAxis.LEFT_X),
            leftStickY = axisValue(VirtualAxis.LEFT_Y),
            rightStickX = axisValue(VirtualAxis.RIGHT_X),
            rightStickY = axisValue(VirtualAxis.RIGHT_Y),
            leftTrigger = axisValue(VirtualAxis.LEFT_TRIGGER),
            rightTrigger = axisValue(VirtualAxis.RIGHT_TRIGGER),
        )
    }
}
