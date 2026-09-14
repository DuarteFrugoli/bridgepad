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

internal data class CornerResizeDelta(
    val scaleDelta: Float,
    val centerDeltaX: Float,
    val centerDeltaY: Float,
)

internal fun cornerResizeDelta(
    currentScale: Float,
    horizontalDirection: Int,
    verticalDirection: Int,
    pointerDeltaX: Float,
    pointerDeltaY: Float,
    baseControlWidth: Float,
    baseControlHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
): CornerResizeDelta {
    val safeBaseWidth = baseControlWidth.coerceAtLeast(1f)
    val safeBaseHeight = baseControlHeight.coerceAtLeast(1f)
    val requestedScaleDelta = (
        pointerDeltaX * horizontalDirection / safeBaseWidth +
            pointerDeltaY * verticalDirection / safeBaseHeight
        ) / 2f
    val targetScale = (currentScale + requestedScaleDelta)
        .coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)
    val appliedScaleDelta = targetScale - currentScale

    return CornerResizeDelta(
        scaleDelta = appliedScaleDelta,
        centerDeltaX = horizontalDirection * safeBaseWidth * appliedScaleDelta /
            (2f * containerWidth.coerceAtLeast(1f)),
        centerDeltaY = verticalDirection * safeBaseHeight * appliedScaleDelta /
            (2f * containerHeight.coerceAtLeast(1f)),
    )
}
