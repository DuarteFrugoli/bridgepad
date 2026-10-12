package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import dev.jonalakas.bridgepad.diagnostics.DeviceInfo
import dev.jonalakas.bridgepad.session.DiscoveredDesktop
import dev.jonalakas.bridgepad.session.DiscoveryDiagnosticEvent
import dev.jonalakas.bridgepad.session.DesktopAvailability
import dev.jonalakas.bridgepad.session.DesktopAvailabilityStatus
import dev.jonalakas.bridgepad.session.TrustedDesktop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryDiagnosticScreen(
    appVersion: String,
    deviceInfo: DeviceInfo,
    events: List<DiscoveryDiagnosticEvent>,
    discovered: List<DiscoveredDesktop>,
    trusted: List<TrustedDesktop>,
    availability: Map<String, DesktopAvailability>,
    discoveryError: String?,
    onMark: () -> Unit,
    onClear: () -> Unit,
    onCopyReport: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discovery_test_title)) },
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
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSectionCard(title = stringResource(R.string.discovery_test_state)) {
                    Text(stringResource(R.string.discovery_test_explanation))
                    Text(stringResource(R.string.discovery_test_visible, discovered.size))
                    discovered.forEach { desktop ->
                        Text("${desktop.name} · ${desktop.endpointHosts.joinToString()} :${desktop.port}")
                    }
                    if (discoveryError != null) {
                        Text(
                            stringResource(R.string.discovery_test_error, discoveryError),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.discovery_test_saved, trusted.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    trusted.forEach { desktop ->
                        val label = when (availability[desktop.peerIdHex]?.status) {
                            DesktopAvailabilityStatus.ONLINE -> R.string.network_desktop_available
                            DesktopAvailabilityStatus.OFFLINE -> R.string.network_desktop_offline
                            else -> R.string.network_desktop_checking
                        }
                        Text(stringResource(label, desktop.name))
                    }
                    Text(
                        stringResource(R.string.discovery_test_privacy),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            onCopyReport(
                                DiscoveryDiagnosticReport.create(
                                    appVersion, deviceInfo, events, discovered, trusted, availability,
                                    discoveryError,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.copy_diagnostics))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onMark, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.discovery_test_mark))
                        }
                        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.discovery_test_clear))
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.discovery_test_events, events.size),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(events.asReversed()) { event ->
                Text(
                    DiscoveryDiagnosticReport.formatEvent(event),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal object DiscoveryDiagnosticReport {
    fun create(
        appVersion: String,
        deviceInfo: DeviceInfo,
        events: List<DiscoveryDiagnosticEvent>,
        discovered: List<DiscoveredDesktop>,
        trusted: List<TrustedDesktop>,
        availability: Map<String, DesktopAvailability>,
        discoveryError: String?,
    ): String = buildString {
        appendLine("BridgePad discovery diagnostic")
        appendLine("App version: $appVersion")
        appendLine("Device: ${deviceInfo.displayModel}")
        appendLine("Android: ${deviceInfo.androidVersion} (API ${deviceInfo.sdkLevel})")
        appendLine("Captured: ${formatTime(System.currentTimeMillis())}")
        appendLine("Discovery error: ${discoveryError ?: "none"}")
        appendLine("Visible desktops: ${discovered.size}")
        discovered.forEach { desktop ->
            appendLine("  ${desktop.name}: ${desktop.endpointHosts.joinToString()} :${desktop.port}")
        }
        appendLine("Saved desktops: ${trusted.size}")
        trusted.forEach { desktop ->
            val verified = availability[desktop.peerIdHex]
            appendLine("  ${desktop.name}: ${desktop.endpointHosts.joinToString()} :${desktop.port}; " +
                "reachability=${verified?.status ?: "CHECKING"}; verifiedHost=${verified?.verifiedHost ?: "none"}")
        }
        appendLine()
        appendLine("Android NSD and TLS reachability events (oldest first):")
        if (events.isEmpty()) appendLine("  none")
        events.forEach { appendLine(formatEvent(it)) }
    }

    fun formatEvent(event: DiscoveryDiagnosticEvent): String =
        "${formatTime(event.timestampMillis)} elapsed=${event.elapsedRealtimeMillis}ms " +
            "generation=${event.generation} ${event.detail}"

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMillis))
}
