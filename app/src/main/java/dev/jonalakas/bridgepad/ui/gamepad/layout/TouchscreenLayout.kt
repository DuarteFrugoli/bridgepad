package dev.jonalakas.bridgepad.ui.gamepad.layout

enum class TouchControlId(
    val baseWidthDp: Float,
    val baseHeightDp: Float,
    val lockAspectRatio: Boolean = false,
) {
    MOUSE_TOUCHPAD(210f, 92f),
    DPAD(132f, 132f),
    LEFT_STICK(132f, 132f, lockAspectRatio = true),
    RIGHT_STICK(132f, 132f, lockAspectRatio = true),
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

enum class TouchscreenLayoutPreset {
    SYMMETRIC,
    ASYMMETRIC,
    MOBILE,
}

data class TouchControlPlacement(
    val centerX: Float,
    val centerY: Float,
    val widthScale: Float = 1f,
    val heightScale: Float = widthScale,
) {
    fun sanitized(
        control: TouchControlId,
        fallback: TouchControlPlacement,
    ): TouchControlPlacement {
        val safeWidthScale = widthScale.finiteOr(fallback.widthScale).coerceAtLeast(MIN_CONTROL_SCALE)
        val safeHeightScale = if (control.lockAspectRatio) {
            safeWidthScale
        } else {
            heightScale.finiteOr(fallback.heightScale).coerceAtLeast(MIN_CONTROL_SCALE)
        }
        return TouchControlPlacement(
            centerX = centerX.finiteOr(fallback.centerX).coerceIn(0f, 1f),
            centerY = centerY.finiteOr(fallback.centerY).coerceIn(0f, 1f),
            widthScale = safeWidthScale,
            heightScale = safeHeightScale,
        )
    }
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

    fun resize(
        control: TouchControlId,
        widthScale: Float,
        heightScale: Float = widthScale,
    ): TouchscreenLayout = update(control) {
        val safeWidthScale = widthScale.coerceAtLeast(MIN_CONTROL_SCALE)
        it.copy(
            widthScale = safeWidthScale,
            heightScale = if (control.lockAspectRatio) {
                safeWidthScale
            } else {
                heightScale.coerceAtLeast(MIN_CONTROL_SCALE)
            },
        )
    }

    fun sanitized(): TouchscreenLayout = TouchscreenLayout(
        TouchControlId.entries.associateWith { control ->
            placement(control).sanitized(
                control,
                DefaultTouchscreenLayout.value.placements.getValue(control),
            )
        },
    )

    private fun update(
        control: TouchControlId,
        transform: (TouchControlPlacement) -> TouchControlPlacement,
    ): TouchscreenLayout = copy(placements = placements + (control to transform(placement(control))))
}

object BuiltInTouchscreenLayouts {
    val symmetric = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.24f, 0.88f),
            TouchControlId.DPAD to TouchControlPlacement(0.15f, 0.43f, 0.95f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.36f, 0.79f, 0.92f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.64f, 0.79f, 0.92f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.07f, 0.08f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.18f, 0.08f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.82f, 0.08f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.93f, 0.08f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.85f, 0.28f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.78f, 0.43f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.92f, 0.43f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.85f, 0.58f),
            TouchControlId.SELECT to TouchControlPlacement(0.44f, 0.43f, 0.82f),
            TouchControlId.START to TouchControlPlacement(0.56f, 0.43f, 0.82f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.44f, 0.60f, 0.75f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.56f, 0.60f, 0.75f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.87f, 0.70f),
        ),
    )

    val asymmetric = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.23f, 0.82f),
            TouchControlId.DPAD to TouchControlPlacement(0.24f, 0.76f, 0.92f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.15f, 0.39f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.76f, 0.76f, 0.92f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.07f, 0.08f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.18f, 0.08f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.82f, 0.08f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.93f, 0.08f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.85f, 0.24f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.78f, 0.39f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.92f, 0.39f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.85f, 0.54f),
            TouchControlId.SELECT to TouchControlPlacement(0.44f, 0.46f, 0.82f),
            TouchControlId.START to TouchControlPlacement(0.56f, 0.46f, 0.82f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.44f, 0.62f, 0.75f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.56f, 0.62f, 0.75f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.84f, 0.70f),
        ),
    )

    val mobile = TouchscreenLayout(
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

    fun layout(preset: TouchscreenLayoutPreset): TouchscreenLayout = when (preset) {
        TouchscreenLayoutPreset.SYMMETRIC -> symmetric
        TouchscreenLayoutPreset.ASYMMETRIC -> asymmetric
        TouchscreenLayoutPreset.MOBILE -> mobile
    }
}

object DefaultTouchscreenLayout {
    val value = BuiltInTouchscreenLayouts.mobile
}

internal object TouchscreenLayoutCodec {
    private const val VERSION = "2"

    fun encode(layout: TouchscreenLayout): String = buildString {
        appendLine(VERSION)
        layout.sanitized().placements.forEach { (control, placement) ->
            appendLine(
                "${control.name},${placement.centerX},${placement.centerY}," +
                    "${placement.widthScale},${placement.heightScale}",
            )
        }
    }

    fun decode(value: String?): TouchscreenLayout? {
        if (value.isNullOrBlank()) return null
        val lines = value.lineSequence().toList()
        val version = lines.firstOrNull()
        if (version != "1" && version != VERSION) return null
        val placements = buildMap {
            lines.drop(1).forEach { line ->
                val parts = line.split(',')
                if (parts.size != if (version == "1") 4 else 5) return@forEach
                runCatching {
                    val widthScale = parts[3].toFloat()
                    put(
                        TouchControlId.valueOf(parts[0]),
                        TouchControlPlacement(
                            centerX = parts[1].toFloat(),
                            centerY = parts[2].toFloat(),
                            widthScale = widthScale,
                            heightScale = if (version == "1") widthScale else parts[4].toFloat(),
                        ),
                    )
                }
            }
        }
        return TouchscreenLayout(placements).sanitized()
    }
}

const val MIN_CONTROL_SCALE = 0.65f

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
