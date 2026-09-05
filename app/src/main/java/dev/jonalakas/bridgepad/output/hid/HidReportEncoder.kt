package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import kotlin.math.roundToInt

object HidReportEncoder {
    const val REPORT_SIZE = 9

    fun encode(state: VirtualGamepadState): ByteArray {
        var buttons = 0
        state.pressedButtons.forEach { buttons = buttons or (1 shl it.ordinal) }

        return byteArrayOf(
            buttons.toByte(),
            (buttons ushr 8).toByte(),
            state.dpad.hatValue.toByte(),
            state.leftStickX.toStickByte(),
            state.leftStickY.toStickByte(),
            state.rightStickX.toStickByte(),
            state.rightStickY.toStickByte(),
            state.leftTrigger.toTriggerByte(),
            state.rightTrigger.toTriggerByte(),
        )
    }

    private val DpadDirection.hatValue: Int
        get() = when (this) {
            DpadDirection.NORTH -> 0
            DpadDirection.NORTH_EAST -> 1
            DpadDirection.EAST -> 2
            DpadDirection.SOUTH_EAST -> 3
            DpadDirection.SOUTH -> 4
            DpadDirection.SOUTH_WEST -> 5
            DpadDirection.WEST -> 6
            DpadDirection.NORTH_WEST -> 7
            DpadDirection.NEUTRAL -> 8
        }

    private fun Float.toStickByte(): Byte = (coerceIn(-1f, 1f) * 127f).roundToInt().toByte()
    private fun Float.toTriggerByte(): Byte = (coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}
