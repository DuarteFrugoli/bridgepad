package dev.jonalakas.bridgepad.input.android

import android.view.KeyEvent
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.RawInputEvent
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.mapping.SourceStateRegistry
import dev.jonalakas.bridgepad.output.hid.HidReportEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidGamepadControllerTest {
    @Test
    fun digitalTriggerKeysReachHidAndReleaseIndependently() {
        val source = SourceId("digital-controller")
        val registry = SourceStateRegistry()
        fun apply(key: Int, pressed: Boolean): ByteArray {
            val event = requireNotNull(
                AndroidGamepadController.digitalTriggerEvent(source, key, pressed, 123L) { false },
            )
            return HidReportEncoder.encode(registry.apply(event).gamepad)
        }
        assertEquals(255, apply(KeyEvent.KEYCODE_BUTTON_L2, true)[7].toInt() and 0xff)
        val both = apply(KeyEvent.KEYCODE_BUTTON_R2, true)
        assertEquals(255, both[7].toInt() and 0xff)
        assertEquals(255, both[8].toInt() and 0xff)
        val rightOnly = apply(KeyEvent.KEYCODE_BUTTON_L2, false)
        assertEquals(0, rightOnly[7].toInt())
        assertEquals(255, rightOnly[8].toInt() and 0xff)
        assertEquals(0, apply(KeyEvent.KEYCODE_BUTTON_R2, false)[8].toInt())
    }

    @Test
    fun duplicateKeyEventsDoNotOverwriteAnalogTriggerValues() {
        val source = SourceId("analog-controller")
        val registry = SourceStateRegistry()
        val analog = RawInputEvent.Axis(source, VirtualAxis.LEFT_TRIGGER, 0.35f, 1L)
        registry.apply(analog)
        for (pressed in listOf(true, false)) {
            val duplicate = AndroidGamepadController.digitalTriggerEvent(
                source, KeyEvent.KEYCODE_BUTTON_L2, pressed, 2L,
            ) { it == VirtualAxis.LEFT_TRIGGER }
            assertNull(duplicate)
        }
        assertEquals(0.35f, registry.snapshots().single().gamepad.leftTrigger, 0f)
        val rightDigital = requireNotNull(
            AndroidGamepadController.digitalTriggerEvent(source, KeyEvent.KEYCODE_BUTTON_R2, true, 3L) {
                it == VirtualAxis.LEFT_TRIGGER
            },
        )
        val state = registry.apply(rightDigital).gamepad
        assertEquals(0.35f, state.leftTrigger, 0f)
        assertEquals(1f, state.rightTrigger, 0f)
    }

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
            KeyEvent.KEYCODE_BUTTON_1 to VirtualControl.GUIDE,
            KeyEvent.KEYCODE_BUTTON_2 to VirtualControl.CAPTURE,
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
