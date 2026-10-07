package dev.jonalakas.bridgepad.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputSessionOwnerTest {
    @Test
    fun claimingAnotherTransportStopsThePreviousOwner() {
        val owner = OutputSessionOwner()
        var stopped = 0
        owner.claim(OutputSessionOwner.Kind.BLUETOOTH_HID) { stopped++ }

        owner.claim(OutputSessionOwner.Kind.NETWORK_DESKTOP) {}

        assertEquals(1, stopped)
    }

    @Test
    fun lateReleaseFromOldLeaseDoesNotReleaseCurrentOwner() {
        val owner = OutputSessionOwner()
        val oldLease = owner.claim(OutputSessionOwner.Kind.BLUETOOTH_HID) {}
        var currentStopped = 0
        owner.claim(OutputSessionOwner.Kind.BLUETOOTH_DESKTOP) { currentStopped++ }

        oldLease.release()
        owner.claim(OutputSessionOwner.Kind.NETWORK_DESKTOP) {}

        assertEquals(1, currentStopped)
    }

    @Test
    fun releasingCurrentLeaseLeavesNothingForNextClaimToStop() {
        val owner = OutputSessionOwner()
        var stopped = 0
        val lease = owner.claim(OutputSessionOwner.Kind.BLUETOOTH_HID) { stopped++ }

        lease.release()
        owner.claim(OutputSessionOwner.Kind.NETWORK_DESKTOP) {}

        assertEquals(0, stopped)
    }
}
