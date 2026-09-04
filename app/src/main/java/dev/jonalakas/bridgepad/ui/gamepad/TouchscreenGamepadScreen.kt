package dev.jonalakas.bridgepad.ui.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore
import dev.jonalakas.bridgepad.output.hid.HidSessionState
import dev.jonalakas.bridgepad.output.hid.HidSessionStatus
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun TouchscreenGamepadScreen(
    hidState: HidSessionState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        TouchGamepadStore.activate()
        onDispose { TouchGamepadStore.deactivate() }
    }
    BackHandler(onBack = onExit)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LeftControls(Modifier.weight(1f).fillMaxHeight())
            CenterControls(hidState, onExit, Modifier.weight(0.9f).fillMaxHeight())
            RightControls(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LeftControls(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TriggerButton("L2", VirtualAxis.LEFT_TRIGGER, Modifier.weight(1f))
            GamepadButton("L1", VirtualControl.LEFT_BUMPER, Modifier.weight(1f))
        }
        DpadPad(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .widthIn(max = 150.dp),
        )
        VirtualStick(
            xAxis = VirtualAxis.LEFT_X,
            yAxis = VirtualAxis.LEFT_Y,
            label = "Left stick",
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .widthIn(max = 150.dp),
        )
    }
}

@Composable
private fun RightControls(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GamepadButton("R1", VirtualControl.RIGHT_BUMPER, Modifier.weight(1f))
            TriggerButton("R2", VirtualAxis.RIGHT_TRIGGER, Modifier.weight(1f))
        }
        FaceButtons(
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .widthIn(max = 150.dp),
        )
        VirtualStick(
            xAxis = VirtualAxis.RIGHT_X,
            yAxis = VirtualAxis.RIGHT_Y,
            label = "Right stick",
            modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .widthIn(max = 150.dp),
        )
    }
}

@Composable
private fun CenterControls(
    hidState: HidSessionState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val connected = hidState.status == HidSessionStatus.CONNECTED
        Text(
            text = if (connected) {
                "Connected · ${formatMetric(hidState.outputRateHz)} Hz · " +
                    (hidState.lastLatencyMs?.let { "${formatMetric(it)} ms" } ?: "waiting for input")
            } else {
                "Disconnected"
            },
            color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
        MouseTouchpad(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GamepadButton("Select", VirtualControl.SELECT)
            GamepadButton("Start", VirtualControl.START)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GamepadButton("L3", VirtualControl.LEFT_STICK_BUTTON)
            GamepadButton("R3", VirtualControl.RIGHT_STICK_BUTTON)
        }
        Spacer(Modifier.weight(1f))
        TouchButton(label = "Menu", onPressedChange = { pressed -> if (pressed) onExit() })
    }
}

@Composable
private fun MouseTouchpad(modifier: Modifier = Modifier) {
    val container = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .semantics {
                contentDescription = "Mouse touchpad"
                stateDescription = "Drag to move the pointer; tap for left click"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var previous = down.position
                    var distance = 0f
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                            ?: break
                        if (!change.pressed) break
                        val delta = change.position - previous
                        distance += hypot(delta.x, delta.y)
                        if (delta != Offset.Zero) TouchMouseStore.move(delta.x, delta.y)
                        previous = change.position
                        change.consume()
                    }
                    if (distance <= 12.dp.toPx()) TouchMouseStore.click()
                }
            },
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = androidx.compose.foundation.BorderStroke(2.dp, outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "Mouse\nDrag to move · Tap to click",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FaceButtons(modifier: Modifier = Modifier) {
    Box(modifier) {
        GamepadButton("Y", VirtualControl.FACE_NORTH, Modifier.align(Alignment.TopCenter))
        GamepadButton("X", VirtualControl.FACE_WEST, Modifier.align(Alignment.CenterStart))
        GamepadButton("B", VirtualControl.FACE_EAST, Modifier.align(Alignment.CenterEnd))
        GamepadButton("A", VirtualControl.FACE_SOUTH, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun GamepadButton(
    label: String,
    control: VirtualControl,
    modifier: Modifier = Modifier,
) {
    TouchButton(
        label = label,
        modifier = modifier,
        onPressedChange = { TouchGamepadStore.setButton(control, it) },
    )
}

@Composable
private fun TriggerButton(
    label: String,
    axis: VirtualAxis,
    modifier: Modifier = Modifier,
) {
    TouchButton(
        label = label,
        modifier = modifier,
        onPressedChange = { TouchGamepadStore.setTrigger(axis, it) },
    )
}

@Composable
private fun TouchButton(
    label: String,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
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
            .height(52.dp)
            .widthIn(min = 52.dp)
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = if (pressed) "Pressed" else "Released"
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
        shape = RoundedCornerShape(18.dp),
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
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.outline
    val base = MaterialTheme.colorScheme.surfaceVariant
    val knob = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .semantics { contentDescription = label }
            .pointerInput(xAxis, yAxis) {
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
                        TouchGamepadStore.setStick(xAxis, yAxis, position.x, position.y)
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
private fun DpadPad(modifier: Modifier = Modifier) {
    var direction by remember { mutableStateOf(DpadDirection.NEUTRAL) }
    val base = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .semantics {
                contentDescription = "D-pad"
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
            .size(48.dp)
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

private fun formatMetric(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
