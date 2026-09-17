package dev.jonalakas.bridgepad.input.touch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persistent preferences shared by every touchpad surface and output transport. */
object TouchpadSettingsStore {
    private const val PREFERENCES = "touchpad_settings"
    private const val INVERTED_SCROLL = "inverted_scroll"
    private const val DEFAULT_INVERTED_SCROLL = true

    private lateinit var context: Context
    private val mutableInvertedScroll = MutableStateFlow(DEFAULT_INVERTED_SCROLL)
    val invertedScroll: StateFlow<Boolean> = mutableInvertedScroll.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        applyInvertedScroll(
            this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(INVERTED_SCROLL, DEFAULT_INVERTED_SCROLL),
        )
    }

    @Synchronized
    fun setInvertedScroll(inverted: Boolean) {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(INVERTED_SCROLL, inverted)
            .apply()
        applyInvertedScroll(inverted)
    }

    private fun applyInvertedScroll(inverted: Boolean) {
        TouchMouseStore.setInvertedScroll(inverted)
        mutableInvertedScroll.value = inverted
    }
}
