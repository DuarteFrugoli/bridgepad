package dev.jonalakas.bridgepad.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.session.TrustedDesktop
import dev.jonalakas.bridgepad.streaming.AndroidPlayableMediaClient
import dev.jonalakas.bridgepad.streaming.PlayableMediaTransport
import dev.jonalakas.bridgepad.streaming.UdpPlaybackDiagnosticConfig
import dev.jonalakas.bridgepad.streaming.UdpRecoveryDiagnosticMode
import dev.jonalakas.bridgepad.streaming.UdpRenderDiagnosticSnapshot
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import kotlinx.coroutines.delay

/** Real paired UDP stream, without input, for a repeatable recovery A/B comparison. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UdpRecoveryDiagnosticScreen(
    desktops: List<TrustedDesktop>,
    requestFor: (String) -> NetworkMediaRequest?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val desktopUnavailable = stringResource(R.string.synthetic_stream_no_desktop)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(desktops.firstOrNull()?.peerIdHex) }
    var relaxed by rememberSaveable { mutableStateOf(true) }
    var injectLoss by rememberSaveable { mutableStateOf(false) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var client by remember { mutableStateOf<AndroidPlayableMediaClient?>(null) }
    var status by remember { mutableStateOf<NetworkMediaStatus>(NetworkMediaStatus.Idle) }
    var result by remember { mutableStateOf(UdpRenderDiagnosticSnapshot()) }
    var generation by remember { mutableStateOf(0L) }
    var lastMode by remember { mutableStateOf(UdpRecoveryDiagnosticMode.RELAXED) }
    var lastInjection by remember { mutableStateOf(false) }

    fun stop() {
        generation++
        result = client?.diagnosticSnapshot() ?: result
        client?.close()
        client = null
    }

    DisposableEffect(Unit) {
        onDispose { client?.close() }
    }
    BackHandler {
        stop()
        onBack()
    }
    LaunchedEffect(client) {
        while (client != null) {
            delay(1_000)
            client?.diagnosticSnapshot()?.let { result = it }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.udp_recovery_test_title)) },
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
            item { Text(stringResource(R.string.udp_recovery_test_description)) }
            item {
                SettingsSectionCard(stringResource(R.string.synthetic_stream_desktop)) {
                    if (desktops.isEmpty()) Text(stringResource(R.string.synthetic_stream_no_desktop))
                    desktops.forEach { desktop ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedId == desktop.peerIdHex,
                                onClick = { selectedId = desktop.peerIdHex },
                                enabled = client == null,
                            )
                            Text(desktop.name)
                        }
                    }
                }
            }
            item {
                SettingsSectionCard(stringResource(R.string.udp_recovery_test_policy)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !relaxed,
                            onClick = { relaxed = false },
                            enabled = client == null,
                        )
                        Text(stringResource(R.string.udp_recovery_test_strict))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = relaxed,
                            onClick = { relaxed = true },
                            enabled = client == null,
                        )
                        Text(stringResource(R.string.udp_recovery_test_relaxed))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = injectLoss,
                            onCheckedChange = { injectLoss = it },
                            enabled = client == null,
                        )
                        Text(stringResource(R.string.udp_recovery_test_inject))
                    }
                    Text(stringResource(R.string.udp_recovery_test_inject_note))
                }
            }
            item {
                Button(
                    onClick = {
                        if (client != null) {
                            stop()
                            status = NetworkMediaStatus.Stopped
                        } else {
                            val peerId = selectedId
                            val target = surface
                            if (peerId == null || target == null || requestFor(peerId) == null) {
                                status = NetworkMediaStatus.Failed(desktopUnavailable)
                            } else {
                                val mode = if (relaxed) UdpRecoveryDiagnosticMode.RELAXED
                                    else UdpRecoveryDiagnosticMode.STRICT
                                lastMode = mode
                                lastInjection = injectLoss
                                result = UdpRenderDiagnosticSnapshot()
                                val currentGeneration = ++generation
                                val created = AndroidPlayableMediaClient(
                                    requestProvider = { requestFor(peerId) },
                                    transport = PlayableMediaTransport.UDP_AEAD,
                                    surface = target,
                                    onStatus = { update ->
                                        mainHandler.post {
                                            if (generation == currentGeneration) status = update
                                        }
                                    },
                                    udpDiagnosticConfig = UdpPlaybackDiagnosticConfig(
                                        mode = mode,
                                        dropEveryNFrames = if (injectLoss) 300 else 0,
                                    ),
                                )
                                client = created
                                created.start()
                            }
                        }
                    },
                    enabled = client != null || (selectedId != null && surface != null),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(
                        if (client == null) R.string.udp_recovery_test_start
                        else R.string.media_transport_test_stop,
                    ))
                }
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { viewContext ->
                            SurfaceView(viewContext).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surface = holder.surface
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        if (surface === holder.surface) surface = null
                                        client?.detachVideoSurface(holder.surface)
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (status !is NetworkMediaStatus.Active) {
                        Text(
                            text = when (val current = status) {
                                NetworkMediaStatus.Idle -> stringResource(R.string.synthetic_stream_idle)
                                NetworkMediaStatus.Connecting -> stringResource(R.string.synthetic_stream_connecting)
                                NetworkMediaStatus.Stopped -> stringResource(R.string.synthetic_stream_stopped)
                                is NetworkMediaStatus.Failed -> current.detail
                                is NetworkMediaStatus.Active -> ""
                            },
                            color = Color.White,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                SettingsSectionCard(stringResource(R.string.udp_recovery_test_results)) {
                    Text(stringResource(R.string.udp_recovery_test_mode_result, lastMode.name))
                    Text(stringResource(R.string.udp_recovery_test_frames, result.renderedFrames, result.measuredMillis))
                    Text(stringResource(R.string.udp_recovery_test_percentiles, result.gapP95Millis, result.gapP99Millis, result.longestGapMillis))
                    Text(stringResource(R.string.udp_recovery_test_stalls, result.gapsOver33Millis, result.gapsOver50Millis, result.gapsOver100Millis))
                    val stallsPerMinute = if (result.measuredMillis > 0)
                        result.gapsOver50Millis * 60_000.0 / result.measuredMillis else 0.0
                    Text(stringResource(
                        R.string.udp_recovery_test_stalls_per_minute,
                        String.format("%.1f", stallsPerMinute),
                    ))
                    Text(stringResource(R.string.udp_recovery_test_events, result.injectedDrops, result.decoderFlushes, result.queueOverflows))
                    Button(
                        onClick = {
                            val report = buildString {
                                appendLine("BridgePad UDP recovery A/B diagnostic")
                                appendLine("Mode: $lastMode; injection every 300 eligible P frames: $lastInjection")
                                appendLine("Rendered: ${result.renderedFrames} in ${result.measuredMillis} ms")
                                appendLine("Render gap p95/p99/max: ${result.gapP95Millis}/${result.gapP99Millis}/${result.longestGapMillis} ms")
                                appendLine("Gaps >33/>50/>100 ms: ${result.gapsOver33Millis}/${result.gapsOver50Millis}/${result.gapsOver100Millis}")
                                appendLine("Gaps >50 ms per minute: ${String.format("%.1f", stallsPerMinute)}")
                                appendLine("Injected drops: ${result.injectedDrops}; decoder flushes: ${result.decoderFlushes}; queue overflows: ${result.queueOverflows}")
                            }
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("BridgePad UDP A/B", report))
                            Toast.makeText(context, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                        },
                        enabled = result.renderedFrames > 0,
                    ) { Text(stringResource(R.string.udp_recovery_test_copy)) }
                }
            }
        }
    }
}
