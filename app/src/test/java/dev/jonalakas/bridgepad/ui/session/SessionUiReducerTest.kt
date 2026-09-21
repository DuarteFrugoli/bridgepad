package dev.jonalakas.bridgepad.ui.session

import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionUiReducerTest {
    @Test
    fun wifiConnectionWithoutPhysicalControllerOpensTouchController() {
        val requested = reduce(
            SessionUiState(),
            SessionUiEvent.ConnectionRequested(ConnectionMethod.WIFI),
        )

        val connected = reduce(
            requested,
            SessionUiEvent.TransportConnected(ConnectionMethod.WIFI, false),
        )

        assertEquals(ConnectionMethod.WIFI, connected.activeTransport)
        assertEquals(SessionSurface.TOUCH_CONTROLLER, connected.surface)
    }

    @Test
    fun bluetoothStateCannotCloseWifiSession() {
        val wifi = activeWifi(physicalControllerConnected = false)

        val afterBluetoothStopped = reduce(
            wifi,
            SessionUiEvent.TransportStopped(ConnectionMethod.BLUETOOTH),
        )

        assertEquals(wifi, afterBluetoothStopped)
    }

    @Test
    fun disconnectingPhysicalControllerChangesAutomaticSurfaceWithoutEndingSession() {
        val wifi = activeWifi(physicalControllerConnected = true)

        val changed = reduce(wifi, SessionUiEvent.PhysicalControllerChanged(false))

        assertEquals(ConnectionMethod.WIFI, changed.activeTransport)
        assertEquals(SessionSurface.TOUCH_CONTROLLER, changed.surface)
    }

    @Test
    fun physicalControllerChangesDoNotOverrideManualSurfaceChoice() {
        val manuallySelected = reduce(
            activeWifi(physicalControllerConnected = false),
            SessionUiEvent.SurfaceSelected(SessionSurface.MOUSE_TOUCHPAD),
        )

        val changed = reduce(
            manuallySelected,
            SessionUiEvent.PhysicalControllerChanged(true),
        )

        assertEquals(manuallySelected, changed)
    }

    @Test
    fun unexpectedTransportCannotClaimPendingConnection() {
        val requested = reduce(
            SessionUiState(),
            SessionUiEvent.ConnectionRequested(ConnectionMethod.WIFI),
        )

        val bluetooth = reduce(
            requested,
            SessionUiEvent.TransportConnected(ConnectionMethod.BLUETOOTH, false),
        )

        assertEquals(requested, bluetooth)
    }

    @Test
    fun activeTransportCanBeRestoredAfterActivityIsRecreated() {
        val restored = reduce(
            SessionUiState(),
            SessionUiEvent.TransportConnected(ConnectionMethod.WIFI, false),
        )

        assertEquals(ConnectionMethod.WIFI, restored.activeTransport)
        assertEquals(SessionSurface.TOUCH_CONTROLLER, restored.surface)
    }

    @Test
    fun onlyTheActiveTransportCanEndTheSessionUi() {
        val wifi = activeWifi(physicalControllerConnected = false)

        assertEquals(
            wifi,
            reduce(wifi, SessionUiEvent.TransportFailed(ConnectionMethod.BLUETOOTH)),
        )
        assertEquals(
            SessionUiState(),
            reduce(wifi, SessionUiEvent.TransportFailed(ConnectionMethod.WIFI)),
        )
    }

    @Test
    fun keyboardReturnsToTheSurfaceThatOpenedIt() {
        val touchController = activeWifi(physicalControllerConnected = false)

        val keyboard = reduce(touchController, SessionUiEvent.KeyboardOpened)
        assertEquals(SessionSurface.KEYBOARD, keyboard.surface)
        assertEquals(SessionSurface.TOUCH_CONTROLLER, keyboard.surfaceBeforeKeyboard)

        val restored = reduce(keyboard, SessionUiEvent.KeyboardClosed)
        assertEquals(SessionSurface.TOUCH_CONTROLLER, restored.surface)
        assertNull(restored.surfaceBeforeKeyboard)
    }

    @Test
    fun automaticSurfaceCanChangeWhileKeyboardIsOpen() {
        val keyboard = reduce(
            activeWifi(physicalControllerConnected = false),
            SessionUiEvent.KeyboardOpened,
        )

        val controllerConnected = reduce(
            keyboard,
            SessionUiEvent.PhysicalControllerChanged(true),
        )
        val restored = reduce(controllerConnected, SessionUiEvent.KeyboardClosed)

        assertEquals(SessionSurface.MOUSE_TOUCHPAD, restored.surface)
    }

    private fun activeWifi(physicalControllerConnected: Boolean): SessionUiState = reduce(
        reduce(
            SessionUiState(),
            SessionUiEvent.ConnectionRequested(ConnectionMethod.WIFI),
        ),
        SessionUiEvent.TransportConnected(ConnectionMethod.WIFI, physicalControllerConnected),
    )

    private fun reduce(state: SessionUiState, event: SessionUiEvent): SessionUiState =
        SessionUiReducer.reduce(state, event)
}
