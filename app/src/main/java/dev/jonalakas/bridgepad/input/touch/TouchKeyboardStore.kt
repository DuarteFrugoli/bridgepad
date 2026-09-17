package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.KeyboardInput

object TouchKeyboardStore {
    private val pending = ArrayDeque<KeyboardInput>()

    @Synchronized
    fun submit(input: KeyboardInput) {
        if (pending.size >= MAX_PENDING_INPUTS) pending.removeFirst()
        pending.addLast(input)
    }

    @Synchronized
    fun consume(): KeyboardInput? = pending.removeFirstOrNull()

    @Synchronized
    fun clear() = pending.clear()

    private const val MAX_PENDING_INPUTS = 128
}
