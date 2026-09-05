package dev.jonalakas.bridgepad.core.ports

import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState

/** Produces normalized controller states without knowing where they will be sent. */
interface GamepadInputSource {
    val sourceId: SourceId
    fun start(consumer: (VirtualGamepadState) -> Unit)
    fun stop()
}
