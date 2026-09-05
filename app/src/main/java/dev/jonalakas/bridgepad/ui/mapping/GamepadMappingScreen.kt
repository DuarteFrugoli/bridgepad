package dev.jonalakas.bridgepad.ui.mapping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import dev.jonalakas.bridgepad.R
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.AxisBinding
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping

private sealed interface MappingStep {
    val prompt: Int
    data class Button(val target: VirtualControl, override val prompt: Int) : MappingStep
    data class Dpad(val target: DpadDirection, override val prompt: Int) : MappingStep
    data class Axis(
        val target: VirtualAxis,
        val expectedSign: Int,
        override val prompt: Int,
    ) : MappingStep
}

private val steps = listOf(
    MappingStep.Button(VirtualControl.FACE_SOUTH, R.string.mapping_prompt_1),
    MappingStep.Button(VirtualControl.FACE_EAST, R.string.mapping_prompt_2),
    MappingStep.Button(VirtualControl.FACE_WEST, R.string.mapping_prompt_3),
    MappingStep.Button(VirtualControl.FACE_NORTH, R.string.mapping_prompt_4),
    MappingStep.Dpad(DpadDirection.WEST, R.string.mapping_prompt_5),
    MappingStep.Dpad(DpadDirection.EAST, R.string.mapping_prompt_6),
    MappingStep.Dpad(DpadDirection.NORTH, R.string.mapping_prompt_7),
    MappingStep.Dpad(DpadDirection.SOUTH, R.string.mapping_prompt_8),
    MappingStep.Axis(VirtualAxis.LEFT_X, -1, R.string.mapping_prompt_9),
    MappingStep.Axis(VirtualAxis.LEFT_X, 1, R.string.mapping_prompt_10),
    MappingStep.Axis(VirtualAxis.LEFT_Y, -1, R.string.mapping_prompt_11),
    MappingStep.Axis(VirtualAxis.LEFT_Y, 1, R.string.mapping_prompt_12),
    MappingStep.Button(VirtualControl.LEFT_STICK_BUTTON, R.string.mapping_prompt_13),
    MappingStep.Axis(VirtualAxis.RIGHT_X, -1, R.string.mapping_prompt_14),
    MappingStep.Axis(VirtualAxis.RIGHT_X, 1, R.string.mapping_prompt_15),
    MappingStep.Axis(VirtualAxis.RIGHT_Y, -1, R.string.mapping_prompt_16),
    MappingStep.Axis(VirtualAxis.RIGHT_Y, 1, R.string.mapping_prompt_17),
    MappingStep.Button(VirtualControl.RIGHT_STICK_BUTTON, R.string.mapping_prompt_18),
    MappingStep.Button(VirtualControl.LEFT_BUMPER, R.string.mapping_prompt_19),
    MappingStep.Axis(VirtualAxis.LEFT_TRIGGER, 1, R.string.mapping_prompt_20),
    MappingStep.Button(VirtualControl.RIGHT_BUMPER, R.string.mapping_prompt_21),
    MappingStep.Axis(VirtualAxis.RIGHT_TRIGGER, 1, R.string.mapping_prompt_22),
    MappingStep.Button(VirtualControl.SELECT, R.string.mapping_prompt_23),
    MappingStep.Button(VirtualControl.START, R.string.mapping_prompt_24),
    MappingStep.Button(VirtualControl.EXTRA_1, R.string.mapping_prompt_25),
    MappingStep.Button(VirtualControl.EXTRA_2, R.string.mapping_prompt_26),
)

data class GamepadMappingInput(
    val deviceName: String,
    val deviceKey: String,
    val rawGamepad: VirtualGamepadState,
    val inputEventCount: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamepadMappingScreen(
    input: GamepadMappingInput,
    onSave: (GamepadMapping) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onCancel)
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var baseline by remember { mutableStateOf(input.rawGamepad) }
    var armed by remember { mutableStateOf(false) }
    val buttons = rememberSaveable(
        saver = buttonMappingSaver(),
    ) { mutableStateMapOf<VirtualControl, VirtualControl>() }
    val axes = rememberSaveable(
        saver = axesMappingSaver(),
    ) { mutableStateMapOf<VirtualAxis, AxisBinding>() }
    val dpad = rememberSaveable(
        saver = dpadMappingSaver(),
    ) { mutableStateMapOf<DpadDirection, DpadDirection>() }
    var instruction by remember { mutableStateOf(context.getString(R.string.mapping_release_all)) }
    val step = steps.getOrNull(stepIndex)

    LaunchedEffect(stepIndex) {
        baseline = input.rawGamepad
        armed = input.rawGamepad.isNeutral()
        instruction = if (armed) context.getString(R.string.mapping_ready) else context.getString(R.string.mapping_release_first)
    }
    LaunchedEffect(input.inputEventCount, stepIndex) {
        val currentStep = steps.getOrNull(stepIndex) ?: return@LaunchedEffect
        if (!armed) {
            if (input.rawGamepad.isNeutral()) {
                baseline = input.rawGamepad
                armed = true
                instruction = context.getString(R.string.mapping_ready)
            }
            return@LaunchedEffect
        }
        val pressedNow = input.rawGamepad.pressedButtons - baseline.pressedButtons
        val skipSource = buttons[VirtualControl.FACE_SOUTH]
        if (currentStep !is MappingStep.Button || currentStep.target != VirtualControl.FACE_SOUTH) {
            if (skipSource != null && skipSource in pressedNow) {
                armed = false
                stepIndex++
                return@LaunchedEffect
            }
        }
        when (currentStep) {
            is MappingStep.Button -> {
                val source = (input.rawGamepad.pressedButtons - baseline.pressedButtons).firstOrNull()
                if (source != null) {
                    val usedBy = buttons.entries.firstOrNull {
                        it.value == source && it.key != currentStep.target
                    }?.key
                    if (usedBy != null) {
                        instruction = context.getString(R.string.mapping_button_used, usedBy.displayName())
                    } else {
                        buttons[currentStep.target] = source
                        armed = false
                        stepIndex++
                    }
                }
            }
            is MappingStep.Dpad -> {
                if (input.rawGamepad.dpad != DpadDirection.NEUTRAL &&
                    input.rawGamepad.dpad != baseline.dpad
                ) {
                    val usedBy = dpad.entries.firstOrNull {
                        it.value == input.rawGamepad.dpad && it.key != currentStep.target
                    }?.key
                    if (usedBy != null) {
                        instruction = context.getString(R.string.mapping_dpad_used)
                    } else {
                        dpad[currentStep.target] = input.rawGamepad.dpad
                        armed = false
                        stepIndex++
                    }
                }
            }
            is MappingStep.Axis -> {
                val movement = VirtualAxis.entries.map { axis ->
                    axis to (input.rawGamepad.valueOf(axis) - baseline.valueOf(axis))
                }.maxByOrNull { kotlin.math.abs(it.second) }
                if (movement != null && kotlin.math.abs(movement.second) >= 0.45f) {
                    val observedSign = if (movement.second < 0f) -1 else 1
                    val candidate = AxisBinding(
                        source = movement.first,
                        inverted = observedSign != currentStep.expectedSign,
                    )
                    val existingForTarget = axes[currentStep.target]
                    val usedBy = axes.entries.firstOrNull {
                        it.value.source == movement.first && it.key != currentStep.target
                    }?.key
                    if (existingForTarget != null && existingForTarget.source != movement.first) {
                        instruction = context.getString(R.string.mapping_same_axis)
                    } else if (existingForTarget != null && existingForTarget.inverted != candidate.inverted) {
                        instruction = context.getString(R.string.mapping_opposite)
                    } else if (usedBy != null) {
                        instruction = context.getString(R.string.mapping_axis_used)
                    } else {
                        axes[currentStep.target] = candidate
                        armed = false
                        stepIndex++
                    }
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.mapping_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(input.deviceName, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { stepIndex.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.mapping_progress, (stepIndex + 1).coerceAtMost(steps.size), steps.size))
            if (step != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(step.prompt), style = MaterialTheme.typography.headlineSmall)
                        Text(instruction)
                        if (stepIndex > 0) Text(stringResource(R.string.mapping_skip))
                    }
                }
                OutlinedButton(
                    onClick = {
                        val previousIndex = stepIndex - 1
                        if (previousIndex >= 0) {
                            when (val previous = steps[previousIndex]) {
                                is MappingStep.Button -> buttons.remove(previous.target)
                                is MappingStep.Dpad -> dpad.remove(previous.target)
                                is MappingStep.Axis -> axes.remove(previous.target)
                            }
                            stepIndex = previousIndex
                        }
                    },
                    enabled = stepIndex > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.mapping_previous)) }
            } else {
                Text(stringResource(R.string.mapping_complete), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.mapping_save_hint))
                Button(
                    onClick = {
                        onSave(
                            GamepadMapping(
                                buttons = buttons.toMap(),
                                axes = axes.toMap(),
                                dpad = dpad.toMap(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.mapping_save)) }
                OutlinedButton(
                    onClick = {
                        val previousIndex = steps.lastIndex
                        when (val previous = steps[previousIndex]) {
                            is MappingStep.Button -> buttons.remove(previous.target)
                            is MappingStep.Dpad -> dpad.remove(previous.target)
                            is MappingStep.Axis -> axes.remove(previous.target)
                        }
                        stepIndex = previousIndex
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.mapping_previous)) }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_action)) }
        }
    }
}

private fun VirtualGamepadState.isNeutral(): Boolean =
    pressedButtons.isEmpty() && dpad == DpadDirection.NEUTRAL &&
        VirtualAxis.entries.all { kotlin.math.abs(valueOf(it)) < 0.2f }

private fun VirtualControl.displayName(): String = when (this) {
    VirtualControl.FACE_SOUTH -> "A"
    VirtualControl.FACE_EAST -> "B"
    VirtualControl.FACE_WEST -> "X"
    VirtualControl.FACE_NORTH -> "Y"
    VirtualControl.LEFT_BUMPER -> "L1"
    VirtualControl.RIGHT_BUMPER -> "R1"
    VirtualControl.START -> "Start / Options / Menu"
    VirtualControl.SELECT -> "Back / Select / Share"
    VirtualControl.LEFT_STICK_BUTTON -> "L3"
    VirtualControl.RIGHT_STICK_BUTTON -> "R3"
    VirtualControl.EXTRA_1 -> "Guide / PS / Home"
    VirtualControl.EXTRA_2 -> "Share / Capture"
    else -> name.replace('_', ' ')
}

private fun VirtualAxis.displayName(): String = name.replace('_', ' ')

private fun buttonMappingSaver() = listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateMap<VirtualControl, VirtualControl>,
    String,
>(
    save = { map -> map.map { (target, source) -> "$target,$source" } },
    restore = { saved ->
        mutableStateMapOf<VirtualControl, VirtualControl>().apply {
            saved.forEach { value ->
                val (target, source) = value.split(',', limit = 2)
                put(VirtualControl.valueOf(target), VirtualControl.valueOf(source))
            }
        }
    },
)

private fun axesMappingSaver() = listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateMap<VirtualAxis, AxisBinding>,
    String,
>(
    save = { map -> map.map { (target, binding) -> "$target,${binding.source},${binding.inverted}" } },
    restore = { saved ->
        mutableStateMapOf<VirtualAxis, AxisBinding>().apply {
            saved.forEach { value ->
                val parts = value.split(',')
                put(VirtualAxis.valueOf(parts[0]), AxisBinding(VirtualAxis.valueOf(parts[1]), parts[2].toBoolean()))
            }
        }
    },
)

private fun dpadMappingSaver() = listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateMap<DpadDirection, DpadDirection>,
    String,
>(
    save = { map -> map.map { (target, source) -> "$target,$source" } },
    restore = { saved ->
        mutableStateMapOf<DpadDirection, DpadDirection>().apply {
            saved.forEach { value ->
                val (target, source) = value.split(',', limit = 2)
                put(DpadDirection.valueOf(target), DpadDirection.valueOf(source))
            }
        }
    },
)
