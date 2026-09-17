package dev.jonalakas.bridgepad.ui.gamepad.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TouchscreenLayoutStore {
    private const val PREFERENCES = "touchscreen_layout"
    private const val ACTIVE_PROFILE = "active_profile"

    private lateinit var context: Context
    private val mutableProfile = MutableStateFlow(DefaultTouchscreenLayoutProfile.value)
    val profile: StateFlow<TouchscreenLayoutProfile> = mutableProfile.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val saved = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ACTIVE_PROFILE, null)
        mutableProfile.value = TouchscreenLayoutProfileCodec.decode(saved) ?: DefaultTouchscreenLayoutProfile.value
    }

    @Synchronized
    fun save(profile: TouchscreenLayoutProfile) {
        check(::context.isInitialized)
        val sanitized = profile.sanitized()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_PROFILE, TouchscreenLayoutProfileCodec.encode(sanitized))
            .apply()
        mutableProfile.value = sanitized
    }

    @Synchronized
    fun reset() {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(ACTIVE_PROFILE)
            .apply()
        mutableProfile.value = DefaultTouchscreenLayoutProfile.value
    }
}
