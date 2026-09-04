package dev.jonalakas.bridgepad.input.touch

import kotlin.math.roundToInt

data class MouseReport(val buttons: Int, val deltaX: Int, val deltaY: Int)

object TouchMouseStore {
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private var clickPending = false
    private var releasePending = false

    @Synchronized
    fun move(deltaX: Float, deltaY: Float) {
        accumulatedX += deltaX * POINTER_SENSITIVITY
        accumulatedY += deltaY * POINTER_SENSITIVITY
    }

    @Synchronized
    fun click() { clickPending = true }

    @Synchronized
    fun consume(): MouseReport? {
        if (clickPending) {
            clickPending = false
            releasePending = true
            return MouseReport(LEFT_BUTTON, takeX(), takeY())
        }
        if (releasePending) {
            releasePending = false
            return MouseReport(0, takeX(), takeY())
        }
        if (accumulatedX.roundToInt() == 0 && accumulatedY.roundToInt() == 0) return null
        return MouseReport(0, takeX(), takeY())
    }

    @Synchronized
    fun clear() {
        accumulatedX = 0f
        accumulatedY = 0f
        clickPending = false
        releasePending = false
    }

    private fun takeX(): Int = accumulatedX.roundToInt().coerceIn(-127, 127).also { accumulatedX -= it }
    private fun takeY(): Int = accumulatedY.roundToInt().coerceIn(-127, 127).also { accumulatedY -= it }

    private const val LEFT_BUTTON = 1
    private const val POINTER_SENSITIVITY = 0.8f
}
