package dev.jonalakas.bridgepad.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.output.hid.*
import dev.jonalakas.bridgepad.ui.components.NoticeCard
import dev.jonalakas.bridgepad.ui.components.NoticeTone
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    bluetoothPermissionGranted: Boolean,
    hidCompatible: Boolean,
    hidState: HidSessionState,
    physicalGamepadState: PhysicalGamepadState,
    inputMode: InputMode,
    onInputModeChanged: (InputMode) -> Unit,
    onRequestPermissions: () -> Unit,
    onStartHid: () -> Unit,
    onConnect: (String) -> Unit,
    onReconnect: () -> Unit,
    onEnableCompatibilityInput: () -> Unit,
    onEnableBackgroundUsb: () -> Unit,
    onPairNewPc: () -> Unit,
    onOpenTouchController: () -> Unit,
    onStopHid: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTechnicalDetails by remember { mutableStateOf(false) }
    val connectionBusy = hidState.status == HidSessionStatus.CONNECTING
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.session_setup), style = MaterialTheme.typography.headlineMedium) }
            item { InputSelector(inputMode, onInputModeChanged, enabled = !hidState.sessionActive) }
            if (inputMode == InputMode.PHYSICAL_GAMEPAD && physicalGamepadState.devices.isEmpty()) {
                item { NoticeCard(stringResource(R.string.physical_input_missing), NoticeTone.WARNING) }
            }
            item {
                InfoCard(
                    stringResource(R.string.bluetooth_hid),
                    listOf(
                        stringResource(R.string.hid_compatibility_label) to stringResource(if (hidCompatible) R.string.hid_compatibility_available else R.string.hid_compatibility_unavailable),
                        stringResource(R.string.permission_label) to if (bluetoothPermissionGranted) "Granted" else "Required",
                        stringResource(R.string.session_state_label) to hidState.status.name,
                        stringResource(R.string.bluetooth_label) to if (hidState.bluetoothEnabled) "Enabled" else "Off or not checked",
                        stringResource(R.string.host_label) to (hidState.connectedHost ?: "Not connected"),
                    ),
                )
            }
            item { SessionFeedback(hidState) }

            if (!hidCompatible) {
                item { NoticeCard("This Android device does not expose the features required by Bluetooth HID.", NoticeTone.ERROR) }
            } else if (!bluetoothPermissionGranted) {
                item { FullWidthButton(onRequestPermissions, R.string.grant_permissions) }
            } else if (!hidState.sessionActive) {
                item { FullWidthButton(onStartHid, R.string.start_hid_spike) }
            }

            if (hidState.sessionActive && hidState.status == HidSessionStatus.READY) {
                if (hidState.canReconnect) item { FullWidthButton(onReconnect, R.string.reconnect_last_pc) }
                if (!hidState.pairingModeActive) {
                    item { FullWidthOutlinedButton(onPairNewPc, R.string.pair_new_pc) }
                }
                if (hidState.pairedHosts.isEmpty() && !hidState.pairingModeActive) {
                    item { NoticeCard(stringResource(R.string.no_paired_computers), NoticeTone.WARNING) }
                }
                items(hidState.pairedHosts, key = { it.address }) { host ->
                    OutlinedButton(
                        onClick = { onConnect(host.address) },
                        enabled = !connectionBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect to ${host.name}") }
                }
            }

            if (hidState.status == HidSessionStatus.CONNECTED) {
                if (inputMode == InputMode.PHYSICAL_GAMEPAD) {
                    item {
                        CaptureModeSelector(
                            mode = hidState.physicalCaptureMode,
                            onCompatibility = onEnableCompatibilityInput,
                            onBackgroundUsb = onEnableBackgroundUsb,
                        )
                    }
                }
                item {
                    if (inputMode == InputMode.TOUCHSCREEN) {
                        FullWidthButton(onOpenTouchController, R.string.open_touch_controller)
                    } else {
                        NoticeCard("Physical gamepad forwarding is active. You can leave BridgePad in the background.", NoticeTone.SUCCESS)
                    }
                }
                item {
                    InfoCard(
                        stringResource(R.string.live_bridge_metrics),
                        listOf(
                            stringResource(R.string.input_rate_label) to "${formatAxis(hidState.inputRateHz)} Hz",
                            stringResource(R.string.output_rate_label) to "${formatAxis(hidState.outputRateHz)} Hz",
                            stringResource(R.string.latency_label) to (hidState.lastLatencyMs?.let { "${formatAxis(it)} ms" } ?: "Waiting for input"),
                        ),
                    )
                }
            }
            if (hidState.sessionActive) item { FullWidthOutlinedButton(onStopHid, R.string.end_session) }

            item {
                TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (showTechnicalDetails) R.string.hide_technical_details else R.string.show_technical_details))
                }
            }
            if (showTechnicalDetails) {
                item { PhysicalGamepadDiagnostic(physicalGamepadState) }
                item {
                    InfoCard(
                        stringResource(R.string.app_information),
                        listOf(
                            stringResource(R.string.version_label) to appVersion,
                            stringResource(R.string.device_label) to deviceInfo.displayModel,
                            stringResource(R.string.android_label) to deviceInfo.androidVersion,
                            stringResource(R.string.api_level_label) to deviceInfo.sdkLevel.toString(),
                        ),
                    )
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium)
                            FullWidthOutlinedButton(onCopyDiagnostics, R.string.copy_diagnostics)
                            FullWidthOutlinedButton(onShareDiagnostics, R.string.share_diagnostics)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputSelector(mode: InputMode, onChanged: (InputMode) -> Unit, enabled: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.choose_input), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == InputMode.TOUCHSCREEN, { onChanged(InputMode.TOUCHSCREEN) }, { Text(stringResource(R.string.touchscreen_input)) }, enabled = enabled)
                FilterChip(mode == InputMode.PHYSICAL_GAMEPAD, { onChanged(InputMode.PHYSICAL_GAMEPAD) }, { Text(stringResource(R.string.physical_input)) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun CaptureModeSelector(
    mode: PhysicalCaptureMode,
    onCompatibility: () -> Unit,
    onBackgroundUsb: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.capture_mode), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == PhysicalCaptureMode.COMPATIBILITY, onCompatibility, { Text(stringResource(R.string.compatibility_mode)) })
                FilterChip(mode == PhysicalCaptureMode.BACKGROUND_USB, onBackgroundUsb, { Text(stringResource(R.string.background_usb_mode)) })
            }
            Text(
                stringResource(if (mode == PhysicalCaptureMode.BACKGROUND_USB) R.string.background_usb_description else R.string.compatibility_mode_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionFeedback(state: HidSessionState) {
    val tone = when (state.feedbackLevel) {
        HidFeedbackLevel.WARNING -> NoticeTone.WARNING
        HidFeedbackLevel.ERROR -> NoticeTone.ERROR
        HidFeedbackLevel.INFO -> NoticeTone.SUCCESS
    }
    NoticeCard(state.message, tone)
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) -> Text("$label: $value") }
        }
    }
}

@Composable
private fun PhysicalGamepadDiagnostic(state: PhysicalGamepadState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.physical_gamepad_diagnostic), style = MaterialTheme.typography.titleMedium)
            if (state.devices.isEmpty()) Text(stringResource(R.string.no_physical_gamepad))
            state.devices.forEach { device ->
                val gamepad = state.sourceStates[device.sourceId]
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text("Vendor/Product: ${device.vendorId}/${device.productId}")
                Text("Axes: ${device.axes.joinToString().ifEmpty { "None reported" }}")
                Text("Buttons: ${gamepad?.pressedButtons?.joinToString()?.ifEmpty { "None" } ?: "None"}")
                Text("D-pad: ${gamepad?.dpad ?: "NEUTRAL"}")
            }
            Text("Last event: ${state.lastRawEvent}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FullWidthButton(onClick: () -> Unit, textId: Int) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(textId)) }
}

@Composable
private fun FullWidthOutlinedButton(onClick: () -> Unit, textId: Int) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(textId)) }
}

private fun formatAxis(value: Float): String = String.format(Locale.ROOT, "%.3f", value)
