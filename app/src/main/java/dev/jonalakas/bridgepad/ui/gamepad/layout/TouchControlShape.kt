package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

internal fun touchControlShape(
    control: TouchControlId,
    shape: TouchControlShape = control.defaultShape,
): Shape = when (shape) {
    TouchControlShape.CIRCLE -> CircleShape
    TouchControlShape.ROUNDED_RECTANGLE -> if (control == TouchControlId.MOUSE_TOUCHPAD) {
        RoundedCornerShape(18.dp)
    } else {
        RoundedCornerShape(16.dp)
    }
}
