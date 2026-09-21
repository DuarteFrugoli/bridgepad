package dev.jonalakas.bridgepad.ui.gamepad.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.runtime.Composable

/** Keeps mandatory system gestures protected while optionally releasing cutout padding. */
@Composable
internal fun touchscreenContentInsets(useDisplayCutoutArea: Boolean): WindowInsets =
    if (useDisplayCutoutArea) WindowInsets.safeGestures else WindowInsets.safeDrawing
