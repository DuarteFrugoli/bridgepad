package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.ui.gamepad.MouseTouchpadScreen
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.gamepad.GameplayKeyboardState
import dev.jonalakas.bridgepad.ui.gamepad.GameplayKeyboardScreen
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayout

@Composable
internal fun DiagnosticGameplaySession(
    physicalControllerConnected: Boolean,
    touchscreenLayout: TouchscreenLayout,
    onEndSession: () -> Unit,
    pointerSupported: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val keyboardState = remember { GameplayKeyboardState() }
    DisposableEffect(Unit) {
        onDispose(keyboardState::reset)
    }
    var surfaceName by rememberSaveable {
        mutableStateOf(
            if (physicalControllerConnected && pointerSupported) {
                DiagnosticGameplaySurface.TOUCHPAD.name
            } else {
                DiagnosticGameplaySurface.GAMEPAD.name
            },
        )
    }
    var keyboardReturnSurfaceName by rememberSaveable {
        mutableStateOf(DiagnosticGameplaySurface.GAMEPAD.name)
    }
    when (DiagnosticGameplaySurface.valueOf(surfaceName)) {
        DiagnosticGameplaySurface.GAMEPAD -> TouchscreenGamepadScreen(
            layout = touchscreenLayout,
            onOpenKeyboard = {
                keyboardReturnSurfaceName = surfaceName
                surfaceName = DiagnosticGameplaySurface.KEYBOARD.name
            },
            onExit = { surfaceName = DiagnosticGameplaySurface.MENU.name },
            modifier = modifier,
        )
        DiagnosticGameplaySurface.TOUCHPAD -> MouseTouchpadScreen(
            onOpenKeyboard = {
                keyboardReturnSurfaceName = surfaceName
                surfaceName = DiagnosticGameplaySurface.KEYBOARD.name
            },
            onExit = { surfaceName = DiagnosticGameplaySurface.MENU.name },
            modifier = modifier,
        )
        DiagnosticGameplaySurface.KEYBOARD -> GameplayKeyboardScreen(
            state = keyboardState,
            onClose = { surfaceName = keyboardReturnSurfaceName },
            modifier = modifier,
        )
        DiagnosticGameplaySurface.MENU -> DiagnosticSessionMenu(
            physicalControllerConnected = physicalControllerConnected,
            onOpenGamepad = { surfaceName = DiagnosticGameplaySurface.GAMEPAD.name },
            onOpenTouchpad = { surfaceName = DiagnosticGameplaySurface.TOUCHPAD.name },
            onEndSession = onEndSession,
            pointerSupported = pointerSupported,
            modifier = modifier,
        )
    }
}

@Composable
private fun DiagnosticSessionMenu(
    physicalControllerConnected: Boolean,
    onOpenGamepad: () -> Unit,
    onOpenTouchpad: () -> Unit,
    onEndSession: () -> Unit,
    pointerSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onOpenGamepad)
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.session_screens), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(
                    if (physicalControllerConnected) {
                        R.string.network_physical_controller_active
                    } else {
                        R.string.automatic_input_virtual_ready
                    },
                ),
            )
            Button(onClick = onOpenGamepad, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_virtual_controller))
            }
            if (pointerSupported) {
                OutlinedButton(onClick = onOpenTouchpad, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_mouse_touchpad))
                }
            }
            Text(stringResource(R.string.session_screens_description), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onEndSession, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.end_session))
            }
        }
    }
}

private enum class DiagnosticGameplaySurface { GAMEPAD, TOUCHPAD, KEYBOARD, MENU }
