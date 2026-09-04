package dev.jonalakas.bridgepad.input.android

import android.view.KeyEvent
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidGamepadControllerTest {
    @Test
    fun mapsRequiredAndroidButtonsByPhysicalPosition() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to VirtualControl.FACE_SOUTH,
            KeyEvent.KEYCODE_BUTTON_B to VirtualControl.FACE_EAST,
            KeyEvent.KEYCODE_BUTTON_X to VirtualControl.FACE_WEST,
            KeyEvent.KEYCODE_BUTTON_Y to VirtualControl.FACE_NORTH,
            KeyEvent.KEYCODE_BUTTON_L1 to VirtualControl.LEFT_BUMPER,
            KeyEvent.KEYCODE_BUTTON_R1 to VirtualControl.RIGHT_BUMPER,
            KeyEvent.KEYCODE_BUTTON_START to VirtualControl.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to VirtualControl.SELECT,
            KeyEvent.KEYCODE_BUTTON_THUMBL to VirtualControl.LEFT_STICK_BUTTON,
            KeyEvent.KEYCODE_BUTTON_THUMBR to VirtualControl.RIGHT_STICK_BUTTON,
        )

        expected.forEach { (keyCode, control) ->
            assertEquals(control, AndroidGamepadController.buttonForKeyCode(keyCode))
        }
        assertNull(AndroidGamepadController.buttonForKeyCode(KeyEvent.KEYCODE_UNKNOWN))
    }

    @Test
    fun mapsHatAxesIncludingDiagonalsAndNeutral() {
        assertEquals(DpadDirection.NEUTRAL, AndroidGamepadController.dpadDirection(0f, 0f))
        assertEquals(DpadDirection.NORTH, AndroidGamepadController.dpadDirection(0f, -1f))
        assertEquals(DpadDirection.SOUTH_EAST, AndroidGamepadController.dpadDirection(1f, 1f))
        assertEquals(DpadDirection.NORTH_WEST, AndroidGamepadController.dpadDirection(-1f, -1f))
    }

    @Test
    fun combinesSimultaneousDpadKeyPresses() {
        assertEquals(
            DpadDirection.NORTH_EAST,
            AndroidGamepadController.dpadDirection(
                setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT),
            ),
        )
    }
}
