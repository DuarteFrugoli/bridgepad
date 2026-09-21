package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.core.ports.KeyboardModifier
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMouseStoreTest {
    @After fun clear() {
        TouchMouseStore.clear()
        TouchMouseStore.setInvertedScroll(true)
    }

    @Test fun unchangedTouchpadProducesNoReport() {
        assertNull(TouchMouseStore.consume())
    }

    @Test fun movementIsRelativeAndConsumed() {
        TouchMouseStore.move(10f, -5f)
        val report = TouchMouseStore.consume()
        assertEquals(8, report?.deltaX)
        assertEquals(-4, report?.deltaY)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun rejectedMovementRemainsAvailableForRetry() {
        TouchMouseStore.move(10f, -5f)

        val firstAttempt = TouchMouseStore.peek()
        TouchMouseStore.acknowledge(sent = false)

        assertEquals(firstAttempt, TouchMouseStore.peek())
        TouchMouseStore.acknowledge(sent = true)
        assertNull(TouchMouseStore.peek())
    }

    @Test fun clickProducesPressAndReleaseReports() {
        TouchMouseStore.click()
        assertEquals(1, TouchMouseStore.consume()?.buttons)
        assertEquals(0, TouchMouseStore.consume()?.buttons)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun rightClickProducesPressAndReleaseReports() {
        TouchMouseStore.rightClick()
        assertEquals(2, TouchMouseStore.consume()?.buttons)
        assertEquals(0, TouchMouseStore.consume()?.buttons)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun rejectedClickDoesNotLoseItsPressOrRelease() {
        TouchMouseStore.click()

        assertEquals(1, TouchMouseStore.peek()?.buttons)
        TouchMouseStore.acknowledge(sent = false)
        assertEquals(1, TouchMouseStore.peek()?.buttons)
        TouchMouseStore.acknowledge(sent = true)
        assertEquals(0, TouchMouseStore.peek()?.buttons)
        TouchMouseStore.acknowledge(sent = true)

        assertNull(TouchMouseStore.peek())
    }

    @Test fun twoFingerMovementProducesWheelReports() {
        TouchMouseStore.scroll(-40f)
        assertEquals(-2, TouchMouseStore.consume()?.scrollY)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun scrollDirectionCanReturnToNonInvertedBehavior() {
        TouchMouseStore.setInvertedScroll(false)
        TouchMouseStore.scroll(-40f)
        assertEquals(2, TouchMouseStore.consume()?.scrollY)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun pinchProducesIndependentZoomWheelReports() {
        TouchMouseStore.zoom(25f)

        val report = TouchMouseStore.consume()

        assertEquals(2, report?.zoomY)
        assertEquals(0, report?.scrollY)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun threeFingerDesktopGestureRestoresBeforeOpeningTaskView() {
        assertEquals(
            KeyboardInput.Shortcut(setOf(KeyboardModifier.META), KeyboardKey.M),
            TouchMouseStore.threeFingerSwipeDown(),
        )
        assertNull(TouchMouseStore.threeFingerSwipeDown())
        assertEquals(
            KeyboardInput.Shortcut(
                setOf(KeyboardModifier.META, KeyboardModifier.SHIFT),
                KeyboardKey.M,
            ),
            TouchMouseStore.threeFingerSwipeUp(),
        )
        assertEquals(
            KeyboardInput.Shortcut(setOf(KeyboardModifier.META), KeyboardKey.TAB),
            TouchMouseStore.threeFingerSwipeUp(),
        )
        assertEquals(
            KeyboardInput.Key(KeyboardKey.ESCAPE),
            TouchMouseStore.threeFingerSwipeDown(),
        )
    }

    @Test fun regularInteractionCancelsRestoringPreviouslyMinimizedWindows() {
        TouchMouseStore.threeFingerSwipeDown()
        TouchMouseStore.click()

        assertEquals(
            KeyboardInput.Shortcut(setOf(KeyboardModifier.META), KeyboardKey.TAB),
            TouchMouseStore.threeFingerSwipeUp(),
        )
    }

    @Test fun encoderClampsMouseMovement() {
        assertArrayEquals(
            byteArrayOf(1, 127, -127, 0),
            dev.jonalakas.bridgepad.output.hid.GamepadHidDescriptor.mouseReport(1, 500, -500),
        )
    }
}
