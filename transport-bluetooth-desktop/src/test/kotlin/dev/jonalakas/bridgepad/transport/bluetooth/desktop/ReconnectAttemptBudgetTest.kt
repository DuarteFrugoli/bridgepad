package dev.jonalakas.bridgepad.transport.bluetooth.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectAttemptBudgetTest {
    @Test
    fun shortFailedConnectionsConsumeTheSameBudget() {
        val budget = ReconnectAttemptBudget(maximumAttempts = 2, stableConnectionNanos = 1_000L)

        budget.connected(0L)
        assertEquals(1, budget.nextAttemptAfterDisconnect(999L))
        budget.connected(1_000L)
        assertEquals(2, budget.nextAttemptAfterDisconnect(1_999L))
        assertNull(budget.nextAttemptAfterDisconnect(2_000L))
    }

    @Test
    fun stableConnectionRestoresTheReconnectBudget() {
        val budget = ReconnectAttemptBudget(maximumAttempts = 2, stableConnectionNanos = 1_000L)

        budget.connected(0L)
        assertEquals(1, budget.nextAttemptAfterDisconnect(500L))
        budget.connected(1_000L)
        assertEquals(1, budget.nextAttemptAfterDisconnect(2_000L))
    }

    @Test
    fun zeroAttemptsDisablesReconnect() {
        val budget = ReconnectAttemptBudget(maximumAttempts = 0, stableConnectionNanos = 1_000L)

        budget.connected(0L)
        assertNull(budget.nextAttemptAfterDisconnect(2_000L))
    }
}
