package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import java.util.ArrayDeque

class OutputScheduler(reportRateHz: Int) {
    private val intervalNanos: Long
    private val transitions = ArrayDeque<VirtualGamepadState>()
    private var latest = VirtualGamepadState()
    private var lastSubmitted = VirtualGamepadState()
    private var lastEmissionNanos: Long? = null

    init {
        require(reportRateHz > 0)
        intervalNanos = 1_000_000_000L / reportRateHz
    }

    fun submit(state: VirtualGamepadState) {
        if (state.pressedButtons != lastSubmitted.pressedButtons || state.dpad != lastSubmitted.dpad) {
            transitions.addLast(state)
        }
        latest = state
        lastSubmitted = state
    }

    fun poll(nowNanos: Long): VirtualGamepadState? {
        val lastEmission = lastEmissionNanos
        if (lastEmission != null && nowNanos - lastEmission < intervalNanos) return null

        lastEmissionNanos = nowNanos
        return if (transitions.isEmpty()) latest else transitions.removeFirst()
    }

    fun stop(): VirtualGamepadState {
        transitions.clear()
        latest = VirtualGamepadState()
        lastSubmitted = latest
        lastEmissionNanos = null
        return latest
    }
}
