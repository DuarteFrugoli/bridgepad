package dev.jonalakas.bridgepad.core.ports

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterDescriptor

data class PointerReport(
    val buttons: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
)

enum class KeyboardKey {
    BACKSPACE,
    ENTER,
    TAB,
    ESCAPE,
}

sealed interface KeyboardInput {
    data class Text(val value: String) : KeyboardInput {
        init {
            require(value.isNotEmpty()) { "Keyboard text must not be empty." }
        }
    }

    data class Key(val key: KeyboardKey) : KeyboardInput
}

data class TransportCapabilities(
    val gamepad: Boolean = true,
    val pointer: Boolean = false,
    val keyboard: Boolean = false,
    val worksInBackground: Boolean = false,
)

/** Sends logical input to one destination without knowing its physical source. */
interface GamepadOutputTransport {
    val descriptor: OutputAdapterDescriptor
    val capabilities: TransportCapabilities
    fun connect(destination: DestinationType, destinationId: String): Boolean
    fun sendGamepad(state: VirtualGamepadState): Boolean
    fun sendPointer(report: PointerReport): Boolean
    fun sendKeyboard(input: KeyboardInput): Boolean
    fun disconnect()
}
