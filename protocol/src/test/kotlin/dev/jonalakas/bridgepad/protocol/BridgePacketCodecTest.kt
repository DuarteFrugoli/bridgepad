package dev.jonalakas.bridgepad.protocol

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

class BridgePacketCodecTest {
    @Test
    fun ping_matchesLanguageNeutralGoldenVector() {
        val encoded = BridgePacketCodec.encode(
            BridgePacket(
                sessionId = 1,
                sequence = 2,
                timestampMicros = 3,
                message = BridgeMessage.Ping(nonce = 4),
            ),
        )

        assertEquals(goldenVectors.getProperty("ping"), encoded.toHex())
        assertEquals(
            BridgePacket(1, 2, 3, BridgeMessage.Ping(4)),
            BridgePacketCodec.decode(encoded),
        )
    }

    @Test
    fun pointer_matchesLanguageNeutralGoldenVector() {
        val encoded = BridgePacketCodec.encode(
            BridgePacket(
                sessionId = 1,
                sequence = 2,
                timestampMicros = 3,
                message = BridgeMessage.PointerFrame(
                    PointerReport(buttons = 3, deltaX = -250, deltaY = 500),
                ),
            ),
        )

        assertEquals(goldenVectors.getProperty("pointer"), encoded.toHex())
        assertEquals(
            PointerReport(buttons = 3, deltaX = -250, deltaY = 500),
            (BridgePacketCodec.decode(encoded).message as BridgeMessage.PointerFrame).report,
        )
    }

    @Test
    fun controlMessages_roundTripWithoutTransportKnowledge() {
        val peerId = PeerId(0x0102, 0x0304)
        val capabilities = BridgeCapabilities.of(
            BridgeCapability.GAMEPAD,
            BridgeCapability.POINTER,
            BridgeCapability.RUMBLE,
        )
        val messages = listOf(
            BridgeMessage.Hello(peerId, capabilities, "Galaxy A35", "0.1.0"),
            BridgeMessage.HelloAck(peerId, capabilities, "BridgePad Desktop", "0.1.0"),
            BridgeMessage.SessionStart(BridgeInputKind.TOUCHSCREEN, capabilities),
            BridgeMessage.SessionReady(capabilities),
            BridgeMessage.SessionStop(BridgeStopReason.USER_REQUEST),
            BridgeMessage.PointerFrame(PointerReport(buttons = 3, deltaX = -250, deltaY = 500)),
            BridgeMessage.Ping(1234),
            BridgeMessage.Pong(1234),
            BridgeMessage.Status(BridgeStatusCode.ACTIVE, "ready"),
            BridgeMessage.Error(BridgeErrorCode.CAPABILITY_MISMATCH, fatal = false, "pointer"),
        )

        messages.forEachIndexed { index, message ->
            val packet = BridgePacket(7, index.toLong(), 99, message)
            assertEquals(packet, BridgePacketCodec.decode(BridgePacketCodec.encode(packet)))
        }
    }

    @Test
    fun pairingAndAuthenticationMessages_roundTripBinaryFields() {
        val peerId = PeerId(0x0102, 0x0304)
        val nonce = ByteArray(BridgeAuthentication.NONCE_SIZE) { it.toByte() }
        val salt = ByteArray(BridgeAuthentication.SALT_SIZE) { (it + 32).toByte() }
        val proof = ByteArray(BridgeAuthentication.PROOF_SIZE) { (it + 64).toByte() }
        val secret = ByteArray(BridgeAuthentication.SHARED_SECRET_SIZE) { (it + 96).toByte() }
        val messages = listOf(
            BridgeMessage.PairRequest(peerId, "Galaxy A35", nonce),
            BridgeMessage.PairChallenge(peerId, nonce, salt, 210_000, 600, proof),
            BridgeMessage.PairProof(proof),
            BridgeMessage.PairResult(true, secret, "Desktop", ""),
            BridgeMessage.AuthRequest(peerId, nonce),
            BridgeMessage.AuthChallenge(peerId, nonce),
            BridgeMessage.AuthProof(proof),
            BridgeMessage.AuthResult(true, "Desktop", ""),
        )

        messages.forEachIndexed { index, message ->
            val decoded = BridgePacketCodec.decode(
                BridgePacketCodec.encode(BridgePacket(9, index.toLong(), 11, message)),
            ).message
            assertEquals(message.type, decoded.type)
            assertMessageContents(message, decoded)
        }
    }

    @Test
    fun gamepadSnapshot_preservesStableButtonBitsAndNormalizedValues() {
        val state = VirtualGamepadState(
            pressedButtons = setOf(
                VirtualControl.FACE_SOUTH,
                VirtualControl.RIGHT_BUMPER,
                VirtualControl.EXTRA_6,
            ),
            dpad = DpadDirection.SOUTH_WEST,
            leftStickX = -1f,
            leftStickY = 0.25f,
            rightStickX = 1f,
            rightStickY = -0.5f,
            leftTrigger = 0.4f,
            rightTrigger = 1f,
        )
        val encoded = BridgePacketCodec.encode(BridgePacket(8, 9, 10, BridgeMessage.GamepadSnapshot(state)))
        val decoded = BridgePacketCodec.decode(encoded).message as BridgeMessage.GamepadSnapshot

        assertEquals(state.pressedButtons, decoded.state.pressedButtons)
        assertEquals(state.dpad, decoded.state.dpad)
        assertEquals(state.leftStickX, decoded.state.leftStickX, NORMALIZED_TOLERANCE)
        assertEquals(state.leftStickY, decoded.state.leftStickY, NORMALIZED_TOLERANCE)
        assertEquals(state.rightStickX, decoded.state.rightStickX, NORMALIZED_TOLERANCE)
        assertEquals(state.rightStickY, decoded.state.rightStickY, NORMALIZED_TOLERANCE)
        assertEquals(state.leftTrigger, decoded.state.leftTrigger, NORMALIZED_TOLERANCE)
        assertEquals(state.rightTrigger, decoded.state.rightTrigger, NORMALIZED_TOLERANCE)

        val payload = encoded.copyOfRange(BridgeProtocol.HEADER_SIZE, encoded.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x21, 0x06), payload.copyOfRange(0, 3))
    }

    @Test
    fun rumble_roundTripsQuantizedStrengths() {
        val message = BridgeMessage.Rumble(0.2f, 0.8f, 250)
        val decoded = BridgePacketCodec.decode(
            BridgePacketCodec.encode(BridgePacket(1, 1, 1, message)),
        ).message as BridgeMessage.Rumble

        assertEquals(message.lowFrequency, decoded.lowFrequency, NORMALIZED_TOLERANCE)
        assertEquals(message.highFrequency, decoded.highFrequency, NORMALIZED_TOLERANCE)
        assertEquals(message.durationMillis, decoded.durationMillis)
    }

    @Test
    fun capabilities_supportNegotiationAndPreserveFutureBits() {
        val local = BridgeCapabilities.of(BridgeCapability.GAMEPAD, BridgeCapability.POINTER)
        val remote = BridgeCapabilities((1 shl BridgeCapability.POINTER.bit) or (1 shl 20))

        assertTrue(BridgeCapability.POINTER in (local intersect remote))
        assertFalse(BridgeCapability.GAMEPAD in (local intersect remote))

        val message = BridgeMessage.SessionReady(remote)
        val decoded = BridgePacketCodec.decode(
            BridgePacketCodec.encode(BridgePacket(1, 1, 1, message)),
        ).message as BridgeMessage.SessionReady
        assertEquals(remote.bits, decoded.enabledCapabilities.bits)
    }

    @Test
    fun decoder_rejectsInvalidEnvelopeFieldsAndLengths() {
        val valid = BridgePacketCodec.encode(BridgePacket(1, 2, 3, BridgeMessage.Ping(4)))

        assertRejected(valid.mutated(0, 0))
        assertRejected(valid.mutated(4, 2))
        assertRejected(valid.mutated(6, 0x7f))
        assertRejected(valid.mutated(7, 1))
        assertRejected(valid.mutated(30, 1))
        assertRejected(valid.copyOf(valid.size - 1))
        assertRejected(valid + 0)
    }

    @Test
    fun codec_rejectsInvalidLocalValuesAndMalformedPayloads() {
        assertThrows(BridgeProtocolException::class.java) {
            BridgePacketCodec.encode(
                BridgePacket(1, 1, 1, BridgeMessage.PointerFrame(PointerReport(buttons = 256))),
            )
        }
        assertThrows(BridgeProtocolException::class.java) {
            BridgePacketCodec.encode(
                BridgePacket(
                    1,
                    1,
                    1,
                    BridgeMessage.Hello(PeerId(1, 1), BridgeCapabilities.NONE, "x".repeat(65), "1"),
                ),
            )
        }

        val invalidBoolean = BridgePacketCodec.encode(
            BridgePacket(
                1,
                1,
                1,
                BridgeMessage.Error(BridgeErrorCode.INVALID_MESSAGE, false, "bad"),
            ),
        ).mutated(BridgeProtocol.HEADER_SIZE + 2, 2)
        assertRejected(invalidBoolean)
    }

    private fun assertRejected(bytes: ByteArray) {
        assertThrows(BridgeProtocolException::class.java) { BridgePacketCodec.decode(bytes) }
    }

    private fun assertMessageContents(expected: BridgeMessage, actual: BridgeMessage) {
        when (expected) {
            is BridgeMessage.PairRequest -> (actual as BridgeMessage.PairRequest).also {
                assertEquals(expected.peerId, it.peerId)
                assertEquals(expected.peerName, it.peerName)
                assertArrayEquals(expected.clientNonce, it.clientNonce)
            }
            is BridgeMessage.PairChallenge -> (actual as BridgeMessage.PairChallenge).also {
                assertEquals(expected.peerId, it.peerId)
                assertArrayEquals(expected.serverNonce, it.serverNonce)
                assertArrayEquals(expected.salt, it.salt)
                assertEquals(expected.iterations, it.iterations)
                assertEquals(expected.expiresInSeconds, it.expiresInSeconds)
                assertArrayEquals(expected.serverProof, it.serverProof)
            }
            is BridgeMessage.PairProof ->
                assertArrayEquals(expected.proof, (actual as BridgeMessage.PairProof).proof)
            is BridgeMessage.PairResult -> (actual as BridgeMessage.PairResult).also {
                assertEquals(expected.accepted, it.accepted)
                assertArrayEquals(expected.sharedSecret, it.sharedSecret)
                assertEquals(expected.peerName, it.peerName)
                assertEquals(expected.detail, it.detail)
            }
            is BridgeMessage.AuthRequest -> (actual as BridgeMessage.AuthRequest).also {
                assertEquals(expected.peerId, it.peerId)
                assertArrayEquals(expected.clientNonce, it.clientNonce)
            }
            is BridgeMessage.AuthChallenge -> (actual as BridgeMessage.AuthChallenge).also {
                assertEquals(expected.peerId, it.peerId)
                assertArrayEquals(expected.serverNonce, it.serverNonce)
            }
            is BridgeMessage.AuthProof ->
                assertArrayEquals(expected.proof, (actual as BridgeMessage.AuthProof).proof)
            is BridgeMessage.AuthResult -> assertEquals(expected, actual)
            else -> error("Unexpected test message: ${expected.type}")
        }
    }

    private fun ByteArray.mutated(index: Int, value: Int): ByteArray = copyOf().also {
        it[index] = value.toByte()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val NORMALIZED_TOLERANCE = 0.00002f

        private val goldenVectors: Properties = Properties().apply {
            BridgePacketCodecTest::class.java.getResourceAsStream("/v1.properties").use { input ->
                requireNotNull(input) { "Missing shared v1 protocol vectors" }
                load(input)
            }
        }
    }
}
