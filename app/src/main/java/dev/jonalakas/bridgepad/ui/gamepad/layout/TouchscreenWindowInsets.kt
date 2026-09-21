package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/** Uses the complete display on demand; otherwise keeps all drawing insets protected. */
@Composable
internal fun touchscreenContentInsets(useDisplayCutoutArea: Boolean): WindowInsets =
    if (useDisplayCutoutArea) WindowInsets(0, 0, 0, 0) else WindowInsets.safeDrawing
