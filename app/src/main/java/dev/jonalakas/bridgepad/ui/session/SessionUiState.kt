package dev.jonalakas.bridgepad.ui.session

import dev.jonalakas.bridgepad.core.session.ConnectionMethod

enum class SessionSurface {
    NONE,
    TOUCH_CONTROLLER,
    MOUSE_TOUCHPAD,
}

enum class SurfaceSelection {
    AUTOMATIC,
    USER,
}

data class SessionUiState(
    val expectedTransport: ConnectionMethod? = null,
    val activeTransport: ConnectionMethod? = null,
    val surface: SessionSurface = SessionSurface.NONE,
    val surfaceSelection: SurfaceSelection = SurfaceSelection.AUTOMATIC,
)

sealed interface SessionUiEvent {
    data class ConnectionRequested(val transport: ConnectionMethod) : SessionUiEvent

    data class TransportConnected(
        val transport: ConnectionMethod,
        val physicalControllerConnected: Boolean,
    ) : SessionUiEvent

    data class TransportFailed(val transport: ConnectionMethod) : SessionUiEvent
    data class TransportStopped(val transport: ConnectionMethod) : SessionUiEvent
    data class PhysicalControllerChanged(val connected: Boolean) : SessionUiEvent
    data class SurfaceSelected(val surface: SessionSurface) : SessionUiEvent
    data object SurfaceClosed : SessionUiEvent
    data object SessionEnded : SessionUiEvent
}

object SessionUiReducer {
    fun reduce(state: SessionUiState, event: SessionUiEvent): SessionUiState = when (event) {
        is SessionUiEvent.ConnectionRequested -> SessionUiState(
            expectedTransport = event.transport,
        )

        is SessionUiEvent.TransportConnected -> when {
            state.activeTransport == event.transport && state.expectedTransport == null -> state
            state.expectedTransport != null && state.expectedTransport != event.transport -> state
            state.activeTransport != null && state.activeTransport != event.transport -> state
            else -> SessionUiState(
                activeTransport = event.transport,
                surface = automaticSurface(event.physicalControllerConnected),
                surfaceSelection = SurfaceSelection.AUTOMATIC,
            )
        }

        is SessionUiEvent.TransportFailed -> state.clearIfOwnedBy(event.transport)
        is SessionUiEvent.TransportStopped -> state.clearIfOwnedBy(event.transport)

        is SessionUiEvent.PhysicalControllerChanged -> {
            if (state.activeTransport == null || state.surfaceSelection != SurfaceSelection.AUTOMATIC) {
                state
            } else {
                state.copy(surface = automaticSurface(event.connected))
            }
        }

        is SessionUiEvent.SurfaceSelected -> {
            if (state.activeTransport == null) state else state.copy(
                surface = event.surface,
                surfaceSelection = SurfaceSelection.USER,
            )
        }

        SessionUiEvent.SurfaceClosed -> {
            if (state.activeTransport == null) state else state.copy(
                surface = SessionSurface.NONE,
                surfaceSelection = SurfaceSelection.USER,
            )
        }

        SessionUiEvent.SessionEnded -> SessionUiState()
    }

    private fun SessionUiState.clearIfOwnedBy(transport: ConnectionMethod): SessionUiState =
        if (expectedTransport == transport || activeTransport == transport) SessionUiState() else this

    private fun automaticSurface(physicalControllerConnected: Boolean): SessionSurface =
        if (physicalControllerConnected) {
            SessionSurface.MOUSE_TOUCHPAD
        } else {
            SessionSurface.TOUCH_CONTROLLER
        }
}
