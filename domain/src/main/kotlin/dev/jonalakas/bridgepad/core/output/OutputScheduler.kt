package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import java.util.ArrayDeque

class OutputScheduler(
    reportRateHz: Int,
    keepaliveIntervalMillis: Long = DEFAULT_KEEPALIVE_INTERVAL_MILLIS,
    private val maximumPendingTransitions: Int = Int.MAX_VALUE,
) {
    private val intervalNanos: Long
    private val keepaliveIntervalNanos: Long
    private val transitions = ArrayDeque<VirtualGamepadState>()
    private var latest = VirtualGamepadState()
    private var lastSubmitted = VirtualGamepadState()
    private var lastSuccessful: VirtualGamepadState? = null
    private var lastAttemptNanos: Long? = null
    private var lastSuccessfulNanos: Long? = null
    private var inFlight: PendingReport? = null
    private var rejectedReports = 0L
    private var droppedTransitions = 0L
    private var peakPendingTransitions = 0

    init {
        require(reportRateHz > 0)
        require(keepaliveIntervalMillis > 0)
        require(maximumPendingTransitions > 0)
        intervalNanos = 1_000_000_000L / reportRateHz
        keepaliveIntervalNanos = keepaliveIntervalMillis * 1_000_000L
    }

    @Synchronized
    fun submit(state: VirtualGamepadState) {
        if (state.pressedButtons != lastSubmitted.pressedButtons || state.dpad != lastSubmitted.dpad) {
            if (transitions.size >= maximumPendingTransitions) {
                droppedTransitions += transitions.size
                transitions.clear()
                inFlight = null
            }
            transitions.addLast(state)
            peakPendingTransitions = maxOf(peakPendingTransitions, transitions.size)
        }
        latest = state
        lastSubmitted = state
    }

    @Synchronized
    fun hasPending(nowNanos: Long): Boolean {
        val lastAttempt = lastAttemptNanos
        if (lastAttempt != null && nowNanos - lastAttempt < intervalNanos) return false
        if (inFlight != null || transitions.isNotEmpty()) return true
        if (lastSuccessful != latest) return true
        val lastSuccess = lastSuccessfulNanos ?: return true
        return nowNanos - lastSuccess >= keepaliveIntervalNanos
    }

    @Synchronized
    fun poll(nowNanos: Long): VirtualGamepadState? {
        if (!hasPending(nowNanos)) return null

        val pending = inFlight ?: (
            if (transitions.isNotEmpty()) {
                PendingReport(transitions.first(), isTransition = true)
            } else {
                PendingReport(latest, isTransition = false)
            }
            ).also { inFlight = it }
        lastAttemptNanos = nowNanos
        return pending.state
    }

    @Synchronized
    fun complete(state: VirtualGamepadState, sent: Boolean, nowNanos: Long) {
        val pending = inFlight ?: return
        if (pending.state != state) return
        if (!sent) {
            rejectedReports++
            if (pending.isTransition && transitions.isNotEmpty()) transitions.removeFirst()
            inFlight = null
            return
        }

        if (pending.isTransition && transitions.isNotEmpty()) transitions.removeFirst()
        lastSuccessful = state
        lastSuccessfulNanos = nowNanos
        inFlight = null
    }

    @Synchronized
    fun diagnostics(): OutputSchedulerDiagnostics = OutputSchedulerDiagnostics(
        pendingTransitions = transitions.size,
        peakPendingTransitions = peakPendingTransitions,
        rejectedReports = rejectedReports,
        droppedTransitions = droppedTransitions,
    )

    /** Drops stale discrete history while retaining the newest full state. */
    @Synchronized
    fun discardPendingTransitions() {
        transitions.clear()
        inFlight = null
        lastAttemptNanos = null
    }

    @Synchronized
    fun stop(): VirtualGamepadState {
        transitions.clear()
        latest = VirtualGamepadState()
        lastSubmitted = latest
        lastSuccessful = null
        lastAttemptNanos = null
        lastSuccessfulNanos = null
        inFlight = null
        rejectedReports = 0L
        droppedTransitions = 0L
        peakPendingTransitions = 0
        return latest
    }

    private data class PendingReport(
        val state: VirtualGamepadState,
        val isTransition: Boolean,
    )

    private companion object {
        const val DEFAULT_KEEPALIVE_INTERVAL_MILLIS = 500L
    }
}

data class OutputSchedulerDiagnostics(
    val pendingTransitions: Int,
    val peakPendingTransitions: Int,
    val rejectedReports: Long,
    val droppedTransitions: Long,
)
