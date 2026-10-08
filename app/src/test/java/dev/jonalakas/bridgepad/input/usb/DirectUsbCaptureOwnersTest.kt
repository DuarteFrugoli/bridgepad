package dev.jonalakas.bridgepad.input.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUsbCaptureOwnersTest {
    @Test
    fun captureStopsOnlyAfterTheLastOwnerReleasesIt() {
        val owners = DirectUsbCaptureOwners()

        owners.acquire("ui")
        owners.acquire("old-transport")
        owners.acquire("new-transport")

        assertFalse(owners.release("old-transport"))
        assertFalse(owners.release("ui"))
        assertTrue(owners.release("new-transport"))
    }

    @Test
    fun duplicateAcquireAndStaleReleaseCannotStopAnotherOwner() {
        val owners = DirectUsbCaptureOwners()

        owners.acquire("session")
        owners.acquire("session")

        assertFalse(owners.release("stale-session"))
        assertTrue(owners.release("session"))
        assertFalse(owners.release("session"))
    }
}
