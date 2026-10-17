package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class UdpGamepadSenderTest {
    @Test
    fun inputEnvelopeRejectsTamperingAndReplay() {
        val key = ByteArray(32) { it.toByte() }
        val sealer = InputUdpSealer(key)
        val opener = InputUdpOpener(key)
        val first = sealer.seal(42, BridgeMessage.Ping(7))
        val tampered = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertNull(opener.open(tampered, tampered.size))
        assertEquals(BridgeMessage.Ping(7), opener.open(first, first.size)?.message)
        assertNull(opener.open(first, first.size))
        val next = sealer.seal(42, BridgeMessage.Ping(8))
        assertEquals(BridgeMessage.Ping(8), opener.open(next, next.size)?.message)
    }

    @Test
    fun encryptedSnapshotsHaveUniqueCountersAndDecodeToSameSession() {
        val loopback = InetAddress.getLoopbackAddress()
        val key = ByteArray(32) { it.toByte() }
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 1_000
            UdpGamepadSender(loopback, loopback, receiver.localPort, key).use { sender ->
                repeat(2) { sender.send(42, VirtualGamepadState()) }
            }
            repeat(2) { expectedCounter ->
                val incoming = ByteArray(1_200)
                val packet = DatagramPacket(incoming, incoming.size)
                receiver.receive(packet)
                val header = incoming.copyOfRange(0, 16)
                assertEquals("BPI1", String(header.copyOfRange(0, 4), Charsets.US_ASCII))
                assertEquals(expectedCounter.toLong(), ByteBuffer.wrap(header, 4, 8).long)
                val nonce = ByteBuffer.allocate(12).putInt(0).putLong(expectedCounter.toLong()).array()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(header)
                val decoded = BridgePacketCodec.decode(cipher.doFinal(incoming, 16, packet.length - 16))
                assertEquals(42L, decoded.sessionId)
                assertEquals(expectedCounter.toLong(), decoded.sequence)
                assertTrue(decoded.message is BridgeMessage.GamepadSnapshot)
            }
        }
    }
}
