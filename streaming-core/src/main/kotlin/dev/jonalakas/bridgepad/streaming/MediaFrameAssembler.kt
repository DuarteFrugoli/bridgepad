package dev.jonalakas.bridgepad.streaming

data class AssembledMediaFrame(
    val kind: MediaPacketKind,
    val flags: Int,
    val frameId: Long,
    val presentationTimestampMicros: Long,
    val payload: ByteArray,
)

data class MediaAssemblyMetrics(
    val acceptedPackets: Long = 0,
    val duplicatePackets: Long = 0,
    val expiredFrames: Long = 0,
    val capacityDroppedFrames: Long = 0,
    val completedFrames: Long = 0,
)

class MediaAssemblyException(message: String) : IllegalArgumentException(message)

/** Bounded, deadline-aware assembler for one authenticated media stream. */
class MediaFrameAssembler(
    private val sessionId: Long,
    private val streamId: Long,
    private val deadlineMicros: Long,
    private val maxInFlightFrames: Int,
) {
    private data class IncompleteFrame(
        val kind: MediaPacketKind,
        val commonFlags: Int,
        val presentationTimestampMicros: Long,
        val originalFrameBytes: Long,
        val expiresAtMicros: Long,
        val packets: Array<ByteArray?>,
        var received: Int = 0,
    )

    private val frames = linkedMapOf<Long, IncompleteFrame>()
    private val terminalFrames = linkedSetOf<Long>()
    private val maxTerminalFrames = maxOf(MIN_TERMINAL_FRAMES, maxInFlightFrames * 4)
    private var mutableMetrics = MediaAssemblyMetrics()

    val metrics: MediaAssemblyMetrics
        @Synchronized get() = mutableMetrics

    init {
        require(sessionId > 0)
        require(streamId in 1..0xffff_ffffL)
        require(deadlineMicros > 0)
        require(maxInFlightFrames > 0)
    }

    @Synchronized
    fun push(bytes: ByteArray, receivedAtMicros: Long): AssembledMediaFrame? {
        expire(receivedAtMicros)
        val datagram = BridgeMediaDatagramCodec.decode(bytes)
        val header = datagram.header
        if (header.sessionId != sessionId) throw MediaAssemblyException("Wrong media session")
        if (header.streamId != streamId) throw MediaAssemblyException("Wrong media stream")
        if (header.fecSourceCount != 0 || header.fecRepairCount != 0) {
            throw MediaAssemblyException("FEC assembly is not implemented")
        }
        if (header.frameId in terminalFrames) {
            mutableMetrics = mutableMetrics.copy(
                duplicatePackets = mutableMetrics.duplicatePackets + 1,
            )
            return null
        }

        val frame = frames[header.frameId] ?: createFrame(header, receivedAtMicros).also {
            makeRoom()
            frames[header.frameId] = it
        }
        val commonFlags = header.flags and
            (MediaPacketFlags.FRAME_START or MediaPacketFlags.FRAME_END).inv()
        if (
            frame.kind != header.kind ||
            frame.commonFlags != commonFlags ||
            frame.presentationTimestampMicros != header.presentationTimestampMicros ||
            frame.originalFrameBytes != header.originalFrameBytes ||
            frame.packets.size != header.framePacketCount
        ) {
            frames.remove(header.frameId)
            markTerminal(header.frameId)
            throw MediaAssemblyException("Inconsistent frame metadata")
        }

        val index = header.framePacketIndex
        if (frame.packets[index] != null) {
            mutableMetrics = mutableMetrics.copy(
                duplicatePackets = mutableMetrics.duplicatePackets + 1,
            )
            return null
        }
        frame.packets[index] = datagram.payload
        frame.received += 1
        mutableMetrics = mutableMetrics.copy(
            acceptedPackets = mutableMetrics.acceptedPackets + 1,
        )
        if (frame.received != frame.packets.size) return null

        frames.remove(header.frameId)
        markTerminal(header.frameId)
        val payloadSize = frame.packets.sumOf { it?.size ?: 0 }
        if (payloadSize.toLong() != frame.originalFrameBytes) {
            throw MediaAssemblyException("Reassembled frame size does not match metadata")
        }
        val payload = ByteArray(payloadSize)
        var offset = 0
        frame.packets.forEach { packet ->
            val present = checkNotNull(packet)
            present.copyInto(payload, destinationOffset = offset)
            offset += present.size
        }
        mutableMetrics = mutableMetrics.copy(
            completedFrames = mutableMetrics.completedFrames + 1,
        )
        return AssembledMediaFrame(
            kind = frame.kind,
            flags = frame.commonFlags,
            frameId = header.frameId,
            presentationTimestampMicros = frame.presentationTimestampMicros,
            payload = payload,
        )
    }

    @Synchronized
    fun expire(nowMicros: Long) {
        val expired = frames.filterValues { it.expiresAtMicros <= nowMicros }.keys
        if (expired.isEmpty()) return
        expired.forEach { frameId ->
            frames.remove(frameId)
            markTerminal(frameId)
        }
        mutableMetrics = mutableMetrics.copy(
            expiredFrames = mutableMetrics.expiredFrames + expired.size,
        )
    }

    private fun createFrame(
        header: MediaDatagramHeader,
        receivedAtMicros: Long,
    ): IncompleteFrame = IncompleteFrame(
        kind = header.kind,
        commonFlags = header.flags and
            (MediaPacketFlags.FRAME_START or MediaPacketFlags.FRAME_END).inv(),
        presentationTimestampMicros = header.presentationTimestampMicros,
        originalFrameBytes = header.originalFrameBytes,
        expiresAtMicros = receivedAtMicros + deadlineMicros,
        packets = arrayOfNulls(header.framePacketCount),
    )

    private fun makeRoom() {
        if (frames.size < maxInFlightFrames) return
        val oldest = frames.keys.firstOrNull() ?: return
        frames.remove(oldest)
        markTerminal(oldest)
        mutableMetrics = mutableMetrics.copy(
            capacityDroppedFrames = mutableMetrics.capacityDroppedFrames + 1,
        )
    }

    private fun markTerminal(frameId: Long) {
        terminalFrames += frameId
        while (terminalFrames.size > maxTerminalFrames) {
            terminalFrames.remove(terminalFrames.first())
        }
    }

    private companion object {
        const val MIN_TERMINAL_FRAMES = 32
    }
}
