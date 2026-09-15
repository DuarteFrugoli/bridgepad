package dev.jonalakas.bridgepad.protocol

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport

/** Transport-independent messages exchanged by BridgePad peers. */
sealed interface BridgeMessage {
    val type: BridgeMessageType

    data class Hello(
        val peerId: PeerId,
        val capabilities: BridgeCapabilities,
        val peerName: String,
        val appVersion: String,
    ) : BridgeMessage {
        override val type = BridgeMessageType.HELLO
    }

    data class HelloAck(
        val peerId: PeerId,
        val capabilities: BridgeCapabilities,
        val peerName: String,
        val appVersion: String,
    ) : BridgeMessage {
        override val type = BridgeMessageType.HELLO_ACK
    }

    data class SessionStart(
        val inputKind: BridgeInputKind,
        val requestedCapabilities: BridgeCapabilities,
    ) : BridgeMessage {
        override val type = BridgeMessageType.SESSION_START
    }

    data class SessionReady(val enabledCapabilities: BridgeCapabilities) : BridgeMessage {
        override val type = BridgeMessageType.SESSION_READY
    }

    data class SessionStop(val reason: BridgeStopReason) : BridgeMessage {
        override val type = BridgeMessageType.SESSION_STOP
    }

    data class GamepadSnapshot(val state: VirtualGamepadState) : BridgeMessage {
        override val type = BridgeMessageType.GAMEPAD_SNAPSHOT
    }

    data class PointerFrame(
        val report: PointerReport,
    ) : BridgeMessage {
        override val type = BridgeMessageType.POINTER
    }

    data class Ping(val nonce: Long) : BridgeMessage {
        override val type = BridgeMessageType.PING
    }

    data class Pong(val nonce: Long) : BridgeMessage {
        override val type = BridgeMessageType.PONG
    }

    data class Status(val status: BridgeStatusCode, val detail: String = "") : BridgeMessage {
        override val type = BridgeMessageType.STATUS
    }

    data class Error(
        val code: BridgeErrorCode,
        val fatal: Boolean,
        val detail: String,
    ) : BridgeMessage {
        override val type = BridgeMessageType.ERROR
    }

    data class Rumble(
        val lowFrequency: Float,
        val highFrequency: Float,
        val durationMillis: Int,
    ) : BridgeMessage {
        override val type = BridgeMessageType.RUMBLE

        init {
            require(durationMillis in 0..0xffff) {
                "durationMillis must fit in an unsigned 16-bit integer"
            }
        }
    }
}
