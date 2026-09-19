package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.SessionStatus
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.session.SessionState
import dev.jonalakas.bridgepad.ui.components.SessionOrientationSelector
import dev.jonalakas.bridgepad.ui.session.SessionOrientationMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    hidState: SessionState,
    physicalGamepadState: PhysicalGamepadState,
    physicalControllerConnected: Boolean,
    mappingAvailable: Boolean,
    sessionOrientationMode: SessionOrientationMode,
    invertedTouchpadScroll: Boolean,
    onEditTouchscreenLayout: () -> Unit,
    onConfigureGamepadMapping: () -> Unit,
    onSessionOrientationModeChanged: (SessionOrientationMode) -> Unit,
    onInvertedTouchpadScrollChanged: (Boolean) -> Unit,
    onOpenNetworkDiagnostic: () -> Unit,
    onOpenBluetoothDesktopDiagnostic: () -> Unit,
    onLanguageSettings: (() -> Unit)?,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsCard(title = stringResource(R.string.session_orientation)) {
                    Text(stringResource(R.string.session_orientation_description))
                    SessionOrientationSelector(
                        selected = sessionOrientationMode,
                        onSelected = onSessionOrientationModeChanged,
                    )
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.virtual_gamepad_settings)) {
                    Text(stringResource(R.string.virtual_gamepad_settings_description))
                    OutlinedButton(
                        onClick = onEditTouchscreenLayout,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.edit_controller_layout))
                    }
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.touchpad_settings)) {
                    Text(stringResource(R.string.touchpad_settings_description))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.invert_touchpad_scroll),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = invertedTouchpadScroll,
                            onCheckedChange = onInvertedTouchpadScrollChanged,
                        )
                    }
                }
            }
            if (physicalControllerConnected) {
                item {
                    SettingsCard(title = stringResource(R.string.physical_gamepad_settings)) {
                        Text(stringResource(R.string.physical_gamepad_settings_description))
                        OutlinedButton(
                            onClick = onConfigureGamepadMapping,
                            enabled = mappingAvailable,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.configure_gamepad_mapping))
                        }
                    }
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.network_diagnostic_title)) {
                    Text(stringResource(R.string.network_diagnostic_settings_description))
                    OutlinedButton(
                        onClick = onOpenNetworkDiagnostic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_network_diagnostic))
                    }
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.bluetooth_desktop_test_title)) {
                    Text(stringResource(R.string.bluetooth_desktop_test_settings_description))
                    OutlinedButton(
                        onClick = onOpenBluetoothDesktopDiagnostic,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.bluetooth_desktop_test_open))
                    }
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.language_title)) {
                    Text(stringResource(R.string.language_description))
                    if (onLanguageSettings != null) {
                        OutlinedButton(
                            onClick = onLanguageSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.change_language))
                        }
                    }
                }
            }
            item {
                SettingsCard(title = stringResource(R.string.diagnostics)) {
                    Text(stringResource(R.string.diagnostic_status, stringResource(hidState.status.labelResource())))
                    Text(
                        stringResource(
                            R.string.diagnostic_version,
                            appVersion,
                            deviceInfo.displayModel,
                            deviceInfo.androidVersion,
                        ),
                    )
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
                    Text(
                        stringResource(
                            R.string.diagnostic_pointer_metrics,
                            formatMetric(hidState.pointerInputRateHz),
                            formatMetric(hidState.pointerOutputRateHz),
                            hidState.pointerRejectedReports,
                            hidState.pointerPendingReports,
                        ),
                    )
                    if (physicalGamepadState.devices.isNotEmpty()) {
                        Text(
                            stringResource(R.string.physical_gamepad_diagnostic),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        physicalGamepadState.devices.forEach { device ->
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.diagnostic_controller_ids, device.vendorId, device.productId))
                            Text(stringResource(R.string.diagnostic_axes, device.axes.joinToString()))
                            Text(
                                physicalGamepadState.sourceStates[device.sourceId]?.toString().orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(physicalGamepadState.lastRawEvent, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.diagnostics_technical_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.copy_diagnostics))
                    }
                    OutlinedButton(
                        onClick = onShareDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.share_diagnostics))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun SessionStatus.labelResource(): Int = when (this) {
    SessionStatus.IDLE -> R.string.state_idle
    SessionStatus.STARTING, SessionStatus.REGISTERING, SessionStatus.STOPPING -> R.string.preparing_connection
    SessionStatus.READY -> R.string.state_ready
    SessionStatus.CONNECTING -> R.string.state_connecting
    SessionStatus.CONNECTED -> R.string.state_connected
    SessionStatus.ERROR -> R.string.state_error
}

private fun formatMetric(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)
