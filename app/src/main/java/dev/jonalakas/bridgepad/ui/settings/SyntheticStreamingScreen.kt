package dev.jonalakas.bridgepad.ui.settings

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.session.TrustedDesktop
import dev.jonalakas.bridgepad.streaming.DecodedVideoFrame
import dev.jonalakas.bridgepad.streaming.MediaPipelineMetrics
import dev.jonalakas.bridgepad.transport.network.NetworkMediaClient
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyntheticStreamingScreen(
    desktops: List<TrustedDesktop>,
    requestFor: (String) -> NetworkMediaRequest?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(desktops.firstOrNull()?.peerIdHex) }
    var status by remember { mutableStateOf<NetworkMediaStatus>(NetworkMediaStatus.Idle) }
    var frame by remember { mutableStateOf<DecodedVideoFrame?>(null) }
    var metrics by remember { mutableStateOf(MediaPipelineMetrics()) }
    var client by remember { mutableStateOf<NetworkMediaClient?>(null) }
    val image = remember(frame?.frameId) { frame?.toImageBitmap() }

    DisposableEffect(Unit) {
        onDispose { client?.stop() }
    }
    LaunchedEffect(frame?.frameId) {
        frame?.let { client?.markPresented(it.frameId) }
    }
    BackHandler {
        client?.stop()
        onBack()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.synthetic_stream_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        client?.stop()
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
                            client?.stop()
                            client = null
                            status = NetworkMediaStatus.Stopped
                        } else {
                            val request = selectedId?.let(requestFor)
                            if (request == null) {
                                status = NetworkMediaStatus.Failed("trusted_desktop_unavailable")
                            } else {
                                frame = null
                                metrics = MediaPipelineMetrics()
                                client = NetworkMediaClient(
                                    request = request,
                                    onStatus = { update -> mainHandler.post { status = update } },
                                    onFrame = { update -> mainHandler.post { frame = update } },
                                    onMetrics = { update -> mainHandler.post { metrics = update } },
                                ).also(NetworkMediaClient::start)
                            }
                        }
                    },
                    enabled = selectedId != null,
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
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = stringResource(R.string.synthetic_stream_frame),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
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
                        Text(stringResource(R.string.synthetic_stream_generation, metrics.generationMicros))
                        Text(stringResource(R.string.synthetic_stream_encode, metrics.encodeMicros))
                        Text(stringResource(R.string.synthetic_stream_network, metrics.networkEstimateMicros))
                        Text(stringResource(R.string.synthetic_stream_decode, metrics.decodeMicros))
                        Text(stringResource(R.string.synthetic_stream_present, metrics.presentationMicros))
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

private fun DecodedVideoFrame.toImageBitmap(): ImageBitmap {
    val pixels = IntArray(width * height)
    var source = 0
    for (index in pixels.indices) {
        val value = ((rgb565[source].toInt() and 0xff) shl 8) or
            (rgb565[source + 1].toInt() and 0xff)
        source += 2
        val red5 = value ushr 11 and 0x1f
        val green6 = value ushr 5 and 0x3f
        val blue5 = value and 0x1f
        pixels[index] = (0xff shl 24) or
            ((red5 * 255 / 31) shl 16) or
            ((green6 * 255 / 63) shl 8) or
            (blue5 * 255 / 31)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
