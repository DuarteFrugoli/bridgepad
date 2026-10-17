package dev.jonalakas.bridgepad.session

import android.content.Context
import dev.jonalakas.bridgepad.transport.network.IpGamepadTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Temporary opt-in switch for comparing playable TCP and UDP gamepad input. */
object IpGamepadTransportStore {
    private const val PREFERENCES = "ip_gamepad_transport"
    private const val SELECTED = "selected"
    private lateinit var context: Context
    private val mutableSelected = MutableStateFlow(IpGamepadTransport.TCP_TLS)
    val selected = mutableSelected.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val saved = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SELECTED, null)
        mutableSelected.value = saved
            ?.let { runCatching { IpGamepadTransport.valueOf(it) }.getOrNull() }
            ?: IpGamepadTransport.TCP_TLS
    }

    @Synchronized
    fun set(transport: IpGamepadTransport) {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(SELECTED, transport.name).apply()
        mutableSelected.value = transport
    }
}
