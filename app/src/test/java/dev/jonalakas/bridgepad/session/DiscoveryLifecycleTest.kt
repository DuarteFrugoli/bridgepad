package dev.jonalakas.bridgepad.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryLifecycleTest {
    @Test
    fun stopThenStartWaitsForAndroidCallbackBeforeRestarting() {
        val lifecycle = DiscoveryLifecycle()

        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
        lifecycle.started()
        assertEquals(DiscoveryLifecycle.Command.STOP, lifecycle.requestStop())
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.requestStart())
        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.stopped())
    }

    @Test
    fun completedStopDoesNotRestartWhenAppRemainsStopped() {
        val lifecycle = DiscoveryLifecycle()

        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
        lifecycle.started()
        assertEquals(DiscoveryLifecycle.Command.STOP, lifecycle.requestStop())
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.stopped())
    }

    @Test
    fun duplicateStartsDoNotCreateConcurrentDiscoveryRequests() {
        val lifecycle = DiscoveryLifecycle()

        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.requestStart())
        lifecycle.started()
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.requestStart())
    }

    @Test
    fun failedStartCanBeRetried() {
        val lifecycle = DiscoveryLifecycle()

        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
        lifecycle.startFailed()
        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
    }

    @Test
    fun failedStopKeepsExistingDiscoveryWhenAppAlreadyReturned() {
        val lifecycle = DiscoveryLifecycle()

        assertEquals(DiscoveryLifecycle.Command.START, lifecycle.requestStart())
        lifecycle.started()
        assertEquals(DiscoveryLifecycle.Command.STOP, lifecycle.requestStop())
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.requestStart())
        assertEquals(DiscoveryLifecycle.Command.NONE, lifecycle.stopFailed())
    }
}
