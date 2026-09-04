package dev.jonalakas.bridgepad.input.touch

import android.os.SystemClock
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class TouchGamepadSnapshot(
    val active: Boolean = false,
    val gamepad: VirtualGamepadState = VirtualGamepadState(),
    val inputEventCount: Long = 0,
    val lastInputTimestampNanos: Long? = null,
)

object TouchGamepadStore {
    val sourceId = SourceId("touchscreen")

    private val mutableState = MutableStateFlow(TouchGamepadSnapshot())
    private val mutableUpdates = MutableSharedFlow<TouchGamepadSnapshot>(extraBufferCapacity = 64)
    val state: StateFlow<TouchGamepadSnapshot> = mutableState.asStateFlow()
    val updates: SharedFlow<TouchGamepadSnapshot> = mutableUpdates.asSharedFlow()

    fun activate() = publish(mutableState.value.copy(active = true, gamepad = VirtualGamepadState()))

    fun deactivate() = publish(mutableState.value.copy(active = false, gamepad = VirtualGamepadState()))

    fun neutralize() = updateGamepad(VirtualGamepadState())

    fun setButton(control: VirtualControl, pressed: Boolean) {
        val current = mutableState.value.gamepad
        updateGamepad(
            current.copy(
                pressedButtons = if (pressed) {
                    current.pressedButtons + control
                } else {
                    current.pressedButtons - control
                },
            ),
        )
    }

    fun setDpad(direction: DpadDirection) {
        updateGamepad(mutableState.value.gamepad.copy(dpad = direction))
    }

    fun setStick(xAxis: VirtualAxis, yAxis: VirtualAxis, x: Float, y: Float) {
        val current = mutableState.value.gamepad
        val next = when (xAxis to yAxis) {
            VirtualAxis.LEFT_X to VirtualAxis.LEFT_Y -> current.copy(
                leftStickX = x.coerceIn(-1f, 1f),
                leftStickY = y.coerceIn(-1f, 1f),
            )
            VirtualAxis.RIGHT_X to VirtualAxis.RIGHT_Y -> current.copy(
                rightStickX = x.coerceIn(-1f, 1f),
                rightStickY = y.coerceIn(-1f, 1f),
            )
            else -> error("Stick axes must be a matching X/Y pair.")
        }
        updateGamepad(next)
    }

    fun setTrigger(axis: VirtualAxis, pressed: Boolean) {
        val value = if (pressed) 1f else 0f
        val current = mutableState.value.gamepad
        val next = when (axis) {
            VirtualAxis.LEFT_TRIGGER -> current.copy(leftTrigger = value)
            VirtualAxis.RIGHT_TRIGGER -> current.copy(rightTrigger = value)
            else -> error("Only trigger axes can be controlled as touch triggers.")
        }
        updateGamepad(next)
    }

    private fun updateGamepad(gamepad: VirtualGamepadState) {
        val current = mutableState.value
        if (!current.active || current.gamepad == gamepad) return
        publish(
            current.copy(
                gamepad = gamepad,
                inputEventCount = current.inputEventCount + 1,
                lastInputTimestampNanos = SystemClock.uptimeMillis() * 1_000_000L,
            ),
        )
    }

    private fun publish(snapshot: TouchGamepadSnapshot) {
        mutableState.value = snapshot
        mutableUpdates.tryEmit(snapshot)
    }
}
