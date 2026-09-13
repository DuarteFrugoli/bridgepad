package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.localization.LocalizedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    @Test
    fun enabledBluetoothClearsServiceOffNotice() {
        val state = SessionState(
            bluetoothEnabled = false,
            message = LocalizedMessage(R.string.hid_bluetooth_off),
            feedbackLevel = FeedbackLevel.WARNING,
        )

        val reconciled = state.reconcileBluetoothAvailability(enabled = true)

        assertEquals(true, reconciled.bluetoothEnabled)
        assertNull(reconciled.message)
        assertEquals(FeedbackLevel.INFO, reconciled.feedbackLevel)
    }

    @Test
    fun enabledBluetoothClearsSetupOffNotice() {
        val state = SessionState(message = LocalizedMessage(R.string.bluetooth_required))

        assertNull(state.reconcileBluetoothAvailability(enabled = true).message)
    }

    @Test
    fun unrelatedNoticeSurvivesBluetoothRefresh() {
        val message = LocalizedMessage(R.string.selected_pc_unavailable)
        val state = SessionState(message = message, feedbackLevel = FeedbackLevel.WARNING)

        val reconciled = state.reconcileBluetoothAvailability(enabled = true)

        assertEquals(message, reconciled.message)
        assertEquals(FeedbackLevel.WARNING, reconciled.feedbackLevel)
    }

    @Test
    fun bluetoothOffNoticeRemainsWhileAdapterIsOff() {
        val message = LocalizedMessage(R.string.hid_bluetooth_off)
        val state = SessionState(message = message, feedbackLevel = FeedbackLevel.WARNING)

        val reconciled = state.reconcileBluetoothAvailability(enabled = false)

        assertEquals(message, reconciled.message)
        assertEquals(FeedbackLevel.WARNING, reconciled.feedbackLevel)
    }

    @Test
    fun missingPermissionDoesNotPretendBluetoothIsOff() {
        val state = SessionState(
            message = LocalizedMessage(R.string.hid_bluetooth_off),
            feedbackLevel = FeedbackLevel.WARNING,
        )

        val reconciled = state.reconcileBluetoothAvailability(
            enabled = false,
            permissionGranted = false,
        )

        assertNull(reconciled.message)
        assertEquals(FeedbackLevel.INFO, reconciled.feedbackLevel)
    }
}
