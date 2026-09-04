package dev.jonalakas.bridgepad.input.touch

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchMouseStoreTest {
    @After fun clear() = TouchMouseStore.clear()

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

    @Test fun clickProducesPressAndReleaseReports() {
        TouchMouseStore.click()
        assertEquals(1, TouchMouseStore.consume()?.buttons)
        assertEquals(0, TouchMouseStore.consume()?.buttons)
        assertNull(TouchMouseStore.consume())
    }

    @Test fun encoderClampsMouseMovement() {
        assertArrayEquals(
            byteArrayOf(1, 127, -127),
            dev.jonalakas.bridgepad.output.hid.GamepadHidDescriptor.mouseReport(1, 500, -500),
        )
    }
}
