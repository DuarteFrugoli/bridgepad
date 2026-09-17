package dev.jonalakas.bridgepad.protocol

object BridgeProtocol {
    const val MAGIC: Int = 0x42504431 // "BPD1"
    const val MAJOR_VERSION: Int = 1
    const val MINOR_VERSION: Int = 0
    const val HEADER_SIZE: Int = 32
    const val MAX_PAYLOAD_SIZE: Int = 4_096
    const val SERVICE_ID: String = "bridgepad"
}

class BridgeProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

data class BridgePacket(
    val sessionId: Long,
    val sequence: Long,
    val timestampMicros: Long,
    val message: BridgeMessage,
) {
    init {
        require(sessionId >= 0) { "sessionId must fit in a signed 64-bit integer" }
        require(sequence in 0..0xffff_ffffL) { "sequence must fit in an unsigned 32-bit integer" }
        require(timestampMicros >= 0) { "timestampMicros must fit in a signed 64-bit integer" }
    }
}

data class PeerId(val high: Long, val low: Long) {
    init {
        require(high != 0L || low != 0L) { "Peer ID must not be all zeroes" }
    }
}

enum class BridgeCapability(val bit: Int) {
    GAMEPAD(0), POINTER(1), RUMBLE(2), VIDEO(3), AUDIO(4), KEYBOARD(5),
}

@JvmInline
value class BridgeCapabilities(val bits: Int) {
    operator fun contains(capability: BridgeCapability): Boolean =
        bits and (1 shl capability.bit) != 0

    operator fun plus(capability: BridgeCapability): BridgeCapabilities =
        BridgeCapabilities(bits or (1 shl capability.bit))

    infix fun intersect(other: BridgeCapabilities): BridgeCapabilities =
        BridgeCapabilities(bits and other.bits)

    companion object {
        val NONE = BridgeCapabilities(0)

        fun of(vararg capabilities: BridgeCapability): BridgeCapabilities =
            capabilities.fold(NONE) { result, capability -> result + capability }
    }
}

enum class BridgeMessageType(val code: Int) {
    HELLO(0x01), HELLO_ACK(0x02), SESSION_START(0x03), SESSION_READY(0x04), SESSION_STOP(0x05),
    PAIR_REQUEST(0x06), PAIR_CHALLENGE(0x07), PAIR_PROOF(0x08), PAIR_RESULT(0x09),
    AUTH_REQUEST(0x0a), AUTH_CHALLENGE(0x0b), AUTH_PROOF(0x0c), AUTH_RESULT(0x0d),
    GAMEPAD_SNAPSHOT(0x10), POINTER(0x11), KEYBOARD(0x12),
    PING(0x20), PONG(0x21), STATUS(0x30), ERROR(0x31),
    RUMBLE(0x40);

    companion object {
        fun fromCode(code: Int): BridgeMessageType = entries.firstOrNull { it.code == code }
            ?: throw BridgeProtocolException("Unknown message type: $code")
    }
}

object BridgeAuthentication {
    const val NONCE_SIZE = 32
    const val SALT_SIZE = 16
    const val PROOF_SIZE = 32
    const val SHARED_SECRET_SIZE = 32
    const val PAIRING_CODE_DIGITS = 12
    const val PBKDF2_ITERATIONS = 210_000
}

enum class BridgeInputKind(val code: Int) {
    TOUCHSCREEN(0), PHYSICAL_GAMEPAD(1), AUTOMATIC(2);

    companion object {
        fun fromCode(code: Int): BridgeInputKind = entries.firstOrNull { it.code == code }
            ?: throw BridgeProtocolException("Unknown input kind: $code")
    }
}

enum class BridgeStopReason(val code: Int) {
    USER_REQUEST(0), CLIENT_SHUTDOWN(1), RECEIVER_SHUTDOWN(2), TRANSPORT_LOST(3), PROTOCOL_ERROR(4);

    companion object {
        fun fromCode(code: Int): BridgeStopReason = entries.firstOrNull { it.code == code }
            ?: throw BridgeProtocolException("Unknown stop reason: $code")
    }
}

enum class BridgeStatusCode(val code: Int) {
    IDLE(0), CONNECTING(1), READY(2), ACTIVE(3), DEGRADED(4);

    companion object {
        fun fromCode(code: Int): BridgeStatusCode = entries.firstOrNull { it.code == code }
            ?: throw BridgeProtocolException("Unknown status code: $code")
    }
}

enum class BridgeErrorCode(val code: Int) {
    UNSUPPORTED_VERSION(1), INVALID_MESSAGE(2), CAPABILITY_MISMATCH(3), SESSION_REJECTED(4),
    INTERNAL_ERROR(5);

    companion object {
        fun fromCode(code: Int): BridgeErrorCode = entries.firstOrNull { it.code == code }
            ?: throw BridgeProtocolException("Unknown error code: $code")
    }
}
