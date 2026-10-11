package dev.jonalakas.bridgepad.streaming

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

internal object NativeMediaProbeSupport {
    private val nextOperationId = AtomicLong(0)

    fun nextOperationId(): Long = nextOperationId.updateAndGet { current ->
        if (current == Long.MAX_VALUE) 1 else current + 1
    }

    fun resolveRoute(host: String, port: Int): Pair<String, String> {
        val address = InetAddress.getByName(host)
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(address, port))
            val localAddress = socket.localAddress.hostAddress.orEmpty()
            val interfaceName = runCatching {
                NetworkInterface.getByInetAddress(socket.localAddress)?.displayName
            }.getOrNull().orEmpty().ifBlank { "unknown" }
            return localAddress to interfaceName
        }
    }
}

internal fun parseNativeMediaProbeResult(
    encoded: String,
    interfaceName: String,
    transport: MediaTransportKind,
): MediaTransportProbeResult {
    val fields = encoded.split('\t')
    if (fields.firstOrNull() == "ERR") {
        throw IllegalStateException(fields.getOrNull(1) ?: "native media diagnostic failed")
    }
    require(fields.size == NATIVE_RESULT_FIELD_COUNT && fields[0] == "OK") {
        "Invalid native media diagnostic result"
    }
    return MediaTransportProbeResult(
        transport = transport,
        localAddress = fields[1],
        interfaceName = interfaceName,
        receivedPackets = fields[2].toLong(),
        expectedPackets = fields[3].toLong(),
        completedFrames = fields[4].toLong(),
        expectedFrames = fields[5].toLong(),
        expiredFrames = fields[6].toLong(),
        duplicatePackets = fields[7].toLong(),
        rejectedPackets = fields[8].toLong(),
        receivedBytes = fields[9].toLong(),
        elapsedMillis = fields[10].toLong(),
        handshakeMillis = fields[11].toLong().takeIf { it >= 0 },
        arrivalGapP95Micros = fields[12].toLong(),
        arrivalGapP99Micros = fields[13].toLong(),
        arrivalGapMaxMicros = fields[14].toLong(),
    )
}

private const val NATIVE_RESULT_FIELD_COUNT = 15
