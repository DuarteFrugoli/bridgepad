package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputSchedulerTest {
    @Test
    fun respectsConfiguredReportRate() {
        val scheduler = OutputScheduler(reportRateHz = 100)

        assertEquals(VirtualGamepadState(), scheduler.poll(0L))
        assertNull(scheduler.poll(9_999_999L))
        assertEquals(VirtualGamepadState(), scheduler.poll(10_000_000L))
    }

    @Test
    fun preservesRapidPressAndReleaseTransitions() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        val pressed = VirtualGamepadState(setOf(VirtualControl.FACE_SOUTH))
        val released = VirtualGamepadState()
        scheduler.submit(pressed)
        scheduler.submit(released)

        assertTrue(VirtualControl.FACE_SOUTH in scheduler.poll(0L)!!.pressedButtons)
        assertTrue(scheduler.poll(10_000_000L)!!.pressedButtons.isEmpty())
    }

    @Test
    fun stopClearsQueuedInputAndReturnsNeutralState() {
        val scheduler = OutputScheduler(reportRateHz = 100)
        scheduler.submit(VirtualGamepadState(setOf(VirtualControl.START)))

        assertEquals(VirtualGamepadState(), scheduler.stop())
        assertEquals(VirtualGamepadState(), scheduler.poll(0L))
    }
}
