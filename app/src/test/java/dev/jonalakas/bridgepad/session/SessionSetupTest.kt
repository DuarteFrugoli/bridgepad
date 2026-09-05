package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetupTest {
    private fun ready(
        input: InputMode? = InputMode.TOUCHSCREEN,
        capture: PhysicalCaptureMode? = null,
        connection: Boolean = true,
        bluetooth: Boolean = true,
        host: String? = "paired-pc",
        newPairing: Boolean = false,
    ) = SessionSetup.canConnect(input, capture, connection, bluetooth, host, newPairing, listOf("paired-pc"))

    @Test fun missingInputBlocksConnection() { assertFalse(ready(input = null)) }
    @Test fun missingTransportBlocksConnection() { assertFalse(ready(connection = false)) }
    @Test fun bluetoothOffBlocksConnection() { assertFalse(ready(bluetooth = false)) }
    @Test fun missingDestinationBlocksConnection() { assertFalse(ready(host = null)) }
    @Test fun staleDestinationBlocksConnection() { assertFalse(ready(host = "forgotten-pc")) }
    @Test fun validPairedPcEnablesConnection() { assertTrue(ready()) }
    @Test fun physicalInputRequiresCaptureMode() { assertFalse(ready(input = InputMode.PHYSICAL_GAMEPAD)) }
    @Test fun compatibilityInputEnablesConnection() {
        assertTrue(ready(input = InputMode.PHYSICAL_GAMEPAD, capture = PhysicalCaptureMode.COMPATIBILITY))
    }
    @Test fun backgroundUsbInputEnablesConnection() {
        assertTrue(ready(input = InputMode.PHYSICAL_GAMEPAD, capture = PhysicalCaptureMode.BACKGROUND_USB))
    }
    @Test fun explicitNewPairingEnablesConnection() { assertTrue(ready(host = null, newPairing = true)) }
    @Test fun newPairingStillRequiresInput() { assertFalse(ready(input = null, host = null, newPairing = true)) }
}
