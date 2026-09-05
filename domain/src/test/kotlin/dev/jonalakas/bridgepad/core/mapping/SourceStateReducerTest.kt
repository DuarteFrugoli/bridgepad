package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.RawInputEvent
import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceStateReducerTest {
    private val sourceId = SourceId("touchscreen")

    @Test
    fun everyButtonCanBePressedAndReleased() {
        VirtualControl.entries.forEach { control ->
            val initial = SourceGamepadState(sourceId)
            val pressed = SourceStateReducer.reduce(
                initial,
                RawInputEvent.Button(sourceId, control, true, 1L),
            )
            val released = SourceStateReducer.reduce(
                pressed,
                RawInputEvent.Button(sourceId, control, false, 2L),
            )

            assertTrue(control.name, control in pressed.gamepad.pressedButtons)
            assertFalse(control.name, control in released.gamepad.pressedButtons)
        }
    }

    @Test
    fun axisEventsUseStickAndTriggerRanges() {
        val registry = SourceStateRegistry()
        registry.apply(RawInputEvent.Axis(sourceId, VirtualAxis.LEFT_X, -2f, 1L))
        val state = registry.apply(RawInputEvent.Axis(sourceId, VirtualAxis.LEFT_TRIGGER, 2f, 2L))

        assertEquals(-1f, state.gamepad.leftStickX, 0f)
        assertEquals(1f, state.gamepad.leftTrigger, 0f)
    }
}
