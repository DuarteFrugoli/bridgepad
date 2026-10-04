package dev.jonalakas.bridgepad.ui.home

import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayModeTest {
    @Test
    fun streamingRequiresAnExplicitPhysicalControllerSelection() {
        assertFalse(
            streamingInputReady(
                controllerType = null,
                captureMode = PhysicalCaptureMode.COMPATIBILITY,
                compatibilityAvailable = true,
                directUsbAvailable = false,
            ),
        )
        assertFalse(
            streamingInputReady(
                controllerType = PlayControllerType.VIRTUAL,
                captureMode = PhysicalCaptureMode.COMPATIBILITY,
                compatibilityAvailable = true,
                directUsbAvailable = false,
            ),
        )
    }

    @Test
    fun captureModeMustHaveItsMatchingPhysicalSource() {
        assertTrue(
            streamingInputReady(
                controllerType = PlayControllerType.PHYSICAL,
                captureMode = PhysicalCaptureMode.COMPATIBILITY,
                compatibilityAvailable = true,
                directUsbAvailable = false,
            ),
        )
        assertFalse(
            streamingInputReady(
                controllerType = PlayControllerType.PHYSICAL,
                captureMode = PhysicalCaptureMode.BACKGROUND_USB,
                compatibilityAvailable = true,
                directUsbAvailable = false,
            ),
        )
        assertTrue(
            streamingInputReady(
                controllerType = PlayControllerType.PHYSICAL,
                captureMode = PhysicalCaptureMode.BACKGROUND_USB,
                compatibilityAvailable = false,
                directUsbAvailable = true,
            ),
        )
    }
}
