package dev.jonalakas.bridgepad.input.usb

import android.content.Context
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore

/** Owns physical USB capture independently from any output transport session. */
object DirectUsbCaptureManager {
    private var controller: DirectUsbGamepadController? = null

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
        initialize(context)
        controller?.start()
    }

    @Synchronized
    fun stop() {
        controller?.stop()
    }
}
