package dev.jonalakas.bridgepad.ui.gamepad.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchscreenLayoutTest {
    @Test
    fun defaultLayoutContainsEveryControl() {
        assertEquals(TouchControlId.entries.toSet(), DefaultTouchscreenLayout.value.placements.keys)
    }

    @Test
    fun everyBuiltInPresetContainsEveryControl() {
        TouchscreenLayoutPreset.entries.forEach { preset ->
            assertEquals(
                TouchControlId.entries.toSet(),
                BuiltInTouchscreenLayouts.layout(preset).placements.keys,
            )
        }
    }

    @Test
    fun builtInPresetsHaveDistinctStickArrangements() {
        val symmetric = BuiltInTouchscreenLayouts.symmetric
        val asymmetric = BuiltInTouchscreenLayouts.asymmetric
        val mobile = BuiltInTouchscreenLayouts.mobile

        assertEquals(
            symmetric.placement(TouchControlId.LEFT_STICK).centerY,
            symmetric.placement(TouchControlId.RIGHT_STICK).centerY,
        )
        assertTrue(
            asymmetric.placement(TouchControlId.LEFT_STICK).centerY <
                asymmetric.placement(TouchControlId.RIGHT_STICK).centerY,
        )
        assertTrue(
            mobile.placement(TouchControlId.LEFT_STICK).centerX <
                mobile.placement(TouchControlId.RIGHT_STICK).centerX,
        )
    }

    @Test
    fun layoutCodecRoundTripsPlacements() {
        val expected = DefaultTouchscreenLayout.value
            .move(TouchControlId.LEFT_STICK, 0.05f, -0.04f)
            .resize(TouchControlId.FACE_SOUTH, 1.25f)

        val decoded = TouchscreenLayoutCodec.decode(TouchscreenLayoutCodec.encode(expected))

        assertEquals(expected, decoded)
    }

    @Test
    fun partialSavedLayoutUsesDefaultsForMissingControls() {
        val decoded = TouchscreenLayoutCodec.decode("1\nFACE_SOUTH,0.5,0.5,1.2\n")

        assertNotNull(decoded)
        assertEquals(TouchControlId.entries.size, decoded?.placements?.size)
        assertEquals(0.5f, decoded?.placement(TouchControlId.FACE_SOUTH)?.centerX)
        assertEquals(
            DefaultTouchscreenLayout.value.placement(TouchControlId.LEFT_STICK),
            decoded?.placement(TouchControlId.LEFT_STICK),
        )
    }

    @Test
    fun malformedVersionIsRejected() {
        assertNull(TouchscreenLayoutCodec.decode("99\nLEFT_STICK,0.5,0.5,1.0"))
    }

    @Test
    fun movementAndSizeStayInsideSupportedRanges() {
        val layout = DefaultTouchscreenLayout.value
            .move(TouchControlId.DPAD, -5f, 8f)
            .resize(TouchControlId.DPAD, 12f)
        val placement = layout.placement(TouchControlId.DPAD)

        assertEquals(0f, placement.centerX)
        assertEquals(1f, placement.centerY)
        assertEquals(MAX_CONTROL_SCALE, placement.scale)
    }

    @Test
    fun renderedOffsetKeepsAControlInsideTheCanvas() {
        val offset = controlOffset(
            placement = TouchControlPlacement(centerX = 1f, centerY = 1f),
            containerWidth = 800f,
            containerHeight = 360f,
            controlWidth = 100f,
            controlHeight = 80f,
        )

        assertEquals(700, offset.x)
        assertEquals(280, offset.y)
    }

    @Test
    fun bottomRightResizeGrowsAndMovesCenterTowardDraggedCorner() {
        val resize = cornerResizeDelta(
            currentScale = 1f,
            horizontalDirection = 1,
            verticalDirection = 1,
            pointerDeltaX = 20f,
            pointerDeltaY = 20f,
            baseControlWidth = 100f,
            baseControlHeight = 100f,
            containerWidth = 800f,
            containerHeight = 400f,
        )

        assertEquals(0.2f, resize.scaleDelta, 0.0001f)
        assertEquals(0.0125f, resize.centerDeltaX, 0.0001f)
        assertEquals(0.025f, resize.centerDeltaY, 0.0001f)
    }

    @Test
    fun topLeftResizeStopsAtMinimumScaleWithoutMovingPastIt() {
        val resize = cornerResizeDelta(
            currentScale = MIN_CONTROL_SCALE,
            horizontalDirection = -1,
            verticalDirection = -1,
            pointerDeltaX = 100f,
            pointerDeltaY = 100f,
            baseControlWidth = 100f,
            baseControlHeight = 100f,
            containerWidth = 800f,
            containerHeight = 400f,
        )

        assertEquals(0f, resize.scaleDelta, 0f)
        assertEquals(0f, resize.centerDeltaX, 0f)
        assertEquals(0f, resize.centerDeltaY, 0f)
    }

    @Test
    fun invalidValuesAreSanitized() {
        val decoded = TouchscreenLayoutCodec.decode("1\nLEFT_STICK,NaN,4.0,99.0\n")
        val placement = decoded?.placement(TouchControlId.LEFT_STICK)

        assertNotNull(placement)
        assertTrue(placement!!.centerX.isFinite())
        assertEquals(1f, placement.centerY)
        assertEquals(MAX_CONTROL_SCALE, placement.scale)
    }
}
