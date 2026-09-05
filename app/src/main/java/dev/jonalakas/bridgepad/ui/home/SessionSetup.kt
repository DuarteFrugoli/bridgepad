package dev.jonalakas.bridgepad.ui.home

internal object SessionSetup {
    fun canConnect(
        inputMode: InputMode?,
        bluetoothSelected: Boolean,
        bluetoothReady: Boolean,
        selectedAddress: String?,
        pairNewPcSelected: Boolean,
        pairedAddresses: Collection<String>,
    ): Boolean = inputMode != null && bluetoothSelected && bluetoothReady &&
        (if (selectedAddress != null) selectedAddress in pairedAddresses else pairNewPcSelected)
}
