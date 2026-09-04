package dev.jonalakas.bridgepad.core.mapping

import org.junit.Assert.assertEquals
import org.junit.Test

class AxisMathTest {
    @Test
    fun normalize_clampsValuesAndMapsExtremesAndCenter() {
        assertEquals(-1f, AxisMath.normalize(-20f, -10f, 10f), 0f)
        assertEquals(-1f, AxisMath.normalize(-10f, -10f, 10f), 0f)
        assertEquals(0f, AxisMath.normalize(0f, -10f, 10f), 0f)
        assertEquals(1f, AxisMath.normalize(10f, -10f, 10f), 0f)
        assertEquals(1f, AxisMath.normalize(20f, -10f, 10f), 0f)
    }

    @Test
    fun normalizeTrigger_clampsValuesAndMapsRange() {
        assertEquals(0f, AxisMath.normalizeTrigger(-1f, 0f, 100f), 0f)
        assertEquals(0.5f, AxisMath.normalizeTrigger(50f, 0f, 100f), 0f)
        assertEquals(1f, AxisMath.normalizeTrigger(101f, 0f, 100f), 0f)
    }

    @Test
    fun radialDeadzone_isNeutralAtCenterAndBoundary() {
        assertEquals(0f to 0f, AxisMath.radialDeadzone(0f, 0f, 0.2f))
        assertEquals(0f to 0f, AxisMath.radialDeadzone(0.2f, 0f, 0.2f))
    }

    @Test
    fun radialDeadzone_rescalesContinuouslyPastBoundary() {
        val justOutside = AxisMath.radialDeadzone(0.2001f, 0f, 0.2f)
        val maximum = AxisMath.radialDeadzone(1f, 0f, 0.2f)

        assertEquals(0.000125f, justOutside.first, 0.00001f)
        assertEquals(0f, justOutside.second, 0f)
        assertEquals(1f to 0f, maximum)
    }
}
