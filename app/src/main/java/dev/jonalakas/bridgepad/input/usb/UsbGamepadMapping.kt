package dev.jonalakas.bridgepad.input.usb

import android.content.Context
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState

data class AxisBinding(val source: VirtualAxis, val inverted: Boolean = false)

data class UsbGamepadMapping(
    val buttons: Map<VirtualControl, VirtualControl> = VirtualControl.entries.associateWith { it },
    val axes: Map<VirtualAxis, AxisBinding> = VirtualAxis.entries.associateWith { AxisBinding(it) },
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
            dpad = raw.dpad,
            leftStickX = axis(VirtualAxis.LEFT_X).coerceIn(-1f, 1f),
            leftStickY = axis(VirtualAxis.LEFT_Y).coerceIn(-1f, 1f),
            rightStickX = axis(VirtualAxis.RIGHT_X).coerceIn(-1f, 1f),
            rightStickY = axis(VirtualAxis.RIGHT_Y).coerceIn(-1f, 1f),
            leftTrigger = axis(VirtualAxis.LEFT_TRIGGER).let { if (it < 0f) (it + 1f) / 2f else it }.coerceIn(0f, 1f),
            rightTrigger = axis(VirtualAxis.RIGHT_TRIGGER).let { if (it < 0f) (it + 1f) / 2f else it }.coerceIn(0f, 1f),
        )
    }
}

object UsbGamepadMappingStore {
    private const val PREFERENCES = "usb_gamepad_mappings"
    private lateinit var context: Context

    fun initialize(context: Context) { this.context = context.applicationContext }

    fun load(deviceKey: String): UsbGamepadMapping {
        if (!::context.isInitialized) return UsbGamepadMapping()
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(deviceKey, null).orEmpty()
        val buttons = mutableMapOf<VirtualControl, VirtualControl>()
        val axes = mutableMapOf<VirtualAxis, AxisBinding>()
        saved.lineSequence().forEach { line ->
            val parts = line.split(',')
            runCatching {
                when (parts.firstOrNull()) {
                    "B" -> buttons[VirtualControl.valueOf(parts[1])] = VirtualControl.valueOf(parts[2])
                    "A" -> axes[VirtualAxis.valueOf(parts[1])] = AxisBinding(VirtualAxis.valueOf(parts[2]), parts.getOrNull(3)?.toBoolean() == true)
                }
            }
        }
        return if (buttons.isEmpty() && axes.isEmpty()) UsbGamepadMapping() else UsbGamepadMapping(buttons, axes)
    }

    fun save(deviceKey: String, mapping: UsbGamepadMapping) {
        check(::context.isInitialized)
        val value = buildString {
            mapping.buttons.forEach { (target, source) -> appendLine("B,$target,$source") }
            mapping.axes.forEach { (target, binding) -> appendLine("A,$target,${binding.source},${binding.inverted}") }
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(deviceKey, value).apply()
    }

    fun reset(deviceKey: String) {
        if (::context.isInitialized) context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(deviceKey).apply()
    }
}
