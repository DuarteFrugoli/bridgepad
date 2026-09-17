package dev.jonalakas.bridgepad.ui.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SessionOrientationMode {
    AUTO,
    PORTRAIT,
    REVERSE_PORTRAIT,
    LANDSCAPE,
    REVERSE_LANDSCAPE,
}

object SessionOrientationStore {
    private const val PREFERENCES = "session_display"
    private const val ORIENTATION_MODE = "orientation_mode"

    private lateinit var context: Context
    private val mutableMode = MutableStateFlow(SessionOrientationMode.LANDSCAPE)
    val mode: StateFlow<SessionOrientationMode> = mutableMode.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        val saved = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ORIENTATION_MODE, null)
        mutableMode.value = saved
            ?.let { runCatching { SessionOrientationMode.valueOf(it) }.getOrNull() }
            ?: SessionOrientationMode.LANDSCAPE
    }

    @Synchronized
    fun set(mode: SessionOrientationMode) {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ORIENTATION_MODE, mode.name)
            .apply()
        mutableMode.value = mode
    }
}
