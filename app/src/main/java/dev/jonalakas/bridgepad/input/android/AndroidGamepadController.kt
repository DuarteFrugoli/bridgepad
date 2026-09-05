package dev.jonalakas.bridgepad.input.android

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.RawInputEvent
import dev.jonalakas.bridgepad.core.gamepad.SourceId
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.mapping.AxisMath
import dev.jonalakas.bridgepad.core.mapping.SourceStateRegistry
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import java.util.Locale

class AndroidGamepadController(context: Context) : InputManager.InputDeviceListener {
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val registry = SourceStateRegistry()
    private val devices = linkedMapOf<Int, PhysicalGamepadInfo>()
    private val diagnostics = linkedMapOf<String, AxisDiagnostic>()
    private val pressedDpadKeys = mutableMapOf<SourceId, MutableSet<Int>>()
    private val mappings = mutableMapOf<SourceId, GamepadMapping>()
    private var lastRawEvent = "Connect a USB gamepad and press a control."
    private var inputEventCount = 0L
    private var lastInputTimestampNanos: Long? = null
    private var listening = false

    init { GamepadMappingStore.initialize(context) }

    fun start() {
        if (listening) return
        listening = true
        inputManager.registerInputDeviceListener(this, null)
        val connectedIds = InputDevice.getDeviceIds().toSet()
        devices.keys.filterNot(connectedIds::contains).toList().forEach(::onInputDeviceRemoved)
        connectedIds.forEach(::addOrUpdateDevice)
        publish()
    }

    fun stop() {
        if (!listening) return
        listening = false
        inputManager.unregisterInputDeviceListener(this)
        registry.clear()
        pressedDpadKeys.clear()
        inputEventCount++
        lastInputTimestampNanos = SystemClock.uptimeMillis() * NANOS_PER_MILLISECOND
        lastRawEvent = "Input capture paused; all controls were neutralized."
        publish()
    }

    fun reloadMappings() {
        mappings.clear()
        publish()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (!device.isGamepad()) return false
        addOrUpdateDevice(device.id)
        val sourceId = sourceId(device)
        val pressed = event.action == KeyEvent.ACTION_DOWN
        val released = event.action == KeyEvent.ACTION_UP
        if (!pressed && !released) return false
        val timestamp = event.eventTime * NANOS_PER_MILLISECOND

        val dpadKeys = pressedDpadKeys.getOrPut(sourceId) { mutableSetOf() }
        if (event.keyCode in DPAD_KEYS) {
            if (pressed) dpadKeys += event.keyCode else dpadKeys -= event.keyCode
            registry.apply(RawInputEvent.Dpad(sourceId, dpadDirection(dpadKeys), timestamp))
        } else {
            val control = buttonForKeyCode(event.keyCode)
            if (control == null) {
                lastRawEvent = "Unmapped key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})"
                publish()
                return false
            }
            registry.apply(RawInputEvent.Button(sourceId, control, pressed, timestamp))
        }

        lastRawEvent = "Key ${KeyEvent.keyCodeToString(event.keyCode)}: " +
            if (pressed) "pressed" else "released"
        inputEventCount++
        lastInputTimestampNanos = timestamp
        publish()
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return false
        if (!device.isGamepad() || event.action != MotionEvent.ACTION_MOVE) return false
        addOrUpdateDevice(device.id)
        val sourceId = sourceId(device)
        val timestamp = event.eventTime * NANOS_PER_MILLISECOND
        val updatedAxes = mutableListOf<String>()

        processStick(device, event, sourceId, timestamp, VirtualAxis.LEFT_X, VirtualAxis.LEFT_Y,
            intArrayOf(MotionEvent.AXIS_X), intArrayOf(MotionEvent.AXIS_Y), updatedAxes)
        processStick(device, event, sourceId, timestamp, VirtualAxis.RIGHT_X, VirtualAxis.RIGHT_Y,
            intArrayOf(MotionEvent.AXIS_RX, MotionEvent.AXIS_Z),
            intArrayOf(MotionEvent.AXIS_RY, MotionEvent.AXIS_RZ), updatedAxes)
        processTrigger(device, event, sourceId, timestamp, VirtualAxis.LEFT_TRIGGER,
            intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE), updatedAxes)
        processTrigger(device, event, sourceId, timestamp, VirtualAxis.RIGHT_TRIGGER,
            intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS), updatedAxes)
        processHat(device, event, sourceId, timestamp, updatedAxes)

        lastRawEvent = if (updatedAxes.isEmpty()) {
            "Motion event with no supported axis"
        } else {
            "Motion: ${updatedAxes.joinToString()}"
        }
        inputEventCount++
        lastInputTimestampNanos = timestamp
        publish()
        return true
    }

    override fun onInputDeviceAdded(deviceId: Int) = addOrUpdateDevice(deviceId)

    override fun onInputDeviceChanged(deviceId: Int) = addOrUpdateDevice(deviceId)

    override fun onInputDeviceRemoved(deviceId: Int) {
        val removed = devices.remove(deviceId) ?: return
        registry.remove(removed.sourceId)
        mappings.remove(removed.sourceId)
        pressedDpadKeys.remove(removed.sourceId)
        diagnostics.keys.removeAll { it.startsWith("${removed.sourceId.value}:") }
        lastRawEvent = "Disconnected ${removed.name}; its controls were neutralized."
        publish()
    }

    private fun addOrUpdateDevice(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (!device.isGamepad()) return
        val sourceId = sourceId(device)
        devices[deviceId] = PhysicalGamepadInfo(
            deviceId = deviceId,
            sourceId = sourceId,
            name = device.name,
            descriptor = device.descriptor,
            vendorId = device.vendorId,
            productId = device.productId,
            axes = device.motionRanges
                .filter { it.source.isGamepadSource() }
                .map { MotionEvent.axisToString(it.axis) }
                .distinct(),
        )
        publish()
    }

    private fun processStick(
        device: InputDevice,
        event: MotionEvent,
        sourceId: SourceId,
        timestamp: Long,
        outputX: VirtualAxis,
        outputY: VirtualAxis,
        candidatesX: IntArray,
        candidatesY: IntArray,
        updatedAxes: MutableList<String>,
    ) {
        val rangeX = device.firstRange(candidatesX) ?: return
        val rangeY = device.firstRange(candidatesY) ?: return
        val rawX = event.getAxisValue(rangeX.axis)
        val rawY = event.getAxisValue(rangeY.axis)
        val normalizedX = AxisMath.normalize(rawX, rangeX.min, rangeX.max)
        val normalizedY = AxisMath.normalize(rawY, rangeY.min, rangeY.max)
        val deadzone = maxOf(rangeX.normalizedFlat(), rangeY.normalizedFlat(), FALLBACK_DEADZONE)
        val (x, y) = AxisMath.radialDeadzone(normalizedX, normalizedY, deadzone)
        registry.apply(RawInputEvent.Axis(sourceId, outputX, x, timestamp))
        registry.apply(RawInputEvent.Axis(sourceId, outputY, y, timestamp))
        recordAxis(sourceId, rangeX.axis, rawX, x, updatedAxes)
        recordAxis(sourceId, rangeY.axis, rawY, y, updatedAxes)
    }

    private fun processTrigger(
        device: InputDevice,
        event: MotionEvent,
        sourceId: SourceId,
        timestamp: Long,
        output: VirtualAxis,
        candidates: IntArray,
        updatedAxes: MutableList<String>,
    ) {
        val range = device.firstRange(candidates) ?: return
        val raw = event.getAxisValue(range.axis)
        val normalized = AxisMath.normalizeTrigger(raw, range.min, range.max)
        registry.apply(RawInputEvent.Axis(sourceId, output, normalized, timestamp))
        recordAxis(sourceId, range.axis, raw, normalized, updatedAxes)
    }

    private fun processHat(
        device: InputDevice,
        event: MotionEvent,
        sourceId: SourceId,
        timestamp: Long,
        updatedAxes: MutableList<String>,
    ) {
        val rangeX = device.firstRange(intArrayOf(MotionEvent.AXIS_HAT_X)) ?: return
        val rangeY = device.firstRange(intArrayOf(MotionEvent.AXIS_HAT_Y)) ?: return
        val rawX = event.getAxisValue(rangeX.axis)
        val rawY = event.getAxisValue(rangeY.axis)
        registry.apply(RawInputEvent.Dpad(sourceId, dpadDirection(rawX, rawY), timestamp))
        recordAxis(sourceId, rangeX.axis, rawX, rawX, updatedAxes)
        recordAxis(sourceId, rangeY.axis, rawY, rawY, updatedAxes)
    }

    private fun recordAxis(
        sourceId: SourceId,
        axis: Int,
        raw: Float,
        normalized: Float,
        updatedAxes: MutableList<String>,
    ) {
        val name = MotionEvent.axisToString(axis)
        diagnostics["${sourceId.value}:$name"] = AxisDiagnostic(raw, normalized)
        updatedAxes += "$name=${String.format(Locale.ROOT, "%.3f", normalized)}"
    }

    private fun publish() {
        val rawStates = registry.snapshots().associate { it.sourceId to it.gamepad }
        PhysicalGamepadStore.set(
            PhysicalGamepadState(
                devices = devices.values.toList(),
                rawSourceStates = rawStates,
                sourceStates = rawStates.mapValues { (sourceId, rawState) ->
                    mappings.getOrPut(sourceId) {
                        GamepadMappingStore.load("android:${sourceId.value}")
                    }.apply(rawState)
                },
                axisDiagnostics = diagnostics.toMap(),
                lastRawEvent = lastRawEvent,
                inputEventCount = inputEventCount,
                lastInputTimestampNanos = lastInputTimestampNanos,
            ),
        )
    }

    private fun InputDevice.isGamepad(): Boolean =
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun sourceId(device: InputDevice): SourceId = SourceId(
        "${device.descriptor}:${device.vendorId}:${device.productId}",
    )

    private fun InputDevice.firstRange(candidates: IntArray): InputDevice.MotionRange? {
        for (axis in candidates) {
            val range = motionRanges.firstOrNull {
                it.axis == axis && it.source.isGamepadSource()
            }
            if (range != null) return range
        }
        return null
    }

    private fun Int.isGamepadSource(): Boolean =
        this and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            this and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun InputDevice.MotionRange.normalizedFlat(): Float =
        (flat / ((max - min) / 2f)).coerceIn(0f, 0.99f)

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val FALLBACK_DEADZONE = 0.1f
        private val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
        )

        fun buttonForKeyCode(keyCode: Int): VirtualControl? = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> VirtualControl.FACE_SOUTH
            KeyEvent.KEYCODE_BUTTON_B -> VirtualControl.FACE_EAST
            KeyEvent.KEYCODE_BUTTON_X -> VirtualControl.FACE_WEST
            KeyEvent.KEYCODE_BUTTON_Y -> VirtualControl.FACE_NORTH
            KeyEvent.KEYCODE_BUTTON_L1 -> VirtualControl.LEFT_BUMPER
            KeyEvent.KEYCODE_BUTTON_R1 -> VirtualControl.RIGHT_BUMPER
            KeyEvent.KEYCODE_BUTTON_START -> VirtualControl.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> VirtualControl.SELECT
            KeyEvent.KEYCODE_BUTTON_THUMBL -> VirtualControl.LEFT_STICK_BUTTON
            KeyEvent.KEYCODE_BUTTON_THUMBR -> VirtualControl.RIGHT_STICK_BUTTON
            KeyEvent.KEYCODE_BUTTON_1 -> VirtualControl.EXTRA_1
            KeyEvent.KEYCODE_BUTTON_2 -> VirtualControl.EXTRA_2
            KeyEvent.KEYCODE_BUTTON_3 -> VirtualControl.EXTRA_3
            KeyEvent.KEYCODE_BUTTON_4 -> VirtualControl.EXTRA_4
            KeyEvent.KEYCODE_BUTTON_5 -> VirtualControl.EXTRA_5
            KeyEvent.KEYCODE_BUTTON_6 -> VirtualControl.EXTRA_6
            else -> null
        }

        fun dpadDirection(keys: Set<Int>): DpadDirection = dpadDirection(
            x = (if (KeyEvent.KEYCODE_DPAD_RIGHT in keys) 1f else 0f) -
                (if (KeyEvent.KEYCODE_DPAD_LEFT in keys) 1f else 0f),
            y = (if (KeyEvent.KEYCODE_DPAD_DOWN in keys) 1f else 0f) -
                (if (KeyEvent.KEYCODE_DPAD_UP in keys) 1f else 0f),
        )

        fun dpadDirection(x: Float, y: Float): DpadDirection {
            val horizontal = when {
                x < -0.5f -> -1
                x > 0.5f -> 1
                else -> 0
            }
            val vertical = when {
                y < -0.5f -> -1
                y > 0.5f -> 1
                else -> 0
            }
            return when (horizontal to vertical) {
                0 to -1 -> DpadDirection.NORTH
                1 to -1 -> DpadDirection.NORTH_EAST
                1 to 0 -> DpadDirection.EAST
                1 to 1 -> DpadDirection.SOUTH_EAST
                0 to 1 -> DpadDirection.SOUTH
                -1 to 1 -> DpadDirection.SOUTH_WEST
                -1 to 0 -> DpadDirection.WEST
                -1 to -1 -> DpadDirection.NORTH_WEST
                else -> DpadDirection.NEUTRAL
            }
        }
    }
}
