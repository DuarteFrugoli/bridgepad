package dev.jonalakas.bridgepad.ui.gamepad

import androidx.compose.ui.geometry.Offset
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchscreenGamepadScreenTest {
    @Test
    fun touchpadDeadzoneIsConsumedInsteadOfBecomingAnInitialJump() {
        val movement = movementAfterDeadzone(Offset(10f, 0f), deadzone = 4f)

        assertEquals(6f, movement.x, 0.0001f)
        assertEquals(0f, movement.y, 0.0001f)
    }

    @Test
    fun touchpadMovementInsideTapToleranceDoesNotMoveThePointer() {
        assertEquals(Offset.Zero, movementAfterDeadzone(Offset(1f, 1f), deadzone = 2f))
    }

    @Test
    fun horizontalWindowSwitcherCountsStepsInBothDirections() {
        assertEquals(-2, windowSwitcherStepCount(-95f, step = 40f))
        assertEquals(2, windowSwitcherStepCount(95f, step = 40f))
        assertEquals(0, windowSwitcherStepCount(20f, step = 40f))
    }

    @Test
    fun dpadCenterIsNeutral() {
        assertEquals(
            DpadDirection.NEUTRAL,
            directionForPosition(Offset(50f, 50f), 100f, 100f),
        )
    }

    @Test
    fun dpadMapsCardinalDirections() {
        assertEquals(DpadDirection.NORTH, directionForPosition(Offset(50f, 0f), 100f, 100f))
        assertEquals(DpadDirection.EAST, directionForPosition(Offset(100f, 50f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH, directionForPosition(Offset(50f, 100f), 100f, 100f))
        assertEquals(DpadDirection.WEST, directionForPosition(Offset(0f, 50f), 100f, 100f))
    }

    @Test
    fun dpadMapsDiagonalDirections() {
        assertEquals(DpadDirection.NORTH_EAST, directionForPosition(Offset(100f, 0f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH_EAST, directionForPosition(Offset(100f, 100f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH_WEST, directionForPosition(Offset(0f, 100f), 100f, 100f))
        assertEquals(DpadDirection.NORTH_WEST, directionForPosition(Offset(0f, 0f), 100f, 100f))
    }

    @Test
    fun dpadConnectorTrianglesRemainClickableNearTheCenter() {
        assertEquals(DpadDirection.NORTH, directionForPosition(Offset(50f, 46f), 100f, 100f))
        assertEquals(DpadDirection.EAST, directionForPosition(Offset(54f, 50f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH, directionForPosition(Offset(50f, 54f), 100f, 100f))
        assertEquals(DpadDirection.WEST, directionForPosition(Offset(46f, 50f), 100f, 100f))
    }

    @Test
    fun dpadSpacesBetweenConnectorTrianglesStillProduceDiagonals() {
        assertEquals(DpadDirection.NORTH_EAST, directionForPosition(Offset(54f, 46f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH_EAST, directionForPosition(Offset(54f, 54f), 100f, 100f))
        assertEquals(DpadDirection.SOUTH_WEST, directionForPosition(Offset(46f, 54f), 100f, 100f))
        assertEquals(DpadDirection.NORTH_WEST, directionForPosition(Offset(46f, 46f), 100f, 100f))
    }
}
