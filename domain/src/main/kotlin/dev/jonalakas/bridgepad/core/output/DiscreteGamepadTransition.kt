package dev.jonalakas.bridgepad.core.output

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState

/**
 * Returns true when coalescing two snapshots could erase a user-visible
 * press/release edge. Stick motion remains continuous and may be coalesced.
 */
fun hasDiscreteGamepadTransition(
    previous: VirtualGamepadState,
    current: VirtualGamepadState,
): Boolean = previous.pressedButtons != current.pressedButtons ||
    previous.dpad != current.dpad ||
    previous.leftTrigger.isPressed() != current.leftTrigger.isPressed() ||
    previous.rightTrigger.isPressed() != current.rightTrigger.isPressed()

private fun Float.isPressed(): Boolean = this > TRIGGER_PRESSED_THRESHOLD

private const val TRIGGER_PRESSED_THRESHOLD = 0.01f
