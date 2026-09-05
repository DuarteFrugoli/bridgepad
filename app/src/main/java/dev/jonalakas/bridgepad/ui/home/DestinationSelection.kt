package dev.jonalakas.bridgepad.ui.home

/** An absent saved host is not consent to advertise the phone for new pairing. */
internal object DestinationSelection {
    const val CHOOSE_PC = "choose_pc"
    const val NEW_PC = "new_pc"

    fun requestFor(
        selectedAddress: String?,
        pairNewPcSelected: Boolean,
        bluetoothReady: Boolean = true,
    ): String = when {
        !bluetoothReady -> CHOOSE_PC
        selectedAddress != null -> selectedAddress
        pairNewPcSelected -> NEW_PC
        else -> CHOOSE_PC
    }
}
