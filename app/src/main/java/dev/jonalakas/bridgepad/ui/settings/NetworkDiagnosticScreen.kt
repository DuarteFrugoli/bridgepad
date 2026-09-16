package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.transport.network.NetworkProbeRequest
import dev.jonalakas.bridgepad.transport.network.NetworkProbeResult
import dev.jonalakas.bridgepad.transport.network.NetworkTlsProbe
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticScreen(
    onBack: () -> Unit,
    gameplayStatus: NetworkGamepadStatus,
    touchscreenLayout: TouchscreenLayout,
    onStartGameplay: (NetworkGamepadRequest) -> Unit,
    onStopGameplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("39393") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<NetworkProbeResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (gameplayStatus is NetworkGamepadStatus.Active) {
        TouchscreenGamepadScreen(
            layout = touchscreenLayout,
            onExit = onStopGameplay,
            modifier = modifier,
        )
        return
    }
    BackHandler(onBack = onBack)
    val connectingGamepad = gameplayStatus is NetworkGamepadStatus.Connecting
    val validatedPort = port.toIntOrNull()?.takeIf { it in 1..65_535 }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_diagnostic_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.back_action))
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
                Text(stringResource(R.string.network_diagnostic_description))
            }
            item {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.network_port)) },
                    singleLine = true,
                    enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    label = { Text(stringResource(R.string.network_certificate_fingerprint)) },
                    supportingText = { Text(stringResource(R.string.network_fingerprint_hint)) },
                    minLines = 3,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = {
                        running = true
                        result = null
                        error = null
                        scope.launch {
                            runCatching {
                                val request = NetworkProbeRequest(
                                    host = host,
                                    port = port.toIntOrNull()
                                        ?: throw IllegalArgumentException("Invalid port"),
                                    certificateSha256 = fingerprint,
                                )
                                withContext(Dispatchers.IO) { NetworkTlsProbe.run(request) }
                            }.onSuccess { probeResult ->
                                result = probeResult
                            }.onFailure { failure ->
                                error = failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName
                            }
                            running = false
                        }
                    },
                    enabled = !running && host.isNotBlank() && port.isNotBlank() && fingerprint.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        stringResource(
                            if (running) R.string.network_test_running else R.string.network_test_action,
                        ),
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        error = null
                        onStartGameplay(
                            NetworkGamepadRequest(
                                host = host,
                                port = requireNotNull(validatedPort),
                                certificateSha256 = fingerprint,
                            ),
                        )
                    },
                    enabled = !running && !connectingGamepad && host.isNotBlank() &&
                        validatedPort != null && fingerprint.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (connectingGamepad) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        stringResource(
                            if (connectingGamepad) {
                                R.string.network_gameplay_connecting
                            } else {
                                R.string.network_gameplay_action
                            },
                        ),
                    )
                }
            }
            result?.let { probeResult ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.network_test_success),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.network_test_result,
                                    probeResult.tlsVersion,
                                    format(probeResult.connectAndHandshakeMillis),
                                    format(probeResult.rttP50Millis),
                                    format(probeResult.rttP95Millis),
                                    format(probeResult.rttP99Millis),
                                    probeResult.samples,
                                ),
                            )
                            Text(
                                probeResult.cipherSuite,
                                style = MaterialTheme.typography.bodySmall,
                            )
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
                                stringResource(R.string.network_test_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(message)
                        }
                    }
                }
            }
            (gameplayStatus as? NetworkGamepadStatus.Failed)?.let { failure ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(R.string.network_gameplay_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(failure.detail)
                        }
                    }
                }
            }
        }
    }
}

private fun format(value: Double): String = String.format(Locale.getDefault(), "%.3f", value)
