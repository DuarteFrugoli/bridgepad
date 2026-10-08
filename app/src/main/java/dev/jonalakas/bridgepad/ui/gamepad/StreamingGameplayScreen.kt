package dev.jonalakas.bridgepad.ui.gamepad

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.streaming.AndroidWebRtcMediaClient
import dev.jonalakas.bridgepad.streaming.MediaPipelineMetrics
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import dev.jonalakas.bridgepad.ui.gamepad.layout.touchscreenContentInsets
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Full-screen playable stream. The complete image is also a relative mouse touchpad. */
@Composable
fun StreamingGameplayScreen(
    peerId: String,
    requestFor: (String) -> NetworkMediaRequest?,
    useDisplayCutoutArea: Boolean,
    onOpenKeyboard: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val eglBase = remember { EglBase.create() }
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var status by remember { mutableStateOf<NetworkMediaStatus>(NetworkMediaStatus.Connecting) }
    var client by remember { mutableStateOf<AndroidWebRtcMediaClient?>(null) }
    var generation by remember { mutableStateOf(0L) }

    fun stop() {
        generation += 1
        client?.close()
        client = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stop()
            renderer?.release()
            renderer = null
            eglBase.release()
        }
    }
    BackHandler {
        stop()
        onExit()
    }
    LaunchedEffect(renderer, peerId) {
        val sink = renderer ?: return@LaunchedEffect
        if (client != null) return@LaunchedEffect
        status = NetworkMediaStatus.Connecting
        val currentGeneration = ++generation
        val created = AndroidWebRtcMediaClient(
            context = context,
            eglContext = eglBase.eglBaseContext,
            requestProvider = { requestFor(peerId) },
            videoSink = sink,
            onStatus = { update ->
                mainHandler.post {
                    if (generation == currentGeneration) status = update
                }
            },
            onMetrics = { _: MediaPipelineMetrics -> Unit },
        )
        client = created
        created.start()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(touchscreenContentInsets(useDisplayCutoutArea)),
    ) {
        val activeFormat = (status as? NetworkMediaStatus.Active)?.format
        val videoAspectRatio = activeFormat
            ?.let { format -> format.width.toFloat() / format.height.toFloat() }
            ?.takeIf { ratio -> ratio.isFinite() && ratio > 0f }
            ?: DEFAULT_VIDEO_ASPECT_RATIO
        val containerAspectRatio = constraints.maxWidth.toFloat() /
            constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val videoModifier = if (containerAspectRatio > videoAspectRatio) {
            Modifier.fillMaxHeight().aspectRatio(videoAspectRatio)
        } else {
            Modifier.fillMaxWidth().aspectRatio(videoAspectRatio)
        }
        AndroidView(
            factory = { viewContext ->
                SurfaceViewRenderer(viewContext).apply {
                    init(eglBase.eglBaseContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    renderer = this
                }
            },
            // Keep the renderer itself at the negotiated video ratio. Relying
            // only on SurfaceViewRenderer's internal FIT scaling can leave its
            // native Surface larger than the video viewport on wide phones,
            // which some devices crop at the top and bottom.
            modifier = videoModifier.align(Alignment.Center),
        )
        if (status !is NetworkMediaStatus.Active) {
            Text(
                text = status.playableLabel(),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        MouseTouchpad(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            transparent = true,
            onOpenKeyboard = onOpenKeyboard,
        )
    }
}

private const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f

@Composable
private fun NetworkMediaStatus.playableLabel(): String = when (this) {
    NetworkMediaStatus.Idle,
    NetworkMediaStatus.Connecting,
    -> stringResource(R.string.streaming_gameplay_connecting)
    is NetworkMediaStatus.Active -> ""
    NetworkMediaStatus.Stopped -> stringResource(R.string.streaming_gameplay_stopped)
    is NetworkMediaStatus.Failed -> stringResource(R.string.synthetic_stream_failed, detail)
}
