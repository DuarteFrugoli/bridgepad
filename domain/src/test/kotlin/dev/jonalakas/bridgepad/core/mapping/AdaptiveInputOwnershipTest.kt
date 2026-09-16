package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveInputOwnershipTest {
    private val physical = SourceId("physical")
    private val touch = SourceId("touch")
    private val tracker = AdaptiveInputOwnership()

    @Test
    fun lastIntentionalNonNeutralSourceOwnsEachAxis() {
        val physicalState = SourceGamepadState(
            physical,
            VirtualGamepadState(leftStickX = -0.8f, rightStickY = 0.6f),
        )
        val touchState = SourceGamepadState(
            touch,
            VirtualGamepadState(leftStickX = 0.5f),
        )
        tracker.observe(physicalState)
        tracker.observe(touchState)

        val ownership = tracker.ownership(listOf(physicalState, touchState))

        assertEquals(touch, ownership.axes[VirtualAxis.LEFT_X])
        assertEquals(physical, ownership.axes[VirtualAxis.RIGHT_Y])
    }

    @Test
    fun releasingLatestSourceFallsBackToStillActiveSource() {
        var physicalState = SourceGamepadState(
            physical,
            VirtualGamepadState(leftStickX = -0.8f),
        )
        var touchState = SourceGamepadState(touch, VirtualGamepadState(leftStickX = 0.5f))
        tracker.observe(physicalState)
        tracker.observe(touchState)
        touchState = SourceGamepadState(touch, VirtualGamepadState())
        tracker.observe(touchState)

        assertEquals(
            physical,
            tracker.ownership(listOf(physicalState, touchState)).axes[VirtualAxis.LEFT_X],
        )

        physicalState = SourceGamepadState(physical, VirtualGamepadState())
        tracker.observe(physicalState)
        assertEquals(
            null,
            tracker.ownership(listOf(physicalState, touchState)).axes[VirtualAxis.LEFT_X],
        )
    }
}
