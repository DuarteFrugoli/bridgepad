package dev.jonalakas.bridgepad.ui.gamepad.layout

enum class TouchControlShape {
    CIRCLE,
    ROUNDED_RECTANGLE,
}

enum class TouchControlInteraction {
    HOLD,
    TOGGLE,
}

enum class TouchControlId(
    val baseWidthDp: Float,
    val baseHeightDp: Float,
    val lockAspectRatio: Boolean = false,
    val adjustableDeadzone: Boolean = false,
    val defaultShape: TouchControlShape = TouchControlShape.ROUNDED_RECTANGLE,
    val customizableShape: Boolean = true,
    val supportsToggle: Boolean = false,
) {
    MOUSE_TOUCHPAD(210f, 92f, customizableShape = false),
    DPAD(132f, 132f, lockAspectRatio = true, customizableShape = false),
    LEFT_STICK(132f, 132f, lockAspectRatio = true, adjustableDeadzone = true, defaultShape = TouchControlShape.CIRCLE, customizableShape = false),
    RIGHT_STICK(132f, 132f, lockAspectRatio = true, adjustableDeadzone = true, defaultShape = TouchControlShape.CIRCLE, customizableShape = false),
    LEFT_TRIGGER(76f, 52f, supportsToggle = true),
    LEFT_BUMPER(76f, 52f, supportsToggle = true),
    RIGHT_BUMPER(76f, 52f, supportsToggle = true),
    RIGHT_TRIGGER(76f, 52f, supportsToggle = true),
    FACE_NORTH(58f, 58f, defaultShape = TouchControlShape.CIRCLE, supportsToggle = true),
    FACE_WEST(58f, 58f, defaultShape = TouchControlShape.CIRCLE, supportsToggle = true),
    FACE_EAST(58f, 58f, defaultShape = TouchControlShape.CIRCLE, supportsToggle = true),
    FACE_SOUTH(58f, 58f, defaultShape = TouchControlShape.CIRCLE, supportsToggle = true),
    SELECT(76f, 48f, supportsToggle = true),
    START(76f, 48f, supportsToggle = true),
    LEFT_STICK_BUTTON(64f, 48f, supportsToggle = true),
    RIGHT_STICK_BUTTON(64f, 48f, supportsToggle = true),
    GUIDE(64f, 48f, supportsToggle = true),
    CAPTURE(64f, 48f, supportsToggle = true),
    KEYBOARD(64f, 48f),
    SESSION_MENU(116f, 48f),
}

enum class TouchscreenLayoutPreset {
    SYMMETRIC,
    ASYMMETRIC,
    MOBILE,
}

enum class TouchscreenLayoutOrientation {
    LANDSCAPE,
    PORTRAIT,
}

data class TouchControlPlacement(
    val centerX: Float,
    val centerY: Float,
    val widthScale: Float = 1f,
    val heightScale: Float = widthScale,
    val deadzone: Float = DEFAULT_STICK_DEADZONE,
    val visible: Boolean = true,
    val shape: TouchControlShape? = null,
    val interaction: TouchControlInteraction = TouchControlInteraction.HOLD,
) {
    fun sanitized(
        control: TouchControlId,
        fallback: TouchControlPlacement,
    ): TouchControlPlacement {
        val resolvedShape = shape ?: control.defaultShape
        val safeWidthScale = widthScale.finiteOr(fallback.widthScale).coerceAtLeast(MIN_CONTROL_SCALE)
        val safeHeightScale = if (control.lockAspectRatio || resolvedShape == TouchControlShape.CIRCLE) {
            safeWidthScale
        } else {
            heightScale.finiteOr(fallback.heightScale).coerceAtLeast(MIN_CONTROL_SCALE)
        }
        return TouchControlPlacement(
            centerX = centerX.finiteOr(fallback.centerX).coerceIn(0f, 1f),
            centerY = centerY.finiteOr(fallback.centerY).coerceIn(0f, 1f),
            widthScale = safeWidthScale,
            heightScale = safeHeightScale,
            deadzone = if (control.adjustableDeadzone) {
                deadzone.finiteOr(fallback.deadzone).coerceIn(MIN_STICK_DEADZONE, MAX_STICK_DEADZONE)
            } else {
                DEFAULT_STICK_DEADZONE
            },
            visible = visible,
            shape = resolvedShape,
            interaction = if (control.supportsToggle) interaction else TouchControlInteraction.HOLD,
        )
    }
}

data class TouchscreenLayout(
    val placements: Map<TouchControlId, TouchControlPlacement>,
) {
    fun placement(control: TouchControlId): TouchControlPlacement =
        placements[control] ?: DefaultTouchscreenLayout.value.placements.getValue(control)

    fun isVisible(control: TouchControlId): Boolean = placement(control).visible

    fun shape(control: TouchControlId): TouchControlShape =
        placement(control).shape ?: control.defaultShape

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
        val lockAspectRatio = control.lockAspectRatio ||
            (it.shape ?: control.defaultShape) == TouchControlShape.CIRCLE
        it.copy(
            widthScale = safeWidthScale,
            heightScale = if (lockAspectRatio) {
                safeWidthScale
            } else {
                heightScale.coerceAtLeast(MIN_CONTROL_SCALE)
            },
        )
    }

    fun setDeadzone(control: TouchControlId, deadzone: Float): TouchscreenLayout {
        require(control.adjustableDeadzone) { "Only analog sticks have an adjustable deadzone." }
        return update(control) {
            it.copy(deadzone = deadzone.coerceIn(MIN_STICK_DEADZONE, MAX_STICK_DEADZONE))
        }
    }

    fun setVisible(control: TouchControlId, visible: Boolean): TouchscreenLayout =
        update(control) { it.copy(visible = visible) }

    fun setShape(control: TouchControlId, shape: TouchControlShape): TouchscreenLayout =
        update(control) { placement ->
            if (shape == TouchControlShape.CIRCLE) {
                val baseDiameter = maxOf(control.baseWidthDp, control.baseHeightDp)
                val diameterScale = maxOf(
                    control.baseWidthDp * placement.widthScale,
                    control.baseHeightDp * placement.heightScale,
                ) / baseDiameter
                placement.copy(
                    widthScale = diameterScale,
                    heightScale = diameterScale,
                    shape = shape,
                )
            } else {
                placement.copy(shape = shape)
            }
        }

    fun setInteraction(
        control: TouchControlId,
        interaction: TouchControlInteraction,
    ): TouchscreenLayout {
        require(control.supportsToggle) { "This control does not support toggle interaction." }
        return update(control) { it.copy(interaction = interaction) }
    }

    fun sanitized(
        fallbackLayout: TouchscreenLayout = DefaultTouchscreenLayout.value,
    ): TouchscreenLayout = TouchscreenLayout(
        TouchControlId.entries.associateWith { control ->
            val fallback = fallbackLayout.placements.getValue(control)
            (placements[control] ?: fallback).sanitized(control, fallback)
        },
    )

    private fun update(
        control: TouchControlId,
        transform: (TouchControlPlacement) -> TouchControlPlacement,
    ): TouchscreenLayout = copy(placements = placements + (control to transform(placement(control))))
}

data class TouchscreenLayoutProfile(
    val landscape: TouchscreenLayout,
    val portrait: TouchscreenLayout,
) {
    fun layout(orientation: TouchscreenLayoutOrientation): TouchscreenLayout = when (orientation) {
        TouchscreenLayoutOrientation.LANDSCAPE -> landscape
        TouchscreenLayoutOrientation.PORTRAIT -> portrait
    }

    fun update(
        orientation: TouchscreenLayoutOrientation,
        layout: TouchscreenLayout,
    ): TouchscreenLayoutProfile = when (orientation) {
        TouchscreenLayoutOrientation.LANDSCAPE -> copy(landscape = layout)
        TouchscreenLayoutOrientation.PORTRAIT -> copy(portrait = layout)
    }

    fun sanitized(): TouchscreenLayoutProfile = TouchscreenLayoutProfile(
        landscape = landscape.sanitized(BuiltInTouchscreenLayouts.mobile),
        portrait = portrait.sanitized(BuiltInTouchscreenLayouts.mobilePortrait),
    )
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
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.70f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.53f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.08f, 0.65f),
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
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.72f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.54f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.08f, 0.65f),
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
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.81f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.64f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.08f, 0.65f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.90f),
        ),
    ).withMobileShapes()

    val symmetricPortrait = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.18f, 0.88f),
            TouchControlId.DPAD to TouchControlPlacement(0.24f, 0.43f, 0.72f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.25f, 0.69f, 0.68f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.75f, 0.69f, 0.68f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.13f, 0.06f, 0.72f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.37f, 0.06f, 0.72f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.63f, 0.06f, 0.72f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.87f, 0.06f, 0.72f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.76f, 0.34f, 0.82f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.64f, 0.43f, 0.82f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.88f, 0.43f, 0.82f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.76f, 0.52f, 0.82f),
            TouchControlId.SELECT to TouchControlPlacement(0.37f, 0.56f, 0.72f),
            TouchControlId.START to TouchControlPlacement(0.63f, 0.56f, 0.72f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.34f, 0.82f, 0.70f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.66f, 0.82f, 0.70f),
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.82f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.68f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.06f, 0.65f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.92f, 0.72f),
        ),
    )

    val asymmetricPortrait = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.18f, 0.88f),
            TouchControlId.DPAD to TouchControlPlacement(0.24f, 0.68f, 0.72f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.24f, 0.42f, 0.68f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.76f, 0.68f, 0.68f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.13f, 0.06f, 0.72f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.37f, 0.06f, 0.72f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.63f, 0.06f, 0.72f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.87f, 0.06f, 0.72f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.76f, 0.33f, 0.82f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.64f, 0.42f, 0.82f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.88f, 0.42f, 0.82f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.76f, 0.51f, 0.82f),
            TouchControlId.SELECT to TouchControlPlacement(0.37f, 0.56f, 0.72f),
            TouchControlId.START to TouchControlPlacement(0.63f, 0.56f, 0.72f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.34f, 0.82f, 0.70f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.66f, 0.82f, 0.70f),
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.82f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.68f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.06f, 0.65f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.92f, 0.72f),
        ),
    )

    val mobilePortrait = TouchscreenLayout(
        mapOf(
            TouchControlId.MOUSE_TOUCHPAD to TouchControlPlacement(0.50f, 0.18f, 0.92f, 1.10f),
            TouchControlId.DPAD to TouchControlPlacement(0.23f, 0.46f, 0.70f),
            TouchControlId.LEFT_STICK to TouchControlPlacement(0.23f, 0.72f, 0.68f),
            TouchControlId.RIGHT_STICK to TouchControlPlacement(0.77f, 0.72f, 0.68f),
            TouchControlId.LEFT_TRIGGER to TouchControlPlacement(0.13f, 0.06f, 0.72f),
            TouchControlId.LEFT_BUMPER to TouchControlPlacement(0.37f, 0.06f, 0.72f),
            TouchControlId.RIGHT_BUMPER to TouchControlPlacement(0.63f, 0.06f, 0.72f),
            TouchControlId.RIGHT_TRIGGER to TouchControlPlacement(0.87f, 0.06f, 0.72f),
            TouchControlId.FACE_NORTH to TouchControlPlacement(0.77f, 0.37f, 0.82f),
            TouchControlId.FACE_WEST to TouchControlPlacement(0.65f, 0.46f, 0.82f),
            TouchControlId.FACE_EAST to TouchControlPlacement(0.89f, 0.46f, 0.82f),
            TouchControlId.FACE_SOUTH to TouchControlPlacement(0.77f, 0.55f, 0.82f),
            TouchControlId.SELECT to TouchControlPlacement(0.37f, 0.59f, 0.72f),
            TouchControlId.START to TouchControlPlacement(0.63f, 0.59f, 0.72f),
            TouchControlId.LEFT_STICK_BUTTON to TouchControlPlacement(0.34f, 0.84f, 0.70f),
            TouchControlId.RIGHT_STICK_BUTTON to TouchControlPlacement(0.66f, 0.84f, 0.70f),
            TouchControlId.GUIDE to TouchControlPlacement(0.50f, 0.83f, 0.65f),
            TouchControlId.CAPTURE to TouchControlPlacement(0.50f, 0.70f, 0.65f),
            TouchControlId.KEYBOARD to TouchControlPlacement(0.50f, 0.06f, 0.65f),
            TouchControlId.SESSION_MENU to TouchControlPlacement(0.50f, 0.93f, 0.72f),
        ),
    ).withMobileShapes()

    fun profile(preset: TouchscreenLayoutPreset): TouchscreenLayoutProfile = when (preset) {
        TouchscreenLayoutPreset.SYMMETRIC -> TouchscreenLayoutProfile(symmetric, symmetricPortrait)
        TouchscreenLayoutPreset.ASYMMETRIC -> TouchscreenLayoutProfile(asymmetric, asymmetricPortrait)
        TouchscreenLayoutPreset.MOBILE -> TouchscreenLayoutProfile(mobile, mobilePortrait)
    }
}

private fun TouchscreenLayout.withMobileShapes(): TouchscreenLayout = copy(
    placements = placements.mapValues { (control, placement) ->
        placement.copy(
            shape = when (control) {
                TouchControlId.MOUSE_TOUCHPAD,
                TouchControlId.DPAD,
                TouchControlId.SESSION_MENU -> TouchControlShape.ROUNDED_RECTANGLE
                else -> TouchControlShape.CIRCLE
            },
        )
    },
)

object DefaultTouchscreenLayout {
    val value = BuiltInTouchscreenLayouts.mobile
}

object DefaultTouchscreenLayoutProfile {
    val value = BuiltInTouchscreenLayouts.profile(TouchscreenLayoutPreset.MOBILE)
}

internal object TouchscreenLayoutProfileCodec {
    private const val VERSION = "5"

    fun encode(profile: TouchscreenLayoutProfile): String = buildString {
        appendLine(VERSION)
        val sanitized = profile.sanitized()
        TouchscreenLayoutOrientation.entries.forEach { orientation ->
            sanitized.layout(orientation).placements.forEach { (control, placement) ->
                appendLine(
                    "${orientation.name},${control.name},${placement.centerX},${placement.centerY}," +
                        "${placement.widthScale},${placement.heightScale},${placement.deadzone}," +
                        "${placement.visible},${(placement.shape ?: control.defaultShape).name}," +
                        placement.interaction.name,
                )
            }
        }
    }

    fun decode(value: String?): TouchscreenLayoutProfile? {
        if (value.isNullOrBlank()) return null
        val lines = value.lineSequence().toList()
        if (lines.firstOrNull() != VERSION) return null
        val placements = TouchscreenLayoutOrientation.entries.associateWith { linkedMapOf<TouchControlId, TouchControlPlacement>() }
        lines.drop(1).forEach { line ->
            val parts = line.split(',')
            if (parts.size != 10) return@forEach
            runCatching {
                placements.getValue(TouchscreenLayoutOrientation.valueOf(parts[0]))[
                    TouchControlId.valueOf(parts[1])
                ] = TouchControlPlacement(
                    centerX = parts[2].toFloat(),
                    centerY = parts[3].toFloat(),
                    widthScale = parts[4].toFloat(),
                    heightScale = parts[5].toFloat(),
                    deadzone = parts[6].toFloat(),
                    visible = parts[7].toBooleanStrict(),
                    shape = TouchControlShape.valueOf(parts[8]),
                    interaction = TouchControlInteraction.valueOf(parts[9]),
                )
            }
        }
        return TouchscreenLayoutProfile(
            landscape = TouchscreenLayout(placements.getValue(TouchscreenLayoutOrientation.LANDSCAPE)),
            portrait = TouchscreenLayout(placements.getValue(TouchscreenLayoutOrientation.PORTRAIT)),
        ).sanitized()
    }
}

const val MIN_CONTROL_SCALE = 0.65f
const val DEFAULT_STICK_DEADZONE = 0.05f
const val MIN_STICK_DEADZONE = 0f
const val MAX_STICK_DEADZONE = 0.4f
const val STICK_DEADZONE_STEP = 0.01f

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
