package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.session.PairedHost
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopProbe
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopProbeRequest
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopProbeResult
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothDesktopDiagnosticScreen(
    pairedHosts: List<PairedHost>,
    gameplaySessionActive: Boolean,
    probe: BluetoothDesktopProbe,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(pairedHosts.singleOrNull()?.address) }
    var runningTransport by remember { mutableStateOf<BluetoothDesktopTransport?>(null) }
    var rfcommResult by remember { mutableStateOf<BluetoothDesktopProbeResult?>(null) }
    var bleResult by remember { mutableStateOf<BluetoothDesktopProbeResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = runningTransport == null, onBack = onBack)

    fun run(transport: BluetoothDesktopTransport) {
        val address = selectedAddress ?: return
        runningTransport = transport
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    probe.run(BluetoothDesktopProbeRequest(address, transport))
                }
            }.onSuccess { result ->
                when (transport) {
                    BluetoothDesktopTransport.RFCOMM -> rfcommResult = result
                    BluetoothDesktopTransport.BLE_GATT -> bleResult = result
                }
            }.onFailure { failure ->
                error = failure.message ?: failure.cause?.message ?: failure.javaClass.simpleName
            }
            runningTransport = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bluetooth_desktop_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = runningTransport == null) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
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
            item { Text(stringResource(R.string.bluetooth_desktop_test_description)) }
            if (gameplaySessionActive) {
                item { DiagnosticWarning(stringResource(R.string.bluetooth_desktop_test_session_active)) }
            }
            if (pairedHosts.isEmpty()) {
                item { DiagnosticWarning(stringResource(R.string.bluetooth_desktop_test_no_pc)) }
            } else {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.bluetooth_desktop_test_choose_pc),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            pairedHosts.forEach { host ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedAddress == host.address,
                                        onClick = { selectedAddress = host.address },
                                        enabled = runningTransport == null,
                                    )
                                    Text(host.name)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { run(BluetoothDesktopTransport.RFCOMM) },
                    enabled = selectedAddress != null && runningTransport == null && !gameplaySessionActive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (runningTransport == BluetoothDesktopTransport.RFCOMM) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.bluetooth_desktop_test_rfcomm))
                }
            }
            item {
                Button(
                    onClick = { run(BluetoothDesktopTransport.BLE_GATT) },
                    enabled = selectedAddress != null && runningTransport == null && !gameplaySessionActive,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (runningTransport == BluetoothDesktopTransport.BLE_GATT) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.bluetooth_desktop_test_ble))
                }
            }
            rfcommResult?.let { result -> item { ProbeResultCard(result) } }
            bleResult?.let { result -> item { ProbeResultCard(result) } }
            if (rfcommResult != null && bleResult != null) {
                item {
                    val rfcomm = requireNotNull(rfcommResult)
                    val ble = requireNotNull(bleResult)
                    val preferred = if (rfcomm.lossPercent < ble.lossPercent ||
                        rfcomm.lossPercent == ble.lossPercent && rfcomm.rttP95Millis <= ble.rttP95Millis
                    ) {
                        "RFCOMM"
                    } else {
                        "BLE GATT"
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.bluetooth_desktop_test_comparison),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.bluetooth_desktop_test_preferred, preferred))
                        }
                    }
                }
            }
            error?.let { message ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.bluetooth_desktop_test_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeResultCard(result: BluetoothDesktopProbeResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(result.transport.displayName(), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.bluetooth_desktop_test_connection_result,
                    format(result.connectMillis),
                ),
            )
            Text(
                stringResource(
                    R.string.bluetooth_desktop_test_delivery_result,
                    result.samplesReceived,
                    result.samplesAccepted,
                    format(result.lossPercent),
                    result.rejectedWrites,
                ),
            )
            Text(
                stringResource(
                    R.string.bluetooth_desktop_test_rate_result,
                    format(result.reportRateHz),
                    format(result.maximumReceiveGapMillis),
                ),
            )
            Text(
                stringResource(
                    R.string.bluetooth_desktop_test_latency_result,
                    format(result.rttP50Millis),
                    format(result.rttP95Millis),
                    format(result.rttP99Millis),
                ),
            )
        }
    }
}

@Composable
private fun DiagnosticWarning(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun BluetoothDesktopTransport.displayName(): String = when (this) {
    BluetoothDesktopTransport.RFCOMM -> "RFCOMM"
    BluetoothDesktopTransport.BLE_GATT -> "BLE GATT"
}

private fun format(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)
