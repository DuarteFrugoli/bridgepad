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
    val triggerButtons: Map<VirtualAxis, VirtualControl> = emptyMap(),
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
        fun trigger(target: VirtualAxis): Float {
            triggerButtons[target]?.let { button ->
                return if (button in raw.pressedButtons) 1f else 0f
            }
            val binding = axes[target] ?: return 0f
            val value = raw.valueOf(binding.source)
            val mapped = if (binding.source == VirtualAxis.LEFT_TRIGGER ||
                binding.source == VirtualAxis.RIGHT_TRIGGER
            ) {
                if (binding.inverted) 1f - value else value
            } else {
                if (binding.inverted) -value else value
            }
            return mapped.coerceIn(0f, 1f)
        }
        return VirtualGamepadState(
            pressedButtons = mappedButtons,
            dpad = dpad.entries.firstOrNull { it.value == raw.dpad }?.key ?: DpadDirection.NEUTRAL,
            leftStickX = axis(VirtualAxis.LEFT_X).coerceIn(-1f, 1f),
            leftStickY = axis(VirtualAxis.LEFT_Y).coerceIn(-1f, 1f),
            rightStickX = axis(VirtualAxis.RIGHT_X).coerceIn(-1f, 1f),
            rightStickY = axis(VirtualAxis.RIGHT_Y).coerceIn(-1f, 1f),
            leftTrigger = trigger(VirtualAxis.LEFT_TRIGGER),
            rightTrigger = trigger(VirtualAxis.RIGHT_TRIGGER),
        )
    }
}
