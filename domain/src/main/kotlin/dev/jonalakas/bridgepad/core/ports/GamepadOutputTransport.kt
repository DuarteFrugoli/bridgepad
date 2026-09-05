package dev.jonalakas.bridgepad.core.ports

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.session.OutputTransportType

data class PointerReport(
    val buttons: Int = 0,
    val deltaX: Int = 0,
    val deltaY: Int = 0,
)

data class TransportCapabilities(
    val gamepad: Boolean = true,
    val pointer: Boolean = false,
    val worksInBackground: Boolean = false,
)

/** Sends logical input to one destination without knowing its physical source. */
interface GamepadOutputTransport {
    val type: OutputTransportType
    val capabilities: TransportCapabilities
    fun connect(destinationId: String): Boolean
    fun sendGamepad(state: VirtualGamepadState): Boolean
    fun sendPointer(report: PointerReport): Boolean
    fun disconnect()
}
