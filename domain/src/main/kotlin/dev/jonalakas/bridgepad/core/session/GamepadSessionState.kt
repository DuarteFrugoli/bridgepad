package dev.jonalakas.bridgepad.core.session

enum class InputMode { TOUCHSCREEN, PHYSICAL_GAMEPAD }

enum class PhysicalCaptureMode { COMPATIBILITY, BACKGROUND_USB }

enum class DestinationType { PC }

enum class ConnectionMethod { BLUETOOTH, WIFI, USB }

@JvmInline
value class OutputAdapterId(val value: String) {
    init {
        require(value.isNotBlank()) { "An output adapter id cannot be blank." }
    }
}

object OutputAdapterIds {
    val GENERIC_BLUETOOTH_HID = OutputAdapterId("bluetooth-hid.generic-composite")
    val DESKTOP_WIFI = OutputAdapterId("desktop-bridge.wifi")
    val DESKTOP_USB = OutputAdapterId("desktop-bridge.usb")
}

enum class TargetSelectionMode { NONE, PAIRED_OR_NEW, RECEIVER }

data class OutputAdapterDescriptor(
    val id: OutputAdapterId,
    val connectionMethod: ConnectionMethod,
    val supportedDestinations: Set<DestinationType>,
    val targetSelectionMode: TargetSelectionMode,
) {
    init {
        require(supportedDestinations.isNotEmpty()) {
            "An output adapter must support at least one destination."
        }
    }

    fun supports(destination: DestinationType, connection: ConnectionMethod): Boolean =
        connectionMethod == connection && destination in supportedDestinations
}

enum class DestinationTargetKind { EXISTING, NEW_PAIRING }

data class DestinationTarget(
    val kind: DestinationTargetKind,
    val id: String? = null,
) {
    init {
        require(kind == DestinationTargetKind.NEW_PAIRING || !id.isNullOrBlank()) {
            "An existing destination requires an id."
        }
        require(kind == DestinationTargetKind.EXISTING || id == null) {
            "A new-pairing target cannot have an existing destination id."
        }
    }
}

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
    val destinationType: DestinationType,
    val connectionMethod: ConnectionMethod,
    val outputAdapterId: OutputAdapterId,
    val destinationTarget: DestinationTarget? = null,
    val inputMode: InputMode,
    val physicalCaptureMode: PhysicalCaptureMode? = null,
) {
    init {
        require(inputMode != InputMode.PHYSICAL_GAMEPAD || physicalCaptureMode != null) {
            "Physical input requires an explicit capture mode."
        }
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
