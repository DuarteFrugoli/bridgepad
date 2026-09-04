package dev.jonalakas.bridgepad.ui.mapping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualControl
import dev.jonalakas.bridgepad.core.gamepad.DpadDirection
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.input.usb.AxisBinding
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import dev.jonalakas.bridgepad.input.usb.UsbGamepadMapping

private sealed interface MappingStep {
    val prompt: String
    data class Button(val target: VirtualControl, override val prompt: String) : MappingStep
    data class Dpad(val target: DpadDirection, override val prompt: String) : MappingStep
    data class Axis(
        val target: VirtualAxis,
        val expectedSign: Int,
        override val prompt: String,
    ) : MappingStep
}

private val steps = listOf(
    MappingStep.Button(VirtualControl.FACE_SOUTH, "Press the bottom face button (A)"),
    MappingStep.Button(VirtualControl.FACE_EAST, "Press the right face button (B)"),
    MappingStep.Button(VirtualControl.FACE_WEST, "Press the left face button (X)"),
    MappingStep.Button(VirtualControl.FACE_NORTH, "Press the top face button (Y)"),
    MappingStep.Dpad(DpadDirection.WEST, "Press D-pad left"),
    MappingStep.Dpad(DpadDirection.EAST, "Press D-pad right"),
    MappingStep.Dpad(DpadDirection.NORTH, "Press D-pad up"),
    MappingStep.Dpad(DpadDirection.SOUTH, "Press D-pad down"),
    MappingStep.Axis(VirtualAxis.LEFT_X, -1, "Move the left stick fully left"),
    MappingStep.Axis(VirtualAxis.LEFT_X, 1, "Move the left stick fully right"),
    MappingStep.Axis(VirtualAxis.LEFT_Y, -1, "Move the left stick fully up"),
    MappingStep.Axis(VirtualAxis.LEFT_Y, 1, "Move the left stick fully down"),
    MappingStep.Button(VirtualControl.LEFT_STICK_BUTTON, "Press the left stick (L3)"),
    MappingStep.Axis(VirtualAxis.RIGHT_X, -1, "Move the right stick fully left"),
    MappingStep.Axis(VirtualAxis.RIGHT_X, 1, "Move the right stick fully right"),
    MappingStep.Axis(VirtualAxis.RIGHT_Y, -1, "Move the right stick fully up"),
    MappingStep.Axis(VirtualAxis.RIGHT_Y, 1, "Move the right stick fully down"),
    MappingStep.Button(VirtualControl.RIGHT_STICK_BUTTON, "Press the right stick (R3)"),
    MappingStep.Button(VirtualControl.LEFT_BUMPER, "Press the left bumper (L1)"),
    MappingStep.Axis(VirtualAxis.LEFT_TRIGGER, 1, "Fully press the left trigger (L2)"),
    MappingStep.Button(VirtualControl.RIGHT_BUMPER, "Press the right bumper (R1)"),
    MappingStep.Axis(VirtualAxis.RIGHT_TRIGGER, 1, "Fully press the right trigger (R2)"),
    MappingStep.Button(VirtualControl.SELECT, "Press Back / Select / Share"),
    MappingStep.Button(VirtualControl.START, "Press Start / Options / Menu"),
    MappingStep.Button(VirtualControl.EXTRA_1, "Press Guide / PS / Home"),
    MappingStep.Button(VirtualControl.EXTRA_2, "Press Share / Capture"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbMappingScreen(
    usbState: DirectUsbState,
    onSave: (UsbGamepadMapping) -> Unit,
    onCancel: () -> Unit,
) {
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var baseline by remember { mutableStateOf(usbState.rawGamepad) }
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
    var instruction by remember { mutableStateOf("Release all controls, then follow the prompt.") }
    val step = steps.getOrNull(stepIndex)

    LaunchedEffect(stepIndex) {
        baseline = usbState.rawGamepad
        armed = usbState.rawGamepad.isNeutral()
        instruction = if (armed) "Ready." else "Release all controls first."
    }
    LaunchedEffect(usbState.inputEventCount, stepIndex) {
        val currentStep = steps.getOrNull(stepIndex) ?: return@LaunchedEffect
        if (!armed) {
            if (usbState.rawGamepad.isNeutral()) {
                baseline = usbState.rawGamepad
                armed = true
                instruction = "Ready."
            }
            return@LaunchedEffect
        }
        val pressedNow = usbState.rawGamepad.pressedButtons - baseline.pressedButtons
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
                val source = (usbState.rawGamepad.pressedButtons - baseline.pressedButtons).firstOrNull()
                if (source != null) {
                    val usedBy = buttons.entries.firstOrNull {
                        it.value == source && it.key != currentStep.target
                    }?.key
                    if (usedBy != null) {
                        instruction = "That physical button is already used by ${usedBy.displayName()}. Choose another button."
                    } else {
                        buttons[currentStep.target] = source
                        armed = false
                        stepIndex++
                    }
                }
            }
            is MappingStep.Dpad -> {
                if (usbState.rawGamepad.dpad != DpadDirection.NEUTRAL &&
                    usbState.rawGamepad.dpad != baseline.dpad
                ) {
                    val usedBy = dpad.entries.firstOrNull {
                        it.value == usbState.rawGamepad.dpad && it.key != currentStep.target
                    }?.key
                    if (usedBy != null) {
                        instruction = "That D-pad direction is already used by ${usedBy.name.replace('_', ' ')}. Choose another direction."
                    } else {
                        dpad[currentStep.target] = usbState.rawGamepad.dpad
                        armed = false
                        stepIndex++
                    }
                }
            }
            is MappingStep.Axis -> {
                val movement = VirtualAxis.entries.map { axis ->
                    axis to (usbState.rawGamepad.valueOf(axis) - baseline.valueOf(axis))
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
                        instruction = "Both directions must use the same physical axis. Move the requested direction on the same stick as the previous step."
                    } else if (existingForTarget != null && existingForTarget.inverted != candidate.inverted) {
                        instruction = "That was the opposite direction. Move the stick in the direction shown by the prompt."
                    } else if (usedBy != null) {
                        instruction = "That physical axis is already used by ${usedBy.displayName()}. Move another axis."
                    } else {
                        axes[currentStep.target] = candidate
                        armed = false
                        stepIndex++
                    }
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Configure USB gamepad") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(usbState.deviceName ?: "USB gamepad", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { stepIndex.toFloat() / steps.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Step ${stepIndex.coerceAtMost(steps.size)} of ${steps.size}")
            if (step != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(step.prompt, style = MaterialTheme.typography.headlineSmall)
                        Text(instruction)
                        if (stepIndex > 0) Text("Press the mapped A button to skip this control.")
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
                ) { Text("Previous step") }
            } else {
                Text("Mapping complete", style = MaterialTheme.typography.headlineSmall)
                Text("Save this profile to apply it automatically whenever this controller is connected.")
                Button(
                    onClick = {
                        onSave(
                            UsbGamepadMapping(
                                buttons = buttons.toMap(),
                                axes = axes.toMap(),
                                dpad = dpad.toMap(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save mapping") }
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
                ) { Text("Previous step") }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
