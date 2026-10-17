package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** A per-session, one-direction encrypted gamepad sender for the UDP experiment. */
internal class UdpGamepadSender(
    localAddress: InetAddress,
    remoteAddress: InetAddress,
    port: Int,
    key: ByteArray,
) : AutoCloseable {
    private val socket = DatagramSocket(null).apply {
        bind(InetSocketAddress(localAddress, 0))
        connect(remoteAddress, port)
    }
    private val sealer = InputUdpSealer(key)

    fun send(sessionId: Long, state: VirtualGamepadState) {
        val datagram = sealer.seal(sessionId, BridgeMessage.GamepadSnapshot(state))
        socket.send(DatagramPacket(datagram, datagram.size))
    }

    override fun close() {
        socket.close()
    }
}
