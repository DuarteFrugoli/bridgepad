package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.ui.gamepad.DpadVisual
import kotlin.math.roundToInt

@Composable
fun TouchscreenLayoutEditorScreen(
    initialProfile: TouchscreenLayoutProfile,
    editingOrientation: TouchscreenLayoutOrientation,
    onSave: (TouchscreenLayoutProfile) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val encodedInitialProfile = remember(initialProfile) {
        TouchscreenLayoutProfileCodec.encode(initialProfile)
    }
    var encodedDraft by rememberSaveable {
        mutableStateOf(encodedInitialProfile)
    }
    var discardConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf(TouchControlId.LEFT_STICK.name) }
    var toolbarExpanded by rememberSaveable { mutableStateOf(false) }
    var optionsVisible by rememberSaveable { mutableStateOf(true) }
    var toolbarCenterX by rememberSaveable { mutableFloatStateOf(0.5f) }
    var toolbarCenterY by rememberSaveable { mutableFloatStateOf(0f) }
    var optionsCenterX by rememberSaveable { mutableFloatStateOf(1f) }
    var optionsCenterY by rememberSaveable { mutableFloatStateOf(1f) }
    var collapsedToolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var expandedToolbarSize by remember { mutableStateOf(IntSize.Zero) }
    var optionsPanelSize by remember { mutableStateOf(IntSize.Zero) }
    val draftProfile = remember(encodedDraft) {
        TouchscreenLayoutProfileCodec.decode(encodedDraft) ?: DefaultTouchscreenLayoutProfile.value
    }
    val draft = draftProfile.layout(editingOrientation)
    val selected = TouchControlId.valueOf(selectedName)

    fun replaceDraft(profile: TouchscreenLayoutProfile) {
        encodedDraft = TouchscreenLayoutProfileCodec.encode(profile)
    }

    fun replaceCurrentLayout(layout: TouchscreenLayout) {
        replaceDraft(draftProfile.update(editingOrientation, layout))
    }

    fun updateDraft(transform: (TouchscreenLayout) -> TouchscreenLayout) {
        val currentProfile = TouchscreenLayoutProfileCodec.decode(encodedDraft)
            ?: DefaultTouchscreenLayoutProfile.value
        val currentLayout = currentProfile.layout(editingOrientation)
        encodedDraft = TouchscreenLayoutProfileCodec.encode(
            currentProfile.update(editingOrientation, transform(currentLayout)),
        )
    }

    fun requestCancel() {
        if (encodedDraft == encodedInitialProfile) {
            onCancel()
        } else {
            discardConfirmationVisible = true
        }
    }

    BackHandler(onBack = { requestCancel() })

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        val widthPixels = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val heightPixels = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val portraitToolbarInsetPixels = with(LocalDensity.current) { 56.dp.toPx() }
        val landscapeEditor = editingOrientation == TouchscreenLayoutOrientation.LANDSCAPE
        val toolbarLocked = toolbarExpanded && optionsVisible
        val currentToolbarSize = if (toolbarExpanded) expandedToolbarSize else collapsedToolbarSize
        val movableToolbarModifier = Modifier
            .align(Alignment.TopStart)
            .offset {
                floatingOverlayOffset(
                    centerX = toolbarCenterX,
                    centerY = toolbarCenterY,
                    containerWidth = widthPixels,
                    containerHeight = heightPixels,
                    overlayWidth = currentToolbarSize.width.toFloat(),
                    overlayHeight = currentToolbarSize.height.toFloat(),
                )
            }
            .pointerInput(widthPixels, heightPixels, currentToolbarSize) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    toolbarCenterX = moveFloatingOverlayCenter(
                        currentCenter = toolbarCenterX,
                        delta = dragAmount.x,
                        containerSize = widthPixels,
                        overlaySize = currentToolbarSize.width.toFloat(),
                    )
                    toolbarCenterY = moveFloatingOverlayCenter(
                        currentCenter = toolbarCenterY,
                        delta = dragAmount.y,
                        containerSize = heightPixels,
                        overlaySize = currentToolbarSize.height.toFloat(),
                    )
                }
            }
        val toolbarModifier = if (toolbarLocked) {
            Modifier.align(
                if (landscapeEditor) Alignment.TopCenter else Alignment.CenterStart,
            )
        } else {
            movableToolbarModifier
        }.onSizeChanged { size ->
            if (toolbarExpanded) {
                expandedToolbarSize = size
            } else {
                collapsedToolbarSize = size
            }
        }.zIndex(EDITOR_OVERLAY_Z_INDEX)
        LayoutGrid(Modifier.fillMaxSize())
        TouchControlId.entries.filter(draft::isVisible).forEach { control ->
            EditableControl(
                control = control,
                placement = draft.placement(control),
                selected = selected == control,
                canvasWidthPixels = widthPixels,
                canvasHeightPixels = heightPixels,
                onSelect = { selectedName = control.name },
                onMove = { deltaX, deltaY ->
                    val currentProfile = TouchscreenLayoutProfileCodec.decode(encodedDraft)
                        ?: DefaultTouchscreenLayoutProfile.value
                    val updated = currentProfile.layout(editingOrientation)
                        .move(control, deltaX, deltaY)
                    if (toolbarExpanded && optionsVisible) {
                        if (landscapeEditor) {
                            optionsCenterX = optionsPanelCenterAfterControlMove(
                                controlCenter = updated.placement(control).centerX,
                                currentOptionsCenter = optionsCenterX,
                            )
                        } else {
                            optionsCenterY = optionsPanelCenterAfterControlMove(
                                controlCenter = updated.placement(control).centerY,
                                currentOptionsCenter = optionsCenterY,
                            )
                        }
                    }
                    replaceCurrentLayout(updated)
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

        if (toolbarExpanded) {
            EditorToolbar(
                optionsVisible = optionsVisible,
                onCollapse = { toolbarExpanded = false },
                onToggleOptions = { optionsVisible = !optionsVisible },
                onCancel = { requestCancel() },
                onSave = { onSave(draftProfile) },
                horizontal = landscapeEditor,
                modifier = toolbarModifier,
            )
        } else {
            CollapsedEditorToolbar(
                onExpand = { toolbarExpanded = true },
                modifier = toolbarModifier,
            )
        }

        if (toolbarExpanded && optionsVisible) {
            EditorOptionsPanel(
                draftProfile = draftProfile,
                editingOrientation = editingOrientation,
                selectedControl = selected,
                onSelectPreset = ::replaceCurrentLayout,
                onDeadzoneChange = { deadzone ->
                    updateDraft { it.setDeadzone(selected, deadzone) }
                },
                onInteractionChange = { interaction ->
                    updateDraft { it.setInteraction(selected, interaction) }
                },
                onDisplayOptionChange = { control, option ->
                    updateDraft { current ->
                        when (option) {
                            ControlDisplayOption.VISIBLE -> current.setVisible(control, true)
                            ControlDisplayOption.CIRCLE -> current
                                .setShape(control, TouchControlShape.CIRCLE)
                                .setVisible(control, true)
                            ControlDisplayOption.ROUNDED_RECTANGLE -> current
                                .setShape(control, TouchControlShape.ROUNDED_RECTANGLE)
                                .setVisible(control, true)
                            ControlDisplayOption.HIDDEN -> current.setVisible(control, false)
                        }
                    }
                    if (option == ControlDisplayOption.HIDDEN && selected == control) {
                        TouchControlId.entries
                            .firstOrNull { candidate -> candidate != control && draft.isVisible(candidate) }
                            ?.let { selectedName = it.name }
                    }
                },
                onReset = {
                    replaceCurrentLayout(
                        DefaultTouchscreenLayoutProfile.value.layout(editingOrientation),
                    )
                },
                horizontal = !landscapeEditor,
                onMove = { delta ->
                    if (landscapeEditor) {
                        optionsCenterX = moveFloatingOverlayCenter(
                            currentCenter = optionsCenterX,
                            delta = delta,
                            containerSize = widthPixels,
                            overlaySize = optionsPanelSize.width.toFloat(),
                        )
                    } else {
                        optionsCenterY = moveFloatingOverlayCenter(
                            currentCenter = optionsCenterY,
                            delta = delta,
                            containerSize = heightPixels,
                            overlaySize = optionsPanelSize.height.toFloat(),
                        )
                    }
                },
                modifier = (if (landscapeEditor) {
                    Modifier
                        .offset {
                            IntOffset(
                                x = floatingOverlayAxisOffset(
                                    center = optionsCenterX,
                                    containerSize = widthPixels,
                                    overlaySize = optionsPanelSize.width.toFloat(),
                                ),
                                y = 60.dp.roundToPx(),
                            )
                        }
                        .widthIn(max = 340.dp)
                        .heightIn(max = (maxHeight - 68.dp).coerceAtLeast(120.dp))
                } else {
                    Modifier
                        .fillMaxWidth(0.78f)
                        .offset {
                            IntOffset(
                                x = portraitToolbarInsetPixels.roundToInt(),
                                y = floatingOverlayAxisOffset(
                                    center = optionsCenterY,
                                    containerSize = heightPixels,
                                    overlaySize = optionsPanelSize.height.toFloat(),
                                ),
                            )
                        }
                        .heightIn(max = 168.dp)
                })
                    .align(Alignment.TopStart)
                    .onSizeChanged { optionsPanelSize = it }
                    .zIndex(EDITOR_OVERLAY_Z_INDEX),
            )
        }
    }

    if (discardConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { discardConfirmationVisible = false },
            title = { Text(stringResource(R.string.discard_layout_title)) },
            text = { Text(stringResource(R.string.discard_layout_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardConfirmationVisible = false
                        onCancel()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.discard_changes))
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmationVisible = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }
}

@Composable
private fun EditorToolbar(
    optionsVisible: Boolean,
    onCollapse: () -> Unit,
    onToggleOptions: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val collapseDescription = stringResource(R.string.collapse_layout_editor_actions)
    val cancelDescription = stringResource(R.string.cancel_action)
    val optionsDescription = stringResource(
        if (optionsVisible) R.string.hide_layout_options else R.string.show_layout_options,
    )
    val saveDescription = stringResource(R.string.save_layout)
    val actions: @Composable () -> Unit = {
            EditorActionButton(
                symbol = COLLAPSE_ACTION_SYMBOL,
                contentDescription = collapseDescription,
                onClick = onCollapse,
            )
            EditorActionButton(
                symbol = CANCEL_ACTION_SYMBOL,
                contentDescription = cancelDescription,
                onClick = onCancel,
                destructive = true,
            )
            EditorActionButton(
                symbol = OPTIONS_ACTION_SYMBOL,
                contentDescription = optionsDescription,
                selected = optionsVisible,
                onClick = onToggleOptions,
            )
            EditorActionButton(
                symbol = SAVE_ACTION_SYMBOL,
                contentDescription = saveDescription,
                emphasized = true,
                onClick = onSave,
            )
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                actions()
            }
        }
    }
}

@Composable
private fun EditorActionButton(
    symbol: String,
    contentDescription: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    emphasized: Boolean = false,
    destructive: Boolean = false,
) {
    val containerColor = when {
        destructive -> MaterialTheme.colorScheme.error
        emphasized -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.onError
        emphasized -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                color = contentColor,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun CollapsedEditorToolbar(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.expand_layout_editor_actions)
    Surface(
        onClick = onExpand,
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                EDITOR_MENU_SYMBOL,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun EditorOptionsPanel(
    draftProfile: TouchscreenLayoutProfile,
    editingOrientation: TouchscreenLayoutOrientation,
    selectedControl: TouchControlId,
    onSelectPreset: (TouchscreenLayout) -> Unit,
    onDeadzoneChange: (Float) -> Unit,
    onInteractionChange: (TouchControlInteraction) -> Unit,
    onDisplayOptionChange: (TouchControlId, ControlDisplayOption) -> Unit,
    onReset: () -> Unit,
    horizontal: Boolean,
    onMove: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = draftProfile.layout(editingOrientation)
    val currentOnMove by rememberUpdatedState(onMove)
    val moveDescription = stringResource(
        if (horizontal) {
            R.string.move_layout_options_vertical
        } else {
            R.string.move_layout_options_horizontal
        },
    )
    Surface(
        modifier = modifier
            .semantics { contentDescription = moveDescription }
            .pointerInput(horizontal) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnMove(if (horizontal) dragAmount.y else dragAmount.x)
                }
            },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        if (horizontal) {
            HorizontalEditorOptions(
                draft = draft,
                editingOrientation = editingOrientation,
                selectedControl = selectedControl,
                onSelectPreset = onSelectPreset,
                onDeadzoneChange = onDeadzoneChange,
                onInteractionChange = onInteractionChange,
                onDisplayOptionChange = onDisplayOptionChange,
                onReset = onReset,
            )
        } else {
            Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.layout_editor_title), style = MaterialTheme.typography.titleLarge)
                Text(OPTIONS_HORIZONTAL_DRAG_SYMBOL, style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.layout_editor_instructions), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(stringResource(R.string.layout_orientation), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (editingOrientation == TouchscreenLayoutOrientation.PORTRAIT) {
                        R.string.orientation_portrait
                    } else {
                        R.string.orientation_landscape
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.layout_orientation_auto_hint), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(stringResource(R.string.layout_presets), style = MaterialTheme.typography.titleSmall)
            TouchscreenLayoutPreset.entries.forEach { preset ->
                val presetLayout = BuiltInTouchscreenLayouts.profile(preset)
                    .layout(editingOrientation)
                    .sanitized()
                FilterChip(
                    selected = draft == presetLayout,
                    onClick = { onSelectPreset(presetLayout) },
                    label = { Text(presetLabel(preset)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.layout_preset_hint), style = MaterialTheme.typography.bodySmall)
            if (selectedControl.adjustableDeadzone) {
                val deadzone = draft.placement(selectedControl).deadzone
                val selectedControlLabel = controlLabel(selectedControl)
                val decreaseDescription = "$selectedControlLabel: ${stringResource(R.string.decrease_deadzone)}"
                val increaseDescription = "$selectedControlLabel: ${stringResource(R.string.increase_deadzone)}"
                HorizontalDivider()
                Text(stringResource(R.string.stick_deadzone), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onDeadzoneChange(deadzone - STICK_DEADZONE_STEP) },
                        enabled = deadzone > MIN_STICK_DEADZONE,
                        modifier = Modifier.semantics {
                            contentDescription = decreaseDescription
                        },
                    ) {
                        Text("−")
                    }
                    Text(
                        stringResource(
                            R.string.stick_deadzone_value,
                            (deadzone * 100f).roundToInt(),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(
                        onClick = { onDeadzoneChange(deadzone + STICK_DEADZONE_STEP) },
                        enabled = deadzone < MAX_STICK_DEADZONE,
                        modifier = Modifier.semantics {
                            contentDescription = increaseDescription
                        },
                    ) {
                        Text("+")
                    }
                }
                Text(
                    stringResource(R.string.stick_deadzone_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (selectedControl.supportsToggle) {
                HorizontalDivider()
                ControlInteractionOptions(
                    interaction = draft.placement(selectedControl).interaction,
                    onInteractionChange = onInteractionChange,
                )
            }
            HorizontalDivider()
            ControlAppearanceOptions(
                layout = draft,
                horizontal = false,
                onDisplayOptionChange = onDisplayOptionChange,
            )
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reset_layout))
            }
        }
        }
    }
}

@Composable
private fun HorizontalEditorOptions(
    draft: TouchscreenLayout,
    editingOrientation: TouchscreenLayoutOrientation,
    selectedControl: TouchControlId,
    onSelectPreset: (TouchscreenLayout) -> Unit,
    onDeadzoneChange: (Float) -> Unit,
    onInteractionChange: (TouchControlInteraction) -> Unit,
    onDisplayOptionChange: (TouchControlId, ControlDisplayOption) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.layout_editor_title), style = MaterialTheme.typography.titleMedium)
                Text(OPTIONS_VERTICAL_DRAG_SYMBOL, style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.layout_orientation), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(
                    if (editingOrientation == TouchscreenLayoutOrientation.PORTRAIT) {
                        R.string.orientation_portrait
                    } else {
                        R.string.orientation_landscape
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    if (editingOrientation == TouchscreenLayoutOrientation.PORTRAIT) {
                        R.string.layout_orientation_rotate_landscape
                    } else {
                        R.string.layout_orientation_rotate_portrait
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        VerticalDivider(modifier = Modifier.heightIn(min = 88.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.layout_presets), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchscreenLayoutPreset.entries.forEach { preset ->
                    val presetLayout = BuiltInTouchscreenLayouts.profile(preset)
                        .layout(editingOrientation)
                        .sanitized()
                    FilterChip(
                        selected = draft == presetLayout,
                        onClick = { onSelectPreset(presetLayout) },
                        label = { Text(presetLabel(preset)) },
                    )
                }
            }
        }
        VerticalDivider(modifier = Modifier.heightIn(min = 88.dp))
        ControlAppearanceOptions(
            layout = draft,
            horizontal = true,
            onDisplayOptionChange = onDisplayOptionChange,
        )
        if (selectedControl.adjustableDeadzone) {
            val deadzone = draft.placement(selectedControl).deadzone
            val selectedControlLabel = controlLabel(selectedControl)
            val decreaseDescription = "$selectedControlLabel: ${stringResource(R.string.decrease_deadzone)}"
            val increaseDescription = "$selectedControlLabel: ${stringResource(R.string.increase_deadzone)}"
            VerticalDivider(modifier = Modifier.heightIn(min = 88.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.stick_deadzone), style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onDeadzoneChange(deadzone - STICK_DEADZONE_STEP) },
                        enabled = deadzone > MIN_STICK_DEADZONE,
                        modifier = Modifier.semantics { contentDescription = decreaseDescription },
                    ) {
                        Text(DECREASE_ACTION_SYMBOL)
                    }
                    Text(
                        stringResource(R.string.stick_deadzone_value, (deadzone * 100f).roundToInt()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(
                        onClick = { onDeadzoneChange(deadzone + STICK_DEADZONE_STEP) },
                        enabled = deadzone < MAX_STICK_DEADZONE,
                        modifier = Modifier.semantics { contentDescription = increaseDescription },
                    ) {
                        Text("+")
                    }
                }
            }
        }
        if (selectedControl.supportsToggle) {
            VerticalDivider(modifier = Modifier.heightIn(min = 88.dp))
            ControlInteractionOptions(
                interaction = draft.placement(selectedControl).interaction,
                onInteractionChange = onInteractionChange,
            )
        }
        VerticalDivider(modifier = Modifier.heightIn(min = 88.dp))
        OutlinedButton(onClick = onReset) {
            Text(stringResource(R.string.reset_layout))
        }
    }
}

@Composable
private fun ControlInteractionOptions(
    interaction: TouchControlInteraction,
    onInteractionChange: (TouchControlInteraction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.control_interaction), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = interaction == TouchControlInteraction.HOLD,
                onClick = { onInteractionChange(TouchControlInteraction.HOLD) },
                label = { Text(stringResource(R.string.control_interaction_hold)) },
            )
            FilterChip(
                selected = interaction == TouchControlInteraction.TOGGLE,
                onClick = { onInteractionChange(TouchControlInteraction.TOGGLE) },
                label = { Text(stringResource(R.string.control_interaction_toggle)) },
            )
        }
        Text(
            stringResource(R.string.control_interaction_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ControlAppearanceOptions(
    layout: TouchscreenLayout,
    horizontal: Boolean,
    onDisplayOptionChange: (TouchControlId, ControlDisplayOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.control_appearance), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.control_appearance_hint), style = MaterialTheme.typography.bodySmall)
        if (horizontal) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TouchControlId.entries.forEach { control ->
                    ControlAppearanceOption(
                        control = control,
                        layout = layout,
                        onDisplayOptionChange = onDisplayOptionChange,
                        modifier = Modifier.widthIn(min = 220.dp),
                    )
                }
            }
        } else {
            TouchControlId.entries.forEach { control ->
                ControlAppearanceOption(
                    control = control,
                    layout = layout,
                    onDisplayOptionChange = onDisplayOptionChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ControlAppearanceOption(
    control: TouchControlId,
    layout: TouchscreenLayout,
    onDisplayOptionChange: (TouchControlId, ControlDisplayOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = layout.isVisible(control)
    val shape = layout.shape(control)
    Column(
        modifier = modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(controlLabel(control), style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (control.customizableShape) {
                DisplayOptionChip(
                    selected = visible && shape == TouchControlShape.CIRCLE,
                    label = stringResource(R.string.control_shape_circle),
                    onClick = { onDisplayOptionChange(control, ControlDisplayOption.CIRCLE) },
                )
                DisplayOptionChip(
                    selected = visible && shape == TouchControlShape.ROUNDED_RECTANGLE,
                    label = stringResource(R.string.control_shape_rounded),
                    onClick = {
                        onDisplayOptionChange(control, ControlDisplayOption.ROUNDED_RECTANGLE)
                    },
                )
            } else {
                DisplayOptionChip(
                    selected = visible,
                    label = stringResource(R.string.control_visible),
                    onClick = { onDisplayOptionChange(control, ControlDisplayOption.VISIBLE) },
                )
            }
            DisplayOptionChip(
                selected = !visible,
                label = stringResource(R.string.control_hidden),
                onClick = { onDisplayOptionChange(control, ControlDisplayOption.HIDDEN) },
            )
        }
    }
}

@Composable
private fun DisplayOptionChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

private enum class ControlDisplayOption {
    VISIBLE,
    CIRCLE,
    ROUNDED_RECTANGLE,
    HIDDEN,
}

@Composable
private fun BoxWithConstraintsScope.EditableControl(
    control: TouchControlId,
    placement: TouchControlPlacement,
    selected: Boolean,
    canvasWidthPixels: Float,
    canvasHeightPixels: Float,
    onSelect: () -> Unit,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val width = controlWidthDp(control, placement).dp
    val height = controlHeightDp(control, placement).dp
    val lockAspectRatio = control.lockAspectRatio ||
        (placement.shape ?: control.defaultShape) == TouchControlShape.CIRCLE
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
            ControlPreview(
                control = control,
                shape = placement.shape ?: control.defaultShape,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
            )
            ResizeAnchor.entries
                .filter { !lockAspectRatio || it.isCorner }
                .forEach { anchor ->
                    ResizeHandle(
                        anchor = anchor,
                        controlLabel = controlLabel(control),
                        currentWidthScale = placement.widthScale,
                        currentHeightScale = placement.heightScale,
                        baseControlWidthPixels = with(density) {
                            controlWidthDp(
                                control,
                                placement.copy(widthScale = 1f, heightScale = 1f),
                            ).dp.toPx()
                        },
                        baseControlHeightPixels = with(density) {
                            controlHeightDp(
                                control,
                                placement.copy(widthScale = 1f, heightScale = 1f),
                            ).dp.toPx()
                        },
                        canvasWidthPixels = canvasWidthPixels,
                        canvasHeightPixels = canvasHeightPixels,
                        lockAspectRatio = lockAspectRatio,
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
private fun ControlPreview(
    control: TouchControlId,
    shape: TouchControlShape,
    modifier: Modifier = Modifier,
) {
    val label = controlLabel(control)
    when (control) {
        TouchControlId.DPAD -> DpadVisual(modifier = modifier)
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
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        TouchControlId.MOUSE_TOUCHPAD -> Surface(
            modifier = modifier,
            shape = touchControlShape(control, shape),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 2.dp,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        else -> Surface(
            modifier = modifier,
            shape = touchControlShape(control, shape),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
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
    TouchControlId.GUIDE -> stringResource(R.string.guide_button)
    TouchControlId.CAPTURE -> stringResource(R.string.capture_button)
    TouchControlId.KEYBOARD -> stringResource(R.string.keyboard_button)
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

private const val HANDLE_SIZE_DP = 20f
private const val HANDLE_RADIUS_DP = HANDLE_SIZE_DP / 2f
private const val EDITOR_OVERLAY_Z_INDEX = 100f
private const val EDITOR_MENU_SYMBOL = "\u22EE"
private const val OPTIONS_HORIZONTAL_DRAG_SYMBOL = "\u2194"
private const val OPTIONS_VERTICAL_DRAG_SYMBOL = "\u2195"
private const val COLLAPSE_ACTION_SYMBOL = "\u2212"
private const val CANCEL_ACTION_SYMBOL = "\u2715"
private const val OPTIONS_ACTION_SYMBOL = "\u2699"
private const val SAVE_ACTION_SYMBOL = "\u2713"
private const val DECREASE_ACTION_SYMBOL = "\u2212"
