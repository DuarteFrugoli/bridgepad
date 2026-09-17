package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSchedulerTest {
    @Test
    fun unchangedStateIsNotRepeatedUntilKeepalive() {
        val scheduler = OutputScheduler(reportRateHz = 100, keepaliveIntervalMillis = 500)

        val initial = scheduler.poll(0L)!!
        assertEquals(VirtualGamepadState(), initial)
        scheduler.complete(initial, sent = true, nowNanos = 0L)
        assertNull(scheduler.poll(9_999_999L))
        assertNull(scheduler.poll(10_000_000L))
        assertEquals(VirtualGamepadState(), scheduler.poll(500_000_000L))
    }

    @Test
    fun preservesRapidPressAndReleaseTransitions() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        val pressed = VirtualGamepadState(setOf(VirtualControl.FACE_SOUTH))
        val released = VirtualGamepadState()
        scheduler.submit(pressed)
        scheduler.submit(released)

        val pressedReport = scheduler.poll(0L)!!
        assertTrue(VirtualControl.FACE_SOUTH in pressedReport.pressedButtons)
        scheduler.complete(pressedReport, sent = true, nowNanos = 0L)
        val releasedReport = scheduler.poll(10_000_000L)!!
        assertTrue(releasedReport.pressedButtons.isEmpty())
        scheduler.complete(releasedReport, sent = true, nowNanos = 10_000_000L)
        assertNull(scheduler.poll(20_000_000L))
    }

    @Test
    fun coalescesIntermediateAnalogPositions() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        scheduler.submit(VirtualGamepadState(leftStickX = -1f))
        scheduler.submit(VirtualGamepadState(leftStickX = 0.25f))
        scheduler.submit(VirtualGamepadState(leftStickX = 1f))

        val report = scheduler.poll(0L)!!
        assertEquals(1f, report.leftStickX)
        scheduler.complete(report, sent = true, nowNanos = 0L)
        assertNull(scheduler.poll(10_000_000L))
    }

    @Test
    fun rejectedReportIsRetainedForRetry() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        val pressed = VirtualGamepadState(setOf(VirtualControl.FACE_SOUTH))
        scheduler.submit(pressed)

        val firstAttempt = scheduler.poll(0L)!!
        scheduler.complete(firstAttempt, sent = false, nowNanos = 0L)

        assertNull(scheduler.poll(9_999_999L))
        assertEquals(firstAttempt, scheduler.poll(10_000_000L))
    }

    @Test
    fun stopClearsQueuedInputAndReturnsNeutralState() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        scheduler.submit(VirtualGamepadState(setOf(VirtualControl.START)))

        assertEquals(VirtualGamepadState(), scheduler.stop())
        assertEquals(VirtualGamepadState(), scheduler.poll(0L))
    }
}
