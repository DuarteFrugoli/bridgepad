package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.SessionStatus
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.session.SessionState
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    hidState: SessionState,
    physicalGamepadState: PhysicalGamepadState,
    onOpenNetworkDiagnostic: () -> Unit,
    onOpenUsbNetworkDiagnostic: () -> Unit,
    onOpenBluetoothDesktopDiagnostic: () -> Unit,
    onOpenUsbAccessoryDiagnostic: () -> Unit,
    onOpenDesktopStreaming: () -> Unit,
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
                title = { Text(stringResource(R.string.tests_title)) },
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
                SettingsSectionCard(title = stringResource(R.string.network_diagnostic_title)) {
                    Text(stringResource(R.string.network_diagnostic_settings_description))
                    TestActionButton(stringResource(R.string.open_network_diagnostic), onOpenNetworkDiagnostic)
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.usb_network_test_title)) {
                    Text(stringResource(R.string.usb_network_test_settings_description))
                    TestActionButton(stringResource(R.string.usb_network_test_open), onOpenUsbNetworkDiagnostic)
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.bluetooth_desktop_test_title)) {
                    Text(stringResource(R.string.bluetooth_desktop_test_settings_description))
                    TestActionButton(
                        stringResource(R.string.bluetooth_desktop_test_open),
                        onOpenBluetoothDesktopDiagnostic,
                    )
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.usb_accessory_test_title)) {
                    Text(stringResource(R.string.usb_accessory_test_settings_description))
                    TestActionButton(stringResource(R.string.usb_accessory_test_open), onOpenUsbAccessoryDiagnostic)
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.synthetic_stream_title)) {
                    Text(stringResource(R.string.synthetic_stream_settings_description))
                    TestActionButton(stringResource(R.string.synthetic_stream_open), onOpenDesktopStreaming)
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.diagnostics)) {
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
                    TestActionButton(stringResource(R.string.copy_diagnostics), onCopyDiagnostics)
                    TestActionButton(stringResource(R.string.share_diagnostics), onShareDiagnostics)
                }
            }
        }
    }
}

@Composable
private fun TestActionButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label)
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
