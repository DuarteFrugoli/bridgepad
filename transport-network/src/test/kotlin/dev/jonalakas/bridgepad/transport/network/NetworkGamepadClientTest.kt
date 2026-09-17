package dev.jonalakas.bridgepad.transport.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkGamepadClientTest {
    @Test
    fun unavailableDesktopBeforeFirstConnectionKeepsInitialFailure() {
        val failure = NetworkGamepadStatus.Failed(
            NetworkFailureReason.DESKTOP_UNAVAILABLE,
            "connection refused",
        )

        assertEquals(failure, normalizeFailureAfterActiveSession(failure, hasBeenActive = false))
    }

    @Test
    fun unavailableDesktopAfterActiveSessionIsReportedAsConnectionLost() {
        val failure = NetworkGamepadStatus.Failed(
            NetworkFailureReason.DESKTOP_UNAVAILABLE,
            "connection refused",
        )

        assertEquals(
            NetworkFailureReason.CONNECTION_LOST,
            normalizeFailureAfterActiveSession(failure, hasBeenActive = true).reason,
        )
    }

    @Test
    fun securityFailureIsNotHiddenAfterActiveSession() {
        val failure = NetworkGamepadStatus.Failed(
            NetworkFailureReason.CERTIFICATE_CHANGED,
            "fingerprint mismatch",
        )

        assertEquals(failure, normalizeFailureAfterActiveSession(failure, hasBeenActive = true))
    }
}
