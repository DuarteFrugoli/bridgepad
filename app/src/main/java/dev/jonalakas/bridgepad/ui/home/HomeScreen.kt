package dev.jonalakas.bridgepad.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.output.hid.HidSessionState
import dev.jonalakas.bridgepad.output.hid.HidSessionStatus
import dev.jonalakas.bridgepad.output.hid.HidFeedbackLevel
import dev.jonalakas.bridgepad.ui.components.NoticeCard
import dev.jonalakas.bridgepad.ui.components.NoticeTone
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    bluetoothPermissionGranted: Boolean,
    hidState: HidSessionState,
    onRequestPermissions: () -> Unit,
    onStartHid: () -> Unit,
    onConnect: (String) -> Unit,
    onPairNewPc: () -> Unit,
    onSendTestButton: () -> Unit,
    onStopHid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.hid_spike_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.hid_spike_description),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                InfoCard(
                    title = stringResource(R.string.bluetooth_hid),
                    rows = listOf(
                        stringResource(R.string.permission_label) to
                            if (bluetoothPermissionGranted) "Granted" else "Required",
                        stringResource(R.string.session_state_label) to hidState.status.name,
                        stringResource(R.string.bluetooth_label) to
                            if (hidState.bluetoothEnabled) "Enabled" else "Not confirmed",
                        stringResource(R.string.host_label) to (hidState.connectedHost ?: "Not connected"),
                    ),
                )
            }
            item {
                when (hidState.feedbackLevel) {
                    HidFeedbackLevel.WARNING -> NoticeCard(
                        message = hidState.message,
                        tone = NoticeTone.WARNING,
                    )
                    HidFeedbackLevel.ERROR -> NoticeCard(
                        message = hidState.message,
                        tone = NoticeTone.ERROR,
                    )
                    HidFeedbackLevel.INFO -> Text(
                        text = hidState.message,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (!bluetoothPermissionGranted) {
                item {
                    Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_permissions))
                    }
                }
            } else if (!hidState.sessionActive) {
                item {
                    Button(onClick = onStartHid, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.start_hid_spike))
                    }
                }
            }
            if (
                hidState.sessionActive &&
                (hidState.status == HidSessionStatus.READY || hidState.status == HidSessionStatus.CONNECTING)
            ) {
                if (!hidState.pairingModeActive) {
                    item {
                        Button(onClick = onPairNewPc, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.pair_new_pc))
                        }
                    }
                }
                if (hidState.pairedHosts.isEmpty() && !hidState.pairingModeActive) {
                    item {
                        NoticeCard(
                            message = stringResource(R.string.no_paired_computers),
                            tone = NoticeTone.WARNING,
                        )
                    }
                }
                items(hidState.pairedHosts, key = { it.address }) { host ->
                    OutlinedButton(
                        onClick = { onConnect(host.address) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Connect to ${host.name}")
                    }
                }
            }
            if (hidState.status == HidSessionStatus.CONNECTED) {
                item {
                    Button(onClick = onSendTestButton, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.send_test_button))
                    }
                }
            }
            if (hidState.sessionActive) {
                item {
                    OutlinedButton(onClick = onStopHid, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.stop_hid_spike))
                    }
                }
            }
            item {
                InfoCard(
                    title = stringResource(R.string.app_information),
                    rows = listOf(
                        stringResource(R.string.version_label) to appVersion,
                        stringResource(R.string.status_label) to stringResource(R.string.status_early_development),
                    ),
                )
            }
            item {
                InfoCard(
                    title = stringResource(R.string.device_information),
                    rows = listOf(
                        stringResource(R.string.device_label) to deviceInfo.displayModel,
                        stringResource(R.string.android_label) to deviceInfo.androidVersion,
                        stringResource(R.string.api_level_label) to deviceInfo.sdkLevel.toString(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) ->
                Text(
                    text = "$label: $value",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    BridgePadTheme {
        HomeScreen(
            appVersion = "0.1.0",
            deviceInfo = DeviceInfo(
                manufacturer = "Samsung",
                model = "Galaxy A35",
                androidVersion = "16",
                sdkLevel = 36,
            ),
            bluetoothPermissionGranted = true,
            hidState = HidSessionState(
                status = HidSessionStatus.READY,
                bluetoothEnabled = true,
                message = "HID gamepad registered. Select a paired PC.",
            ),
            onRequestPermissions = {},
            onStartHid = {},
            onConnect = {},
            onPairNewPc = {},
            onSendTestButton = {},
            onStopHid = {},
        )
    }
}
