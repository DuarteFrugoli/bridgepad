package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.KeyboardInput

object TouchKeyboardStore {
    private val pending = ArrayDeque<KeyboardInput>()
    private var inFlight: KeyboardInput? = null

    @Synchronized
    fun submit(input: KeyboardInput) {
        if (pending.size >= MAX_PENDING_INPUTS) {
            if (inFlight == null) pending.removeFirst() else pending.removeLast()
        }
        pending.addLast(input)
    }

    @Synchronized
    fun peek(): KeyboardInput? = inFlight ?: pending.firstOrNull()?.also { inFlight = it }

    @Synchronized
    fun acknowledge(sent: Boolean) {
        val current = inFlight ?: return
        if (!sent) return
        if (pending.firstOrNull() == current) pending.removeFirst()
        inFlight = null
    }

    @Synchronized
    fun consume(): KeyboardInput? {
        val input = peek() ?: return null
        acknowledge(sent = true)
        return input
    }

    @Synchronized
    fun hasPending(): Boolean = inFlight != null || pending.isNotEmpty()

    @Synchronized
    fun clear() {
        pending.clear()
        inFlight = null
    }

    private const val MAX_PENDING_INPUTS = 128
}
