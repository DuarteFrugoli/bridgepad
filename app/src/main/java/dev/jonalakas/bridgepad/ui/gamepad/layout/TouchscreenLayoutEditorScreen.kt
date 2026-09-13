package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import dev.jonalakas.bridgepad.R
import kotlin.math.roundToInt

@Composable
fun TouchscreenLayoutEditorScreen(
    initialLayout: TouchscreenLayout,
    onSave: (TouchscreenLayout) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var encodedDraft by rememberSaveable {
        mutableStateOf(TouchscreenLayoutCodec.encode(initialLayout))
    }
    var selectedName by rememberSaveable { mutableStateOf(TouchControlId.LEFT_STICK.name) }
    val draft = remember(encodedDraft) {
        TouchscreenLayoutCodec.decode(encodedDraft) ?: DefaultTouchscreenLayout.value
    }
    val selected = TouchControlId.valueOf(selectedName)

    fun updateDraft(layout: TouchscreenLayout) {
        encodedDraft = TouchscreenLayoutCodec.encode(layout)
    }

    BackHandler(onBack = onCancel)

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)),
        ) {
            val widthPixels = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val heightPixels = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val density = LocalDensity.current
            val previewScale = with(density) {
                minOf(
                    1f,
                    constraints.maxWidth.toDp().value / REFERENCE_WIDTH_DP,
                    constraints.maxHeight.toDp().value / REFERENCE_HEIGHT_DP,
                )
            }
            LayoutGrid(Modifier.fillMaxSize())
            TouchControlId.entries.forEach { control ->
                EditableControl(
                    control = control,
                    placement = draft.placement(control),
                    selected = selected == control,
                    canvasWidthPixels = widthPixels,
                    canvasHeightPixels = heightPixels,
                    previewScale = previewScale,
                    onSelect = { selectedName = control.name },
                    onMove = { deltaX, deltaY ->
                        updateDraft(draft.move(control, deltaX, deltaY))
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.layout_editor_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.layout_editor_instructions), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(
                stringResource(R.string.layout_selected_control, controlLabel(selected)),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    R.string.layout_control_size,
                    (draft.placement(selected).scale * 100f).roundToInt(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = draft.placement(selected).scale,
                onValueChange = { updateDraft(draft.resize(selected, it)) },
                valueRange = MIN_CONTROL_SCALE..MAX_CONTROL_SCALE,
            )
            OutlinedButton(
                onClick = { updateDraft(DefaultTouchscreenLayout.value) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.reset_layout)) }
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel_action))
            }
            Button(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_layout))
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.EditableControl(
    control: TouchControlId,
    placement: TouchControlPlacement,
    selected: Boolean,
    canvasWidthPixels: Float,
    canvasHeightPixels: Float,
    previewScale: Float,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val width = (control.baseWidthDp * placement.scale * previewScale).dp
    val height = (control.baseHeightDp * placement.scale * previewScale).dp
    val widthPixels = with(density) { width.toPx() }
    val heightPixels = with(density) { height.toPx() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)
    val offset = controlOffset(
        placement = placement,
        containerWidth = canvasWidthPixels,
        containerHeight = canvasHeightPixels,
        controlWidth = widthPixels,
        controlHeight = heightPixels,
    )

    Box(
        modifier = Modifier
            .zIndex(if (selected) 1f else 0f)
            .offset { offset }
            .size(width, height)
            .pointerInput(control, canvasWidthPixels, canvasHeightPixels) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnSelect()
                    val pointerId = down.id
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                            ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            currentOnMove(
                                delta.x / canvasWidthPixels,
                                delta.y / canvasHeightPixels,
                            )
                        }
                        change.consume()
                    }
                }
            },
    ) {
        ControlPreview(control, Modifier.fillMaxSize())
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary, controlShape(control)),
            )
        }
    }
}

@Composable
private fun ControlPreview(control: TouchControlId, modifier: Modifier = Modifier) {
    val label = controlLabel(control)
    when (control) {
        TouchControlId.LEFT_STICK, TouchControlId.RIGHT_STICK -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            val base = MaterialTheme.colorScheme.surface
            val outline = MaterialTheme.colorScheme.outline
            val knob = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val radius = minOf(size.width, size.height) * 0.42f
                drawCircle(base, radius)
                drawCircle(outline, radius, style = Stroke(width = 2.dp.toPx()))
                drawCircle(knob, radius * 0.42f)
            }
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
        else -> Surface(
            modifier = modifier,
            shape = controlShape(control),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = if (control == TouchControlId.MOUSE_TOUCHPAD) 0.dp else 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LayoutGrid(modifier: Modifier = Modifier) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    Canvas(modifier) {
        for (step in 1 until 4) {
            val fraction = step / 4f
            drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height))
            drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        }
    }
}

@Composable
internal fun controlLabel(control: TouchControlId): String = when (control) {
    TouchControlId.MOUSE_TOUCHPAD -> stringResource(R.string.mouse_label)
    TouchControlId.DPAD -> stringResource(R.string.dpad_name)
    TouchControlId.LEFT_STICK -> stringResource(R.string.left_stick)
    TouchControlId.RIGHT_STICK -> stringResource(R.string.right_stick)
    TouchControlId.LEFT_TRIGGER -> "L2"
    TouchControlId.LEFT_BUMPER -> "L1"
    TouchControlId.RIGHT_BUMPER -> "R1"
    TouchControlId.RIGHT_TRIGGER -> "R2"
    TouchControlId.FACE_NORTH -> "Y"
    TouchControlId.FACE_WEST -> "X"
    TouchControlId.FACE_EAST -> "B"
    TouchControlId.FACE_SOUTH -> "A"
    TouchControlId.SELECT -> "Select"
    TouchControlId.START -> "Start"
    TouchControlId.LEFT_STICK_BUTTON -> "L3"
    TouchControlId.RIGHT_STICK_BUTTON -> "R3"
    TouchControlId.SESSION_MENU -> stringResource(R.string.session_menu)
}

private fun controlShape(control: TouchControlId) = when (control) {
    TouchControlId.LEFT_STICK,
    TouchControlId.RIGHT_STICK,
    TouchControlId.FACE_NORTH,
    TouchControlId.FACE_WEST,
    TouchControlId.FACE_EAST,
    TouchControlId.FACE_SOUTH -> CircleShape
    else -> RoundedCornerShape(16.dp)
}

private const val REFERENCE_WIDTH_DP = 780f
private const val REFERENCE_HEIGHT_DP = 360f
