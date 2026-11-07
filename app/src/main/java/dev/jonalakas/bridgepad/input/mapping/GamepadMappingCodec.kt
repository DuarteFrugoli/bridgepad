package dev.jonalakas.bridgepad.input.mapping

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.mapping.AxisBinding
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping

/** Local preference format; existing button, axis and D-pad rows remain compatible. */
internal object GamepadMappingCodec {
    fun decode(saved: String): GamepadMapping {
        val buttons = mutableMapOf<VirtualControl, VirtualControl>()
        val axes = mutableMapOf<VirtualAxis, AxisBinding>()
        val dpad = mutableMapOf<DpadDirection, DpadDirection>()
        val triggerButtons = mutableMapOf<VirtualAxis, VirtualControl>()
        saved.lineSequence().forEach { line ->
            val parts = line.split(',')
            runCatching {
                when (parts.firstOrNull()) {
                    "B" -> buttons[VirtualControl.valueOf(parts[1])] = VirtualControl.valueOf(parts[2])
                    "A" -> axes[VirtualAxis.valueOf(parts[1])] = AxisBinding(
                        VirtualAxis.valueOf(parts[2]),
                        parts.getOrNull(3)?.toBoolean() == true,
                    )
                    "D" -> dpad[DpadDirection.valueOf(parts[1])] = DpadDirection.valueOf(parts[2])
                    "T" -> {
                        val target = VirtualAxis.valueOf(parts[1])
                        if (target == VirtualAxis.LEFT_TRIGGER || target == VirtualAxis.RIGHT_TRIGGER) {
                            triggerButtons[target] = VirtualControl.valueOf(parts[2])
                        }
                    }
                }
            }
        }
        return if (buttons.isEmpty() && axes.isEmpty() && dpad.isEmpty() && triggerButtons.isEmpty()) {
            GamepadMapping()
        } else {
            GamepadMapping(
                buttons = buttons,
                axes = axes,
                dpad = dpad.ifEmpty { DpadDirection.entries.associateWith { it } },
                triggerButtons = triggerButtons,
            )
        }
    }

    fun encode(mapping: GamepadMapping): String = buildString {
        mapping.buttons.forEach { (target, source) -> appendLine("B,$target,$source") }
        mapping.axes.forEach { (target, binding) -> appendLine("A,$target,${binding.source},${binding.inverted}") }
        mapping.dpad.forEach { (target, source) -> appendLine("D,$target,$source") }
        mapping.triggerButtons.forEach { (target, source) -> appendLine("T,$target,$source") }
    }
}
