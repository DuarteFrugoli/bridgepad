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
