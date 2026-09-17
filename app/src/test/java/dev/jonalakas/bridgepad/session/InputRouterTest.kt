package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InputRouterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun resetInputs() {
        PhysicalGamepadStore.set(PhysicalGamepadState())
        DirectUsbGamepadStore.clear()
    }

    @After
    fun cleanUp() {
        PhysicalGamepadStore.set(PhysicalGamepadState())
        DirectUsbGamepadStore.clear()
        scope.cancel()
    }

    @Test
    fun compatibilityAndDirectUsbSubscribersKeepIndependentCaptureModes() {
        val router = InputRouter(scope)
        var compatibility = VirtualGamepadState()
        var directUsb = VirtualGamepadState()
        val compatibilitySubscription = router.observe(PhysicalCaptureMode.COMPATIBILITY) {
            compatibility = it.gamepad
        }
        val directUsbSubscription = router.observe(PhysicalCaptureMode.BACKGROUND_USB) {
            directUsb = it.gamepad
        }

        val physicalSource = SourceId("test-physical")
        PhysicalGamepadStore.set(
            PhysicalGamepadState(
                sourceStates = mapOf(
                    physicalSource to VirtualGamepadState(
                        pressedButtons = setOf(VirtualControl.FACE_EAST),
                    ),
                ),
                inputEventCount = 1,
                lastInputTimestampNanos = 1,
            ),
        )
        DirectUsbGamepadStore.set(
            DirectUsbState(
                active = true,
                gamepad = VirtualGamepadState(
                    pressedButtons = setOf(VirtualControl.FACE_SOUTH),
                ),
                inputEventCount = 1,
                lastInputTimestampNanos = 2,
            ),
        )

        assertEquals(setOf(VirtualControl.FACE_EAST), compatibility.pressedButtons)
        assertEquals(setOf(VirtualControl.FACE_SOUTH), directUsb.pressedButtons)

        compatibilitySubscription.cancel()
        directUsbSubscription.cancel()
    }
}
