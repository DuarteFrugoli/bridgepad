package dev.jonalakas.bridgepad.protocol

import java.io.DataInputStream
import java.io.OutputStream

/** Transport-independent framing for stream transports such as TLS and RFCOMM. */
object BridgePacketStream {
    fun read(input: DataInputStream): BridgePacket {
        val header = ByteArray(BridgeProtocol.HEADER_SIZE)
        input.readFully(header)
        val payloadLength = ((header[28].toInt() and 0xff) shl 8) or
            (header[29].toInt() and 0xff)
        if (payloadLength > BridgeProtocol.MAX_PAYLOAD_SIZE) {
            throw BridgeProtocolException("Peer payload exceeds the v1 limit")
        }
        val packet = header.copyOf(BridgeProtocol.HEADER_SIZE + payloadLength)
        input.readFully(packet, BridgeProtocol.HEADER_SIZE, payloadLength)
        return BridgePacketCodec.decode(packet)
    }

    fun write(output: OutputStream, packet: BridgePacket) {
        output.write(BridgePacketCodec.encode(packet))
        output.flush()
    }
}
