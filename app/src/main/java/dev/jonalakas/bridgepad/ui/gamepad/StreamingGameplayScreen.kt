package dev.jonalakas.bridgepad.ui.gamepad

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import dev.jonalakas.bridgepad.streaming.PlayableMediaTransport
import dev.jonalakas.bridgepad.ui.gamepad.layout.touchscreenContentInsets

/** Full-screen playable stream. The complete image is also a relative mouse touchpad. */
@Composable
fun StreamingGameplayScreen(
    peerId: String,
    requestFor: (String) -> NetworkMediaRequest?,
    mediaTransport: PlayableMediaTransport,
    useDisplayCutoutArea: Boolean,
    keyboardState: GameplayKeyboardState,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val media: StreamingMediaViewModel = viewModel()
    val status by media.status.collectAsState()
    val activity = LocalContext.current.findActivity()
    var surface by remember { mutableStateOf<Surface?>(null) }
    var keyboardOpen by remember { mutableStateOf(false) }

    DisposableEffect(media, activity) {
        onDispose {
            surface?.let(media::detachSurface)
            if (activity?.isChangingConfigurations != true) media.stop()
        }
    }
    BackHandler {
        media.stop()
        onExit()
    }
    LaunchedEffect(surface, peerId, mediaTransport) {
        val target = surface ?: return@LaunchedEffect
        media.attachSurface(peerId, mediaTransport, target, requestFor)
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
                            media.detachSurface(holder.surface)
                            if (surface === holder.surface) surface = null
                        }
                    })
                }
            },
            // Keep the decoder surface at the negotiated video ratio.
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
            onOpenKeyboard = { keyboardOpen = true },
        )
        if (keyboardOpen) GameplayKeyboardOverlay(keyboardState) { keyboardOpen = false }
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
