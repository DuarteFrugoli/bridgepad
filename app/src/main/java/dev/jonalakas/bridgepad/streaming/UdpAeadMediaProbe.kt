package dev.jonalakas.bridgepad.streaming

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UdpMediaProbeStatus {
    data object Idle : UdpMediaProbeStatus
    data object Connecting : UdpMediaProbeStatus
    data class Receiving(val localAddress: String, val interfaceName: String) : UdpMediaProbeStatus
    data class Completed(val result: UdpMediaProbeResult) : UdpMediaProbeStatus
    data class Failed(val detail: String) : UdpMediaProbeStatus
    data object Stopped : UdpMediaProbeStatus
}

data class UdpMediaProbeResult(
    val localAddress: String,
    val interfaceName: String,
    val receivedPackets: Long,
    val expectedPackets: Long,
    val completedFrames: Long,
    val expectedFrames: Long,
    val expiredFrames: Long,
    val rejectedPackets: Long,
    val receivedBytes: Long,
    val elapsedMillis: Long,
    val arrivalGapP95Micros: Long,
    val arrivalGapP99Micros: Long,
    val arrivalGapMaxMicros: Long,
)

/** Receives the fixed-key LAN diagnostic emitted by `media_udp_server`. */
class UdpAeadMediaProbe(
    private val host: String,
    private val port: Int,
    private val durationSeconds: Int,
    private val onStatus: (UdpMediaProbeStatus) -> Unit,
) : AutoCloseable {
    private val stopping = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        onStatus(UdpMediaProbeStatus.Connecting)
        Thread(::runProbe, "BridgePad-media-udp-probe").apply { isDaemon = true }.start()
    }

    override fun close() {
        if (!stopping.compareAndSet(false, true)) return
        socket?.close()
        onStatus(UdpMediaProbeStatus.Stopped)
    }

    private fun runProbe() {
        try {
            require(port in 1..0xffff) { "Invalid UDP port" }
            require(durationSeconds in 1..MAX_DURATION_SECONDS) { "Invalid test duration" }
            DatagramSocket().use { localSocket ->
                socket = localSocket
                localSocket.soTimeout = RECEIVE_TIMEOUT_MILLIS
                localSocket.connect(InetSocketAddress(host, port))
                val localAddress = localSocket.localAddress.hostAddress.orEmpty()
                val interfaceName = runCatching {
                    NetworkInterface.getByInetAddress(localSocket.localAddress)?.displayName
                }.getOrNull().orEmpty().ifBlank { "unknown" }
                onStatus(UdpMediaProbeStatus.Receiving(localAddress, interfaceName))
                localSocket.send(
                    DatagramPacket(
                        hello(durationSeconds),
                        HELLO_SIZE,
                    ),
                )
                onStatus(
                    UdpMediaProbeStatus.Completed(
                        receiveWorkload(localSocket, localAddress, interfaceName),
                    ),
                )
            }
        } catch (error: Throwable) {
            if (!stopping.get()) {
                onStatus(UdpMediaProbeStatus.Failed(error.message ?: "UDP media probe failed"))
            }
        } finally {
            socket = null
        }
    }

    private fun receiveWorkload(
        localSocket: DatagramSocket,
        localAddress: String,
        interfaceName: String,
    ): UdpMediaProbeResult {
        val receiver = UdpAeadReceiver(TEST_KEY, KEY_EPOCH)
        val assembler = MediaFrameAssembler(
            sessionId = SESSION_ID,
            streamId = STREAM_ID,
            deadlineMicros = ASSEMBLY_DEADLINE_MICROS,
            maxInFlightFrames = MAX_IN_FLIGHT_FRAMES,
        )
        val startedNanos = System.nanoTime()
        var previousArrivalNanos: Long? = null
        val arrivalGapsMicros = mutableListOf<Long>()
        var rejectedPackets = 0L
        var receivedPackets = 0L
        var receivedBytes = 0L
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        val expectedFrames = durationSeconds.toLong() * FRAMES_PER_SECOND
        val expectedPackets = expectedFrames * PACKETS_PER_FRAME

        while (!stopping.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                localSocket.receive(packet)
            } catch (_: SocketTimeoutException) {
                break
            } catch (error: SocketException) {
                if (stopping.get()) break else throw error
            }
            if (packet.length == DONE_MAGIC.size &&
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    .contentEquals(DONE_MAGIC)
            ) {
                localSocket.soTimeout = DONE_DRAIN_TIMEOUT_MILLIS
                continue
            }
            val arrivalNanos = System.nanoTime()
            previousArrivalNanos?.let { previous ->
                arrivalGapsMicros += (arrivalNanos - previous).coerceAtLeast(0) / 1_000
            }
            previousArrivalNanos = arrivalNanos
            receivedBytes += packet.length
            val sealed = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            try {
                val plaintext = receiver.open(sealed)
                val receivedAtMicros = (arrivalNanos - startedNanos).coerceAtLeast(0) / 1_000
                assembler.push(plaintext, receivedAtMicros)
                receivedPackets += 1
                if (receivedPackets >= expectedPackets) break
            } catch (_: IllegalArgumentException) {
                rejectedPackets += 1
            }
        }

        val elapsedMicros = (System.nanoTime() - startedNanos).coerceAtLeast(0) / 1_000
        assembler.expire(elapsedMicros + ASSEMBLY_DEADLINE_MICROS)
        arrivalGapsMicros.sort()
        return UdpMediaProbeResult(
            localAddress = localAddress,
            interfaceName = interfaceName,
            receivedPackets = receivedPackets,
            expectedPackets = expectedPackets,
            completedFrames = assembler.metrics.completedFrames,
            expectedFrames = expectedFrames,
            expiredFrames = assembler.metrics.expiredFrames,
            rejectedPackets = rejectedPackets,
            receivedBytes = receivedBytes,
            elapsedMillis = elapsedMicros / 1_000,
            arrivalGapP95Micros = percentile(arrivalGapsMicros, 95),
            arrivalGapP99Micros = percentile(arrivalGapsMicros, 99),
            arrivalGapMaxMicros = arrivalGapsMicros.lastOrNull() ?: 0,
        )
    }

    private fun hello(seconds: Int): ByteArray = ByteBuffer.allocate(HELLO_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .put(HELLO_MAGIC)
        .putShort(seconds.toShort())
        .array()

    private fun percentile(sorted: List<Long>, percentile: Int): Long {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.lastIndex * percentile) + 99) / 100
        return sorted[index]
    }

    private companion object {
        const val MAX_DURATION_SECONDS = 300
        const val RECEIVE_TIMEOUT_MILLIS = 2_500
        const val DONE_DRAIN_TIMEOUT_MILLIS = 250
        const val MAX_DATAGRAM_SIZE = 1_200
        const val HELLO_SIZE = 10
        const val KEY_EPOCH = 1L
        const val SESSION_ID = 0x4250_4d45_4449_4131L
        const val STREAM_ID = 1L
        const val ASSEMBLY_DEADLINE_MICROS = 10_000L
        const val MAX_IN_FLIGHT_FRAMES = 4
        const val FRAMES_PER_SECOND = 60L
        const val PACKETS_PER_FRAME = 16L
        val HELLO_MAGIC = "BPT1HELO".encodeToByteArray()
        val DONE_MAGIC = "BPT1DONE".encodeToByteArray()
        val TEST_KEY = ByteArray(32) { it.toByte() }
    }
}
