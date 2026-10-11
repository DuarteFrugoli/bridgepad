package dev.jonalakas.bridgepad.ui.gamepad

import android.view.Surface
import androidx.lifecycle.ViewModel
import dev.jonalakas.bridgepad.streaming.AndroidQuicMediaClient
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaStatus
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Retains the media connection while Android recreates the screen for rotation. */
class StreamingMediaViewModel : ViewModel() {
    private val mutableStatus = MutableStateFlow<NetworkMediaStatus>(NetworkMediaStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private val generation = AtomicLong()
    private var activePeerId: String? = null
    private var client: AndroidQuicMediaClient? = null

    fun attachSurface(
        peerId: String,
        surface: Surface,
        requestFor: (String) -> NetworkMediaRequest?,
    ) {
        if (activePeerId == peerId && client != null) {
            client?.setVideoSurface(surface)
            return
        }
        stop()
        activePeerId = peerId
        val currentGeneration = generation.incrementAndGet()
        val created = AndroidQuicMediaClient(
            requestProvider = { requestFor(peerId) },
            surface = surface,
            onStatus = { update ->
                if (generation.get() == currentGeneration) mutableStatus.value = update
            },
        )
        client = created
        created.start()
    }

    fun detachSurface(surface: Surface) {
        client?.detachVideoSurface(surface)
    }

    fun stop() {
        generation.incrementAndGet()
        client?.close()
        client = null
        activePeerId = null
        mutableStatus.value = NetworkMediaStatus.Idle
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
