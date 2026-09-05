package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode

internal object SessionSetup {
    fun canConnect(
        inputMode: InputMode?,
        physicalCaptureMode: PhysicalCaptureMode?,
        bluetoothSelected: Boolean,
        bluetoothReady: Boolean,
        selectedAddress: String?,
        pairNewPcSelected: Boolean,
        pairedAddresses: Collection<String>,
    ): Boolean = inputMode != null &&
        (inputMode != InputMode.PHYSICAL_GAMEPAD || physicalCaptureMode != null) &&
        bluetoothSelected && bluetoothReady &&
        (if (selectedAddress != null) selectedAddress in pairedAddresses else pairNewPcSelected)
}
