package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.transport.usb.accessory.UsbAccessoryProbe
import dev.jonalakas.bridgepad.transport.usb.accessory.UsbAccessoryProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbAccessoryDiagnosticScreen(
    probe: UsbAccessoryProbe,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UsbAccessoryProbeResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = !running, onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.usb_accessory_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !running) {
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.usb_accessory_test_description)) }
            item {
                Button(
                    onClick = {
                        running = true
                        result = null
                        error = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { probe.run() } }
                                .onSuccess { result = it }
                                .onFailure {
                                    error = it.cause?.message ?: it.message ?: it.javaClass.simpleName
                                }
                            running = false
                        }
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(if (running) R.string.usb_accessory_test_running else R.string.usb_accessory_test_action))
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
                                stringResource(R.string.usb_accessory_test_success),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.usb_accessory_test_result,
                                    probeResult.samples,
                                    probeResult.lostSamples,
                                    formatUsbMetric(probeResult.reportRateHz),
                                    formatUsbMetric(probeResult.rttP50Millis),
                                    formatUsbMetric(probeResult.rttP95Millis),
                                    formatUsbMetric(probeResult.rttP99Millis),
                                ),
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
                                stringResource(R.string.usb_accessory_test_failed),
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

private fun formatUsbMetric(value: Double): String =
    String.format(Locale.getDefault(), "%.3f", value)
