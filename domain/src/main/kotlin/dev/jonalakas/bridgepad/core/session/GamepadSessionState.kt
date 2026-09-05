package dev.jonalakas.bridgepad.core.session

enum class InputMode { TOUCHSCREEN, PHYSICAL_GAMEPAD }

enum class PhysicalCaptureMode { COMPATIBILITY, BACKGROUND_USB }

enum class OutputTransportType { BLUETOOTH_HID, WIFI_BRIDGE, USB_BRIDGE }

enum class DestinationType { WINDOWS, LINUX, PLAYSTATION, XBOX }

enum class SessionStatus {
    IDLE,
    STARTING,
    REGISTERING,
    READY,
    CONNECTING,
    CONNECTED,
    STOPPING,
    ERROR,
}

data class SessionConfiguration(
    val inputMode: InputMode,
    val physicalCaptureMode: PhysicalCaptureMode? = null,
    val outputTransport: OutputTransportType,
    val destinationType: DestinationType,
    val destinationId: String,
) {
    init {
        require(inputMode != InputMode.PHYSICAL_GAMEPAD || physicalCaptureMode != null) {
            "Physical input requires an explicit capture mode."
        }
        require(destinationId.isNotBlank())
    }
}

data class GamepadSessionState(
    val status: SessionStatus = SessionStatus.IDLE,
    val errorMessage: String? = null,
) {
    init {
        require(status == SessionStatus.ERROR || errorMessage == null) {
            "An error message can only be attached to an error session."
        }
    }
}
