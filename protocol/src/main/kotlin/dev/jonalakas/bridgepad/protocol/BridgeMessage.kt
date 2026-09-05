package dev.jonalakas.bridgepad.protocol

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport

/**
 * Language-neutral messages exchanged with a BridgePad receiver.
 *
 * Wire encoders belong in this module so Android and desktop implementations can
 * evolve independently while sharing a versioned contract.
 */
sealed interface BridgeMessage {
    val sequence: Long

    data class Hello(
        override val sequence: Long,
        val protocolVersion: Int = BridgeProtocol.CURRENT_VERSION,
        val clientName: String,
    ) : BridgeMessage

    data class GamepadFrame(
        override val sequence: Long,
        val state: VirtualGamepadState,
    ) : BridgeMessage

    data class PointerFrame(
        override val sequence: Long,
        val report: PointerReport,
    ) : BridgeMessage

    data class Heartbeat(override val sequence: Long) : BridgeMessage
}

object BridgeProtocol {
    const val CURRENT_VERSION = 1
    const val SERVICE_ID = "bridgepad"
}
