package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState

internal class UsbHidReportParser(descriptor: ByteArray) {
    private data class Field(
        val reportId: Int, val bitOffset: Int, val bitSize: Int,
        val usagePage: Int, val usage: Int, val logicalMin: Int, val logicalMax: Int,
    )

    private val fields = parseFields(descriptor)
    private val hasRxRy = fields.any { it.usagePage == 0x01 && it.usage == 0x33 } &&
        fields.any { it.usagePage == 0x01 && it.usage == 0x34 }

    init { require(fields.isNotEmpty()) { "The USB HID descriptor has no readable gamepad fields." } }

    fun decode(report: ByteArray): VirtualGamepadState {
        val reportId = if (fields.any { it.reportId != 0 }) report.firstOrNull()?.toInt()?.and(0xff) ?: 0 else 0
        val payloadBitOffset = if (reportId == 0) 0 else 8
        var state = VirtualGamepadState()
        fields.filter { it.reportId == reportId }.forEach { field ->
            val raw = readBits(report, payloadBitOffset + field.bitOffset, field.bitSize)
            val value = if (field.logicalMin < 0) signExtend(raw, field.bitSize) else raw
            when (field.usagePage) {
                0x09 -> button(field.usage)?.let { control ->
                    state = state.copy(pressedButtons = if (value != 0) state.pressedButtons + control else state.pressedButtons - control)
                }
                0x01 -> state = applyDesktop(state, field.usage, value, field.logicalMin, field.logicalMax)
                0x02 -> when (field.usage) {
                    0xc4 -> state = state.copy(rightTrigger = normalizeTrigger(value, field.logicalMin, field.logicalMax))
                    0xc5 -> state = state.copy(leftTrigger = normalizeTrigger(value, field.logicalMin, field.logicalMax))
                }
            }
        }
        return state
    }

    private fun applyDesktop(state: VirtualGamepadState, usage: Int, value: Int, min: Int, max: Int): VirtualGamepadState {
        val axis = normalizeAxis(value, min, max)
        return when (usage) {
            0x30 -> state.copy(leftStickX = axis)
            0x31 -> state.copy(leftStickY = axis)
            0x32 -> if (hasRxRy) state.copy(leftTrigger = normalizeTrigger(value, min, max)) else state.copy(rightStickX = axis)
            0x33 -> state.copy(rightStickX = axis)
            0x34 -> state.copy(rightStickY = axis)
            0x35 -> if (hasRxRy) state.copy(rightTrigger = normalizeTrigger(value, min, max)) else state.copy(rightStickY = axis)
            0x39 -> state.copy(dpad = hat(value, min))
            else -> state
        }
    }

    private fun parseFields(bytes: ByteArray): List<Field> {
        val result = mutableListOf<Field>()
        val offsets = mutableMapOf<Int, Int>()
        var page = 0; var size = 0; var count = 0; var reportId = 0; var min = 0; var max = 1
        var usages = mutableListOf<Int>(); var usageMin: Int? = null
        var index = 0
        while (index < bytes.size) {
            val prefix = bytes[index++].toInt() and 0xff
            if (prefix == 0xfe) { if (index + 1 >= bytes.size) break; val n = bytes[index].toInt() and 0xff; index += n + 2; continue }
            val length = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            if (index + length > bytes.size) break
            var unsigned = 0
            repeat(length) { byte -> unsigned = unsigned or ((bytes[index + byte].toInt() and 0xff) shl (byte * 8)) }
            val signed = signExtend(unsigned, length * 8)
            index += length
            when (prefix and 0xfc) {
                0x04 -> page = unsigned
                0x14 -> min = signed
                0x24 -> max = signed
                0x74 -> size = unsigned
                0x94 -> count = unsigned
                0x84 -> reportId = unsigned
                0x08 -> usages += unsigned
                0x18 -> usageMin = unsigned
                0x80 -> {
                    val constant = unsigned and 1 != 0
                    val start = offsets[reportId] ?: 0
                    if (!constant && size in 1..32) repeat(count) { item ->
                        val usage = usages.getOrNull(item) ?: usageMin?.plus(item)
                        if (usage != null) result += Field(reportId, start + item * size, size, page, usage, min, max)
                    }
                    offsets[reportId] = start + size * count
                    usages = mutableListOf(); usageMin = null
                }
                0xa0, 0xc0 -> { usages = mutableListOf(); usageMin = null }
            }
        }
        return result
    }

    private fun readBits(bytes: ByteArray, offset: Int, size: Int): Int {
        var value = 0
        repeat(size) { bit ->
            val absolute = offset + bit
            if (absolute / 8 < bytes.size) value = value or (((bytes[absolute / 8].toInt() ushr (absolute % 8)) and 1) shl bit)
        }
        return value
    }

    private fun normalizeAxis(value: Int, min: Int, max: Int): Float =
        if (max <= min) 0f else (((value - min).toFloat() / (max - min)) * 2f - 1f).let { if (kotlin.math.abs(it) < .08f) 0f else it.coerceIn(-1f, 1f) }
    private fun normalizeTrigger(value: Int, min: Int, max: Int) = if (max <= min) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    private fun hat(value: Int, min: Int) = DpadDirection.entries.getOrElse(value - min) { DpadDirection.NEUTRAL }
    private fun button(usage: Int) = VirtualControl.entries.getOrNull(usage - 1)

    companion object {
        private fun signExtend(value: Int, bits: Int): Int {
            if (bits <= 0 || bits >= 32) return value
            val sign = 1 shl (bits - 1)
            return if (value and sign != 0) value or (-1 shl bits) else value
        }
    }
}
