package dev.jonalakas.bridgepad.ui.home

import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode

/** User-facing experience selected before transport and input details. */
enum class PlayMode {
    CONTROLLER,
    STREAMING,
}

/** Input surface used while playing. Virtual streaming controls follow later. */
enum class PlayControllerType {
    PHYSICAL,
    VIRTUAL,
}

internal fun streamingInputReady(
    controllerType: PlayControllerType?,
    captureMode: PhysicalCaptureMode?,
    compatibilityAvailable: Boolean,
    directUsbAvailable: Boolean,
): Boolean = controllerType == PlayControllerType.PHYSICAL && when (captureMode) {
    PhysicalCaptureMode.COMPATIBILITY -> compatibilityAvailable
    PhysicalCaptureMode.BACKGROUND_USB -> directUsbAvailable
    null -> false
}
