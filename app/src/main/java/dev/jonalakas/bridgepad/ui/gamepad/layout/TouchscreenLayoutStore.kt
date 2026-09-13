package dev.jonalakas.bridgepad.ui.gamepad.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TouchscreenLayoutStore {
    private const val PREFERENCES = "touchscreen_layout"
    private const val ACTIVE_LAYOUT = "active_layout"

    private lateinit var context: Context
    private val mutableLayout = MutableStateFlow(DefaultTouchscreenLayout.value)
    val layout: StateFlow<TouchscreenLayout> = mutableLayout.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val saved = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACTIVE_LAYOUT, null)
        mutableLayout.value = TouchscreenLayoutCodec.decode(saved) ?: DefaultTouchscreenLayout.value
    }

    @Synchronized
    fun save(layout: TouchscreenLayout) {
        check(::context.isInitialized)
        val sanitized = layout.sanitized()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_LAYOUT, TouchscreenLayoutCodec.encode(sanitized))
            .apply()
        mutableLayout.value = sanitized
    }

    @Synchronized
    fun reset() {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(ACTIVE_LAYOUT)
            .apply()
        mutableLayout.value = DefaultTouchscreenLayout.value
    }
}
