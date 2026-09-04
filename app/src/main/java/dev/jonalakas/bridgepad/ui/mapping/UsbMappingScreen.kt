package dev.jonalakas.bridgepad.ui.mapping

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    data class Axis(val target: VirtualAxis, override val prompt: String) : MappingStep
}

private val steps = listOf(
    MappingStep.Button(VirtualControl.FACE_SOUTH, "Press the bottom face button (A)"),
    MappingStep.Button(VirtualControl.FACE_EAST, "Press the right face button (B)"),
    MappingStep.Button(VirtualControl.FACE_WEST, "Press the left face button (X)"),
    MappingStep.Button(VirtualControl.FACE_NORTH, "Press the top face button (Y)"),
    MappingStep.Button(VirtualControl.LEFT_BUMPER, "Press the left bumper (L1)"),
    MappingStep.Button(VirtualControl.RIGHT_BUMPER, "Press the right bumper (R1)"),
    MappingStep.Button(VirtualControl.SELECT, "Press Select / Back"),
    MappingStep.Button(VirtualControl.START, "Press Start / Menu"),
    MappingStep.Button(VirtualControl.LEFT_STICK_BUTTON, "Press the left stick (L3)"),
    MappingStep.Button(VirtualControl.RIGHT_STICK_BUTTON, "Press the right stick (R3)"),
    MappingStep.Axis(VirtualAxis.LEFT_X, "Move the left stick fully right"),
    MappingStep.Axis(VirtualAxis.LEFT_Y, "Move the left stick fully down"),
    MappingStep.Axis(VirtualAxis.RIGHT_X, "Move the right stick fully right"),
    MappingStep.Axis(VirtualAxis.RIGHT_Y, "Move the right stick fully down"),
    MappingStep.Axis(VirtualAxis.LEFT_TRIGGER, "Fully press the left trigger (L2)"),
    MappingStep.Axis(VirtualAxis.RIGHT_TRIGGER, "Fully press the right trigger (R2)"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbMappingScreen(
    usbState: DirectUsbState,
    onSave: (UsbGamepadMapping) -> Unit,
    onCancel: () -> Unit,
) {
    var stepIndex by remember { mutableIntStateOf(0) }
    var baseline by remember { mutableStateOf(usbState.rawGamepad) }
    var armed by remember { mutableStateOf(false) }
    val buttons = remember { mutableStateMapOf<VirtualControl, VirtualControl>() }
    val axes = remember { mutableStateMapOf<VirtualAxis, AxisBinding>() }
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
        when (currentStep) {
            is MappingStep.Button -> {
                val source = (usbState.rawGamepad.pressedButtons - baseline.pressedButtons).firstOrNull()
                if (source != null) {
                    buttons[currentStep.target] = source
                    armed = false
                    stepIndex++
                }
            }
            is MappingStep.Axis -> {
                val movement = VirtualAxis.entries.map { axis ->
                    axis to (usbState.rawGamepad.valueOf(axis) - baseline.valueOf(axis))
                }.maxByOrNull { kotlin.math.abs(it.second) }
                if (movement != null && kotlin.math.abs(movement.second) >= 0.45f) {
                    axes[currentStep.target] = AxisBinding(movement.first, movement.second < 0f)
                    armed = false
                    stepIndex++
                }
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Configure USB gamepad") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
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
                    }
                }
                OutlinedButton(
                    onClick = {
                        baseline = usbState.rawGamepad
                        armed = usbState.rawGamepad.isNeutral()
                        instruction = if (armed) "Ready." else "Release all controls first."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset this step") }
            } else {
                Text("Mapping complete", style = MaterialTheme.typography.headlineSmall)
                Text("Save this profile to apply it automatically whenever this controller is connected.")
                Button(
                    onClick = { onSave(UsbGamepadMapping(buttons.toMap(), axes.toMap())) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save mapping") }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private fun VirtualGamepadState.isNeutral(): Boolean =
    pressedButtons.isEmpty() && dpad == DpadDirection.NEUTRAL &&
        VirtualAxis.entries.all { kotlin.math.abs(valueOf(it)) < 0.2f }
