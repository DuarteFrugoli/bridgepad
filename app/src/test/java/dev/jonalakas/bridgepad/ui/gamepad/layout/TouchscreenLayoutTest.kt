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
            TouchscreenLayoutOrientation.entries.forEach { orientation ->
                assertEquals(
                    TouchControlId.entries.toSet(),
                    BuiltInTouchscreenLayouts.profile(preset).layout(orientation).placements.keys,
                )
            }
        }
    }

    @Test
    fun profileCodecKeepsPortraitAndLandscapeTogether() {
        val expected = DefaultTouchscreenLayoutProfile.value.copy(
            landscape = DefaultTouchscreenLayoutProfile.value.landscape
                .move(TouchControlId.LEFT_STICK, 0.05f, 0f),
            portrait = DefaultTouchscreenLayoutProfile.value.portrait
                .move(TouchControlId.RIGHT_STICK, -0.04f, 0f),
        )

        val decoded = TouchscreenLayoutProfileCodec.decode(
            TouchscreenLayoutProfileCodec.encode(expected),
        )

        assertEquals(expected, decoded)
    }

    @Test
    fun editingOneOrientationDoesNotChangeTheOther() {
        val original = DefaultTouchscreenLayoutProfile.value
        val changedPortrait = original.portrait.move(TouchControlId.DPAD, 0.1f, 0f)

        val changed = original.update(TouchscreenLayoutOrientation.PORTRAIT, changedPortrait)

        assertEquals(original.landscape, changed.landscape)
        assertEquals(changedPortrait, changed.portrait)
    }

    @Test
    fun controlVisibilityIsIndependentBetweenOrientations() {
        val original = DefaultTouchscreenLayoutProfile.value
        val hiddenPortrait = original.portrait.setVisible(TouchControlId.FACE_NORTH, false)

        val changed = original.update(TouchscreenLayoutOrientation.PORTRAIT, hiddenPortrait)

        assertTrue(changed.landscape.isVisible(TouchControlId.FACE_NORTH))
        assertEquals(false, changed.portrait.isVisible(TouchControlId.FACE_NORTH))
    }

    @Test
    fun toggleInteractionIsSavedPerControlAndOrientation() {
        val original = DefaultTouchscreenLayoutProfile.value
        val expected = original.update(
            TouchscreenLayoutOrientation.LANDSCAPE,
            original.landscape.setInteraction(
                TouchControlId.LEFT_TRIGGER,
                TouchControlInteraction.TOGGLE,
            ),
        )

        val decoded = TouchscreenLayoutProfileCodec.decode(
            TouchscreenLayoutProfileCodec.encode(expected),
        )

        assertEquals(
            TouchControlInteraction.TOGGLE,
            decoded?.landscape?.placement(TouchControlId.LEFT_TRIGGER)?.interaction,
        )
        assertEquals(
            TouchControlInteraction.HOLD,
            decoded?.portrait?.placement(TouchControlId.LEFT_TRIGGER)?.interaction,
        )
    }

    @Test
    fun hiddenControlKeepsItsPlacementAndSurvivesCodecRoundTrip() {
        val original = DefaultTouchscreenLayoutProfile.value
        val expectedPlacement = original.landscape.placement(TouchControlId.FACE_NORTH)
        val expected = original.update(
            TouchscreenLayoutOrientation.LANDSCAPE,
            original.landscape.setVisible(TouchControlId.FACE_NORTH, false),
        )

        val decoded = TouchscreenLayoutProfileCodec.decode(
            TouchscreenLayoutProfileCodec.encode(expected),
        )

        assertEquals(false, decoded?.landscape?.isVisible(TouchControlId.FACE_NORTH))
        assertEquals(
            expectedPlacement.copy(visible = false),
            decoded?.landscape?.placement(TouchControlId.FACE_NORTH),
        )
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
    fun presetsUseConsistentControlShapes() {
        val roundedControls = setOf(
            TouchControlId.MOUSE_TOUCHPAD,
            TouchControlId.DPAD,
            TouchControlId.LEFT_TRIGGER,
            TouchControlId.LEFT_BUMPER,
            TouchControlId.RIGHT_BUMPER,
            TouchControlId.RIGHT_TRIGGER,
            TouchControlId.SELECT,
            TouchControlId.START,
            TouchControlId.SESSION_MENU,
        )

        TouchscreenLayoutPreset.entries.forEach { preset ->
            TouchscreenLayoutOrientation.entries.forEach { orientation ->
                val layout = BuiltInTouchscreenLayouts.profile(preset).layout(orientation)
                TouchControlId.entries.forEach { control ->
                    val expected = if (control in roundedControls) {
                        TouchControlShape.ROUNDED_RECTANGLE
                    } else {
                        TouchControlShape.CIRCLE
                    }
                    assertEquals(expected, layout.shape(control))
                }
            }
        }
    }

    @Test
    fun mobileFaceButtonsFollowTheReferenceArc() {
        listOf(BuiltInTouchscreenLayouts.mobile, BuiltInTouchscreenLayouts.mobilePortrait)
            .forEach { layout ->
                val ordered = listOf(
                    TouchControlId.FACE_NORTH,
                    TouchControlId.FACE_WEST,
                    TouchControlId.FACE_EAST,
                    TouchControlId.FACE_SOUTH,
                ).map(layout::placement)

                assertTrue(ordered.zipWithNext().all { (first, second) ->
                    first.centerX > second.centerX && first.centerY < second.centerY
                })
            }
    }

    @Test
    fun portraitPresetsFollowTheVerticalReferenceZones() {
        TouchscreenLayoutPreset.entries.forEach { preset ->
            val layout = BuiltInTouchscreenLayouts.profile(preset).portrait
            assertTrue(
                layout.placement(TouchControlId.MOUSE_TOUCHPAD).centerY <
                    layout.placement(TouchControlId.LEFT_TRIGGER).centerY,
            )
            assertTrue(
                layout.placement(TouchControlId.LEFT_TRIGGER).centerY <
                    layout.placement(TouchControlId.SELECT).centerY,
            )
            assertTrue(
                layout.placement(TouchControlId.SELECT).centerY <
                    layout.placement(TouchControlId.LEFT_STICK).centerY,
            )
            assertEquals(false, layout.isVisible(TouchControlId.SESSION_MENU))
        }
    }

    @Test
    fun circularShapeUsesEqualDimensionsAndSurvivesBeingHidden() {
        val layout = DefaultTouchscreenLayout.value
            .setShape(TouchControlId.LEFT_TRIGGER, TouchControlShape.CIRCLE)
            .setVisible(TouchControlId.LEFT_TRIGGER, false)
        val placement = layout.placement(TouchControlId.LEFT_TRIGGER)

        assertEquals(TouchControlShape.CIRCLE, layout.shape(TouchControlId.LEFT_TRIGGER))
        assertEquals(false, layout.isVisible(TouchControlId.LEFT_TRIGGER))
        assertEquals(
            controlWidthDp(TouchControlId.LEFT_TRIGGER, placement),
            controlHeightDp(TouchControlId.LEFT_TRIGGER, placement),
            0.0001f,
        )
    }

    @Test
    fun dpadAlwaysKeepsOneToOneAspectRatio() {
        val placement = DefaultTouchscreenLayout.value
            .resize(TouchControlId.DPAD, widthScale = 1.8f, heightScale = 0.7f)
            .placement(TouchControlId.DPAD)

        assertEquals(1.8f, placement.widthScale)
        assertEquals(placement.widthScale, placement.heightScale)
    }

    @Test
    fun partialSavedLayoutUsesDefaultsForMissingControls() {
        val decoded = TouchscreenLayoutProfileCodec.decode(
            "5\nLANDSCAPE,FACE_SOUTH,0.5,0.5,1.2,0.8,0.05,true,ROUNDED_RECTANGLE,TOGGLE\n",
        )?.landscape

        assertNotNull(decoded)
        assertEquals(TouchControlId.entries.size, decoded?.placements?.size)
        assertEquals(0.5f, decoded?.placement(TouchControlId.FACE_SOUTH)?.centerX)
        assertEquals(1.2f, decoded?.placement(TouchControlId.FACE_SOUTH)?.widthScale)
        assertEquals(0.8f, decoded?.placement(TouchControlId.FACE_SOUTH)?.heightScale)
        assertEquals(TouchControlInteraction.TOGGLE, decoded?.placement(TouchControlId.FACE_SOUTH)?.interaction)
        assertEquals(
            DefaultTouchscreenLayout.value.placement(TouchControlId.LEFT_STICK),
            decoded?.placement(TouchControlId.LEFT_STICK),
        )
    }

    @Test
    fun malformedVersionIsRejected() {
        assertNull(TouchscreenLayoutProfileCodec.decode("99\nLANDSCAPE,LEFT_STICK,0.5,0.5,1.0,1.0,0.05"))
    }

    @Test
    fun stickDeadzonesAreIndependentAndBounded() {
        val layout = DefaultTouchscreenLayout.value
            .setDeadzone(TouchControlId.LEFT_STICK, -1f)
            .setDeadzone(TouchControlId.RIGHT_STICK, 1f)

        assertEquals(MIN_STICK_DEADZONE, layout.placement(TouchControlId.LEFT_STICK).deadzone)
        assertEquals(MAX_STICK_DEADZONE, layout.placement(TouchControlId.RIGHT_STICK).deadzone)
    }

    @Test
    fun restoringAControlDoesNotChangeTheRestOfTheLayout() {
        val reference = BuiltInTouchscreenLayouts.mobile
        val customized = reference
            .move(TouchControlId.LEFT_STICK, 0.25f, -0.2f)
            .resize(TouchControlId.LEFT_STICK, 1.8f)
            .setDeadzone(TouchControlId.LEFT_STICK, 0.2f)
            .move(TouchControlId.RIGHT_STICK, -0.1f, 0.1f)

        val restored = customized.restoreControl(TouchControlId.LEFT_STICK, reference)

        assertEquals(
            reference.placement(TouchControlId.LEFT_STICK),
            restored.placement(TouchControlId.LEFT_STICK),
        )
        assertEquals(
            customized.placement(TouchControlId.RIGHT_STICK),
            restored.placement(TouchControlId.RIGHT_STICK),
        )
    }

    @Test
    fun movementIsBoundedButSizeOnlyHasAMinimum() {
        val layout = DefaultTouchscreenLayout.value
            .move(TouchControlId.SESSION_MENU, -5f, 8f)
            .resize(TouchControlId.SESSION_MENU, widthScale = 12f, heightScale = -4f)
        val placement = layout.placement(TouchControlId.SESSION_MENU)

        assertEquals(0f, placement.centerX)
        assertEquals(1f, placement.centerY)
        assertEquals(12f, placement.widthScale)
        assertEquals(MIN_CONTROL_SCALE, placement.heightScale)
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
    fun floatingOverlayOffsetKeepsTheEntireOverlayInsideTheCanvas() {
        val offset = floatingOverlayOffset(
            centerX = 1f,
            centerY = 1f,
            containerWidth = 800f,
            containerHeight = 360f,
            overlayWidth = 300f,
            overlayHeight = 60f,
        )

        assertEquals(500, offset.x)
        assertEquals(300, offset.y)
    }

    @Test
    fun floatingOverlayStartsMovingImmediatelyFromAClampedEdge() {
        val center = moveFloatingOverlayCenter(
            currentCenter = 0f,
            delta = 20f,
            containerSize = 800f,
            overlaySize = 300f,
        )

        assertEquals(170f / 800f, center, 0.0001f)
    }

    @Test
    fun optionsPanelMovesOppositeToAControlEnteringItsSide() {
        assertEquals(0f, optionsPanelCenterAfterControlMove(0.75f, 0.8f), 0f)
        assertEquals(1f, optionsPanelCenterAfterControlMove(0.25f, 0.2f), 0f)
    }

    @Test
    fun optionsPanelStaysPutWhileControlIsOnTheOppositeSide() {
        assertEquals(0.8f, optionsPanelCenterAfterControlMove(0.25f, 0.8f), 0f)
        assertEquals(0.2f, optionsPanelCenterAfterControlMove(0.75f, 0.2f), 0f)
    }

    @Test
    fun bottomRightResizeGrowsAndMovesCenterTowardDraggedCorner() {
        val resize = controlResizeDelta(
            currentWidthScale = 1f,
            currentHeightScale = 1f,
            horizontalDirection = 1,
            verticalDirection = 1,
            pointerDeltaX = 20f,
            pointerDeltaY = 20f,
            baseControlWidth = 100f,
            baseControlHeight = 100f,
            containerWidth = 800f,
            containerHeight = 400f,
            lockAspectRatio = false,
        )

        assertEquals(0.2f, resize.widthScaleDelta, 0.0001f)
        assertEquals(0.2f, resize.heightScaleDelta, 0.0001f)
        assertEquals(0.0125f, resize.centerDeltaX, 0.0001f)
        assertEquals(0.025f, resize.centerDeltaY, 0.0001f)
    }

    @Test
    fun topLeftResizeStopsAtMinimumScaleWithoutMovingPastIt() {
        val resize = controlResizeDelta(
            currentWidthScale = MIN_CONTROL_SCALE,
            currentHeightScale = MIN_CONTROL_SCALE,
            horizontalDirection = -1,
            verticalDirection = -1,
            pointerDeltaX = 100f,
            pointerDeltaY = 100f,
            baseControlWidth = 100f,
            baseControlHeight = 100f,
            containerWidth = 800f,
            containerHeight = 400f,
            lockAspectRatio = false,
        )

        assertEquals(0f, resize.widthScaleDelta, 0f)
        assertEquals(0f, resize.heightScaleDelta, 0f)
        assertEquals(0f, resize.centerDeltaX, 0f)
        assertEquals(0f, resize.centerDeltaY, 0f)
    }

    @Test
    fun sideHandleOnlyChangesOneDimension() {
        val resize = controlResizeDelta(
            currentWidthScale = 1f,
            currentHeightScale = 1f,
            horizontalDirection = 1,
            verticalDirection = 0,
            pointerDeltaX = 50f,
            pointerDeltaY = 80f,
            baseControlWidth = 100f,
            baseControlHeight = 100f,
            containerWidth = 800f,
            containerHeight = 400f,
            lockAspectRatio = false,
        )

        assertEquals(0.5f, resize.widthScaleDelta, 0f)
        assertEquals(0f, resize.heightScaleDelta, 0f)
        assertEquals(0f, resize.centerDeltaY, 0f)
    }

    @Test
    fun analogStickAlwaysKeepsItsAspectRatio() {
        val placement = DefaultTouchscreenLayout.value
            .resize(TouchControlId.LEFT_STICK, widthScale = 2f, heightScale = 4f)
            .placement(TouchControlId.LEFT_STICK)

        assertEquals(2f, placement.widthScale)
        assertEquals(2f, placement.heightScale)
    }

    @Test
    fun invalidValuesAreSanitized() {
        val decoded = TouchscreenLayoutProfileCodec.decode(
            "5\nLANDSCAPE,LEFT_STICK,NaN,4.0,99.0,99.0,NaN,true,CIRCLE,TOGGLE\n",
        )
        val placement = decoded?.landscape?.placement(TouchControlId.LEFT_STICK)

        assertNotNull(placement)
        assertTrue(placement!!.centerX.isFinite())
        assertEquals(1f, placement.centerY)
        assertEquals(99f, placement.widthScale)
        assertEquals(99f, placement.heightScale)
        assertEquals(DEFAULT_STICK_DEADZONE, placement.deadzone)
        assertEquals(TouchControlInteraction.HOLD, placement.interaction)
    }
}
