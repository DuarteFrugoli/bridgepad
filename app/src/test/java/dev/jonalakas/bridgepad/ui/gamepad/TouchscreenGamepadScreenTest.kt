package dev.jonalakas.bridgepad.ui.gamepad

import androidx.compose.ui.geometry.Offset
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchscreenGamepadScreenTest {
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
}
