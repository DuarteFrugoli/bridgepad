package dev.jonalakas.bridgepad.ui.gamepad.layout

enum class TouchControlId(
    val baseWidthDp: Float,
    val baseHeightDp: Float,
) {
    MOUSE_TOUCHPAD(210f, 92f),
    DPAD(132f, 132f),
    LEFT_STICK(132f, 132f),
    RIGHT_STICK(132f, 132f),
    LEFT_TRIGGER(76f, 52f),
    LEFT_BUMPER(76f, 52f),
    RIGHT_BUMPER(76f, 52f),
    RIGHT_TRIGGER(76f, 52f),
    FACE_NORTH(58f, 58f),
    FACE_WEST(58f, 58f),
    FACE_EAST(58f, 58f),
    FACE_SOUTH(58f, 58f),
    SELECT(76f, 48f),
    START(76f, 48f),
    LEFT_STICK_BUTTON(64f, 48f),
    RIGHT_STICK_BUTTON(64f, 48f),
    SESSION_MENU(116f, 48f),
}

data class TouchControlPlacement(
    val centerX: Float,
    val centerY: Float,
    val scale: Float = 1f,
) {
    fun sanitized(fallback: TouchControlPlacement): TouchControlPlacement = TouchControlPlacement(
        centerX = centerX.finiteOr(fallback.centerX).coerceIn(0f, 1f),
        centerY = centerY.finiteOr(fallback.centerY).coerceIn(0f, 1f),
        scale = scale.finiteOr(fallback.scale).coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE),
    )
}

data class TouchscreenLayout(
    val placements: Map<TouchControlId, TouchControlPlacement>,
) {
    fun placement(control: TouchControlId): TouchControlPlacement =
        placements[control] ?: DefaultTouchscreenLayout.value.placements.getValue(control)

    fun move(control: TouchControlId, deltaX: Float, deltaY: Float): TouchscreenLayout =
        update(control) {
            it.copy(
                centerX = (it.centerX + deltaX).coerceIn(0f, 1f),
                centerY = (it.centerY + deltaY).coerceIn(0f, 1f),
            )
        }

    fun resize(control: TouchControlId, scale: Float): TouchscreenLayout =
        update(control) { it.copy(scale = scale.coerceIn(MIN_CONTROL_SCALE, MAX_CONTROL_SCALE)) }

    fun sanitized(): TouchscreenLayout = TouchscreenLayout(
        TouchControlId.entries.associateWith { control ->
            placement(control).sanitized(DefaultTouchscreenLayout.value.placements.getValue(control))
        },
    )

    private fun update(
        control: TouchControlId,
        transform: (TouchControlPlacement) -> TouchControlPlacement,
    ): TouchscreenLayout = copy(placements = placements + (control to transform(placement(control))))
}

object DefaultTouchscreenLayout {
    val value = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.26f),
            TouchControlId.DPAD to TouchControlPlacement(0.16f, 0.39f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.16f, 0.76f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.84f, 0.76f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.07f, 0.08f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.18f, 0.08f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.82f, 0.08f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.93f, 0.08f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.84f, 0.27f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.77f, 0.42f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.91f, 0.42f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.84f, 0.57f),
            TouchControlId.SELECT to TouchControlPlacement(0.43f, 0.56f),
            TouchControlId.START to TouchControlPlacement(0.57f, 0.56f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.43f, 0.72f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.57f, 0.72f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.90f),
        ),
    )
}

internal object TouchscreenLayoutCodec {
    private const val VERSION = "1"

    fun encode(layout: TouchscreenLayout): String = buildString {
        appendLine(VERSION)
        layout.sanitized().placements.forEach { (control, placement) ->
            appendLine("${control.name},${placement.centerX},${placement.centerY},${placement.scale}")
        }
    }

    fun decode(value: String?): TouchscreenLayout? {
        if (value.isNullOrBlank()) return null
        val lines = value.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return null
        val placements = buildMap {
            lines.drop(1).forEach { line ->
                val parts = line.split(',')
                if (parts.size != 4) return@forEach
                runCatching {
                    put(
                        TouchControlId.valueOf(parts[0]),
                        TouchControlPlacement(
                            centerX = parts[1].toFloat(),
                            centerY = parts[2].toFloat(),
                            scale = parts[3].toFloat(),
                        ),
                    )
                }
            }
        }
        return TouchscreenLayout(placements).sanitized()
    }
}

const val MIN_CONTROL_SCALE = 0.65f
const val MAX_CONTROL_SCALE = 1.50f

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
