package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jonalakas.bridgepad.R

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

    fun replaceDraft(layout: TouchscreenLayout) {
        encodedDraft = TouchscreenLayoutCodec.encode(layout)
    }

    fun updateDraft(transform: (TouchscreenLayout) -> TouchscreenLayout) {
        val current = TouchscreenLayoutCodec.decode(encodedDraft) ?: DefaultTouchscreenLayout.value
        encodedDraft = TouchscreenLayoutCodec.encode(transform(current))
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
                        updateDraft { it.move(control, deltaX, deltaY) }
                    },
                    onResize = { widthDelta, heightDelta, centerDeltaX, centerDeltaY ->
                        updateDraft { current ->
                            val placement = current.placement(control)
                            current.resize(
                                control = control,
                                widthScale = placement.widthScale + widthDelta,
                                heightScale = placement.heightScale + heightDelta,
                            ).move(control, centerDeltaX, centerDeltaY)
                        }
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
            Text(stringResource(R.string.layout_presets), style = MaterialTheme.typography.titleSmall)
            TouchscreenLayoutPreset.entries.forEach { preset ->
                val presetLayout = BuiltInTouchscreenLayouts.layout(preset)
                FilterChip(
                    selected = draft == presetLayout,
                    onClick = { replaceDraft(presetLayout) },
                    label = { Text(presetLabel(preset)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.layout_preset_hint), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(
                stringResource(R.string.layout_selected_control, controlLabel(selected)),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    if (selected.lockAspectRatio) {
                        R.string.layout_resize_proportional_hint
                    } else {
                        R.string.layout_resize_independent_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { replaceDraft(DefaultTouchscreenLayout.value) },
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
    onResize: (Float, Float, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val width = (control.baseWidthDp * placement.widthScale * previewScale).dp
    val height = (control.baseHeightDp * placement.heightScale * previewScale).dp
    val widthPixels = with(density) { width.toPx() }
    val heightPixels = with(density) { height.toPx() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnResize by rememberUpdatedState(onResize)
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
            .size(width, height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
            )
            ResizeAnchor.entries
                .filter { !control.lockAspectRatio || it.isCorner }
                .forEach { anchor ->
                    ResizeHandle(
                        anchor = anchor,
                        controlLabel = controlLabel(control),
                        currentWidthScale = placement.widthScale,
                        currentHeightScale = placement.heightScale,
                        baseControlWidthPixels = with(density) {
                            (control.baseWidthDp * previewScale).dp.toPx()
                        },
                        baseControlHeightPixels = with(density) {
                            (control.baseHeightDp * previewScale).dp.toPx()
                        },
                        canvasWidthPixels = canvasWidthPixels,
                        canvasHeightPixels = canvasHeightPixels,
                        lockAspectRatio = control.lockAspectRatio,
                        onSelect = currentOnSelect,
                        onResize = currentOnResize,
                    )
                }
        }
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    anchor: ResizeAnchor,
    controlLabel: String,
    currentWidthScale: Float,
    currentHeightScale: Float,
    baseControlWidthPixels: Float,
    baseControlHeightPixels: Float,
    canvasWidthPixels: Float,
    canvasHeightPixels: Float,
    lockAspectRatio: Boolean,
    onSelect: () -> Unit,
    onResize: (Float, Float, Float, Float) -> Unit,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnResize by rememberUpdatedState(onResize)
    val latestWidthScale by rememberUpdatedState(currentWidthScale)
    val latestHeightScale by rememberUpdatedState(currentHeightScale)
    val resizeDescription = stringResource(R.string.resize_control, controlLabel)
    Box(
        modifier = Modifier
            .align(anchor.alignment)
            .offset(
                x = (anchor.horizontalDirection * HANDLE_RADIUS_DP).dp,
                y = (anchor.verticalDirection * HANDLE_RADIUS_DP).dp,
            )
            .size(HANDLE_SIZE_DP.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .semantics {
                contentDescription = resizeDescription
                role = Role.Button
            }
            .pointerInput(
                anchor,
                baseControlWidthPixels,
                baseControlHeightPixels,
                canvasWidthPixels,
                canvasHeightPixels,
                lockAspectRatio,
            ) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    currentOnSelect()
                    val pointerId = down.id
                    var gestureWidthScale = latestWidthScale
                    var gestureHeightScale = latestHeightScale
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId }
                            ?: break
                        if (!change.pressed) break
                        val delta = change.positionChange()
                        if (delta != Offset.Zero) {
                            val resize = controlResizeDelta(
                                currentWidthScale = gestureWidthScale,
                                currentHeightScale = gestureHeightScale,
                                horizontalDirection = anchor.horizontalDirection,
                                verticalDirection = anchor.verticalDirection,
                                pointerDeltaX = delta.x,
                                pointerDeltaY = delta.y,
                                baseControlWidth = baseControlWidthPixels,
                                baseControlHeight = baseControlHeightPixels,
                                containerWidth = canvasWidthPixels,
                                containerHeight = canvasHeightPixels,
                                lockAspectRatio = lockAspectRatio,
                            )
                            gestureWidthScale += resize.widthScaleDelta
                            gestureHeightScale += resize.heightScaleDelta
                            currentOnResize(
                                resize.widthScaleDelta,
                                resize.heightScaleDelta,
                                resize.centerDeltaX,
                                resize.centerDeltaY,
                            )
                        }
                        change.consume()
                    }
                }
            },
    )
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

@Composable
private fun presetLabel(preset: TouchscreenLayoutPreset): String = stringResource(
    when (preset) {
        TouchscreenLayoutPreset.SYMMETRIC -> R.string.layout_preset_symmetric
        TouchscreenLayoutPreset.ASYMMETRIC -> R.string.layout_preset_asymmetric
        TouchscreenLayoutPreset.MOBILE -> R.string.layout_preset_mobile
    },
)

private fun controlShape(control: TouchControlId) = when (control) {
    TouchControlId.LEFT_STICK,
    TouchControlId.RIGHT_STICK,
    TouchControlId.FACE_NORTH,
    TouchControlId.FACE_WEST,
    TouchControlId.FACE_EAST,
    TouchControlId.FACE_SOUTH -> CircleShape
    else -> RoundedCornerShape(16.dp)
}

private enum class ResizeAnchor(
    val horizontalDirection: Int,
    val verticalDirection: Int,
    val alignment: Alignment,
    val isCorner: Boolean,
) {
    TOP_LEFT(-1, -1, Alignment.TopStart, true),
    TOP_CENTER(0, -1, Alignment.TopCenter, false),
    TOP_RIGHT(1, -1, Alignment.TopEnd, true),
    CENTER_LEFT(-1, 0, Alignment.CenterStart, false),
    CENTER_RIGHT(1, 0, Alignment.CenterEnd, false),
    BOTTOM_LEFT(-1, 1, Alignment.BottomStart, true),
    BOTTOM_CENTER(0, 1, Alignment.BottomCenter, false),
    BOTTOM_RIGHT(1, 1, Alignment.BottomEnd, true),
}

private const val REFERENCE_WIDTH_DP = 780f
private const val REFERENCE_HEIGHT_DP = 360f
private const val HANDLE_SIZE_DP = 20f
private const val HANDLE_RADIUS_DP = HANDLE_SIZE_DP / 2f
