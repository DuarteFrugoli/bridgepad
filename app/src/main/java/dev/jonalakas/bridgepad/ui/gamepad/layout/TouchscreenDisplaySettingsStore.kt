package dev.jonalakas.bridgepad.ui.gamepad.layout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local display preferences that must be shared by gameplay and the editor. */
object TouchscreenDisplaySettingsStore {
    private const val PREFERENCES = "touchscreen_display_settings"
    private const val USE_DISPLAY_CUTOUT_AREA = "use_display_cutout_area"

    private lateinit var context: Context
    private val mutableUseDisplayCutoutArea = MutableStateFlow(false)
    val useDisplayCutoutArea: StateFlow<Boolean> = mutableUseDisplayCutoutArea.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        this.context = context.applicationContext
        mutableUseDisplayCutoutArea.value = this.context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(USE_DISPLAY_CUTOUT_AREA, false)
    }

    @Synchronized
    fun setUseDisplayCutoutArea(enabled: Boolean) {
        check(::context.isInitialized)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(USE_DISPLAY_CUTOUT_AREA, enabled)
            .apply()
        mutableUseDisplayCutoutArea.value = enabled
    }
}
