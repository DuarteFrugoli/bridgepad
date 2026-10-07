package dev.jonalakas.bridgepad.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.ui.components.SessionOrientationSelector
import dev.jonalakas.bridgepad.ui.session.SessionOrientationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    physicalControllerConnected: Boolean,
    mappingAvailable: Boolean,
    sessionOrientationMode: SessionOrientationMode,
    invertedTouchpadScroll: Boolean,
    useDisplayCutoutArea: Boolean,
    onOpenTests: () -> Unit,
    onEditTouchscreenLayout: () -> Unit,
    onConfigureGamepadMapping: () -> Unit,
    onSessionOrientationModeChanged: (SessionOrientationMode) -> Unit,
    onInvertedTouchpadScrollChanged: (Boolean) -> Unit,
    onUseDisplayCutoutAreaChanged: (Boolean) -> Unit,
    onLanguageSettings: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cutoutConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSectionCard(title = stringResource(R.string.tests_title)) {
                    Text(stringResource(R.string.tests_settings_description))
                    OutlinedButton(
                        onClick = onOpenTests,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_tests))
                    }
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.session_orientation)) {
                    Text(stringResource(R.string.session_orientation_description))
                    SessionOrientationSelector(
                        selected = sessionOrientationMode,
                        onSelected = onSessionOrientationModeChanged,
                    )
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.virtual_gamepad_settings)) {
                    Text(stringResource(R.string.virtual_gamepad_settings_description))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.use_display_cutout_area),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = useDisplayCutoutArea,
                            onCheckedChange = { enabled ->
                                if (enabled) {
                                    cutoutConfirmationVisible = true
                                } else {
                                    onUseDisplayCutoutAreaChanged(false)
                                }
                            },
                        )
                    }
                    Text(
                        text = stringResource(R.string.use_display_cutout_area_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onEditTouchscreenLayout,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.edit_controller_layout))
                    }
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.touchpad_settings)) {
                    Text(stringResource(R.string.touchpad_settings_description))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.invert_touchpad_scroll),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = invertedTouchpadScroll,
                            onCheckedChange = onInvertedTouchpadScrollChanged,
                        )
                    }
                }
            }
            if (physicalControllerConnected) {
                item {
                    SettingsSectionCard(title = stringResource(R.string.physical_gamepad_settings)) {
                        Text(stringResource(R.string.physical_gamepad_settings_description))
                        OutlinedButton(
                            onClick = onConfigureGamepadMapping,
                            enabled = mappingAvailable,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.configure_gamepad_mapping))
                        }
                    }
                }
            }
            item {
                SettingsSectionCard(title = stringResource(R.string.language_title)) {
                    Text(stringResource(R.string.language_description))
                    if (onLanguageSettings != null) {
                        OutlinedButton(
                            onClick = onLanguageSettings,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.change_language))
                        }
                    }
                }
            }
        }
    }
    if (cutoutConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { cutoutConfirmationVisible = false },
            title = { Text(stringResource(R.string.display_cutout_warning_title)) },
            text = { Text(stringResource(R.string.display_cutout_warning_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cutoutConfirmationVisible = false
                        onUseDisplayCutoutAreaChanged(true)
                        onEditTouchscreenLayout()
                    },
                ) {
                    Text(stringResource(R.string.enable_and_edit_action))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { cutoutConfirmationVisible = false }) {
                        Text(stringResource(R.string.cancel_action))
                    }
                    TextButton(
                        onClick = {
                            cutoutConfirmationVisible = false
                            onUseDisplayCutoutAreaChanged(true)
                        },
                    ) {
                        Text(stringResource(R.string.enable_only_action))
                    }
                }
            },
        )
    }
}
