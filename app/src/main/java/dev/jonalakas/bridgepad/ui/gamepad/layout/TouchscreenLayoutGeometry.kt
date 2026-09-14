package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

internal fun controlOffset(
    placement: TouchControlPlacement,
    containerWidth: Float,
    containerHeight: Float,
    controlWidth: Float,
    controlHeight: Float,
): IntOffset {
    val maximumX = (containerWidth - controlWidth).coerceAtLeast(0f)
    val maximumY = (containerHeight - controlHeight).coerceAtLeast(0f)
    return IntOffset(
        x = (placement.centerX * containerWidth - controlWidth / 2f)
            .coerceIn(0f, maximumX)
            .roundToInt(),
        y = (placement.centerY * containerHeight - controlHeight / 2f)
            .coerceIn(0f, maximumY)
            .roundToInt(),
    )
}

internal fun floatingOverlayOffset(
    centerX: Float,
    centerY: Float,
    containerWidth: Float,
    containerHeight: Float,
    overlayWidth: Float,
    overlayHeight: Float,
): IntOffset {
    return IntOffset(
        x = floatingOverlayAxisOffset(centerX, containerWidth, overlayWidth),
        y = floatingOverlayAxisOffset(centerY, containerHeight, overlayHeight),
    )
}

internal fun floatingOverlayAxisOffset(
    center: Float,
    containerSize: Float,
    overlaySize: Float,
): Int {
    val maximum = (containerSize - overlaySize).coerceAtLeast(0f)
    return (center * containerSize - overlaySize / 2f)
        .coerceIn(0f, maximum)
        .roundToInt()
}

internal fun moveFloatingOverlayCenter(
    currentCenter: Float,
    delta: Float,
    containerSize: Float,
    overlaySize: Float,
): Float {
    val safeContainerSize = containerSize.coerceAtLeast(1f)
    val boundedOverlaySize = overlaySize.coerceIn(0f, safeContainerSize)
    val minimumCenter = boundedOverlaySize / 2f
    val maximumCenter = safeContainerSize - minimumCenter
    val currentCenterPixels = (currentCenter * safeContainerSize)
        .coerceIn(minimumCenter, maximumCenter)
    return ((currentCenterPixels + delta).coerceIn(minimumCenter, maximumCenter) / safeContainerSize)
        .coerceIn(0f, 1f)
}

internal data class ControlResizeDelta(
    val widthScaleDelta: Float,
    val heightScaleDelta: Float,
    val centerDeltaX: Float,
    val centerDeltaY: Float,
)

internal fun controlResizeDelta(
    currentWidthScale: Float,
    currentHeightScale: Float,
    horizontalDirection: Int,
    verticalDirection: Int,
    pointerDeltaX: Float,
    pointerDeltaY: Float,
    baseControlWidth: Float,
    baseControlHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    lockAspectRatio: Boolean,
): ControlResizeDelta {
    val safeBaseWidth = baseControlWidth.coerceAtLeast(1f)
    val safeBaseHeight = baseControlHeight.coerceAtLeast(1f)
    val requestedWidthDelta = if (horizontalDirection == 0) {
        0f
    } else {
        pointerDeltaX * horizontalDirection / safeBaseWidth
    }
    val requestedHeightDelta = if (verticalDirection == 0) {
        0f
    } else {
        pointerDeltaY * verticalDirection / safeBaseHeight
    }
    val (widthScaleDelta, heightScaleDelta) = if (lockAspectRatio) {
        val activeDeltas = buildList {
            if (horizontalDirection != 0) add(requestedWidthDelta)
            if (verticalDirection != 0) add(requestedHeightDelta)
        }
        val requestedUniformDelta = if (activeDeltas.isEmpty()) {
            0f
        } else {
            activeDeltas.average().toFloat()
        }
        val currentScale = minOf(currentWidthScale, currentHeightScale)
        val appliedUniformDelta = (currentScale + requestedUniformDelta)
            .coerceAtLeast(MIN_CONTROL_SCALE) - currentScale
        appliedUniformDelta to appliedUniformDelta
    } else {
        val appliedWidthDelta = (currentWidthScale + requestedWidthDelta)
            .coerceAtLeast(MIN_CONTROL_SCALE) - currentWidthScale
        val appliedHeightDelta = (currentHeightScale + requestedHeightDelta)
            .coerceAtLeast(MIN_CONTROL_SCALE) - currentHeightScale
        appliedWidthDelta to appliedHeightDelta
    }

    return ControlResizeDelta(
        widthScaleDelta = widthScaleDelta,
        heightScaleDelta = heightScaleDelta,
        centerDeltaX = horizontalDirection * safeBaseWidth * widthScaleDelta /
            (2f * containerWidth.coerceAtLeast(1f)),
        centerDeltaY = verticalDirection * safeBaseHeight * heightScaleDelta /
            (2f * containerHeight.coerceAtLeast(1f)),
    )
}
