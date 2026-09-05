package dev.jonalakas.bridgepad.ui

import dev.jonalakas.bridgepad.ui.home.InputMode
import dev.jonalakas.bridgepad.ui.home.SessionSetup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetupTest {
    private fun ready(
        input: InputMode? = InputMode.TOUCHSCREEN,
        connection: Boolean = true,
        bluetooth: Boolean = true,
        host: String? = "paired-pc",
        newPairing: Boolean = false,
    ) = SessionSetup.canConnect(input, connection, bluetooth, host, newPairing, listOf("paired-pc"))

    @Test fun missingInputBlocksConnection() { assertFalse(ready(input = null)) }
    @Test fun missingTransportBlocksConnection() { assertFalse(ready(connection = false)) }
    @Test fun bluetoothOffBlocksConnection() { assertFalse(ready(bluetooth = false)) }
    @Test fun missingDestinationBlocksConnection() { assertFalse(ready(host = null)) }
    @Test fun staleDestinationBlocksConnection() { assertFalse(ready(host = "forgotten-pc")) }
    @Test fun validPairedPcEnablesConnection() { assertTrue(ready()) }
    @Test fun physicalInputAlsoEnablesConnection() { assertTrue(ready(input = InputMode.PHYSICAL_GAMEPAD)) }
    @Test fun explicitNewPairingEnablesConnection() { assertTrue(ready(host = null, newPairing = true)) }
    @Test fun newPairingStillRequiresInput() { assertFalse(ready(input = null, host = null, newPairing = true)) }
}
