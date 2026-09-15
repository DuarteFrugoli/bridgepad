package dev.jonalakas.bridgepad.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSessionTest {
    @Test
    fun sequenceTracker_detectsGapsDuplicatesOldPacketsAndWraparound() {
        val tracker = SequenceTracker()

        assertEquals(SequenceObservation(PacketOrder.FIRST), tracker.observe(0xffff_fffeL))
        assertEquals(SequenceObservation(PacketOrder.NEXT), tracker.observe(0xffff_ffffL))
        assertEquals(SequenceObservation(PacketOrder.NEXT), tracker.observe(0))
        assertEquals(SequenceObservation(PacketOrder.GAP, 2), tracker.observe(3))
        assertEquals(SequenceObservation(PacketOrder.DUPLICATE), tracker.observe(3))
        assertEquals(SequenceObservation(PacketOrder.OLDER), tracker.observe(2))
        assertEquals(3L, tracker.latest)
    }

    @Test
    fun counters_onlyAcceptNewPackets() {
        val observations = listOf(
            SequenceObservation(PacketOrder.FIRST),
            SequenceObservation(PacketOrder.NEXT),
            SequenceObservation(PacketOrder.GAP, 4),
            SequenceObservation(PacketOrder.DUPLICATE),
            SequenceObservation(PacketOrder.OLDER),
        )

        val counters = observations.fold(ProtocolCounters()) { current, observation ->
            current.record(observation)
        }

        assertEquals(ProtocolCounters(3, 4, 1, 1), counters)
    }

    @Test
    fun liveness_usesLocalTimeAndTimesOutAtConfiguredBoundary() {
        val monitor = PeerLivenessMonitor(HeartbeatPolicy(500, 2_000))

        assertEquals(PeerLiveness.UNKNOWN, monitor.livenessAt(0))
        assertTrue(monitor.shouldSendHeartbeat(0))
        monitor.onPacketSent(100)
        assertFalse(monitor.shouldSendHeartbeat(599))
        assertTrue(monitor.shouldSendHeartbeat(600))

        monitor.onPacketReceived(1_000)
        assertEquals(PeerLiveness.ALIVE, monitor.livenessAt(2_999))
        assertEquals(PeerLiveness.TIMED_OUT, monitor.livenessAt(3_000))
    }

    @Test
    fun clientSession_requiresOrderedHandshakeAndAllowsFailureFromAnyPhase() {
        val session = ClientSessionStateMachine()
        assertEquals(
            ClientSessionPhase.NEGOTIATING,
            session.transition(ClientSessionEvent.TRANSPORT_CONNECTED),
        )
        assertEquals(
            ClientSessionPhase.READY,
            session.transition(ClientSessionEvent.HELLO_ACKNOWLEDGED),
        )
        assertEquals(
            ClientSessionPhase.ACTIVE,
            session.transition(ClientSessionEvent.SESSION_ACCEPTED),
        )
        assertEquals(
            ClientSessionPhase.STOPPING,
            session.transition(ClientSessionEvent.STOP_REQUESTED),
        )
        assertEquals(
            ClientSessionPhase.CLOSED,
            session.transition(ClientSessionEvent.TRANSPORT_CLOSED),
        )

        val invalid = ClientSessionStateMachine()
        assertThrows(BridgeProtocolException::class.java) {
            invalid.transition(ClientSessionEvent.SESSION_ACCEPTED)
        }
        assertEquals(ClientSessionPhase.FAILED, invalid.transition(ClientSessionEvent.FAILURE))
    }
}
