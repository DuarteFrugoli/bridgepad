package dev.jonalakas.bridgepad.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.*
import dev.jonalakas.bridgepad.core.session.SessionStatus as HidSessionStatus
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import dev.jonalakas.bridgepad.session.*
import dev.jonalakas.bridgepad.session.FeedbackLevel as HidFeedbackLevel
import dev.jonalakas.bridgepad.session.SessionState as HidSessionState
import dev.jonalakas.bridgepad.transport.network.NetworkFailureReason
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadStatus
import dev.jonalakas.bridgepad.ui.components.NoticeCard
import dev.jonalakas.bridgepad.ui.components.NoticeTone
import dev.jonalakas.bridgepad.ui.components.SessionOrientationSelector
import dev.jonalakas.bridgepad.ui.session.SessionOrientationMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    bluetoothPermissionGranted: Boolean,
    bluetoothEnabled: Boolean,
    hidCompatible: Boolean,
    hidState: HidSessionState,
    physicalGamepadState: PhysicalGamepadState,
    physicalCaptureMode: PhysicalCaptureMode?,
    sessionDraft: SessionDraft,
    outputAdapters: OutputAdapterCatalog,
    destinationType: DestinationType?,
    connectionMethod: ConnectionMethod?,
    directUsbState: DirectUsbState,
    mappingAvailable: Boolean,
    onDestinationChanged: (DestinationType) -> Unit,
    onSelectBluetooth: () -> Unit,
    onSelectWifi: () -> Unit,
    pairedHosts: List<PairedHost>,
    selectedAddress: String?,
    pairNewPcSelected: Boolean,
    preparingConnection: Boolean,
    onSelectHost: (String?) -> Unit,
    discoveredDesktops: List<DiscoveredDesktop>,
    trustedDesktops: List<TrustedDesktop>,
    selectedNetworkDesktopId: String?,
    networkPairingStatus: NetworkPairingStatus,
    networkGameplayStatus: NetworkGamepadStatus,
    bluetoothDesktopGameplayStatus: BluetoothDesktopGamepadStatus,
    networkDiscoveryError: String?,
    onSelectNetworkDesktop: (String?) -> Unit,
    onPairNetworkDesktop: (String, String) -> Unit,
    onForgetNetworkDesktop: (String) -> Unit,
    onRepairNetworkDesktop: (String) -> Unit,
    onDismissNetworkPairingStatus: () -> Unit,
    onPhysicalCaptureModeChanged: (PhysicalCaptureMode) -> Unit,
    onPrepareBluetooth: () -> Unit,
    onUseDirectBluetooth: () -> Unit,
    onPlay: () -> Unit,
    onConfigureGamepadMapping: () -> Unit,
    onEditTouchscreenLayout: () -> Unit,
    sessionOrientationMode: SessionOrientationMode,
    onSessionOrientationModeChanged: (SessionOrientationMode) -> Unit,
    onOpenTouchController: () -> Unit,
    onOpenMouseTouchpad: () -> Unit,
    onStopHid: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pairingDesktopId by rememberSaveable { mutableStateOf<String?>(null) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var forgettingDesktopId by rememberSaveable { mutableStateOf<String?>(null) }
    val bluetoothSelected = connectionMethod == ConnectionMethod.BLUETOOTH
    val wifiSelected = connectionMethod == ConnectionMethod.WIFI
    val networkConnected = networkGameplayStatus is NetworkGamepadStatus.Active
    val bluetoothDesktopConnected =
        bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Active
    val connected = hidState.status == HidSessionStatus.CONNECTED ||
        networkConnected || bluetoothDesktopConnected
    val visibleHidState = hidState.reconcileBluetoothAvailability(
        enabled = bluetoothEnabled && bluetoothPermissionGranted,
        permissionGranted = bluetoothPermissionGranted,
    )
    val physicalConnected = physicalGamepadState.devices.isNotEmpty() || directUsbState.active
    val selectedNetworkTrusted = trustedDesktops.any { it.peerIdHex == selectedNetworkDesktopId }
    val selectedNetworkOnline = discoveredDesktops.any { it.peerIdHex == selectedNetworkDesktopId }
    val targetChosen = connected || when {
        destinationType != DestinationType.PC -> false
        bluetoothSelected -> selectedAddress != null || pairNewPcSelected
        wifiSelected -> selectedNetworkDesktopId != null
        else -> false
    }
    val setupComplete = if (wifiSelected) {
        destinationType == DestinationType.PC && selectedNetworkTrusted && selectedNetworkOnline
    } else {
        SessionSetup.canConnect(
            draft = sessionDraft,
            adapters = outputAdapters,
            connectionAvailable = bluetoothEnabled && bluetoothPermissionGranted,
            availableTargetIds = pairedHosts.map { it.address },
        )
    }
    val busy = preparingConnection || hidState.status in listOf(
        HidSessionStatus.STARTING,
        HidSessionStatus.REGISTERING,
        HidSessionStatus.CONNECTING,
    ) || hidState.pairingModeActive ||
        networkGameplayStatus is NetworkGamepadStatus.Connecting ||
        networkGameplayStatus is NetworkGamepadStatus.Reconnecting ||
        bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Connecting ||
        bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Reconnecting ||
        networkPairingStatus is NetworkPairingStatus.Pairing

    PairingDialog(
        peerId = pairingDesktopId,
        desktopName = discoveredDesktops.firstOrNull { it.peerIdHex == pairingDesktopId }?.name.orEmpty(),
        code = pairingCode,
        onCodeChanged = { pairingCode = it.filter(Char::isDigit).take(12) },
        onConfirm = { peerId ->
            onPairNetworkDesktop(peerId, pairingCode)
            pairingDesktopId = null
            pairingCode = ""
        },
        onDismiss = {
            pairingDesktopId = null
            pairingCode = ""
        },
    )
    ForgetDialog(
        peerId = forgettingDesktopId,
        desktopName = trustedDesktops.firstOrNull { it.peerIdHex == forgettingDesktopId }?.name.orEmpty(),
        onConfirm = { peerId ->
            onForgetNetworkDesktop(peerId)
            forgettingDesktopId = null
        },
        onDismiss = { forgettingDesktopId = null },
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    stringResource(if (connected) R.string.session_active else R.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(stringResource(R.string.home_description), style = MaterialTheme.typography.bodyMedium)
            }
            item {
                SetupCard(R.string.step_destination) {
                    Choice(
                        destinationType == DestinationType.PC,
                        R.string.destination_pc,
                        !busy && !connected,
                    ) { onDestinationChanged(DestinationType.PC) }
                }
            }
            if (destinationType != null) {
                item {
                    SetupCard(R.string.step_transport) {
                        Choice(
                            bluetoothSelected,
                            R.string.bluetooth_label,
                            !busy && !connected,
                            onSelectBluetooth,
                        )
                        Choice(wifiSelected, R.string.wifi_label, !busy && !connected, onSelectWifi)
                        Choice(false, R.string.usb_connection_coming_soon, false) {}
                        if (bluetoothSelected) {
                            BluetoothDestinations(
                                connected,
                                if (bluetoothDesktopConnected) {
                                    pairedHosts.firstOrNull { it.address == selectedAddress }?.name
                                } else {
                                    hidState.connectedHost
                                },
                                bluetoothPermissionGranted,
                                bluetoothEnabled,
                                busy,
                                pairedHosts,
                                selectedAddress,
                                pairNewPcSelected,
                                onPrepareBluetooth,
                                onSelectHost,
                            )
                        }
                        if (wifiSelected) {
                            WifiDestinations(
                                connected,
                                busy,
                                discoveredDesktops,
                                trustedDesktops,
                                selectedNetworkDesktopId,
                                networkDiscoveryError,
                                onSelectNetworkDesktop,
                                onPair = { pairingDesktopId = it },
                                onForget = { forgettingDesktopId = it },
                            )
                        }
                    }
                }
            }
            if (targetChosen) {
                item {
                    InputCard(
                        physicalGamepadState,
                        directUsbState,
                        physicalCaptureMode,
                        mappingAvailable,
                        busy,
                        onPhysicalCaptureModeChanged,
                        onConfigureGamepadMapping,
                        onEditTouchscreenLayout,
                        sessionOrientationMode,
                        onSessionOrientationModeChanged,
                    )
                }
            }
            if (bluetoothSelected && !hidCompatible && pairNewPcSelected) {
                item { NoticeCard(stringResource(R.string.hid_unavailable), NoticeTone.ERROR) }
            }
            if (bluetoothSelected && hidState.sessionActive && visibleHidState.message != null) {
                item {
                    NoticeCard(
                        stringResource(
                            visibleHidState.message.resourceId,
                            *visibleHidState.message.arguments.toTypedArray(),
                        ),
                        when (visibleHidState.feedbackLevel) {
                            HidFeedbackLevel.ERROR -> NoticeTone.ERROR
                            HidFeedbackLevel.WARNING -> NoticeTone.WARNING
                            HidFeedbackLevel.INFO -> NoticeTone.SUCCESS
                        },
                    )
                }
            }
            if (bluetoothSelected) {
                when (val status = bluetoothDesktopGameplayStatus) {
                    BluetoothDesktopGamepadStatus.Connecting -> item {
                        NoticeCard(
                            stringResource(R.string.bluetooth_desktop_connecting),
                            NoticeTone.WARNING,
                        )
                    }
                    is BluetoothDesktopGamepadStatus.Reconnecting -> item {
                        NoticeCard(
                            stringResource(
                                R.string.bluetooth_desktop_reconnecting,
                                status.attempt,
                                status.maximumAttempts,
                            ),
                            NoticeTone.WARNING,
                        )
                    }
                    BluetoothDesktopGamepadStatus.Active -> item {
                        NoticeCard(
                            stringResource(R.string.bluetooth_desktop_connected),
                            NoticeTone.SUCCESS,
                        )
                    }
                    is BluetoothDesktopGamepadStatus.Failed -> item {
                        NoticeCard(
                            message = stringResource(R.string.bluetooth_desktop_unavailable),
                            tone = NoticeTone.WARNING,
                            actionLabel = if (hidCompatible) {
                                stringResource(R.string.bluetooth_use_direct_hid)
                            } else null,
                            onAction = if (hidCompatible) onUseDirectBluetooth else null,
                        )
                    }
                    BluetoothDesktopGamepadStatus.Stopped -> Unit
                }
            }
            if (wifiSelected) {
                when (val pairing = networkPairingStatus) {
                    is NetworkPairingStatus.Failed -> item {
                        NoticeCard(stringResource(pairing.reason.messageResource()), NoticeTone.ERROR)
                    }
                    is NetworkPairingStatus.Success -> item {
                        NoticeCard(stringResource(R.string.wifi_pairing_complete), NoticeTone.SUCCESS)
                        LaunchedEffect(pairing.peerIdHex) {
                            kotlinx.coroutines.delay(3_000)
                            onDismissNetworkPairingStatus()
                        }
                    }
                    else -> Unit
                }
            }
            if (wifiSelected) {
                when (val status = networkGameplayStatus) {
                    is NetworkGamepadStatus.Reconnecting -> item {
                        NoticeCard(
                            stringResource(
                                R.string.wifi_reconnecting,
                                status.attempt,
                                status.maximumAttempts,
                            ),
                            NoticeTone.WARNING,
                        )
                    }
                    is NetworkGamepadStatus.Failed -> item {
                        val repairPeerId = selectedNetworkDesktopId?.takeIf {
                            status.reason == NetworkFailureReason.AUTHENTICATION_REJECTED &&
                                selectedNetworkOnline
                        }
                        NoticeCard(
                            message = stringResource(status.reason.messageResource()),
                            tone = NoticeTone.ERROR,
                            actionLabel = repairPeerId?.let {
                                stringResource(R.string.wifi_pair_again_action)
                            },
                            onAction = repairPeerId?.let { peerId ->
                                {
                                    onRepairNetworkDesktop(peerId)
                                    pairingDesktopId = peerId
                                }
                            },
                        )
                    }
                    else -> Unit
                }
            }
            if (!connected) {
                item {
                    if (!setupComplete && !busy) {
                        Text(
                            stringResource(R.string.complete_session_setup),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = onPlay,
                        enabled = (!bluetoothSelected || selectedAddress != null || hidCompatible) &&
                            !busy && setupComplete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    hidState.pairingModeActive -> R.string.waiting_for_pairing
                                    busy -> R.string.preparing_connection
                                    else -> R.string.connect_and_play
                                },
                            ),
                        )
                    }
                }
            }
            if (connected) {
                item {
                    SetupCard(R.string.session_screens) {
                        Button(onClick = onOpenTouchController, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.open_virtual_controller))
                        }
                        OutlinedButton(onClick = onOpenMouseTouchpad, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.open_mouse_touchpad))
                        }
                        Text(
                            stringResource(R.string.session_screens_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (
                hidState.sessionActive || networkConnected ||
                networkGameplayStatus is NetworkGamepadStatus.Reconnecting ||
                bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Connecting ||
                bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Reconnecting ||
                bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Active
            ) {
                item {
                    OutlinedButton(onClick = onStopHid, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.end_session))
                    }
                }
            }
        }
    }
}

@Composable
private fun BluetoothDestinations(
    connected: Boolean,
    connectedHostName: String?,
    permissionGranted: Boolean,
    enabled: Boolean,
    busy: Boolean,
    hosts: List<PairedHost>,
    selectedAddress: String?,
    pairNewPcSelected: Boolean,
    onPrepare: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    HorizontalDivider()
    Text(stringResource(R.string.choose_destination_title), style = MaterialTheme.typography.titleSmall)
    when {
        connected -> {
            Text(stringResource(R.string.connected_to, connectedHostName.orEmpty()))
            Text(stringResource(R.string.change_destination_hint), style = MaterialTheme.typography.bodySmall)
        }
        !permissionGranted -> {
            Text(stringResource(R.string.bluetooth_destination_permission))
            OutlinedButton(onClick = onPrepare, enabled = !busy) {
                Text(stringResource(R.string.grant_permissions))
            }
        }
        !enabled -> {
            Text(stringResource(R.string.bluetooth_destination_off))
            OutlinedButton(onClick = onPrepare, enabled = !busy) {
                Text(stringResource(R.string.enable_bluetooth))
            }
        }
        else -> {
            hosts.forEach { host ->
                FilterChip(
                    selected = selectedAddress == host.address,
                    onClick = { onSelect(host.address) },
                    label = { Text(host.name) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Choice(pairNewPcSelected, R.string.pair_new_pc, !busy) { onSelect(null) }
            if (selectedAddress == null && !pairNewPcSelected) {
                Text(stringResource(R.string.choose_destination_hint))
            }
            if (selectedAddress != null && hosts.none { it.address == selectedAddress }) {
                NoticeCard(stringResource(R.string.selected_pc_unavailable), NoticeTone.WARNING)
            }
        }
    }
}

@Composable
private fun WifiDestinations(
    connected: Boolean,
    busy: Boolean,
    discovered: List<DiscoveredDesktop>,
    trusted: List<TrustedDesktop>,
    selectedId: String?,
    discoveryError: String?,
    onSelect: (String?) -> Unit,
    onPair: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    HorizontalDivider()
    Text(stringResource(R.string.choose_destination_title), style = MaterialTheme.typography.titleSmall)
    val discoveredIds = discovered.mapTo(hashSetOf()) { it.peerIdHex }
    if (connected) {
        val name = trusted.firstOrNull { it.peerIdHex == selectedId }?.name.orEmpty()
        Text(stringResource(R.string.connected_to, name))
        Text(stringResource(R.string.change_destination_hint), style = MaterialTheme.typography.bodySmall)
        return
    }
    trusted.forEach { desktop ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedId == desktop.peerIdHex,
                onClick = { onSelect(desktop.peerIdHex) },
                label = {
                    Text(
                        stringResource(
                            if (desktop.peerIdHex in discoveredIds) {
                                R.string.wifi_desktop_online
                            } else {
                                R.string.wifi_desktop_offline
                            },
                            desktop.name,
                        ),
                    )
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onForget(desktop.peerIdHex) }, enabled = !busy) {
                Text(stringResource(R.string.forget_action))
            }
        }
    }
    discovered.filter { candidate -> trusted.none { it.peerIdHex == candidate.peerIdHex } }
        .forEach { desktop ->
            OutlinedButton(
                onClick = { onPair(desktop.peerIdHex) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.wifi_pair_desktop, desktop.name)) }
        }
    if (trusted.isEmpty() && discovered.isEmpty()) {
        Text(stringResource(R.string.wifi_searching_desktops))
    }
    if (discoveryError != null) {
        NoticeCard(stringResource(R.string.wifi_discovery_failed), NoticeTone.WARNING)
    }
    if (selectedId != null && selectedId !in discoveredIds) {
        NoticeCard(stringResource(R.string.wifi_desktop_not_available), NoticeTone.WARNING)
    }
}

@Composable
private fun InputCard(
    physical: PhysicalGamepadState,
    usb: DirectUsbState,
    captureMode: PhysicalCaptureMode?,
    mappingAvailable: Boolean,
    busy: Boolean,
    onCaptureModeChanged: (PhysicalCaptureMode) -> Unit,
    onConfigureMapping: () -> Unit,
    onEditLayout: () -> Unit,
    orientationMode: SessionOrientationMode,
    onOrientationModeChanged: (SessionOrientationMode) -> Unit,
) {
    val physicalConnected = physical.devices.isNotEmpty() || usb.active
    SetupCard(R.string.step_input) {
        Text(stringResource(R.string.automatic_input_description))
        Text(stringResource(R.string.session_orientation), style = MaterialTheme.typography.titleSmall)
        SessionOrientationSelector(
            selected = orientationMode,
            enabled = !busy,
            onSelected = onOrientationModeChanged,
        )
        Text(stringResource(R.string.session_orientation_description), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.virtual_controller), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = onEditLayout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.edit_controller_layout))
        }
        if (!physicalConnected) {
            Text(stringResource(R.string.automatic_input_virtual_ready), style = MaterialTheme.typography.bodySmall)
            return@SetupCard
        }
        HorizontalDivider()
        Text(stringResource(R.string.physical_controller), style = MaterialTheme.typography.titleSmall)
        val names = physical.devices.joinToString { it.name }
        Text(usb.deviceName ?: names, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.capture_mode), style = MaterialTheme.typography.titleSmall)
        Choice(captureMode == PhysicalCaptureMode.COMPATIBILITY, R.string.compatibility_mode, !busy) {
            onCaptureModeChanged(PhysicalCaptureMode.COMPATIBILITY)
        }
        Text(stringResource(R.string.compatibility_mode_description), style = MaterialTheme.typography.bodySmall)
        Choice(captureMode == PhysicalCaptureMode.BACKGROUND_USB, R.string.background_usb_mode, !busy) {
            onCaptureModeChanged(PhysicalCaptureMode.BACKGROUND_USB)
        }
        Text(stringResource(R.string.background_usb_description), style = MaterialTheme.typography.bodySmall)
        Text(
            when (captureMode) {
                PhysicalCaptureMode.BACKGROUND_USB -> usb.deviceName
                    ?: stringResource(R.string.physical_input_missing_usb)
                PhysicalCaptureMode.COMPATIBILITY -> names.ifEmpty {
                    stringResource(R.string.physical_input_missing)
                }
                null -> stringResource(R.string.compatibility_mode)
            },
        )
        if (captureMode == PhysicalCaptureMode.BACKGROUND_USB && usb.statusMessage != null) {
            NoticeCard(
                stringResource(usb.statusMessage.resourceId, *usb.statusMessage.arguments.toTypedArray()),
                if (usb.statusIsError) NoticeTone.WARNING else NoticeTone.SUCCESS,
            )
        }
        OutlinedButton(
            onClick = onConfigureMapping,
            enabled = mappingAvailable && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.configure_gamepad_mapping)) }
        Text(stringResource(R.string.mapping_optional_both_modes), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PairingDialog(
    peerId: String?,
    desktopName: String,
    code: String,
    onCodeChanged: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    peerId ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_pair_title, desktopName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wifi_pair_description))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChanged,
                    label = { Text(stringResource(R.string.wifi_pairing_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(peerId) }, enabled = code.length == 12) {
                Text(stringResource(R.string.pair_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) } },
    )
}

@Composable
private fun ForgetDialog(
    peerId: String?,
    desktopName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    peerId ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_forget_title, desktopName)) },
        text = { Text(stringResource(R.string.wifi_forget_description)) },
        confirmButton = {
            TextButton(onClick = { onConfirm(peerId) }) {
                Text(stringResource(R.string.forget_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) } },
    )
}

@Composable
private fun SetupCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Choice(selected: Boolean, label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun NetworkPairingFailure.messageResource(): Int = when (this) {
    NetworkPairingFailure.CODE_REJECTED -> R.string.wifi_pairing_code_rejected
    NetworkPairingFailure.IDENTITY_CHANGED -> R.string.wifi_identity_changed
    NetworkPairingFailure.DESKTOP_UNAVAILABLE -> R.string.wifi_desktop_not_available
    NetworkPairingFailure.INVALID_CODE -> R.string.wifi_pairing_code_invalid
    NetworkPairingFailure.UNKNOWN -> R.string.wifi_pairing_failed
}

private fun NetworkFailureReason.messageResource(): Int = when (this) {
    NetworkFailureReason.DESKTOP_UNAVAILABLE -> R.string.wifi_desktop_not_available
    NetworkFailureReason.CERTIFICATE_CHANGED -> R.string.wifi_identity_changed
    NetworkFailureReason.AUTHENTICATION_REJECTED -> R.string.wifi_authentication_rejected
    NetworkFailureReason.CONNECTION_LOST -> R.string.wifi_connection_lost
    NetworkFailureReason.PROTOCOL_ERROR -> R.string.wifi_protocol_error
    NetworkFailureReason.UNKNOWN -> R.string.wifi_connection_failed
}
