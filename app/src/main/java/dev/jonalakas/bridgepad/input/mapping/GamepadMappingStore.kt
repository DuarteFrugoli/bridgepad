package dev.jonalakas.bridgepad.input.mapping

import android.content.Context
import dev.jonalakas.bridgepad.core.mapping.GamepadMapping
import java.util.concurrent.ConcurrentHashMap

/** Android persistence adapter for the platform-independent mapping model. */
object GamepadMappingStore {
    // Keep the original preference file name so existing profiles migrate automatically.
    private const val PREFERENCES = "usb_gamepad_mappings"
    private lateinit var context: Context
    private val cache = ConcurrentHashMap<String, GamepadMapping>()

    fun initialize(context: Context) { this.context = context.applicationContext }

    fun load(deviceKey: String): GamepadMapping {
        if (!::context.isInitialized) return GamepadMapping()
        return cache.computeIfAbsent(deviceKey, ::loadPersisted)
    }

    private fun loadPersisted(deviceKey: String): GamepadMapping {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(deviceKey, null).orEmpty()
        return GamepadMappingCodec.decode(saved)
    }

    fun save(deviceKey: String, mapping: GamepadMapping) {
        check(::context.isInitialized)
        cache[deviceKey] = mapping
        val value = GamepadMappingCodec.encode(mapping)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(deviceKey, value)
            .apply()
    }

    fun reset(deviceKey: String) {
        cache.remove(deviceKey)
        if (::context.isInitialized) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(deviceKey)
                .apply()
        }
    }
}
