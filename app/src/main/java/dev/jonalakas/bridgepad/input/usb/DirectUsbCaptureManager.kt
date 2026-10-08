package dev.jonalakas.bridgepad.input.usb

import android.content.Context
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore

/** Owns physical USB capture independently from any output transport session. */
object DirectUsbCaptureManager {
    private var controller: DirectUsbGamepadController? = null
    private val owners = DirectUsbCaptureOwners()

    @Synchronized
    fun initialize(context: Context) {
        if (controller != null) return
        GamepadMappingStore.initialize(context)
        controller = DirectUsbGamepadController(context.applicationContext) { active, message, error ->
            DirectUsbGamepadStore.update {
                it.copy(
                    active = active,
                    statusMessage = message,
                    statusIsError = error,
                    permissionPending = !active && !error,
                )
            }
        }.also(DirectUsbGamepadController::register)
    }

    @Synchronized
    fun start(context: Context) {
        acquire(context, LEGACY_OWNER)
    }

    @Synchronized
    fun acquire(context: Context, owner: String) {
        require(owner.isNotBlank())
        initialize(context)
        owners.acquire(owner)
        controller?.start()
    }

    @Synchronized
    fun stop() {
        release(LEGACY_OWNER)
    }

    @Synchronized
    fun release(owner: String) {
        if (owners.release(owner)) controller?.stop()
    }

    private const val LEGACY_OWNER = "ui-selection"
}

internal class DirectUsbCaptureOwners {
    private val values = linkedSetOf<String>()

    fun acquire(owner: String) {
        require(owner.isNotBlank())
        values += owner
    }

    /** Returns true only when the last actual owner was released. */
    fun release(owner: String): Boolean = values.remove(owner) && values.isEmpty()
}
