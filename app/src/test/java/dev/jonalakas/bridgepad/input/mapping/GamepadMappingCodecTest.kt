package dev.jonalakas.bridgepad.input.mapping

import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.AxisBinding
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMappingCodecTest {
    @Test
    fun readsExistingProfilesWithoutAddingSkippedTriggers() {
        val mapping = GamepadMappingCodec.decode(
            "B,FACE_SOUTH,FACE_EAST\nA,RIGHT_X,LEFT_X,true\nD,NORTH,SOUTH\n",
        )
        assertTrue(mapping.triggerButtons.isEmpty())
        val state = mapping.apply(
            VirtualGamepadState(
                pressedButtons = setOf(VirtualControl.FACE_EAST),
                leftStickX = 0.5f,
                leftTrigger = 1f,
                dpad = DpadDirection.SOUTH,
            ),
        )
        assertEquals(setOf(VirtualControl.FACE_SOUTH), state.pressedButtons)
        assertEquals(-0.5f, state.rightStickX, 0f)
        assertEquals(DpadDirection.NORTH, state.dpad)
        assertEquals(0f, state.leftTrigger, 0f)
    }

    @Test
    fun digitalBindingsSurviveSavingAndReloadingAlongsideAnalogBindings() {
        val mapping = GamepadMapping(
            buttons = mapOf(VirtualControl.FACE_SOUTH to VirtualControl.FACE_EAST),
            axes = mapOf(VirtualAxis.RIGHT_TRIGGER to AxisBinding(VirtualAxis.RIGHT_TRIGGER)),
            triggerButtons = mapOf(VirtualAxis.LEFT_TRIGGER to VirtualControl.EXTRA_3),
        )
        val restored = GamepadMappingCodec.decode(GamepadMappingCodec.encode(mapping))
        assertEquals(mapping, restored)
        val state = restored.apply(
            VirtualGamepadState(pressedButtons = setOf(VirtualControl.EXTRA_3), rightTrigger = 0.4f),
        )
        assertEquals(1f, state.leftTrigger, 0f)
        assertEquals(0.4f, state.rightTrigger, 0f)
        assertEquals(0f, restored.apply(VirtualGamepadState()).leftTrigger, 0f)
    }

    @Test
    fun profileContainingOnlyDigitalTriggersIsNotReplacedByDefaultMapping() {
        val mapping = GamepadMappingCodec.decode("T,LEFT_TRIGGER,EXTRA_3\nT,RIGHT_TRIGGER,EXTRA_4\n")
        assertTrue(mapping.axes.isEmpty())
        assertTrue(mapping.buttons.isEmpty())
        val state = mapping.apply(VirtualGamepadState(pressedButtons = setOf(VirtualControl.EXTRA_4)))
        assertEquals(0f, state.leftTrigger, 0f)
        assertEquals(1f, state.rightTrigger, 0f)
    }
}
