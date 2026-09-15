package dev.jonalakas.bridgepad.protocol

private const val UINT32_MASK = 0xffff_ffffL
private const val UINT32_HALF_RANGE = 0x8000_0000L

enum class PacketOrder {
    FIRST,
    NEXT,
    GAP,
    DUPLICATE,
    OLDER,
}

data class SequenceObservation(
    val order: PacketOrder,
    val missingPackets: Long = 0,
)

/** Orders unsigned 32-bit sequence values, including wraparound. */
class SequenceTracker {
    var latest: Long? = null
        private set

    fun observe(sequence: Long): SequenceObservation {
        require(sequence in 0..UINT32_MASK) { "sequence must fit in an unsigned 32-bit integer" }
        val previous = latest
        if (previous == null) {
            latest = sequence
            return SequenceObservation(PacketOrder.FIRST)
        }

        val distance = (sequence - previous) and UINT32_MASK
        return when {
            distance == 0L -> SequenceObservation(PacketOrder.DUPLICATE)
            distance < UINT32_HALF_RANGE -> {
                latest = sequence
                if (distance == 1L) {
                    SequenceObservation(PacketOrder.NEXT)
                } else {
                    SequenceObservation(PacketOrder.GAP, missingPackets = distance - 1)
                }
            }
            else -> SequenceObservation(PacketOrder.OLDER)
        }
    }

    fun reset() {
        latest = null
    }
}

data class HeartbeatPolicy(
    val sendAfterMicros: Long,
    val timeoutAfterMicros: Long,
) {
    init {
        require(sendAfterMicros > 0) { "sendAfterMicros must be positive" }
        require(timeoutAfterMicros > sendAfterMicros) {
            "timeoutAfterMicros must be greater than sendAfterMicros"
        }
    }
}

enum class PeerLiveness {
    UNKNOWN,
    ALIVE,
    TIMED_OUT,
}

/** Uses only the local monotonic clock; peer timestamps are never compared directly. */
class PeerLivenessMonitor(private val policy: HeartbeatPolicy) {
    private var lastReceivedAtMicros: Long? = null
    private var lastSentAtMicros: Long? = null

    fun onPacketReceived(nowMicros: Long) {
        requireMonotonic(nowMicros)
        lastReceivedAtMicros = nowMicros
    }

    fun onPacketSent(nowMicros: Long) {
        requireMonotonic(nowMicros)
        lastSentAtMicros = nowMicros
    }

    fun livenessAt(nowMicros: Long): PeerLiveness {
        requireMonotonic(nowMicros)
        val receivedAt = lastReceivedAtMicros ?: return PeerLiveness.UNKNOWN
        return if (nowMicros - receivedAt >= policy.timeoutAfterMicros) {
            PeerLiveness.TIMED_OUT
        } else {
            PeerLiveness.ALIVE
        }
    }

    fun shouldSendHeartbeat(nowMicros: Long): Boolean {
        requireMonotonic(nowMicros)
        val sentAt = lastSentAtMicros ?: return true
        return nowMicros - sentAt >= policy.sendAfterMicros
    }

    fun reset() {
        lastReceivedAtMicros = null
        lastSentAtMicros = null
    }

    private fun requireMonotonic(nowMicros: Long) {
        require(nowMicros >= 0) { "monotonic time must not be negative" }
        require(lastReceivedAtMicros == null || nowMicros >= lastReceivedAtMicros!!) {
            "monotonic time moved backwards"
        }
        require(lastSentAtMicros == null || nowMicros >= lastSentAtMicros!!) {
            "monotonic time moved backwards"
        }
    }
}

enum class ClientSessionPhase {
    DISCONNECTED,
    NEGOTIATING,
    READY,
    ACTIVE,
    STOPPING,
    CLOSED,
    FAILED,
}

enum class ClientSessionEvent {
    TRANSPORT_CONNECTED,
    HELLO_ACKNOWLEDGED,
    SESSION_ACCEPTED,
    STOP_REQUESTED,
    TRANSPORT_CLOSED,
    FAILURE,
}

class ClientSessionStateMachine {
    var phase: ClientSessionPhase = ClientSessionPhase.DISCONNECTED
        private set

    fun transition(event: ClientSessionEvent): ClientSessionPhase {
        phase = when (event) {
            ClientSessionEvent.TRANSPORT_CONNECTED -> requirePhase(
                ClientSessionPhase.DISCONNECTED,
                next = ClientSessionPhase.NEGOTIATING,
            )
            ClientSessionEvent.HELLO_ACKNOWLEDGED -> requirePhase(
                ClientSessionPhase.NEGOTIATING,
                next = ClientSessionPhase.READY,
            )
            ClientSessionEvent.SESSION_ACCEPTED -> requirePhase(
                ClientSessionPhase.READY,
                next = ClientSessionPhase.ACTIVE,
            )
            ClientSessionEvent.STOP_REQUESTED -> requirePhase(
                ClientSessionPhase.ACTIVE,
                next = ClientSessionPhase.STOPPING,
            )
            ClientSessionEvent.TRANSPORT_CLOSED -> when (phase) {
                ClientSessionPhase.DISCONNECTED,
                ClientSessionPhase.CLOSED,
                -> throw BridgeProtocolException("Cannot close a session while $phase")
                else -> ClientSessionPhase.CLOSED
            }
            ClientSessionEvent.FAILURE -> ClientSessionPhase.FAILED
        }
        return phase
    }

    private fun requirePhase(expected: ClientSessionPhase, next: ClientSessionPhase): ClientSessionPhase {
        if (phase != expected) {
            throw BridgeProtocolException("Expected session phase $expected, found $phase")
        }
        return next
    }
}

data class ProtocolCounters(
    val acceptedPackets: Long = 0,
    val missingPackets: Long = 0,
    val duplicatePackets: Long = 0,
    val olderPackets: Long = 0,
) {
    fun record(observation: SequenceObservation): ProtocolCounters = when (observation.order) {
        PacketOrder.FIRST,
        PacketOrder.NEXT,
        -> copy(acceptedPackets = acceptedPackets + 1)
        PacketOrder.GAP -> copy(
            acceptedPackets = acceptedPackets + 1,
            missingPackets = missingPackets + observation.missingPackets,
        )
        PacketOrder.DUPLICATE -> copy(duplicatePackets = duplicatePackets + 1)
        PacketOrder.OLDER -> copy(olderPackets = olderPackets + 1)
    }
}
