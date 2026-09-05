package dev.jonalakas.bridgepad.core.mapping

import dev.jonalakas.bridgepad.core.gamepad.RawInputEvent
import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputMergerTest {
    private val physical = SourceId("physical")
    private val touch = SourceId("touch")

    @Test
    fun buttonsAreUnionedAcrossSources() {
        val merged = InputMerger.merge(
            listOf(
                SourceGamepadState(physical, VirtualGamepadState(setOf(VirtualControl.FACE_SOUTH))),
                SourceGamepadState(touch, VirtualGamepadState(setOf(VirtualControl.START))),
            ),
        )

        assertEquals(setOf(VirtualControl.FACE_SOUTH, VirtualControl.START), merged.pressedButtons)
    }

    @Test
    fun eachAxisUsesItsExplicitOwner() {
        val merged = InputMerger.merge(
            listOf(
                SourceGamepadState(physical, VirtualGamepadState(leftStickX = -0.75f, rightStickX = -0.5f)),
                SourceGamepadState(touch, VirtualGamepadState(leftStickX = 0.25f, rightStickX = 0.8f)),
            ),
            InputOwnership(
                axes = mapOf(VirtualAxis.LEFT_X to physical, VirtualAxis.RIGHT_X to touch),
            ),
        )

        assertEquals(-0.75f, merged.leftStickX, 0f)
        assertEquals(0.8f, merged.rightStickX, 0f)
    }

    @Test
    fun removingActiveSourceRemovesItsContribution() {
        val registry = SourceStateRegistry()
        registry.apply(RawInputEvent.Button(physical, VirtualControl.FACE_SOUTH, true, 1L))
        registry.remove(physical)

        val merged = InputMerger.merge(registry.snapshots())

        assertTrue(merged.pressedButtons.isEmpty())
        assertEquals(VirtualGamepadState(), merged)
    }

    @Test
    fun clearingAllSourcesProducesNeutralState() {
        val registry = SourceStateRegistry()
        registry.apply(RawInputEvent.Button(touch, VirtualControl.START, true, 1L))
        registry.clear()

        assertEquals(VirtualGamepadState(), InputMerger.merge(registry.snapshots()))
    }
}
