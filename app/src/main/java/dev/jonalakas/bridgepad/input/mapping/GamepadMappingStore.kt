package dev.jonalakas.bridgepad.input.mapping

import android.content.Context
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.mapping.AxisBinding
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping

/** Android persistence adapter for the platform-independent mapping model. */
object GamepadMappingStore {
    // Keep the original preference file name so existing profiles migrate automatically.
    private const val PREFERENCES = "usb_gamepad_mappings"
    private lateinit var context: Context

    fun initialize(context: Context) { this.context = context.applicationContext }

    fun load(deviceKey: String): GamepadMapping {
        if (!::context.isInitialized) return GamepadMapping()
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(deviceKey, null).orEmpty()
        val buttons = mutableMapOf<VirtualControl, VirtualControl>()
        val axes = mutableMapOf<VirtualAxis, AxisBinding>()
        val dpad = mutableMapOf<DpadDirection, DpadDirection>()
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
                }
            }
        }
        return if (buttons.isEmpty() && axes.isEmpty() && dpad.isEmpty()) {
            GamepadMapping()
        } else {
            GamepadMapping(
                buttons = buttons,
                axes = axes,
                dpad = dpad.ifEmpty { DpadDirection.entries.associateWith { it } },
            )
        }
    }

    fun save(deviceKey: String, mapping: GamepadMapping) {
        check(::context.isInitialized)
        val value = buildString {
            mapping.buttons.forEach { (target, source) -> appendLine("B,$target,$source") }
            mapping.axes.forEach { (target, binding) -> appendLine("A,$target,${binding.source},${binding.inverted}") }
            mapping.dpad.forEach { (target, source) -> appendLine("D,$target,$source") }
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(deviceKey, value)
            .apply()
    }

    fun reset(deviceKey: String) {
        if (::context.isInitialized) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(deviceKey)
                .apply()
        }
    }
}
