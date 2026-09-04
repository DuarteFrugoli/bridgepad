package dev.jonalakas.bridgepad.core.mapping

import kotlin.math.hypot

object AxisMath {
    fun normalize(raw: Float, minimum: Float, maximum: Float): Float {
        require(maximum > minimum)
        val center = (minimum + maximum) / 2f
        val halfRange = (maximum - minimum) / 2f
        return ((raw - center) / halfRange).coerceIn(-1f, 1f)
    }

    fun normalizeTrigger(raw: Float, minimum: Float, maximum: Float): Float {
        require(maximum > minimum)
        return ((raw - minimum) / (maximum - minimum)).coerceIn(0f, 1f)
    }

    fun radialDeadzone(x: Float, y: Float, deadzone: Float, rescale: Boolean = true): Pair<Float, Float> {
        require(deadzone in 0f..<1f)
        val magnitude = hypot(x, y)
        if (magnitude <= deadzone || magnitude == 0f) return 0f to 0f
        val limitedMagnitude = magnitude.coerceAtMost(1f)
        val outputMagnitude = if (rescale) {
            (limitedMagnitude - deadzone) / (1f - deadzone)
        } else {
            limitedMagnitude
        }
        val scale = outputMagnitude / magnitude
        return (x * scale).coerceIn(-1f, 1f) to (y * scale).coerceIn(-1f, 1f)
    }
}
