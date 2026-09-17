package dev.jonalakas.bridgepad.input.touch

import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchKeyboardStoreTest {
    @After fun clear() = TouchKeyboardStore.clear()

    @Test fun rejectedInputRemainsAvailableForRetry() {
        val input = KeyboardInput.Text("BridgePad")
        TouchKeyboardStore.submit(input)

        assertEquals(input, TouchKeyboardStore.peek())
        TouchKeyboardStore.acknowledge(sent = false)
        assertEquals(input, TouchKeyboardStore.peek())
        TouchKeyboardStore.acknowledge(sent = true)

        assertNull(TouchKeyboardStore.peek())
    }

    @Test fun consumeKeepsTheImmediateDeliveryContract() {
        val input = KeyboardInput.Text("A")
        TouchKeyboardStore.submit(input)

        assertEquals(input, TouchKeyboardStore.consume())
        assertNull(TouchKeyboardStore.consume())
    }
}
