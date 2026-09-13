package dev.jonalakas.bridgepad.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterCatalog
import dev.jonalakas.bridgepad.core.session.SessionDraft
import dev.jonalakas.bridgepad.core.session.SessionStatus as HidSessionStatus
import dev.jonalakas.bridgepad.session.FeedbackLevel as HidFeedbackLevel
import dev.jonalakas.bridgepad.session.PairedHost
import dev.jonalakas.bridgepad.session.SessionState as HidSessionState
import dev.jonalakas.bridgepad.ui.components.NoticeCard
import dev.jonalakas.bridgepad.ui.components.NoticeTone
import dev.jonalakas.bridgepad.session.SessionSetup
import dev.jonalakas.bridgepad.session.reconcileBluetoothAvailability
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    bluetoothPermissionGranted: Boolean,
    bluetoothEnabled: Boolean,
    hidCompatible: Boolean,
    hidState: HidSessionState,
    physicalGamepadState: PhysicalGamepadState,
    inputMode: InputMode?,
    physicalCaptureMode: PhysicalCaptureMode?,
    sessionDraft: SessionDraft,
    outputAdapters: OutputAdapterCatalog,
    destinationType: DestinationType?,
    connectionMethod: ConnectionMethod?,
    directUsbState: DirectUsbState,
    mappingAvailable: Boolean,
    onDestinationChanged: (DestinationType) -> Unit,
    onSelectBluetooth: () -> Unit,
    pairedHosts: List<PairedHost>,
    selectedAddress: String?,
    pairNewPcSelected: Boolean,
    preparingConnection: Boolean,
    onSelectHost: (String?) -> Unit,
    onInputModeChanged: (InputMode) -> Unit,
    onPhysicalCaptureModeChanged: (PhysicalCaptureMode) -> Unit,
    onPrepareBluetooth: () -> Unit,
    onPlay: () -> Unit,
    onConfigureGamepadMapping: () -> Unit,
    onEditTouchscreenLayout: () -> Unit,
    onOpenTouchController: () -> Unit,
    onOpenMouseTouchpad: () -> Unit,
    onStopHid: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onLanguageSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    val connected = hidState.status == HidSessionStatus.CONNECTED
    val visibleHidState = hidState.reconcileBluetoothAvailability(
        enabled = bluetoothEnabled && bluetoothPermissionGranted,
        permissionGranted = bluetoothPermissionGranted,
    )
    val bluetoothSelected = connectionMethod == ConnectionMethod.BLUETOOTH
    val targetChosen = connected || (
        destinationType == DestinationType.PC &&
            bluetoothSelected &&
            (selectedAddress != null || pairNewPcSelected)
        )
    val setupComplete = SessionSetup.canConnect(
        draft = sessionDraft,
        adapters = outputAdapters,
        connectionAvailable = bluetoothEnabled && bluetoothPermissionGranted,
        availableTargetIds = pairedHosts.map { it.address },
    )
    val busy = preparingConnection || hidState.status in listOf(
        HidSessionStatus.STARTING, HidSessionStatus.REGISTERING, HidSessionStatus.CONNECTING,
    ) || hidState.pairingModeActive
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { TextButton(onClick = { panel = "settings" }) { Text(stringResource(R.string.settings)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(if (connected) R.string.session_active else R.string.home_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.home_description), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                SetupCard(R.string.step_destination) {
                    Choice(
                        destinationType == DestinationType.PC,
                        R.string.destination_pc,
                        !busy && !connected,
                    ) { onDestinationChanged(DestinationType.PC) }
                }
            }
            if (destinationType != null) {
                item {
                    SetupCard(R.string.step_transport) {
                        Choice(
                            bluetoothSelected,
                            R.string.bluetooth_label,
                            !busy && !connected && destinationType == DestinationType.PC,
                            onSelectBluetooth,
                        )
                        Choice(false, R.string.wifi_coming_soon, false) {}
                        Choice(false, R.string.usb_connection_coming_soon, false) {}
                        if (bluetoothSelected && destinationType == DestinationType.PC) {
                            HorizontalDivider()
                            Text(stringResource(R.string.choose_destination_title), style = MaterialTheme.typography.titleSmall)
                            if (connected) {
                                Text(stringResource(R.string.connected_to, hidState.connectedHost.orEmpty()))
                                Text(stringResource(R.string.change_destination_hint), style = MaterialTheme.typography.bodySmall)
                            } else if (!bluetoothPermissionGranted) {
                                Text(stringResource(R.string.bluetooth_destination_permission))
                                OutlinedButton(onClick = onPrepareBluetooth, enabled = !busy && hidCompatible) {
                                    Text(stringResource(R.string.grant_permissions))
                                }
                            } else if (!bluetoothEnabled) {
                                Text(stringResource(R.string.bluetooth_destination_off))
                                OutlinedButton(onClick = onPrepareBluetooth, enabled = !busy && hidCompatible) {
                                    Text(stringResource(R.string.enable_bluetooth))
                                }
                            } else {
                                pairedHosts.forEach { host ->
                                    FilterChip(
                                        selected = selectedAddress == host.address,
                                        onClick = { onSelectHost(host.address) },
                                        label = { Text(host.name) },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Choice(pairNewPcSelected, R.string.pair_new_pc, !busy) { onSelectHost(null) }
                                if (selectedAddress == null && !pairNewPcSelected) {
                                    Text(stringResource(R.string.choose_destination_hint))
                                }
                                if (selectedAddress != null && pairedHosts.none { it.address == selectedAddress }) {
                                    NoticeCard(stringResource(R.string.selected_pc_unavailable), NoticeTone.WARNING)
                                }
                            }
                        }
                    }
                }
            }
            if (targetChosen) {
                item {
                    SetupCard(R.string.step_input) {
                        Choice(inputMode == InputMode.TOUCHSCREEN, R.string.touchscreen_input, !busy) { onInputModeChanged(InputMode.TOUCHSCREEN) }
                        Choice(inputMode == InputMode.PHYSICAL_GAMEPAD, R.string.physical_input, !busy) { onInputModeChanged(InputMode.PHYSICAL_GAMEPAD) }
                        if (inputMode == InputMode.TOUCHSCREEN) {
                            OutlinedButton(
                                onClick = onEditTouchscreenLayout,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.edit_controller_layout)) }
                        } else if (inputMode == InputMode.PHYSICAL_GAMEPAD) {
                            val deviceNames = physicalGamepadState.devices.joinToString { it.name }
                            Text(stringResource(R.string.capture_mode), style = MaterialTheme.typography.titleSmall)
                            Choice(
                                physicalCaptureMode == PhysicalCaptureMode.COMPATIBILITY,
                                R.string.compatibility_mode,
                                !busy,
                            ) { onPhysicalCaptureModeChanged(PhysicalCaptureMode.COMPATIBILITY) }
                            Text(stringResource(R.string.compatibility_mode_description), style = MaterialTheme.typography.bodySmall)
                            Choice(
                                physicalCaptureMode == PhysicalCaptureMode.BACKGROUND_USB,
                                R.string.background_usb_mode,
                                !busy,
                            ) { onPhysicalCaptureModeChanged(PhysicalCaptureMode.BACKGROUND_USB) }
                            Text(stringResource(R.string.background_usb_description), style = MaterialTheme.typography.bodySmall)
                            Text(
                                when (physicalCaptureMode) {
                                    PhysicalCaptureMode.BACKGROUND_USB -> directUsbState.deviceName
                                        ?: stringResource(R.string.physical_input_missing_usb)
                                    PhysicalCaptureMode.COMPATIBILITY -> deviceNames.ifEmpty {
                                        stringResource(R.string.physical_input_missing)
                                    }
                                    null -> stringResource(R.string.choose_capture_mode)
                                },
                            )
                            if (physicalCaptureMode == PhysicalCaptureMode.BACKGROUND_USB && directUsbState.statusMessage != null) {
                                NoticeCard(
                                    stringResource(
                                        directUsbState.statusMessage.resourceId,
                                        *directUsbState.statusMessage.arguments.toTypedArray(),
                                    ),
                                    if (directUsbState.statusIsError) NoticeTone.WARNING else NoticeTone.SUCCESS,
                                )
                            }
                            OutlinedButton(
                                onClick = onConfigureGamepadMapping,
                                enabled = mappingAvailable && !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.configure_gamepad_mapping)) }
                            Text(stringResource(R.string.mapping_optional_both_modes), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.choose_input_hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!hidCompatible) item { NoticeCard(stringResource(R.string.hid_unavailable), NoticeTone.ERROR) }
            if (visibleHidState.message != null) {
                item {
                    NoticeCard(stringResource(visibleHidState.message.resourceId, *visibleHidState.message.arguments.toTypedArray()), when (visibleHidState.feedbackLevel) {
                        HidFeedbackLevel.ERROR -> NoticeTone.ERROR
                        HidFeedbackLevel.WARNING -> NoticeTone.WARNING
                        HidFeedbackLevel.INFO -> NoticeTone.SUCCESS
                    })
                }
            }
            item {
                if (!connected && !setupComplete && !busy) {
                    Text(stringResource(R.string.complete_session_setup), modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(
                    onClick = if (connected) {
                        if (inputMode == InputMode.TOUCHSCREEN) onOpenTouchController else onOpenMouseTouchpad
                    } else onPlay,
                    enabled = hidCompatible && !busy && (connected || setupComplete),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(when {
                        connected -> R.string.resume_session
                        hidState.pairingModeActive -> R.string.waiting_for_pairing
                        busy -> R.string.preparing_connection
                        else -> R.string.connect_and_play
                    }))
                }
            }
            if (hidState.sessionActive) item {
                OutlinedButton(onClick = onStopHid, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.end_session)) }
            }
        }
    }
    if (panel != null) {
        AlertDialog(
            onDismissRequest = { panel = null },
            title = { Text(stringResource(R.string.settings)) },
            confirmButton = { TextButton(onClick = { panel = null }) { Text(stringResource(R.string.close_action)) } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.virtual_gamepad_settings), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.virtual_gamepad_settings_description))
                    OutlinedButton(
                        onClick = {
                            panel = null
                            onEditTouchscreenLayout()
                        },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.edit_controller_layout)) }
                    HorizontalDivider()
                    Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.language_description))
                    if (onLanguageSettings != null) TextButton(onClick = onLanguageSettings) { Text(stringResource(R.string.change_language)) }
                    HorizontalDivider()
                    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.diagnostic_status, stringResource(hidState.status.labelResource())))
                    Text(stringResource(R.string.diagnostic_version, appVersion, deviceInfo.displayModel, deviceInfo.androidVersion))
                    Text(stringResource(R.string.diagnostic_api, deviceInfo.sdkLevel))
                    Text(
                        stringResource(
                            R.string.diagnostic_metrics,
                            formatMetric(hidState.inputRateHz),
                            formatMetric(hidState.outputRateHz),
                            hidState.lastLatencyMs?.let(::formatMetric) ?: "—",
                            formatMetric(hidState.maxOutputDelayMs),
                        ),
                    )
                    if (physicalGamepadState.devices.isNotEmpty()) {
                        HorizontalDivider()
                        Text(stringResource(R.string.physical_gamepad_diagnostic), style = MaterialTheme.typography.titleMedium)
                        physicalGamepadState.devices.forEach { device ->
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.diagnostic_controller_ids, device.vendorId, device.productId))
                            Text(stringResource(R.string.diagnostic_axes, device.axes.joinToString()))
                            Text(physicalGamepadState.sourceStates[device.sourceId]?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(physicalGamepadState.lastRawEvent, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.diagnostics_technical_note), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCopyDiagnostics) { Text(stringResource(R.string.copy_diagnostics)) }
                    OutlinedButton(onClick = onShareDiagnostics) { Text(stringResource(R.string.share_diagnostics)) }
                }
            },
        )
    }
}

@Composable
private fun SetupCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth())
}

fun HidSessionStatus.labelResource(): Int = when (this) {
    HidSessionStatus.IDLE -> R.string.state_idle
    HidSessionStatus.STARTING, HidSessionStatus.REGISTERING, HidSessionStatus.STOPPING -> R.string.preparing_connection
    HidSessionStatus.READY -> R.string.state_ready
    HidSessionStatus.CONNECTING -> R.string.state_connecting
    HidSessionStatus.CONNECTED -> R.string.state_connected
    HidSessionStatus.ERROR -> R.string.state_error
}

private fun formatMetric(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
