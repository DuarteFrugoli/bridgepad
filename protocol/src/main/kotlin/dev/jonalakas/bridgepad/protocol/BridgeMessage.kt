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

    data class MediaOffer(
        val requestedCapabilities: BridgeCapabilities,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFramesPerSecond: Int,
        val maxBitrateBitsPerSecond: Long,
    ) : BridgeMessage {
        override val type = BridgeMessageType.MEDIA_OFFER

        init {
            require(maxWidth in 1..0xffff)
            require(maxHeight in 1..0xffff)
            require(maxFramesPerSecond in 1..0xffff)
            require(maxBitrateBitsPerSecond in 1..0xffff_ffffL)
        }
    }

    data class MediaAnswer(
        val enabledCapabilities: BridgeCapabilities,
        val codec: BridgeVideoCodec,
        val width: Int,
        val height: Int,
        val framesPerSecond: Int,
        val targetBitrateBitsPerSecond: Long,
        val keyframeIntervalMillis: Int,
    ) : BridgeMessage {
        override val type = BridgeMessageType.MEDIA_ANSWER

        init {
            require(width in 1..0xffff)
            require(height in 1..0xffff)
            require(framesPerSecond in 1..0xffff)
            require(targetBitrateBitsPerSecond in 1..0xffff_ffffL)
            require(keyframeIntervalMillis in 1..0xffff)
        }
    }

    data class VideoChunk(
        val frameId: Long,
        val presentationTimestampMicros: Long,
        val keyframe: Boolean,
        val chunkIndex: Int,
        val chunkCount: Int,
        val totalFrameBytes: Long,
        val generationMicros: Long,
        val encodeMicros: Long,
        val data: ByteArray,
    ) : BridgeMessage {
        override val type = BridgeMessageType.VIDEO_CHUNK

        init {
            require(frameId in 0..0xffff_ffffL)
            require(presentationTimestampMicros >= 0)
            require(chunkIndex in 0..0xffff)
            require(chunkCount in 1..0xffff)
            require(chunkIndex < chunkCount)
            require(totalFrameBytes in 1..0xffff_ffffL)
            require(generationMicros in 0..0xffff_ffffL)
            require(encodeMicros in 0..0xffff_ffffL)
            require(data.isNotEmpty())
        }
    }

    data class MediaFeedback(
        val lastPresentedFrameId: Long,
        val lostFrames: Long,
        val receiveBitrateBitsPerSecond: Long,
        val decodeMicros: Long,
        val presentationMicros: Long,
        val requestedBitrateBitsPerSecond: Long,
        val keyframeRequested: Boolean,
    ) : BridgeMessage {
        override val type = BridgeMessageType.MEDIA_FEEDBACK

        init {
            require(lastPresentedFrameId in 0..0xffff_ffffL)
            require(lostFrames in 0..0xffff_ffffL)
            require(receiveBitrateBitsPerSecond in 0..0xffff_ffffL)
            require(decodeMicros in 0..0xffff_ffffL)
            require(presentationMicros in 0..0xffff_ffffL)
            require(requestedBitrateBitsPerSecond in 0..0xffff_ffffL)
        }
    }

    data class MediaStop(val reason: BridgeMediaStopReason) : BridgeMessage {
        override val type = BridgeMessageType.MEDIA_STOP
    }

    data class WebRtcOffer(val sdp: String) : BridgeMessage {
        override val type = BridgeMessageType.WEBRTC_OFFER

        init {
            require(sdp.isNotEmpty())
            require(sdp.toByteArray(Charsets.UTF_8).size <= BridgeProtocol.MAX_PAYLOAD_SIZE)
        }
    }

    data class WebRtcAnswer(val sdp: String) : BridgeMessage {
        override val type = BridgeMessageType.WEBRTC_ANSWER

        init {
            require(sdp.isNotEmpty())
            require(sdp.toByteArray(Charsets.UTF_8).size <= BridgeProtocol.MAX_PAYLOAD_SIZE)
        }
    }
}
