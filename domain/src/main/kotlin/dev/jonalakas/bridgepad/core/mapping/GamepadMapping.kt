package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection

data class AxisBinding(val source: VirtualAxis, val inverted: Boolean = false)

data class GamepadMapping(
    val buttons: Map<VirtualControl, VirtualControl> = VirtualControl.entries.associateWith { it },
    val axes: Map<VirtualAxis, AxisBinding> = VirtualAxis.entries.associateWith { AxisBinding(it) },
    val dpad: Map<DpadDirection, DpadDirection> = DpadDirection.entries.associateWith { it },
) {
    fun apply(raw: VirtualGamepadState): VirtualGamepadState {
        val mappedButtons = buttons.mapNotNullTo(mutableSetOf()) { (target, source) ->
            target.takeIf { source in raw.pressedButtons }
        }
        fun axis(target: VirtualAxis): Float {
            val binding = axes[target] ?: return 0f
            val value = raw.valueOf(binding.source)
            return if (binding.inverted) -value else value
        }
        return VirtualGamepadState(
            pressedButtons = mappedButtons,
            dpad = dpad.entries.firstOrNull { it.value == raw.dpad }?.key ?: DpadDirection.NEUTRAL,
            leftStickX = axis(VirtualAxis.LEFT_X).coerceIn(-1f, 1f),
            leftStickY = axis(VirtualAxis.LEFT_Y).coerceIn(-1f, 1f),
            rightStickX = axis(VirtualAxis.RIGHT_X).coerceIn(-1f, 1f),
            rightStickY = axis(VirtualAxis.RIGHT_Y).coerceIn(-1f, 1f),
            leftTrigger = axis(VirtualAxis.LEFT_TRIGGER).let { if (it < 0f) (it + 1f) / 2f else it }.coerceIn(0f, 1f),
            rightTrigger = axis(VirtualAxis.RIGHT_TRIGGER).let { if (it < 0f) (it + 1f) / 2f else it }.coerceIn(0f, 1f),
        )
    }
}
