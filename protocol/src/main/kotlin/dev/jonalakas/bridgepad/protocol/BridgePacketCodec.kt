package dev.jonalakas.bridgepad.protocol

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.roundToInt

object BridgePacketCodec {
    private const val FLAGS_NONE = 0
    private const val RESERVED = 0
    private const val MAX_PEER_NAME_BYTES = 64
    private const val MAX_APP_VERSION_BYTES = 32

    fun encode(packet: BridgePacket): ByteArray {
        val payload = encodePayload(packet.message)
        if (payload.size > BridgeProtocol.MAX_PAYLOAD_SIZE) {
            throw BridgeProtocolException("Payload exceeds ${BridgeProtocol.MAX_PAYLOAD_SIZE} bytes")
        }

        return ByteArrayOutputStream(BridgeProtocol.HEADER_SIZE + payload.size).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(BridgeProtocol.MAGIC)
                output.writeByte(BridgeProtocol.MAJOR_VERSION)
                output.writeByte(BridgeProtocol.MINOR_VERSION)
                output.writeByte(packet.message.type.code)
                output.writeByte(FLAGS_NONE)
                output.writeLong(packet.sessionId)
                output.writeInt(packet.sequence.toInt())
                output.writeLong(packet.timestampMicros)
                output.writeShort(payload.size)
                output.writeShort(RESERVED)
                output.write(payload)
            }
            bytes.toByteArray()
        }
    }

    fun decode(bytes: ByteArray): BridgePacket {
        if (bytes.size < BridgeProtocol.HEADER_SIZE) {
            throw BridgeProtocolException("Packet is shorter than the v1 header")
        }

        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != BridgeProtocol.MAGIC) {
                    throw BridgeProtocolException("Invalid packet magic")
                }
                val major = input.readUnsignedByte()
                val minor = input.readUnsignedByte()
                if (major != BridgeProtocol.MAJOR_VERSION || minor != BridgeProtocol.MINOR_VERSION) {
                    throw BridgeProtocolException("Unsupported protocol version: $major.$minor")
                }
                val type = BridgeMessageType.fromCode(input.readUnsignedByte())
                if (input.readUnsignedByte() != FLAGS_NONE) {
                    throw BridgeProtocolException("Unsupported packet flags")
                }
                val sessionId = input.readLong()
                val sequence = input.readInt().toLong() and 0xffff_ffffL
                val timestampMicros = input.readLong()
                val payloadLength = input.readUnsignedShort()
                if (input.readUnsignedShort() != RESERVED) {
                    throw BridgeProtocolException("Reserved header bytes must be zero")
                }
                if (sessionId < 0 || timestampMicros < 0) {
                    throw BridgeProtocolException("Unsigned header values exceed the supported range")
                }
                if (payloadLength > BridgeProtocol.MAX_PAYLOAD_SIZE) {
                    throw BridgeProtocolException("Payload exceeds ${BridgeProtocol.MAX_PAYLOAD_SIZE} bytes")
                }
                if (input.available() != payloadLength) {
                    throw BridgeProtocolException(
                        "Payload length mismatch: expected $payloadLength, found ${input.available()}",
                    )
                }
                val payload = ByteArray(payloadLength)
                input.readFully(payload)
                return BridgePacket(
                    sessionId = sessionId,
                    sequence = sequence,
                    timestampMicros = timestampMicros,
                    message = decodePayload(type, payload),
                )
            }
        } catch (error: EOFException) {
            throw BridgeProtocolException("Truncated packet", error)
        }
    }

    private fun encodePayload(message: BridgeMessage): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                when (message) {
                    is BridgeMessage.Hello -> output.writePeer(message)
                    is BridgeMessage.HelloAck -> output.writePeer(message)
                    is BridgeMessage.SessionStart -> {
                        output.writeByte(message.inputKind.code)
                        output.writeInt(message.requestedCapabilities.bits)
                    }
                    is BridgeMessage.SessionReady -> output.writeInt(message.enabledCapabilities.bits)
                    is BridgeMessage.SessionStop -> output.writeByte(message.reason.code)
                    is BridgeMessage.PairRequest -> {
                        output.writePeerId(message.peerId)
                        output.writeUtf8U8(message.peerName, MAX_PEER_NAME_BYTES, "peerName")
                        output.write(message.clientNonce)
                    }
                    is BridgeMessage.PairChallenge -> {
                        output.writePeerId(message.peerId)
                        output.write(message.serverNonce)
                        output.write(message.salt)
                        output.writeInt(message.iterations)
                        output.writeShort(message.expiresInSeconds)
                        output.write(message.serverProof)
                    }
                    is BridgeMessage.PairProof -> output.write(message.proof)
                    is BridgeMessage.PairResult -> {
                        output.writeByte(if (message.accepted) 1 else 0)
                        output.writeByte(message.sharedSecret.size)
                        output.write(message.sharedSecret)
                        output.writeUtf8U8(message.peerName, MAX_PEER_NAME_BYTES, "peerName")
                        output.writeUtf8U16(message.detail)
                    }
                    is BridgeMessage.AuthRequest -> {
                        output.writePeerId(message.peerId)
                        output.write(message.clientNonce)
                    }
                    is BridgeMessage.AuthChallenge -> {
                        output.writePeerId(message.peerId)
                        output.write(message.serverNonce)
                    }
                    is BridgeMessage.AuthProof -> output.write(message.proof)
                    is BridgeMessage.AuthResult -> {
                        output.writeByte(if (message.accepted) 1 else 0)
                        output.writeUtf8U8(message.peerName, MAX_PEER_NAME_BYTES, "peerName")
                        output.writeUtf8U16(message.detail)
                    }
                    is BridgeMessage.GamepadSnapshot -> output.writeGamepad(message.state)
                    is BridgeMessage.PointerFrame -> output.writePointer(message.report)
                    is BridgeMessage.Ping -> output.writeLong(message.nonce)
                    is BridgeMessage.Pong -> output.writeLong(message.nonce)
                    is BridgeMessage.Status -> {
                        output.writeByte(message.status.code)
                        output.writeUtf8U16(message.detail)
                    }
                    is BridgeMessage.Error -> {
                        output.writeShort(message.code.code)
                        output.writeByte(if (message.fatal) 1 else 0)
                        output.writeUtf8U16(message.detail)
                    }
                    is BridgeMessage.Rumble -> {
                        output.writeShort(message.lowFrequency.toUnsignedNormalized())
                        output.writeShort(message.highFrequency.toUnsignedNormalized())
                        output.writeShort(message.durationMillis)
                    }
                }
            }
            bytes.toByteArray()
        }

    private fun decodePayload(type: BridgeMessageType, payload: ByteArray): BridgeMessage {
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                val message = when (type) {
                    BridgeMessageType.HELLO -> input.readPeer(isAcknowledgement = false)
                    BridgeMessageType.HELLO_ACK -> input.readPeer(isAcknowledgement = true)
                    BridgeMessageType.SESSION_START -> BridgeMessage.SessionStart(
                        inputKind = BridgeInputKind.fromCode(input.readUnsignedByte()),
                        requestedCapabilities = BridgeCapabilities(input.readInt()),
                    )
                    BridgeMessageType.SESSION_READY ->
                        BridgeMessage.SessionReady(BridgeCapabilities(input.readInt()))
                    BridgeMessageType.SESSION_STOP ->
                        BridgeMessage.SessionStop(BridgeStopReason.fromCode(input.readUnsignedByte()))
                    BridgeMessageType.PAIR_REQUEST -> BridgeMessage.PairRequest(
                        peerId = input.readPeerId(),
                        peerName = input.readUtf8U8(MAX_PEER_NAME_BYTES, "peerName"),
                        clientNonce = input.readSizedBytes(BridgeAuthentication.NONCE_SIZE),
                    )
                    BridgeMessageType.PAIR_CHALLENGE -> BridgeMessage.PairChallenge(
                        peerId = input.readPeerId(),
                        serverNonce = input.readSizedBytes(BridgeAuthentication.NONCE_SIZE),
                        salt = input.readSizedBytes(BridgeAuthentication.SALT_SIZE),
                        iterations = input.readInt(),
                        expiresInSeconds = input.readUnsignedShort(),
                        serverProof = input.readSizedBytes(BridgeAuthentication.PROOF_SIZE),
                    )
                    BridgeMessageType.PAIR_PROOF ->
                        BridgeMessage.PairProof(input.readSizedBytes(BridgeAuthentication.PROOF_SIZE))
                    BridgeMessageType.PAIR_RESULT -> {
                        val accepted = input.readBooleanByte()
                        val secretSize = input.readUnsignedByte()
                        BridgeMessage.PairResult(
                            accepted = accepted,
                            sharedSecret = input.readSizedBytes(secretSize),
                            peerName = input.readUtf8U8(MAX_PEER_NAME_BYTES, "peerName"),
                            detail = input.readUtf8U16(),
                        )
                    }
                    BridgeMessageType.AUTH_REQUEST -> BridgeMessage.AuthRequest(
                        peerId = input.readPeerId(),
                        clientNonce = input.readSizedBytes(BridgeAuthentication.NONCE_SIZE),
                    )
                    BridgeMessageType.AUTH_CHALLENGE -> BridgeMessage.AuthChallenge(
                        peerId = input.readPeerId(),
                        serverNonce = input.readSizedBytes(BridgeAuthentication.NONCE_SIZE),
                    )
                    BridgeMessageType.AUTH_PROOF ->
                        BridgeMessage.AuthProof(input.readSizedBytes(BridgeAuthentication.PROOF_SIZE))
                    BridgeMessageType.AUTH_RESULT -> BridgeMessage.AuthResult(
                        accepted = input.readBooleanByte(),
                        peerName = input.readUtf8U8(MAX_PEER_NAME_BYTES, "peerName"),
                        detail = input.readUtf8U16(),
                    )
                    BridgeMessageType.GAMEPAD_SNAPSHOT ->
                        BridgeMessage.GamepadSnapshot(input.readGamepad())
                    BridgeMessageType.POINTER -> BridgeMessage.PointerFrame(input.readPointer())
                    BridgeMessageType.PING -> BridgeMessage.Ping(input.readLong())
                    BridgeMessageType.PONG -> BridgeMessage.Pong(input.readLong())
                    BridgeMessageType.STATUS -> BridgeMessage.Status(
                        status = BridgeStatusCode.fromCode(input.readUnsignedByte()),
                        detail = input.readUtf8U16(),
                    )
                    BridgeMessageType.ERROR -> BridgeMessage.Error(
                        code = BridgeErrorCode.fromCode(input.readUnsignedShort()),
                        fatal = input.readBooleanByte(),
                        detail = input.readUtf8U16(),
                    )
                    BridgeMessageType.RUMBLE -> BridgeMessage.Rumble(
                        lowFrequency = input.readUnsignedShort().fromUnsignedNormalized(),
                        highFrequency = input.readUnsignedShort().fromUnsignedNormalized(),
                        durationMillis = input.readUnsignedShort(),
                    )
                }
                if (input.available() != 0) {
                    throw BridgeProtocolException("Unexpected trailing message data")
                }
                return message
            }
        } catch (error: EOFException) {
            throw BridgeProtocolException("Truncated ${type.name} payload", error)
        }
    }

    private fun DataOutputStream.writePeer(message: BridgeMessage.Hello) {
        writePeerFields(message.peerId, message.capabilities, message.peerName, message.appVersion)
    }

    private fun DataOutputStream.writePeer(message: BridgeMessage.HelloAck) {
        writePeerFields(message.peerId, message.capabilities, message.peerName, message.appVersion)
    }

    private fun DataOutputStream.writePeerFields(
        peerId: PeerId,
        capabilities: BridgeCapabilities,
        peerName: String,
        appVersion: String,
    ) {
        writeLong(peerId.high)
        writeLong(peerId.low)
        writeInt(capabilities.bits)
        writeUtf8U8(peerName, MAX_PEER_NAME_BYTES, "peerName")
        writeUtf8U8(appVersion, MAX_APP_VERSION_BYTES, "appVersion")
    }

    private fun DataOutputStream.writePeerId(peerId: PeerId) {
        writeLong(peerId.high)
        writeLong(peerId.low)
    }

    private fun DataInputStream.readPeerId(): PeerId = try {
        PeerId(readLong(), readLong())
    } catch (error: IllegalArgumentException) {
        throw BridgeProtocolException("Invalid peer ID", error)
    }

    private fun DataInputStream.readPeer(isAcknowledgement: Boolean): BridgeMessage {
        val peerId = try {
            PeerId(readLong(), readLong())
        } catch (error: IllegalArgumentException) {
            throw BridgeProtocolException("Invalid peer ID", error)
        }
        val capabilities = BridgeCapabilities(readInt())
        val peerName = readUtf8U8(MAX_PEER_NAME_BYTES, "peerName")
        val appVersion = readUtf8U8(MAX_APP_VERSION_BYTES, "appVersion")
        return if (isAcknowledgement) {
            BridgeMessage.HelloAck(peerId, capabilities, peerName, appVersion)
        } else {
            BridgeMessage.Hello(peerId, capabilities, peerName, appVersion)
        }
    }

    private fun DataOutputStream.writeGamepad(state: VirtualGamepadState) {
        var buttons = 0
        state.pressedButtons.forEach { control -> buttons = buttons or (1 shl control.wireBit) }
        writeShort(buttons)
        writeByte(state.dpad.wireCode)
        writeShort(state.leftStickX.toSignedNormalized())
        writeShort(state.leftStickY.toSignedNormalized())
        writeShort(state.rightStickX.toSignedNormalized())
        writeShort(state.rightStickY.toSignedNormalized())
        writeShort(state.leftTrigger.toUnsignedNormalized())
        writeShort(state.rightTrigger.toUnsignedNormalized())
    }

    private fun DataInputStream.readGamepad(): VirtualGamepadState {
        val buttonBits = readUnsignedShort()
        return VirtualGamepadState(
            pressedButtons = VirtualControl.entries.filterTo(linkedSetOf()) {
                buttonBits and (1 shl it.wireBit) != 0
            },
            dpad = decodeDpad(readUnsignedByte()),
            leftStickX = readShort().toInt().fromSignedNormalized(),
            leftStickY = readShort().toInt().fromSignedNormalized(),
            rightStickX = readShort().toInt().fromSignedNormalized(),
            rightStickY = readShort().toInt().fromSignedNormalized(),
            leftTrigger = readUnsignedShort().fromUnsignedNormalized(),
            rightTrigger = readUnsignedShort().fromUnsignedNormalized(),
        )
    }

    private fun DataOutputStream.writePointer(report: PointerReport) {
        if (report.buttons !in 0..0xff) {
            throw BridgeProtocolException("Pointer buttons must fit in an unsigned byte")
        }
        writeByte(report.buttons)
        writeInt(report.deltaX)
        writeInt(report.deltaY)
    }

    private fun DataInputStream.readPointer() = PointerReport(
        buttons = readUnsignedByte(),
        deltaX = readInt(),
        deltaY = readInt(),
    )

    private fun DataOutputStream.writeUtf8U8(value: String, maxBytes: Int, field: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > maxBytes) throw BridgeProtocolException("$field exceeds $maxBytes UTF-8 bytes")
        writeByte(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeUtf8U16(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > BridgeProtocol.MAX_PAYLOAD_SIZE - 2) {
            throw BridgeProtocolException("Text exceeds the v1 payload limit")
        }
        writeShort(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readUtf8U8(maxBytes: Int, field: String): String {
        val size = readUnsignedByte()
        if (size > maxBytes) throw BridgeProtocolException("$field exceeds $maxBytes UTF-8 bytes")
        return readUtf8(size)
    }

    private fun DataInputStream.readUtf8U16(): String = readUtf8(readUnsignedShort())

    private fun DataInputStream.readUtf8(size: Int): String {
        val encoded = ByteArray(size)
        readFully(encoded)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded))
                .toString()
        } catch (error: Exception) {
            throw BridgeProtocolException("Invalid UTF-8 text", error)
        }
    }

    private fun DataInputStream.readSizedBytes(size: Int): ByteArray = ByteArray(size).also { readFully(it) }

    private fun DataInputStream.readBooleanByte(): Boolean = when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw BridgeProtocolException("Invalid boolean value: $value")
    }

    private fun Float.toSignedNormalized(): Int {
        if (!isFinite()) throw BridgeProtocolException("Axis values must be finite")
        return (coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt()
    }

    private fun Int.fromSignedNormalized(): Float = (toFloat() / Short.MAX_VALUE).coerceIn(-1f, 1f)

    private fun Float.toUnsignedNormalized(): Int {
        if (!isFinite()) throw BridgeProtocolException("Normalized values must be finite")
        return (coerceIn(0f, 1f) * 0xffff).roundToInt()
    }

    private fun Int.fromUnsignedNormalized(): Float = toFloat() / 0xffff

    private val VirtualControl.wireBit: Int
        get() = when (this) {
            VirtualControl.FACE_SOUTH -> 0
            VirtualControl.FACE_EAST -> 1
            VirtualControl.FACE_WEST -> 2
            VirtualControl.FACE_NORTH -> 3
            VirtualControl.LEFT_BUMPER -> 4
            VirtualControl.RIGHT_BUMPER -> 5
            VirtualControl.START -> 6
            VirtualControl.SELECT -> 7
            VirtualControl.LEFT_STICK_BUTTON -> 8
            VirtualControl.RIGHT_STICK_BUTTON -> 9
            VirtualControl.EXTRA_1 -> 10
            VirtualControl.EXTRA_2 -> 11
            VirtualControl.EXTRA_3 -> 12
            VirtualControl.EXTRA_4 -> 13
            VirtualControl.EXTRA_5 -> 14
            VirtualControl.EXTRA_6 -> 15
        }

    private val DpadDirection.wireCode: Int
        get() = when (this) {
            DpadDirection.NEUTRAL -> 0
            DpadDirection.NORTH -> 1
            DpadDirection.NORTH_EAST -> 2
            DpadDirection.EAST -> 3
            DpadDirection.SOUTH_EAST -> 4
            DpadDirection.SOUTH -> 5
            DpadDirection.SOUTH_WEST -> 6
            DpadDirection.WEST -> 7
            DpadDirection.NORTH_WEST -> 8
        }

    private fun decodeDpad(code: Int): DpadDirection = when (code) {
        0 -> DpadDirection.NEUTRAL
        1 -> DpadDirection.NORTH
        2 -> DpadDirection.NORTH_EAST
        3 -> DpadDirection.EAST
        4 -> DpadDirection.SOUTH_EAST
        5 -> DpadDirection.SOUTH
        6 -> DpadDirection.SOUTH_WEST
        7 -> DpadDirection.WEST
        8 -> DpadDirection.NORTH_WEST
        else -> throw BridgeProtocolException("Unknown d-pad direction: $code")
    }
}
