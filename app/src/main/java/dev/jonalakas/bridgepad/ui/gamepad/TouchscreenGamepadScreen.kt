package dev.jonalakas.bridgepad.ui.gamepad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.mapping.AxisMath
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.KeyboardKey
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchKeyboardStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchControlId
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchControlInteraction
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
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        TouchGamepadStore.activate()
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                TouchGamepadStore.deactivate()
            }
        }
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
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_BUMPER -> GamepadButton(
                "L1",
                VirtualControl.LEFT_BUMPER,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_BUMPER -> GamepadButton(
                "R1",
                VirtualControl.RIGHT_BUMPER,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_TRIGGER -> TriggerButton(
                "R2",
                VirtualAxis.RIGHT_TRIGGER,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_NORTH -> GamepadButton(
                "Y",
                VirtualControl.FACE_NORTH,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_WEST -> GamepadButton(
                "X",
                VirtualControl.FACE_WEST,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_EAST -> GamepadButton(
                "B",
                VirtualControl.FACE_EAST,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.FACE_SOUTH -> GamepadButton(
                "A",
                VirtualControl.FACE_SOUTH,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.SELECT -> GamepadButton(
                "Select",
                VirtualControl.SELECT,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.START -> GamepadButton(
                "Start",
                VirtualControl.START,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.LEFT_STICK_BUTTON -> GamepadButton(
                "L3",
                VirtualControl.LEFT_STICK_BUTTON,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.RIGHT_STICK_BUTTON -> GamepadButton(
                "R3",
                VirtualControl.RIGHT_STICK_BUTTON,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.GUIDE -> GamepadButton(
                stringResource(R.string.guide_button),
                VirtualControl.GUIDE,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.CAPTURE -> GamepadButton(
                stringResource(R.string.capture_button),
                VirtualControl.CAPTURE,
                shape,
                placement.interaction,
                Modifier.fillMaxSize(),
            )
            TouchControlId.KEYBOARD -> AndroidKeyboardButton(
                shape = shape,
                modifier = Modifier.fillMaxSize(),
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
private fun AndroidKeyboardButton(
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf(TextFieldValue()) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val description = stringResource(R.string.keyboard_button)

    Box(modifier = modifier.semantics { contentDescription = description }) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                submitKeyboardDifference(value.text, next.text)
                value = next
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
        TouchButton(
            label = KEYBOARD_SYMBOL,
            onPressedChange = { pressed ->
                if (pressed) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            },
            shape = shape,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun submitKeyboardDifference(previous: String, next: String) {
    if (previous == next) return
    val commonPrefix = previous.indices
        .takeWhile { index -> index < next.length && previous[index] == next[index] }
        .count()
    repeat(previous.length - commonPrefix) {
        TouchKeyboardStore.submit(KeyboardInput.Key(KeyboardKey.BACKSPACE))
    }
    val text = StringBuilder()
    fun flushText() {
        if (text.isNotEmpty()) {
            TouchKeyboardStore.submit(KeyboardInput.Text(text.toString()))
            text.clear()
        }
    }
    next.substring(commonPrefix).forEach { character ->
        val key = when (character) {
            '\n', '\r' -> KeyboardKey.ENTER
            '\t' -> KeyboardKey.TAB
            else -> null
        }
        if (key == null) {
            text.append(character)
        } else {
            flushText()
            TouchKeyboardStore.submit(KeyboardInput.Key(key))
        }
    }
    flushText()
}

@Composable
fun MouseTouchpadScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onExit)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            AndroidKeyboardButton(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(44.dp),
            )
            TouchButton(
                label = stringResource(R.string.right_click_short),
                onPressedChange = { pressed -> if (pressed) TouchMouseStore.rightClick() },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(44.dp),
                accessibilityLabel = stringResource(R.string.right_click),
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .weight(1f)
                .fillMaxWidth(),
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
                val motionThreshold = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var maximumPointerCount = 1
                    var activePointerMode = 1
                    var bufferedDelta = Offset.Zero
                    var motionAccepted = false
                    var producedMotion = false
                    while (true) {
                        val changes = awaitPointerEvent().changes
                        val pressedChanges = changes.filter { it.pressed }
                        if (pressedChanges.isEmpty()) break
                        val pointerMode = if (pressedChanges.size >= 2) 2 else 1
                        maximumPointerCount = maxOf(maximumPointerCount, pointerMode)
                        if (pointerMode != activePointerMode) {
                            activePointerMode = pointerMode
                            bufferedDelta = Offset.Zero
                            motionAccepted = false
                        }
                        val delta = pressedChanges.fold(Offset.Zero) { total, change ->
                            total + change.positionChange()
                        } / pressedChanges.size.toFloat()
                        if (delta != Offset.Zero) {
                            if (motionAccepted) {
                                if (activePointerMode >= 2) {
                                    TouchMouseStore.scroll(delta.y)
                                } else {
                                    TouchMouseStore.move(delta.x, delta.y)
                                }
                            } else {
                                bufferedDelta += delta
                                if (hypot(bufferedDelta.x, bufferedDelta.y) >= motionThreshold) {
                                    motionAccepted = true
                                    producedMotion = true
                                    if (activePointerMode >= 2) {
                                        TouchMouseStore.scroll(bufferedDelta.y)
                                    } else {
                                        TouchMouseStore.move(bufferedDelta.x, bufferedDelta.y)
                                    }
                                    bufferedDelta = Offset.Zero
                                }
                            }
                        }
                        changes.forEach { it.consume() }
                    }
                    if (!producedMotion) {
                        if (maximumPointerCount >= 2) {
                            TouchMouseStore.rightClick()
                        } else {
                            TouchMouseStore.click()
                        }
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
    interaction: TouchControlInteraction,
    modifier: Modifier = Modifier,
) {
    val snapshot by TouchGamepadStore.state.collectAsState()
    val activeInGamepadState = control in snapshot.gamepad.pressedButtons
    TouchButton(
        label = label,
        shape = shape,
        modifier = modifier,
        latched = activeInGamepadState,
        onPressedChange = { pressed ->
            when (interaction) {
                TouchControlInteraction.HOLD -> TouchGamepadStore.setButton(control, pressed)
                TouchControlInteraction.TOGGLE -> if (pressed) TouchGamepadStore.toggleButton(control)
            }
        },
    )
}

@Composable
private fun TriggerButton(
    label: String,
    axis: VirtualAxis,
    shape: Shape,
    interaction: TouchControlInteraction,
    modifier: Modifier = Modifier,
) {
    val snapshot by TouchGamepadStore.state.collectAsState()
    val triggerValue = when (axis) {
        VirtualAxis.LEFT_TRIGGER -> snapshot.gamepad.leftTrigger
        VirtualAxis.RIGHT_TRIGGER -> snapshot.gamepad.rightTrigger
        else -> 0f
    }
    TouchButton(
        label = label,
        shape = shape,
        modifier = modifier,
        latched = triggerValue > 0f,
        onPressedChange = { pressed ->
            when (interaction) {
                TouchControlInteraction.HOLD -> TouchGamepadStore.setTrigger(axis, pressed)
                TouchControlInteraction.TOGGLE -> if (pressed) TouchGamepadStore.toggleTrigger(axis)
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun TouchButton(
    label: String,
    onPressedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = label,
    latched: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    val active = pressed || latched
    val pressDescription = stringResource(if (active) R.string.control_pressed else R.string.control_released)
    val containerColor by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "button color",
    )
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = accessibilityLabel
                role = Role.Button
                stateDescription = pressDescription
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnPressedChange(true)
                        tryAwaitRelease()
                        pressed = false
                        currentOnPressedChange(false)
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
    modifier: Modifier = Modifier,
) {
    val dpadLabel = stringResource(R.string.dpad_name)
    var direction by remember { mutableStateOf(DpadDirection.NEUTRAL) }

    Box(
        modifier = modifier
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
        DpadVisual(direction = direction, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun DpadVisual(
    direction: DpadDirection = DpadDirection.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val horizontalStart = size.width * (DPAD_CENTER_FRACTION - DPAD_CONNECTOR_HALF_BASE)
            val horizontalEnd = size.width * (DPAD_CENTER_FRACTION + DPAD_CONNECTOR_HALF_BASE)
            val verticalStart = size.height * (DPAD_CENTER_FRACTION - DPAD_CONNECTOR_HALF_BASE)
            val verticalEnd = size.height * (DPAD_CENTER_FRACTION + DPAD_CONNECTOR_HALF_BASE)
            val innerX = size.width * DPAD_INNER_EDGE
            val innerY = size.height * DPAD_INNER_EDGE
            val farInnerX = size.width * (1f - DPAD_INNER_EDGE)
            val farInnerY = size.height * (1f - DPAD_INNER_EDGE)

            fun connector(
                first: Offset,
                second: Offset,
                selected: Boolean,
            ) {
                drawPath(
                    path = Path().apply {
                        moveTo(first.x, first.y)
                        lineTo(second.x, second.y)
                        lineTo(center.x, center.y)
                        close()
                    },
                    color = if (selected) active else base,
                )
            }

            connector(
                Offset(horizontalStart, innerY),
                Offset(horizontalEnd, innerY),
                direction.hasNorth,
            )
            connector(
                Offset(farInnerX, verticalStart),
                Offset(farInnerX, verticalEnd),
                direction.hasEast,
            )
            connector(
                Offset(horizontalStart, farInnerY),
                Offset(horizontalEnd, farInnerY),
                direction.hasSouth,
            )
            connector(
                Offset(innerX, verticalStart),
                Offset(innerX, verticalEnd),
                direction.hasWest,
            )
        }
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
            .fillMaxSize(DPAD_CELL_FRACTION)
            .aspectRatio(1f)
            .background(if (selected) active else base, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else content)
    }
}

internal fun directionForPosition(position: Offset, width: Float, height: Float): DpadDirection {
    if (width <= 0f || height <= 0f) return DpadDirection.NEUTRAL
    val horizontal = position.x / width
    val vertical = position.y / height
    val x = (position.x - width / 2f) / (width / 2f)
    val y = (position.y - height / 2f) / (height / 2f)
    if (hypot(x, y) < DPAD_CENTER_DEAD_ZONE) return DpadDirection.NEUTRAL
    directionForDpadCell(horizontal, vertical)?.let { return it }
    directionForDpadConnector(horizontal, vertical)?.let { return it }
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

private fun directionForDpadCell(x: Float, y: Float): DpadDirection? {
    val start = (1f - DPAD_CELL_FRACTION) / 2f
    val end = 1f - start
    return when {
        x in start..end && y <= DPAD_INNER_EDGE -> DpadDirection.NORTH
        x >= 1f - DPAD_INNER_EDGE && y in start..end -> DpadDirection.EAST
        x in start..end && y >= 1f - DPAD_INNER_EDGE -> DpadDirection.SOUTH
        x <= DPAD_INNER_EDGE && y in start..end -> DpadDirection.WEST
        else -> null
    }
}

private fun directionForDpadConnector(x: Float, y: Float): DpadDirection? {
    val horizontalDistance = kotlin.math.abs(x - DPAD_CENTER_FRACTION)
    val verticalDistance = kotlin.math.abs(y - DPAD_CENTER_FRACTION)
    val connectorSlope = DPAD_CONNECTOR_HALF_BASE / (DPAD_CENTER_FRACTION - DPAD_INNER_EDGE)
    return when {
        y in DPAD_INNER_EDGE..DPAD_CENTER_FRACTION &&
            horizontalDistance <= (DPAD_CENTER_FRACTION - y) * connectorSlope -> DpadDirection.NORTH
        x in DPAD_CENTER_FRACTION..(1f - DPAD_INNER_EDGE) &&
            verticalDistance <= (x - DPAD_CENTER_FRACTION) * connectorSlope -> DpadDirection.EAST
        y in DPAD_CENTER_FRACTION..(1f - DPAD_INNER_EDGE) &&
            horizontalDistance <= (y - DPAD_CENTER_FRACTION) * connectorSlope -> DpadDirection.SOUTH
        x in DPAD_INNER_EDGE..DPAD_CENTER_FRACTION &&
            verticalDistance <= (DPAD_CENTER_FRACTION - x) * connectorSlope -> DpadDirection.WEST
        else -> null
    }
}

private val DpadDirection.hasNorth: Boolean
    get() = this in setOf(DpadDirection.NORTH, DpadDirection.NORTH_EAST, DpadDirection.NORTH_WEST)

private val DpadDirection.hasEast: Boolean
    get() = this in setOf(DpadDirection.EAST, DpadDirection.NORTH_EAST, DpadDirection.SOUTH_EAST)

private val DpadDirection.hasSouth: Boolean
    get() = this in setOf(DpadDirection.SOUTH, DpadDirection.SOUTH_EAST, DpadDirection.SOUTH_WEST)

private val DpadDirection.hasWest: Boolean
    get() = this in setOf(DpadDirection.WEST, DpadDirection.NORTH_WEST, DpadDirection.SOUTH_WEST)

private const val KEYBOARD_SYMBOL = "⌨"
private const val DPAD_CELL_FRACTION = 0.36f
private const val DPAD_INNER_EDGE = 0.36f
private const val DPAD_CENTER_FRACTION = 0.5f
private const val DPAD_CONNECTOR_HALF_BASE = 0.09f
private const val DPAD_CENTER_DEAD_ZONE = 0.05f
