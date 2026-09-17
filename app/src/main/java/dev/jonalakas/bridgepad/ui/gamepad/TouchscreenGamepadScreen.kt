package dev.jonalakas.bridgepad.ui.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.mapping.AxisMath
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchControlId
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchControlPlacement
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayout
import dev.jonalakas.bridgepad.ui.gamepad.layout.controlHeightDp
import dev.jonalakas.bridgepad.ui.gamepad.layout.controlOffset
import dev.jonalakas.bridgepad.ui.gamepad.layout.controlWidthDp
import dev.jonalakas.bridgepad.ui.gamepad.layout.touchControlShape
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun TouchscreenGamepadScreen(
    layout: TouchscreenLayout,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        TouchGamepadStore.activate()
        onDispose { TouchGamepadStore.deactivate() }
    }
    BackHandler(onBack = onExit)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        val widthPixels = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPixels = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        TouchControlId.entries.filter(layout::isVisible).forEach { control ->
            RuntimeLayoutControl(
                control = control,
                placement = layout.placement(control),
                containerWidthPixels = widthPixels,
                containerHeightPixels = heightPixels,
                onExit = onExit,
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.RuntimeLayoutControl(
    control: TouchControlId,
    placement: TouchControlPlacement,
    containerWidthPixels: Float,
    containerHeightPixels: Float,
    onExit: () -> Unit,
) {
    val density = LocalDensity.current
    val width = controlWidthDp(control, placement).dp
    val height = controlHeightDp(control, placement).dp
    val shape = touchControlShape(control, placement.shape ?: control.defaultShape)
    val offset = controlOffset(
        placement = placement,
        containerWidth = containerWidthPixels,
        containerHeight = containerHeightPixels,
        controlWidth = with(density) { width.toPx() },
        controlHeight = with(density) { height.toPx() },
    )
    Box(
        modifier = Modifier
            .offset { offset }
            .size(width, height),
    ) {
        when (control) {
            TouchControlId.MOUSE_TOUCHPAD -> MouseTouchpad(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
            )
            TouchControlId.DPAD -> DpadPad(
                shape = shape,
                modifier = Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_STICK -> VirtualStick(
                VirtualAxis.LEFT_X,
                VirtualAxis.LEFT_Y,
                stringResource(R.string.left_stick),
                placement.deadzone,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_STICK -> VirtualStick(
                VirtualAxis.RIGHT_X,
                VirtualAxis.RIGHT_Y,
                stringResource(R.string.right_stick),
                placement.deadzone,
                Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_TRIGGER -> TriggerButton(
                "L2",
                VirtualAxis.LEFT_TRIGGER,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_BUMPER -> GamepadButton(
                "L1",
                VirtualControl.LEFT_BUMPER,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_BUMPER -> GamepadButton(
                "R1",
                VirtualControl.RIGHT_BUMPER,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_TRIGGER -> TriggerButton(
                "R2",
                VirtualAxis.RIGHT_TRIGGER,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_NORTH -> GamepadButton(
                "Y",
                VirtualControl.FACE_NORTH,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_WEST -> GamepadButton(
                "X",
                VirtualControl.FACE_WEST,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_EAST -> GamepadButton(
                "B",
                VirtualControl.FACE_EAST,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_SOUTH -> GamepadButton(
                "A",
                VirtualControl.FACE_SOUTH,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.SELECT -> GamepadButton(
                "Select",
                VirtualControl.SELECT,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.START -> GamepadButton(
                "Start",
                VirtualControl.START,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_STICK_BUTTON -> GamepadButton(
                "L3",
                VirtualControl.LEFT_STICK_BUTTON,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_STICK_BUTTON -> GamepadButton(
                "R3",
                VirtualControl.RIGHT_STICK_BUTTON,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.GUIDE -> GamepadButton(
                stringResource(R.string.guide_button),
                VirtualControl.GUIDE,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.CAPTURE -> GamepadButton(
                stringResource(R.string.capture_button),
                VirtualControl.CAPTURE,
                shape,
                Modifier.fillMaxSize(),
            )
            TouchControlId.SESSION_MENU -> TouchButton(
                label = stringResource(R.string.session_menu),
                onPressedChange = { pressed -> if (pressed) onExit() },
                shape = shape,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun MouseTouchpadScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        MouseTouchpad(modifier = Modifier.fillMaxSize())
        Text(
            text = stringResource(R.string.mouse_touchpad_back_hint),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun MouseTouchpad(
    modifier: Modifier = Modifier,
    shape: Shape = touchControlShape(TouchControlId.MOUSE_TOUCHPAD),
) {
    val touchpadLabel = stringResource(R.string.open_mouse_touchpad)
    val touchpadHint = stringResource(R.string.mouse_touchpad_instructions)
    val container = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .semantics {
                contentDescription = touchpadLabel
                stateDescription = touchpadHint
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var distance = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                            ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        distance += hypot(delta.x, delta.y)
                        if (delta != Offset.Zero) {
                            TouchMouseStore.move(delta.x, delta.y)
                        }
                        change.consume()
                    }
                    if (distance <= 12.dp.toPx()) {
                        TouchMouseStore.click()
                    }
                }
            },
        shape = shape,
        color = container,
        border = androidx.compose.foundation.BorderStroke(2.dp, outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.mouse_surface),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GamepadButton(
    label: String,
    control: VirtualControl,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    TouchButton(
        label = label,
        shape = shape,
        modifier = modifier,
        onPressedChange = { TouchGamepadStore.setButton(control, it) },
    )
}

@Composable
private fun TriggerButton(
    label: String,
    axis: VirtualAxis,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    TouchButton(
        label = label,
        shape = shape,
        modifier = modifier,
        onPressedChange = { TouchGamepadStore.setTrigger(axis, it) },
    )
}

@Composable
private fun TouchButton(
    label: String,
    onPressedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val pressDescription = stringResource(if (pressed) R.string.control_pressed else R.string.control_released)
    val containerColor by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "button color",
    )
    val contentColor = if (pressed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = pressDescription
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        onPressedChange(true)
                        tryAwaitRelease()
                        pressed = false
                        onPressedChange(false)
                    },
                )
            },
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun VirtualStick(
    xAxis: VirtualAxis,
    yAxis: VirtualAxis,
    label: String,
    deadzone: Float,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.outline
    val base = MaterialTheme.colorScheme.surfaceVariant
    val knob = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .semantics { contentDescription = label }
            .pointerInput(xAxis, yAxis, deadzone) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    fun update(raw: Offset) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = raw - center
                        val radius = minOf(size.width, size.height) / 2f
                        val magnitude = hypot(delta.x, delta.y)
                        val scale = if (magnitude > radius) radius / magnitude else 1f
                        position = Offset(delta.x * scale / radius, delta.y * scale / radius)
                        val (outputX, outputY) = AxisMath.radialDeadzone(
                            position.x,
                            position.y,
                            deadzone,
                        )
                        TouchGamepadStore.setStick(xAxis, yAxis, outputX, outputY)
                    }
                    try {
                        update(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                                ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        position = Offset.Zero
                        TouchGamepadStore.setStick(xAxis, yAxis, 0f, 0f)
                    }
                }
            },
    ) {
        val radius = minOf(size.width, size.height) * 0.42f
        drawCircle(base, radius)
        drawCircle(outline, radius, style = Stroke(width = 3.dp.toPx()))
        drawCircle(
            color = knob,
            radius = radius * 0.42f,
            center = center + Offset(position.x * radius, position.y * radius),
        )
    }
}

@Composable
private fun DpadPad(
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val dpadLabel = stringResource(R.string.dpad_name)
    var direction by remember { mutableStateOf(DpadDirection.NEUTRAL) }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                shape = shape,
            )
            .semantics {
                contentDescription = dpadLabel
                stateDescription = direction.name
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    fun update(position: Offset) {
                        direction = directionForPosition(position, size.width.toFloat(), size.height.toFloat())
                        TouchGamepadStore.setDpad(direction)
                    }
                    try {
                        update(down.position)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                                ?: break
                            if (!change.pressed) break
                            update(change.position)
                            change.consume()
                        }
                    } finally {
                        direction = DpadDirection.NEUTRAL
                        TouchGamepadStore.setDpad(DpadDirection.NEUTRAL)
                    }
                }
            },
    ) {
        DpadCell("▲", direction in setOf(DpadDirection.NORTH, DpadDirection.NORTH_EAST, DpadDirection.NORTH_WEST), base, active, content, Modifier.align(Alignment.TopCenter))
        DpadCell("◀", direction in setOf(DpadDirection.WEST, DpadDirection.NORTH_WEST, DpadDirection.SOUTH_WEST), base, active, content, Modifier.align(Alignment.CenterStart))
        DpadCell("▶", direction in setOf(DpadDirection.EAST, DpadDirection.NORTH_EAST, DpadDirection.SOUTH_EAST), base, active, content, Modifier.align(Alignment.CenterEnd))
        DpadCell("▼", direction in setOf(DpadDirection.SOUTH, DpadDirection.SOUTH_EAST, DpadDirection.SOUTH_WEST), base, active, content, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DpadCell(
    label: String,
    selected: Boolean,
    base: Color,
    active: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize(0.36f)
            .aspectRatio(1f)
            .background(if (selected) active else base, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else content)
    }
}

internal fun directionForPosition(position: Offset, width: Float, height: Float): DpadDirection {
    if (width <= 0f || height <= 0f) return DpadDirection.NEUTRAL
    val x = (position.x - width / 2f) / (width / 2f)
    val y = (position.y - height / 2f) / (height / 2f)
    if (hypot(x, y) < 0.2f) return DpadDirection.NEUTRAL
    val degrees = (atan2(y, x) * 180f / PI.toFloat() + 360f) % 360f
    return when {
        degrees < 22.5f || degrees >= 337.5f -> DpadDirection.EAST
        degrees < 67.5f -> DpadDirection.SOUTH_EAST
        degrees < 112.5f -> DpadDirection.SOUTH
        degrees < 157.5f -> DpadDirection.SOUTH_WEST
        degrees < 202.5f -> DpadDirection.WEST
        degrees < 247.5f -> DpadDirection.NORTH_WEST
        degrees < 292.5f -> DpadDirection.NORTH
        else -> DpadDirection.NORTH_EAST
    }
}
