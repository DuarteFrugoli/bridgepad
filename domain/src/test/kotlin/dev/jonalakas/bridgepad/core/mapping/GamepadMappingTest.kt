package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMappingTest {
    @Test
    fun digitalTriggersCanBeHeldAndReleasedIndependently() {
        val mapping = GamepadMapping(
            buttons = emptyMap(),
            triggerButtons = mapOf(
                VirtualAxis.LEFT_TRIGGER to VirtualControl.EXTRA_3,
                VirtualAxis.RIGHT_TRIGGER to VirtualControl.EXTRA_4,
            ),
        )
        val both = mapping.apply(
            VirtualGamepadState(pressedButtons = setOf(VirtualControl.EXTRA_3, VirtualControl.EXTRA_4)),
        )
        assertEquals(1f, both.leftTrigger, 0f)
        assertEquals(1f, both.rightTrigger, 0f)
        assertTrue(both.pressedButtons.isEmpty())

        val rightOnly = mapping.apply(VirtualGamepadState(pressedButtons = setOf(VirtualControl.EXTRA_4)))
        assertEquals(0f, rightOnly.leftTrigger, 0f)
        assertEquals(1f, rightOnly.rightTrigger, 0f)
        assertEquals(VirtualGamepadState(), mapping.apply(VirtualGamepadState()))
    }

    @Test
    fun identityMappingPreservesPartialAnalogTriggerValues() {
        val state = VirtualGamepadState(leftTrigger = 0.25f, rightTrigger = 0.75f)
        assertEquals(state, GamepadMapping().apply(state))
    }

    @Test
    fun invertedAnalogTriggerUsesTheWholeRangeWithoutADiscontinuity() {
        val mapping = GamepadMapping(
            axes = mapOf(VirtualAxis.LEFT_TRIGGER to AxisBinding(VirtualAxis.LEFT_TRIGGER, inverted = true)),
        )
        for (value in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(1f - value, mapping.apply(VirtualGamepadState(leftTrigger = value)).leftTrigger, 0f)
        }
    }

    @Test
    fun stickMappedToTriggerOnlyRespondsInTheChosenDirection() {
        val mapping = GamepadMapping(
            axes = mapOf(
                VirtualAxis.LEFT_TRIGGER to AxisBinding(VirtualAxis.LEFT_Y, inverted = true),
                VirtualAxis.RIGHT_TRIGGER to AxisBinding(VirtualAxis.RIGHT_X),
            ),
        )
        val pressed = mapping.apply(VirtualGamepadState(leftStickY = -0.6f, rightStickX = 0.4f))
        assertEquals(0.6f, pressed.leftTrigger, 0f)
        assertEquals(0.4f, pressed.rightTrigger, 0f)
        val opposite = mapping.apply(VirtualGamepadState(leftStickY = 0.6f, rightStickX = -0.4f))
        assertEquals(0f, opposite.leftTrigger, 0f)
        assertEquals(0f, opposite.rightTrigger, 0f)
        assertEquals(0f, mapping.apply(VirtualGamepadState()).leftTrigger, 0f)
    }
}
