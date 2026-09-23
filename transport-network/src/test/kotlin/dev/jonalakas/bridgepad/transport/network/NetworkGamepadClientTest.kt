package dev.jonalakas.bridgepad.transport.network

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.PointerReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGamepadClientTest {
    @Test
    fun fullPointerQueueAppliesBackpressureWithoutDiscardingAcceptedReports() {
        val client = newClient()

        repeat(POINTER_QUEUE_CAPACITY) { index ->
            assertTrue(client.sendPointer(PointerReport(0, index + 1, 0, 0)))
        }
        assertFalse(client.sendPointer(PointerReport(1, 0, 0, 0)))

        assertEquals(
            NetworkInputDiagnostics(
                acceptedPointerReports = POINTER_QUEUE_CAPACITY.toLong(),
                pointerBackpressureCount = 1,
                pendingPointerReports = POINTER_QUEUE_CAPACITY,
                acceptedKeyboardInputs = 0,
                keyboardBackpressureCount = 0,
                pendingKeyboardInputs = 0,
            ),
            client.inputDiagnostics(),
        )
    }

    @Test
    fun stoppedClientRejectsPointerAndKeyboardInput() {
        val client = newClient()
        client.stop()

        assertFalse(client.sendPointer(PointerReport(0, 1, 2, 0)))
        assertFalse(client.sendKeyboard(KeyboardInput.Text("a")))
    }

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

    @Test
    fun endpointHostsKeepPrimaryFirstAndRemoveDuplicates() {
        val request = NetworkGamepadRequest(
            host = "10.232.206.43",
            alternateHosts = listOf("192.168.15.3", "10.232.206.43"),
            certificateSha256 = "test",
        )

        assertEquals(listOf("10.232.206.43", "192.168.15.3"), request.endpointHosts)
    }

    private fun newClient() = NetworkGamepadClient(
        request = NetworkGamepadRequest(
            host = "127.0.0.1",
            certificateSha256 = "test",
        ),
        onStatus = {},
    )

    private companion object {
        const val POINTER_QUEUE_CAPACITY = 8
    }
}
