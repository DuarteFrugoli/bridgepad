package dev.jonalakas.bridgepad.core.gamepad

@JvmInline
value class SourceId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class VirtualControl {
    FACE_SOUTH, FACE_EAST, FACE_WEST, FACE_NORTH,
    LEFT_BUMPER, RIGHT_BUMPER, START, SELECT,
    LEFT_STICK_BUTTON, RIGHT_STICK_BUTTON,
    EXTRA_1, EXTRA_2, EXTRA_3, EXTRA_4, EXTRA_5, EXTRA_6,
}

enum class VirtualAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }

enum class DpadDirection {
    NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST, NEUTRAL,
}

data class VirtualGamepadState(
    val pressedButtons: Set<VirtualControl> = emptySet(),
    val dpad: DpadDirection = DpadDirection.NEUTRAL,
    val leftStickX: Float = 0f,
    val leftStickY: Float = 0f,
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
) {
    fun valueOf(axis: VirtualAxis): Float = when (axis) {
        VirtualAxis.LEFT_X -> leftStickX
        VirtualAxis.LEFT_Y -> leftStickY
        VirtualAxis.RIGHT_X -> rightStickX
        VirtualAxis.RIGHT_Y -> rightStickY
        VirtualAxis.LEFT_TRIGGER -> leftTrigger
        VirtualAxis.RIGHT_TRIGGER -> rightTrigger
    }
}

data class SourceGamepadState(
    val sourceId: SourceId,
    val gamepad: VirtualGamepadState = VirtualGamepadState(),
)

sealed interface RawInputEvent {
    val sourceId: SourceId
    val timestampNanos: Long

    data class Button(
        override val sourceId: SourceId,
        val control: VirtualControl,
        val pressed: Boolean,
        override val timestampNanos: Long,
    ) : RawInputEvent

    data class Axis(
        override val sourceId: SourceId,
        val axis: VirtualAxis,
        val value: Float,
        override val timestampNanos: Long,
    ) : RawInputEvent

    data class Dpad(
        override val sourceId: SourceId,
        val direction: DpadDirection,
        override val timestampNanos: Long,
    ) : RawInputEvent
}
