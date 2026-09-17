package dev.jonalakas.bridgepad.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.ui.session.SessionOrientationMode

@Composable
fun SessionOrientationSelector(
    selected: SessionOrientationMode,
    onSelected: (SessionOrientationMode) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(sessionOrientationLabel(selected))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SessionOrientationMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sessionOrientationLabel(mode)) },
                    onClick = {
                        expanded = false
                        onSelected(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun sessionOrientationLabel(mode: SessionOrientationMode): String = stringResource(
    when (mode) {
        SessionOrientationMode.AUTO -> R.string.orientation_auto
        SessionOrientationMode.PORTRAIT -> R.string.orientation_portrait
        SessionOrientationMode.REVERSE_PORTRAIT -> R.string.orientation_reverse_portrait
        SessionOrientationMode.LANDSCAPE -> R.string.orientation_landscape
        SessionOrientationMode.REVERSE_LANDSCAPE -> R.string.orientation_reverse_landscape
    },
)
