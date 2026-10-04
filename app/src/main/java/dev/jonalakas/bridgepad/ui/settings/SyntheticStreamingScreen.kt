package dev.jonalakas.bridgepad.ui.settings

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.session.TrustedDesktop
import dev.jonalakas.bridgepad.streaming.AndroidWebRtcMediaClient
import dev.jonalakas.bridgepad.streaming.MediaPipelineMetrics
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopStreamingScreen(
    desktops: List<TrustedDesktop>,
    requestFor: (String) -> NetworkMediaRequest?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val eglBase = remember { EglBase.create() }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(desktops.firstOrNull()?.peerIdHex) }
    var status by remember { mutableStateOf<NetworkMediaStatus>(NetworkMediaStatus.Idle) }
    var metrics by remember { mutableStateOf(MediaPipelineMetrics()) }
    var client by remember { mutableStateOf<AndroidWebRtcMediaClient?>(null) }
    var sessionGeneration by remember { mutableStateOf(0L) }

    fun stop() {
        sessionGeneration += 1
        client?.close()
        client = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stop()
            renderer?.release()
            eglBase.release()
        }
    }
    BackHandler {
        stop()
        onBack()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.synthetic_stream_title)) },
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
            item { Text(stringResource(R.string.synthetic_stream_description)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.synthetic_stream_desktop),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (desktops.isEmpty()) {
                            Text(stringResource(R.string.synthetic_stream_no_desktop))
                        }
                        desktops.forEach { desktop ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = selectedId == desktop.peerIdHex,
                                    onClick = { selectedId = desktop.peerIdHex },
                                    enabled = status !is NetworkMediaStatus.Active,
                                )
                                Text(desktop.name)
                            }
                        }
                    }
                }
            }
            item {
                val active = status is NetworkMediaStatus.Active ||
                    status is NetworkMediaStatus.Connecting
                Button(
                    onClick = {
                        if (active) {
                            stop()
                            status = NetworkMediaStatus.Stopped
                        } else {
                            val request = selectedId?.let(requestFor)
                            val sink = renderer
                            if (request == null || sink == null) {
                                status = NetworkMediaStatus.Failed("trusted_desktop_unavailable")
                            } else {
                                metrics = MediaPipelineMetrics()
                                val peerId = checkNotNull(selectedId)
                                val generation = ++sessionGeneration
                                val created = AndroidWebRtcMediaClient(
                                    context = context,
                                    eglContext = eglBase.eglBaseContext,
                                    requestProvider = { requestFor(peerId) },
                                    videoSink = sink,
                                    onStatus = { update ->
                                        mainHandler.post {
                                            if (sessionGeneration == generation) status = update
                                        }
                                    },
                                    onMetrics = { update ->
                                        mainHandler.post {
                                            if (sessionGeneration == generation) metrics = update
                                        }
                                    },
                                )
                                client = created
                                created.start()
                            }
                        }
                    },
                    enabled = selectedId != null && renderer != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (active) R.string.synthetic_stream_stop
                            else R.string.synthetic_stream_start,
                        ),
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { viewContext ->
                            SurfaceViewRenderer(viewContext).apply {
                                init(eglBase.eglBaseContext, null)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                setEnableHardwareScaler(true)
                                renderer = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (status !is NetworkMediaStatus.Active) {
                        Text(
                            text = status.label(),
                            color = Color.White,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.synthetic_stream_metrics),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.synthetic_stream_network, metrics.networkEstimateMicros))
                        Text(stringResource(R.string.synthetic_stream_decode, metrics.decodeMicros))
                        Text(
                            stringResource(
                                R.string.synthetic_stream_present,
                                metrics.presentationMicros,
                            ),
                        )
                        Text(
                            stringResource(
                                R.string.synthetic_stream_totals,
                                metrics.receivedFrames,
                                metrics.droppedFrames,
                                metrics.receivedBytes,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkMediaStatus.label(): String = when (this) {
    NetworkMediaStatus.Idle -> stringResource(R.string.synthetic_stream_idle)
    NetworkMediaStatus.Connecting -> stringResource(R.string.synthetic_stream_connecting)
    is NetworkMediaStatus.Active -> stringResource(
        R.string.synthetic_stream_active,
        format.width,
        format.height,
        format.framesPerSecond,
    )
    NetworkMediaStatus.Stopped -> stringResource(R.string.synthetic_stream_stopped)
    is NetworkMediaStatus.Failed -> stringResource(R.string.synthetic_stream_failed, detail)
}
