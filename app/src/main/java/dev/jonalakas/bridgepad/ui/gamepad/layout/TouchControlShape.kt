package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal fun touchControlShape(control: TouchControlId): Shape = when (control) {
    TouchControlId.LEFT_STICK,
    TouchControlId.RIGHT_STICK,
    TouchControlId.FACE_NORTH,
    TouchControlId.FACE_WEST,
    TouchControlId.FACE_EAST,
    TouchControlId.FACE_SOUTH -> CircleShape
    else -> RoundedCornerShape(16.dp)
}
