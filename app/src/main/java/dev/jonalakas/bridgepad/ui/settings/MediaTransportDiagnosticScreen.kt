package dev.jonalakas.bridgepad.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.streaming.MediaTransportKind
import dev.jonalakas.bridgepad.streaming.MediaTransportProbeResult
import dev.jonalakas.bridgepad.streaming.MediaTransportProbeStatus
import dev.jonalakas.bridgepad.streaming.QuicMediaProbe
import dev.jonalakas.bridgepad.streaming.UdpAeadMediaProbe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTransportDiagnosticScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var host by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableStateOf("5") }
    var status by remember {
        mutableStateOf<MediaTransportProbeStatus>(MediaTransportProbeStatus.Idle)
    }
    var probe by remember { mutableStateOf<AutoCloseable?>(null) }
    var generation by remember { mutableLongStateOf(0L) }

    fun stop() {
        generation += 1
        probe?.close()
        probe = null
    }

    fun start(transport: MediaTransportKind) {
        val parsedDuration = duration.toIntOrNull()
        if (host.isBlank() || parsedDuration == null) {
            status = MediaTransportProbeStatus.Failed("invalid_parameters")
            return
        }
        stop()
        val currentGeneration = ++generation
        val callback: (MediaTransportProbeStatus) -> Unit = { update ->
            mainHandler.post {
                if (generation == currentGeneration) status = update
            }
        }
        val created = when (transport) {
            MediaTransportKind.UDP_AEAD -> UdpAeadMediaProbe(
                host = host,
                port = UDP_AEAD_PORT,
                durationSeconds = parsedDuration,
                onStatus = callback,
            )
            MediaTransportKind.QUIC_DATAGRAM -> QuicMediaProbe(
                host = host,
                port = QUIC_DATAGRAM_PORT,
                durationSeconds = parsedDuration,
                onStatus = callback,
            )
        }
        probe = created
        when (created) {
            is UdpAeadMediaProbe -> created.start()
            is QuicMediaProbe -> created.start()
        }
    }

    DisposableEffect(Unit) { onDispose(::stop) }
    BackHandler {
        stop()
        onBack()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.media_transport_test_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        stop()
                        onBack()
                    }) {
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
            item { Text(stringResource(R.string.media_transport_test_description)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text(stringResource(R.string.network_host)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.media_transport_test_duration)) },
                            supportingText = {
                                Text(stringResource(R.string.media_transport_test_duration_hint))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                val running = status is MediaTransportProbeStatus.Connecting ||
                    status is MediaTransportProbeStatus.Receiving
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (running) {
                        Button(
                            onClick = {
                                stop()
                                status = MediaTransportProbeStatus.Stopped
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.media_transport_test_stop))
                        }
                    } else {
                        Button(
                            onClick = { start(MediaTransportKind.QUIC_DATAGRAM) },
                            enabled = host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.media_transport_test_start_quic))
                        }
                        Button(
                            onClick = { start(MediaTransportKind.UDP_AEAD) },
                            enabled = host.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.media_transport_test_start_udp))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.media_transport_test_status),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(status.label())
                        (status as? MediaTransportProbeStatus.Completed)?.result?.let { result ->
                            ProbeResult(result)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTransportProbeStatus.label(): String = when (this) {
    MediaTransportProbeStatus.Idle -> stringResource(R.string.media_transport_test_idle)
    MediaTransportProbeStatus.Connecting -> stringResource(R.string.media_transport_test_connecting)
    is MediaTransportProbeStatus.Receiving -> stringResource(
        R.string.media_transport_test_receiving,
        localAddress,
        interfaceName,
    )
    is MediaTransportProbeStatus.Completed -> stringResource(R.string.media_transport_test_completed)
    is MediaTransportProbeStatus.Failed -> stringResource(R.string.media_transport_test_failed, detail)
    MediaTransportProbeStatus.Stopped -> stringResource(R.string.media_transport_test_stopped)
}

@Composable
private fun ProbeResult(result: MediaTransportProbeResult) {
    Text(
        stringResource(
            R.string.media_transport_test_transport,
            stringResource(
                when (result.transport) {
                    MediaTransportKind.UDP_AEAD -> R.string.media_transport_udp_aead
                    MediaTransportKind.QUIC_DATAGRAM -> R.string.media_transport_quic
                },
            ),
        ),
    )
    Text(
        stringResource(
            R.string.media_transport_test_route,
            result.localAddress,
            result.interfaceName,
        ),
    )
    Text(
        stringResource(
            R.string.media_transport_test_packets,
            result.receivedPackets,
            result.expectedPackets,
            result.rejectedPackets,
            result.duplicatePackets,
        ),
    )
    Text(
        stringResource(
            R.string.media_transport_test_frames,
            result.completedFrames,
            result.expectedFrames,
            result.expiredFrames,
        ),
    )
    Text(
        stringResource(
            R.string.media_transport_test_gaps,
            result.arrivalGapP95Micros,
            result.arrivalGapP99Micros,
            result.arrivalGapMaxMicros,
        ),
    )
    Text(
        stringResource(
            R.string.media_transport_test_bytes,
            result.receivedBytes,
            result.elapsedMillis,
        ),
    )
    result.handshakeMillis?.let { handshakeMillis ->
        Text(stringResource(R.string.media_transport_test_handshake, handshakeMillis))
    }
}

private const val UDP_AEAD_PORT = 39495
private const val QUIC_DATAGRAM_PORT = 39496
