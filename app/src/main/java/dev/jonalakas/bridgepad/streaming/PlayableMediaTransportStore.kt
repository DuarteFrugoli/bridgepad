package dev.jonalakas.bridgepad.streaming

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlayableMediaTransport { QUIC, UDP_AEAD }

/** Local media preference. Input always uses its separately authenticated connection. */
object PlayableMediaTransportStore {
    private const val PREFERENCES = "media_transport"
    private const val SELECTED = "playable_transport"

    private lateinit var context: Context
    private val mutableSelected = MutableStateFlow(PlayableMediaTransport.QUIC)
    val selected: StateFlow<PlayableMediaTransport> = mutableSelected.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val saved = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SELECTED, null)
        mutableSelected.value = saved
            ?.let { runCatching { PlayableMediaTransport.valueOf(it) }.getOrNull() }
            ?: PlayableMediaTransport.QUIC
    }

    @Synchronized
    fun set(transport: PlayableMediaTransport) {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(SELECTED, transport.name).apply()
        mutableSelected.value = transport
    }
}
