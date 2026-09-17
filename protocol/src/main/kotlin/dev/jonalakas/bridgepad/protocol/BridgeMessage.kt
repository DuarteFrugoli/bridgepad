package dev.jonalakas.bridgepad.protocol

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.ports.KeyboardInput

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

    data class PairRequest(
        val peerId: PeerId,
        val peerName: String,
        val clientNonce: ByteArray,
    ) : BridgeMessage {
        override val type = BridgeMessageType.PAIR_REQUEST

        init {
            require(clientNonce.size == BridgeAuthentication.NONCE_SIZE)
        }
    }

    data class PairChallenge(
        val peerId: PeerId,
        val serverNonce: ByteArray,
        val salt: ByteArray,
        val iterations: Int,
        val expiresInSeconds: Int,
        val serverProof: ByteArray,
    ) : BridgeMessage {
        override val type = BridgeMessageType.PAIR_CHALLENGE

        init {
            require(serverNonce.size == BridgeAuthentication.NONCE_SIZE)
            require(salt.size == BridgeAuthentication.SALT_SIZE)
            require(iterations > 0)
            require(expiresInSeconds in 0..0xffff)
            require(serverProof.size == BridgeAuthentication.PROOF_SIZE)
        }
    }

    data class PairProof(val proof: ByteArray) : BridgeMessage {
        override val type = BridgeMessageType.PAIR_PROOF

        init {
            require(proof.size == BridgeAuthentication.PROOF_SIZE)
        }
    }

    data class PairResult(
        val accepted: Boolean,
        val sharedSecret: ByteArray,
        val peerName: String,
        val detail: String = "",
    ) : BridgeMessage {
        override val type = BridgeMessageType.PAIR_RESULT

        init {
            require(
                sharedSecret.size == BridgeAuthentication.SHARED_SECRET_SIZE ||
                    (!accepted && sharedSecret.isEmpty()),
            )
        }
    }

    data class AuthRequest(
        val peerId: PeerId,
        val clientNonce: ByteArray,
    ) : BridgeMessage {
        override val type = BridgeMessageType.AUTH_REQUEST

        init {
            require(clientNonce.size == BridgeAuthentication.NONCE_SIZE)
        }
    }

    data class AuthChallenge(
        val peerId: PeerId,
        val serverNonce: ByteArray,
    ) : BridgeMessage {
        override val type = BridgeMessageType.AUTH_CHALLENGE

        init {
            require(serverNonce.size == BridgeAuthentication.NONCE_SIZE)
        }
    }

    data class AuthProof(val proof: ByteArray) : BridgeMessage {
        override val type = BridgeMessageType.AUTH_PROOF

        init {
            require(proof.size == BridgeAuthentication.PROOF_SIZE)
        }
    }

    data class AuthResult(
        val accepted: Boolean,
        val peerName: String,
        val detail: String = "",
    ) : BridgeMessage {
        override val type = BridgeMessageType.AUTH_RESULT
    }

    data class GamepadSnapshot(val state: VirtualGamepadState) : BridgeMessage {
        override val type = BridgeMessageType.GAMEPAD_SNAPSHOT
    }

    data class PointerFrame(
        val report: PointerReport,
    ) : BridgeMessage {
        override val type = BridgeMessageType.POINTER
    }

    data class KeyboardFrame(val input: KeyboardInput) : BridgeMessage {
        override val type = BridgeMessageType.KEYBOARD
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
