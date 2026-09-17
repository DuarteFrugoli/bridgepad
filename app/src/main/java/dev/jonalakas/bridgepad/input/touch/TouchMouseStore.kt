package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.PointerReport
import kotlin.math.roundToInt

object TouchMouseStore {
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private var accumulatedScrollY = 0f
    private val pendingClicks = ArrayDeque<Int>()
    private var releasePending = false

    @Synchronized
    fun move(deltaX: Float, deltaY: Float) {
        accumulatedX += deltaX * POINTER_SENSITIVITY
        accumulatedY += deltaY * POINTER_SENSITIVITY
    }

    @Synchronized
    fun scroll(deltaY: Float) {
        accumulatedScrollY += deltaY * SCROLL_SENSITIVITY
    }

    @Synchronized
    fun click() { pendingClicks.addLast(LEFT_BUTTON) }

    @Synchronized
    fun rightClick() { pendingClicks.addLast(RIGHT_BUTTON) }

    @Synchronized
    fun consume(): PointerReport? {
        if (releasePending) {
            releasePending = false
            return PointerReport(0, takeX(), takeY(), takeScrollY())
        }
        if (pendingClicks.isNotEmpty()) {
            releasePending = true
            return PointerReport(pendingClicks.removeFirst(), takeX(), takeY(), takeScrollY())
        }
        if (
            accumulatedX.roundToInt() == 0 &&
            accumulatedY.roundToInt() == 0 &&
            accumulatedScrollY.roundToInt() == 0
        ) return null
        return PointerReport(0, takeX(), takeY(), takeScrollY())
    }

    @Synchronized
    fun clear() {
        accumulatedX = 0f
        accumulatedY = 0f
        accumulatedScrollY = 0f
        pendingClicks.clear()
        releasePending = false
    }

    private fun takeX(): Int = accumulatedX.roundToInt().coerceIn(-127, 127).also { accumulatedX -= it }
    private fun takeY(): Int = accumulatedY.roundToInt().coerceIn(-127, 127).also { accumulatedY -= it }
    private fun takeScrollY(): Int = accumulatedScrollY.roundToInt().coerceIn(-127, 127).also {
        accumulatedScrollY -= it
    }

    private const val LEFT_BUTTON = 1
    private const val RIGHT_BUTTON = 2
    private const val POINTER_SENSITIVITY = 0.8f
    private const val SCROLL_SENSITIVITY = -0.05f
}
