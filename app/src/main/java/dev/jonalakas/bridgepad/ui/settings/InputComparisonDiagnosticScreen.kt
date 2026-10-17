package dev.jonalakas.bridgepad.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.session.TrustedDesktop
import dev.jonalakas.bridgepad.transport.network.InputComparisonPhase
import dev.jonalakas.bridgepad.transport.network.InputComparisonResult
import dev.jonalakas.bridgepad.transport.network.InputTransportMeasurements
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkInputComparisonClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputComparisonDiagnosticScreen(
    desktops: List<TrustedDesktop>,
    requestFor: (String, ConnectionMethod) -> NetworkGamepadRequest?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(desktops.firstOrNull()?.peerIdHex) }
    var routeName by rememberSaveable { mutableStateOf(ConnectionMethod.WIFI.name) }
    var client by remember { mutableStateOf<NetworkInputComparisonClient?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var phase by remember { mutableStateOf(InputComparisonPhase.CONNECTING) }
    var progress by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<InputComparisonResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0L) }

    fun stop() {
        generation++
        client?.close()
        job?.cancel()
        client = null
        job = null
    }

    DisposableEffect(Unit) { onDispose { client?.close() } }
    BackHandler {
        stop()
        onBack()
    }
    val running = client != null
    val desktopUnavailable = stringResource(R.string.synthetic_stream_no_desktop)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.input_comparison_title)) },
                navigationIcon = {
                    IconButton(onClick = { stop(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_action))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.input_comparison_description)) }
            item {
                SettingsSectionCard(stringResource(R.string.synthetic_stream_desktop)) {
                    if (desktops.isEmpty()) Text(desktopUnavailable)
                    desktops.forEach { desktop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedId == desktop.peerIdHex,
                                onClick = { selectedId = desktop.peerIdHex },
                                enabled = !running,
                            )
                            Text(desktop.name)
                        }
                    }
                }
            }
            item {
                SettingsSectionCard(stringResource(R.string.input_comparison_route)) {
                    listOf(ConnectionMethod.WIFI, ConnectionMethod.USB).forEach { method ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = routeName == method.name,
                                onClick = { routeName = method.name },
                                enabled = !running,
                            )
                            Text(stringResource(if (method == ConnectionMethod.WIFI)
                                R.string.wifi_label else R.string.usb_connection_label))
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        if (running) {
                            stop()
                        } else {
                            val target = selectedId?.let { id ->
                                requestFor(id, ConnectionMethod.valueOf(routeName))
                            }
                            if (target == null) {
                                error = desktopUnavailable
                            } else {
                                val created = NetworkInputComparisonClient(target)
                                client = created
                                result = null
                                error = null
                                progress = 0
                                val currentGeneration = ++generation
                                job = scope.launch {
                                    try {
                                        val measured = withContext(Dispatchers.IO) {
                                            created.run { nextPhase, count ->
                                                mainHandler.post {
                                                    if (generation == currentGeneration) {
                                                        phase = nextPhase
                                                        progress = count
                                                    }
                                                }
                                            }
                                        }
                                        if (generation == currentGeneration) result = measured
                                    } catch (failure: Exception) {
                                        if (generation == currentGeneration) {
                                            error = failure.message ?: failure.javaClass.simpleName
                                        }
                                    } finally {
                                        created.close()
                                        if (generation == currentGeneration) {
                                            client = null
                                            job = null
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (running) R.string.input_comparison_stop
                        else R.string.input_comparison_start))
                }
            }
            if (running) item {
                Text(stringResource(R.string.input_comparison_progress, phase.name, progress))
            }
            error?.let { detail -> item {
                SettingsSectionCard(stringResource(R.string.state_error)) { Text(detail) }
            } }
            result?.let { comparison ->
                item {
                    SettingsSectionCard(stringResource(R.string.input_comparison_results)) {
                        Text(stringResource(R.string.input_comparison_actual_route, comparison.route))
                        Text(stringResource(R.string.input_comparison_tcp))
                        Text(comparison.tcp.summary())
                        Text(stringResource(R.string.input_comparison_udp))
                        Text(comparison.udp.summary())
                        if (comparison.udp.received > 0) {
                            val fasterP95 = if (comparison.udp.p95Micros < comparison.tcp.p95Micros)
                                "UDP+AEAD" else "TCP/TLS"
                            Text(stringResource(R.string.input_comparison_lower_p95, fasterP95))
                        }
                        if (comparison.udpStoppedEarly) {
                            Text(stringResource(R.string.input_comparison_udp_aborted))
                        }
                        Text(stringResource(R.string.input_comparison_interpretation))
                        Button(onClick = {
                            val report = comparison.report()
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("BridgePad input comparison", report))
                            Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.copy_diagnostics)) }
                    }
                }
            }
        }
    }
}

private fun InputTransportMeasurements.summary(): String = String.format(
    Locale.getDefault(),
    "%d/%d · RTT p50/p95/p99/max: %.2f/%.2f/%.2f/%.2f ms · >16/>33/>50 ms: %d/%d/%d · %d ms",
    received, attempted, p50Micros / 1_000.0, p95Micros / 1_000.0,
    p99Micros / 1_000.0, maxMicros / 1_000.0,
    over16Millis, over33Millis, over50Millis, elapsedMillis,
)

private fun InputComparisonResult.report(): String = buildString {
    appendLine("BridgePad IP input transport comparison")
    appendLine("Route: $route")
    appendLine("TCP/TLS: ${tcp.summary()}")
    appendLine("UDP+AEAD: ${udp.summary()}")
    appendLine("UDP stopped early: $udpStoppedEarly")
    if (udp.received > 0) {
        appendLine("Lower p95 RTT: ${if (udp.p95Micros < tcp.p95Micros) "UDP+AEAD" else "TCP/TLS"}")
    }
    appendLine("Transport echo RTT only; not Android-to-game presentation latency.")
}
