package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.PointerReport
import kotlin.math.roundToInt

object TouchMouseStore {
    private var accumulatedX = 0f
    private var accumulatedY = 0f
    private var accumulatedScrollY = 0f
    private val pendingClicks = ArrayDeque<Int>()
    private var releasePending = false
    private var inFlight: PendingPointer? = null
    private var inputEventCount = 0L
    private var invertedScroll = true

    @Synchronized
    fun move(deltaX: Float, deltaY: Float) {
        if (deltaX == 0f && deltaY == 0f) return
        accumulatedX += deltaX * POINTER_SENSITIVITY
        accumulatedY += deltaY * POINTER_SENSITIVITY
        inputEventCount++
    }

    @Synchronized
    fun scroll(deltaY: Float) {
        if (deltaY == 0f) return
        val direction = if (invertedScroll) 1f else -1f
        accumulatedScrollY += deltaY * SCROLL_SENSITIVITY * direction
        inputEventCount++
    }

    @Synchronized
    fun setInvertedScroll(inverted: Boolean) {
        invertedScroll = inverted
    }

    @Synchronized
    fun click() {
        pendingClicks.addLast(LEFT_BUTTON)
        inputEventCount++
    }

    @Synchronized
    fun rightClick() {
        pendingClicks.addLast(RIGHT_BUTTON)
        inputEventCount++
    }

    @Synchronized
    fun peek(): PointerReport? {
        inFlight?.let { return it.report }
        val deltaX = nextX()
        val deltaY = nextY()
        val scrollY = nextScrollY()
        val pending = when {
            releasePending -> PendingPointer(
                PointerReport(0, deltaX, deltaY, scrollY),
                PendingPointerKind.RELEASE,
            )
            pendingClicks.isNotEmpty() -> PendingPointer(
                PointerReport(pendingClicks.first(), deltaX, deltaY, scrollY),
                PendingPointerKind.PRESS,
            )
            deltaX != 0 || deltaY != 0 || scrollY != 0 -> PendingPointer(
                PointerReport(0, deltaX, deltaY, scrollY),
                PendingPointerKind.MOVEMENT,
            )
            else -> null
        }
        inFlight = pending
        return pending?.report
    }

    @Synchronized
    fun acknowledge(sent: Boolean) {
        val pending = inFlight ?: return
        if (!sent) return

        accumulatedX -= pending.report.deltaX
        accumulatedY -= pending.report.deltaY
        accumulatedScrollY -= pending.report.scrollY
        when (pending.kind) {
            PendingPointerKind.PRESS -> {
                if (pendingClicks.isNotEmpty()) pendingClicks.removeFirst()
                releasePending = true
            }
            PendingPointerKind.RELEASE -> releasePending = false
            PendingPointerKind.MOVEMENT -> Unit
        }
        inFlight = null
    }

    @Synchronized
    fun consume(): PointerReport? {
        val report = peek() ?: return null
        acknowledge(sent = true)
        return report
    }

    @Synchronized
    fun diagnostics(): TouchMouseDiagnostics {
        val movementPending = nextX() != 0 || nextY() != 0 || nextScrollY() != 0
        return TouchMouseDiagnostics(
            inputEventCount = inputEventCount,
            pendingReportCount = pendingClicks.size +
                (if (releasePending) 1 else 0) +
                (if (movementPending) 1 else 0),
        )
    }

    @Synchronized
    fun hasPending(): Boolean {
        if (inFlight != null || releasePending || pendingClicks.isNotEmpty()) return true
        if (
            accumulatedX.roundToInt() == 0 &&
            accumulatedY.roundToInt() == 0 &&
            accumulatedScrollY.roundToInt() == 0
        ) return false
        return true
    }

    @Synchronized
    fun clear() {
        accumulatedX = 0f
        accumulatedY = 0f
        accumulatedScrollY = 0f
        pendingClicks.clear()
        releasePending = false
        inFlight = null
    }

    private fun nextX(): Int = accumulatedX.roundToInt().coerceIn(-127, 127)
    private fun nextY(): Int = accumulatedY.roundToInt().coerceIn(-127, 127)
    private fun nextScrollY(): Int = accumulatedScrollY.roundToInt().coerceIn(-127, 127)

    private data class PendingPointer(
        val report: PointerReport,
        val kind: PendingPointerKind,
    )

    private enum class PendingPointerKind {
        PRESS,
        RELEASE,
        MOVEMENT,
    }

    private const val LEFT_BUTTON = 1
    private const val RIGHT_BUTTON = 2
    private const val POINTER_SENSITIVITY = 0.8f
    private const val SCROLL_SENSITIVITY = 0.05f
}

data class TouchMouseDiagnostics(
    val inputEventCount: Long,
    val pendingReportCount: Int,
)
