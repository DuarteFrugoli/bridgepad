package dev.jonalakas.bridgepad

import dev.jonalakas.bridgepad.localization.LocalizedMessage
import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.jonalakas.bridgepad.session.PairedHost
import dev.jonalakas.bridgepad.session.reconcileBluetoothAvailability
import dev.jonalakas.bridgepad.core.session.SessionStatus as HidSessionStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jonalakas.bridgepad.diagnostics.AndroidDeviceInfoProvider
import dev.jonalakas.bridgepad.diagnostics.DiagnosticReport
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.input.android.AndroidGamepadController
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchpadSettingsStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadStore
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore
import dev.jonalakas.bridgepad.session.FeedbackLevel as HidFeedbackLevel
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationTarget
import dev.jonalakas.bridgepad.core.session.DestinationTargetKind
import dev.jonalakas.bridgepad.core.session.OutputAdapterIds
import dev.jonalakas.bridgepad.core.session.SessionDraft
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.ui.home.HomeScreen
import dev.jonalakas.bridgepad.ui.home.DestinationSelection
import dev.jonalakas.bridgepad.session.SessionSetup
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.gamepad.MouseTouchpadScreen
import dev.jonalakas.bridgepad.ui.gamepad.GameplayKeyboardState
import dev.jonalakas.bridgepad.ui.gamepad.GameplayKeyboardScreen
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayoutEditorScreen
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayoutOrientation
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayoutStore
import dev.jonalakas.bridgepad.ui.onboarding.OnboardingScreen
import dev.jonalakas.bridgepad.ui.mapping.GamepadMappingInput
import dev.jonalakas.bridgepad.ui.mapping.GamepadMappingScreen
import dev.jonalakas.bridgepad.ui.settings.SettingsScreen
import dev.jonalakas.bridgepad.ui.settings.NetworkDiagnosticScreen
import dev.jonalakas.bridgepad.ui.settings.BluetoothDesktopDiagnosticScreen
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopProbe
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadStatus
import dev.jonalakas.bridgepad.ui.session.SessionSurface
import dev.jonalakas.bridgepad.ui.session.SessionOrientationMode
import dev.jonalakas.bridgepad.ui.session.SessionOrientationStore
import dev.jonalakas.bridgepad.ui.session.SessionUiEvent
import dev.jonalakas.bridgepad.ui.session.SessionUiViewModel
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

class MainActivity : ComponentActivity() {
    private lateinit var gamepadController: AndroidGamepadController
    private lateinit var networkDesktopCoordinator: dev.jonalakas.bridgepad.session.NetworkDesktopCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gamepadController = AndroidGamepadController(this)
        val bridgePadApplication = application as BridgePadApplication
        val sessionCoordinator = bridgePadApplication.sessionCoordinator
        val networkGameplayController = bridgePadApplication.networkGameplayController
        val bluetoothDesktopGameplayController = bridgePadApplication.bluetoothDesktopGameplayController
        networkDesktopCoordinator = bridgePadApplication.networkDesktopCoordinator

        val deviceInfo = AndroidDeviceInfoProvider.get()
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        setContent {
            BridgePadTheme {
                var bluetoothPermissionGranted by remember { mutableStateOf(hasBluetoothPermission()) }
                val sessionUiViewModel: SessionUiViewModel = viewModel()
                val sessionUiState by sessionUiViewModel.state.collectAsState()
                val gameplayKeyboardState = remember { GameplayKeyboardState() }
                var showGamepadMapping by rememberSaveable { mutableStateOf(false) }
                var returnToSettingsAfterGamepadMapping by rememberSaveable { mutableStateOf(false) }
                var showTouchscreenLayoutEditor by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showNetworkDiagnostic by rememberSaveable { mutableStateOf(false) }
                var showBluetoothDesktopDiagnostic by rememberSaveable { mutableStateOf(false) }
                var returnToSettingsAfterLayoutEditor by rememberSaveable { mutableStateOf(false) }
                var onboardingComplete by rememberSaveable {
                    mutableStateOf(preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false))
                }
                var destinationTypeName by rememberSaveable { mutableStateOf<String?>(null) }
                var connectionMethodName by rememberSaveable { mutableStateOf<String?>(null) }
                var captureModeName by rememberSaveable {
                    mutableStateOf(PhysicalCaptureMode.COMPATIBILITY.name)
                }
                var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
                var selectedNetworkDesktopId by rememberSaveable { mutableStateOf<String?>(null) }
                var pairNewPcSelected by rememberSaveable { mutableStateOf(false) }
                var useDirectBluetooth by rememberSaveable { mutableStateOf(false) }
                var pendingDestination by rememberSaveable { mutableStateOf<String?>(null) }
                var connectionGate by rememberSaveable { mutableStateOf<String?>(null) }
                var pairedHosts by remember { mutableStateOf(readPairedHosts()) }
                var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled()) }
                DisposableEffect(Unit) {
                    fun refresh() {
                        bluetoothPermissionGranted = hasBluetoothPermission()
                        bluetoothEnabled = isBluetoothEnabled()
                        pairedHosts = readPairedHosts()
                    }
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) refresh()
                    }
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
                    }
                    lifecycle.addObserver(observer)
                    ContextCompat.registerReceiver(
                        this@MainActivity, receiver,
                        IntentFilter().apply {
                            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                        },
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                    onDispose {
                        lifecycle.removeObserver(observer)
                        unregisterReceiver(receiver)
                    }
                }
                val enableBluetoothLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    bluetoothEnabled = isBluetoothEnabled()
                    connectionGate = null
                    if (!bluetoothEnabled && result.resultCode == RESULT_OK && pendingDestination != null) {
                        // Approval may arrive before the adapter actually reaches STATE_ON.
                        connectionGate = "bluetooth_starting"
                    } else if (!bluetoothEnabled) {
                        pendingDestination = null
                        sessionCoordinator.updateState { it.copy(message = LocalizedMessage(R.string.bluetooth_required), feedbackLevel = HidFeedbackLevel.WARNING) }
                    }
                    pairedHosts = readPairedHosts()
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    bluetoothPermissionGranted = hasBluetoothPermission()
                    pairedHosts = readPairedHosts()
                    bluetoothEnabled = isBluetoothEnabled()
                    connectionGate = null
                    if (!bluetoothPermissionGranted) {
                        pendingDestination = null
                        SessionLog.record("PERMISSION", "Bluetooth permission was not granted")
                        sessionCoordinator.updateState {
                            it.copy(
                                message = LocalizedMessage(R.string.bluetooth_permission_required),
                                feedbackLevel = HidFeedbackLevel.WARNING,
                            )
                        }
                    }
                }
                val discoverableLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (!sessionCoordinator.state.value.sessionActive) {
                        sessionUiViewModel.dispatch(
                            SessionUiEvent.TransportFailed(ConnectionMethod.BLUETOOTH),
                        )
                    } else if (result.resultCode > 0) {
                        sessionCoordinator.pairingWindowStarted(result.resultCode)
                    } else {
                        sessionUiViewModel.dispatch(
                            SessionUiEvent.TransportFailed(ConnectionMethod.BLUETOOTH),
                        )
                        sessionCoordinator.updateState {
                            it.copy(
                                message = LocalizedMessage(R.string.visibility_not_enabled),
                                feedbackLevel = HidFeedbackLevel.WARNING,
                            )
                        }
                    }
                }
                val hidState by sessionCoordinator.state.collectAsState()
                val networkGameplayStatus by networkGameplayController.status.collectAsState()
                val bluetoothDesktopGameplayStatus by
                    bluetoothDesktopGameplayController.status.collectAsState()
                val gameplaySessionActive = hidState.sessionActive ||
                    networkGameplayStatus is NetworkGamepadStatus.Connecting ||
                    networkGameplayStatus is NetworkGamepadStatus.Reconnecting ||
                    networkGameplayStatus is NetworkGamepadStatus.Active ||
                    bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Connecting ||
                    bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Reconnecting ||
                    bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Active
                val discoveredDesktops by networkDesktopCoordinator.discoveredDesktops.collectAsState()
                val trustedDesktops by networkDesktopCoordinator.trustedDesktops.collectAsState()
                val networkPairingStatus by networkDesktopCoordinator.pairingStatus.collectAsState()
                val networkDiscoveryError by networkDesktopCoordinator.discoveryError.collectAsState()
                val touchscreenLayoutProfile by TouchscreenLayoutStore.profile.collectAsState()
                val sessionOrientationMode by SessionOrientationStore.mode.collectAsState()
                val invertedTouchpadScroll by TouchpadSettingsStore.invertedScroll.collectAsState()
                val configurationOrientation = LocalConfiguration.current.orientation
                val currentLayoutOrientation = if (configurationOrientation == Configuration.ORIENTATION_PORTRAIT) {
                    TouchscreenLayoutOrientation.PORTRAIT
                } else {
                    TouchscreenLayoutOrientation.LANDSCAPE
                }
                val touchscreenLayout = touchscreenLayoutProfile.layout(currentLayoutOrientation)
                val physicalGamepadState by PhysicalGamepadStore.state.collectAsState()
                val directUsbState by DirectUsbGamepadStore.state.collectAsState()
                val physicalControllerConnected =
                    physicalGamepadState.devices.isNotEmpty() || directUsbState.active
                val effectiveCaptureMode = if (hidState.sessionActive) {
                    hidState.physicalCaptureMode
                } else {
                    PhysicalCaptureMode.valueOf(captureModeName)
                }
                val effectiveDestinationType = if (hidState.sessionActive) {
                    hidState.destinationType
                } else {
                    destinationTypeName?.let(DestinationType::valueOf)
                }
                val effectiveConnectionMethod = if (hidState.sessionActive) {
                    hidState.connectionMethod
                } else {
                    connectionMethodName?.let(ConnectionMethod::valueOf)
                }
                val sessionDraft = SessionDraft(
                    destinationType = effectiveDestinationType,
                    connectionMethod = effectiveConnectionMethod,
                    outputAdapterId = if (
                        effectiveDestinationType == DestinationType.PC &&
                        effectiveConnectionMethod == ConnectionMethod.BLUETOOTH
                    ) {
                        if (selectedAddress != null && !useDirectBluetooth) {
                            OutputAdapterIds.DESKTOP_BLUETOOTH
                        } else {
                            OutputAdapterIds.GENERIC_BLUETOOTH_HID
                        }
                    } else null,
                    destinationTarget = when {
                        selectedAddress != null -> DestinationTarget(
                            DestinationTargetKind.EXISTING,
                            selectedAddress,
                        )
                        pairNewPcSelected -> DestinationTarget(DestinationTargetKind.NEW_PAIRING)
                        else -> null
                    },
                    physicalCaptureMode = effectiveCaptureMode,
                )
                val mappingInput = when (effectiveCaptureMode) {
                    PhysicalCaptureMode.BACKGROUND_USB -> directUsbState.deviceKey?.let { key ->
                        GamepadMappingInput(
                            deviceName = directUsbState.deviceName ?: getString(R.string.usb_gamepad),
                            deviceKey = key,
                            rawGamepad = directUsbState.rawGamepad,
                            inputEventCount = directUsbState.inputEventCount,
                        )
                    }
                    PhysicalCaptureMode.COMPATIBILITY -> physicalGamepadState.devices.firstOrNull()?.let { device ->
                        GamepadMappingInput(
                            deviceName = device.name,
                            deviceKey = device.mappingKey,
                            rawGamepad = physicalGamepadState.rawSourceStates[device.sourceId]
                                ?: dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState(),
                            inputEventCount = physicalGamepadState.inputEventCount,
                        )
                    }
                }

                LaunchedEffect(showGamepadMapping, mappingInput) {
                    if (showGamepadMapping && mappingInput == null) {
                        showGamepadMapping = false
                        if (returnToSettingsAfterGamepadMapping) showSettings = true
                        returnToSettingsAfterGamepadMapping = false
                    }
                }

                LaunchedEffect(networkPairingStatus) {
                    val success = networkPairingStatus as? dev.jonalakas.bridgepad.session.NetworkPairingStatus.Success
                    if (success != null) selectedNetworkDesktopId = success.peerIdHex
                }

                LaunchedEffect(
                    networkGameplayStatus,
                    physicalControllerConnected,
                    showNetworkDiagnostic,
                ) {
                    if (!showNetworkDiagnostic) {
                        when (networkGameplayStatus) {
                            NetworkGamepadStatus.Active -> sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportConnected(
                                    ConnectionMethod.WIFI,
                                    physicalControllerConnected,
                                ),
                            )
                            is NetworkGamepadStatus.Failed -> sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportFailed(ConnectionMethod.WIFI),
                            )
                            NetworkGamepadStatus.Stopped -> sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportStopped(ConnectionMethod.WIFI),
                            )
                            else -> Unit
                        }
                    }
                }

                LaunchedEffect(
                    bluetoothDesktopGameplayStatus,
                    physicalControllerConnected,
                    showBluetoothDesktopDiagnostic,
                ) {
                    if (!showBluetoothDesktopDiagnostic) {
                        when (bluetoothDesktopGameplayStatus) {
                            BluetoothDesktopGamepadStatus.Active -> sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportConnected(
                                    ConnectionMethod.BLUETOOTH,
                                    physicalControllerConnected,
                                ),
                            )
                            is BluetoothDesktopGamepadStatus.Failed -> sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportFailed(ConnectionMethod.BLUETOOTH),
                            )
                            BluetoothDesktopGamepadStatus.Stopped -> {
                                if (!hidState.sessionActive) {
                                    sessionUiViewModel.dispatch(
                                        SessionUiEvent.TransportStopped(ConnectionMethod.BLUETOOTH),
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }

                LaunchedEffect(physicalControllerConnected) {
                    sessionUiViewModel.dispatch(
                        SessionUiEvent.PhysicalControllerChanged(physicalControllerConnected),
                    )
                }

                LaunchedEffect(
                    sessionUiState.expectedTransport,
                    sessionUiState.activeTransport,
                ) {
                    if (
                        sessionUiState.expectedTransport == null &&
                        sessionUiState.activeTransport == null
                    ) {
                        gameplayKeyboardState.reset()
                    }
                }

                val diagnosticUsesGamepadMode =
                    showNetworkDiagnostic && networkGameplayStatus is NetworkGamepadStatus.Active ||
                        showBluetoothDesktopDiagnostic &&
                        bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Active
                val compatibilityInputNeedsScreen =
                    sessionUiState.activeTransport != null &&
                        physicalControllerConnected &&
                        effectiveCaptureMode == PhysicalCaptureMode.COMPATIBILITY
                val keepScreenAwake =
                    sessionUiState.surface != SessionSurface.NONE ||
                        showTouchscreenLayoutEditor ||
                        diagnosticUsesGamepadMode ||
                        compatibilityInputNeedsScreen

                DisposableEffect(keepScreenAwake) {
                    if (keepScreenAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        if (keepScreenAwake) {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }

                LaunchedEffect(
                    sessionUiState.surface,
                    showNetworkDiagnostic,
                    networkGameplayStatus,
                    bluetoothDesktopGameplayStatus,
                    showTouchscreenLayoutEditor,
                    sessionOrientationMode,
                ) {
                    when {
                        showTouchscreenLayoutEditor -> enterGamepadMode(SessionOrientationMode.AUTO)
                        sessionUiState.surface != SessionSurface.NONE || diagnosticUsesGamepadMode -> {
                            enterGamepadMode(sessionOrientationMode)
                        }
                        else -> exitGamepadMode()
                    }
                }

                LaunchedEffect(bluetoothEnabled, bluetoothPermissionGranted) {
                    if (!bluetoothEnabled || !bluetoothPermissionGranted) {
                        pairNewPcSelected = false
                    }
                    sessionCoordinator.updateState {
                        it.reconcileBluetoothAvailability(
                            enabled = bluetoothEnabled && bluetoothPermissionGranted,
                            permissionGranted = bluetoothPermissionGranted,
                        )
                    }
                }
                LaunchedEffect(connectionGate) {
                    if (connectionGate == "bluetooth_starting") {
                        kotlinx.coroutines.delay(15_000)
                        if (!isBluetoothEnabled()) {
                            pendingDestination = null
                            connectionGate = null
                            sessionCoordinator.updateState { it.copy(message = LocalizedMessage(R.string.bluetooth_required), feedbackLevel = HidFeedbackLevel.WARNING) }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // A restored Activity must not wait forever for a service lost with the process.
                    if (connectionGate == "service" && !sessionCoordinator.state.value.sessionActive) {
                        pendingDestination = null
                        connectionGate = null
                    }
                }

                // A user gesture owns the whole preflight. Recomposition never starts a session by itself.
                LaunchedEffect(pendingDestination, connectionGate, bluetoothPermissionGranted, bluetoothEnabled, hidState.status, hidState.sessionActive, hidState.message) {
                    val destination = pendingDestination ?: return@LaunchedEffect
                    when {
                        connectionGate == "permission" || connectionGate == "bluetooth" -> Unit
                        connectionGate == "bluetooth_starting" && !bluetoothEnabled -> Unit
                        connectionGate == "bluetooth_starting" -> connectionGate = null
                        connectionGate == "restart" && !hidState.sessionActive -> connectionGate = null
                        connectionGate == "service" && !bluetoothEnabled -> {
                            pendingDestination = null
                            connectionGate = null
                        }
                        !bluetoothPermissionGranted -> {
                            pendingDestination = DestinationSelection.CHOOSE_PC
                            pairNewPcSelected = false
                            connectionGate = "permission"
                            permissionLauncher.launch(requiredPermissions())
                        }
                        !bluetoothEnabled -> {
                            pendingDestination = DestinationSelection.CHOOSE_PC
                            pairNewPcSelected = false
                            connectionGate = "bluetooth"
                            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                        destination == DestinationSelection.CHOOSE_PC -> {
                            pairedHosts = readPairedHosts()
                            pendingDestination = null
                            connectionGate = null
                        }
                        destination != NEW_PC && pairedHosts.none { it.address == destination } -> {
                            pendingDestination = null
                            connectionGate = null
                            sessionCoordinator.updateState { it.copy(message = LocalizedMessage(R.string.selected_pc_unavailable), feedbackLevel = HidFeedbackLevel.WARNING) }
                        }
                        connectionGate == "service" && !hidState.sessionActive && hidState.message != null -> {
                            pendingDestination = null
                            connectionGate = null
                        }
                        hidState.status == HidSessionStatus.READY -> {
                            pendingDestination = null
                            connectionGate = null
                            sessionUiViewModel.dispatch(
                                SessionUiEvent.ConnectionRequested(ConnectionMethod.BLUETOOTH),
                            )
                            if (destination == NEW_PC) {
                                discoverableLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                                )
                            } else {
                                sessionCoordinator.connect(destination)
                            }
                        }
                        connectionGate == null && !hidState.sessionActive -> {
                            connectionGate = "service"
                            sessionCoordinator.updateState { it.copy(message = null, status = HidSessionStatus.IDLE) }
                            sessionCoordinator.start(
                                adapterId = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
                                destination = requireNotNull(effectiveDestinationType),
                                physicalCaptureMode = effectiveCaptureMode,
                            )
                        }
                        hidState.status == HidSessionStatus.ERROR && hidState.sessionActive && connectionGate != "restart" -> {
                            connectionGate = "restart"
                            sessionCoordinator.stop()
                        }
                    }
                }
                LaunchedEffect(
                    hidState.status,
                    hidState.sessionActive,
                    physicalControllerConnected,
                    sessionUiState.activeTransport,
                    bluetoothDesktopGameplayStatus,
                ) {
                    when {
                        hidState.status == HidSessionStatus.CONNECTED -> {
                            sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportConnected(
                                    ConnectionMethod.BLUETOOTH,
                                    physicalControllerConnected,
                                ),
                            )
                            hidState.connectedHostAddress?.let { address ->
                                selectedAddress = address
                                pairNewPcSelected = false
                            }
                        }
                        hidState.status == HidSessionStatus.ERROR -> sessionUiViewModel.dispatch(
                            SessionUiEvent.TransportFailed(ConnectionMethod.BLUETOOTH),
                        )
                        !hidState.sessionActive &&
                            bluetoothDesktopGameplayStatus is BluetoothDesktopGamepadStatus.Stopped -> {
                            sessionUiViewModel.dispatch(
                                SessionUiEvent.TransportStopped(ConnectionMethod.BLUETOOTH),
                            )
                        }
                    }
                }

                if (!onboardingComplete) {
                    OnboardingScreen(
                        onContinue = {
                            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                            onboardingComplete = true
                        },
                    )
                } else if (showTouchscreenLayoutEditor) {
                    TouchscreenLayoutEditorScreen(
                        initialProfile = touchscreenLayoutProfile,
                        editingOrientation = currentLayoutOrientation,
                        onSave = { profile ->
                            TouchGamepadStore.neutralize()
                            TouchscreenLayoutStore.save(profile)
                            showTouchscreenLayoutEditor = false
                            if (returnToSettingsAfterLayoutEditor) showSettings = true
                            returnToSettingsAfterLayoutEditor = false
                        },
                        onCancel = {
                            showTouchscreenLayoutEditor = false
                            if (returnToSettingsAfterLayoutEditor) showSettings = true
                            returnToSettingsAfterLayoutEditor = false
                        },
                    )
                } else if (showBluetoothDesktopDiagnostic) {
                    BluetoothDesktopDiagnosticScreen(
                        pairedHosts = pairedHosts,
                        gameplaySessionActive = gameplaySessionActive,
                        gameplayStatus = bluetoothDesktopGameplayStatus,
                        physicalControllerConnected = physicalControllerConnected,
                        touchscreenLayout = touchscreenLayout,
                        probe = remember { BluetoothDesktopProbe(this@MainActivity) },
                        onStartGameplay = { address ->
                            bluetoothDesktopGameplayController.start(address, effectiveCaptureMode)
                        },
                        onStopGameplay = bluetoothDesktopGameplayController::stop,
                        onBack = {
                            bluetoothDesktopGameplayController.shutdown()
                            showBluetoothDesktopDiagnostic = false
                            showSettings = true
                        },
                    )
                } else if (showNetworkDiagnostic) {
                    NetworkDiagnosticScreen(
                        gameplayStatus = networkGameplayStatus,
                        physicalControllerConnected = physicalControllerConnected,
                        touchscreenLayout = touchscreenLayout,
                        onStartGameplay = { request ->
                            networkGameplayController.start(request, effectiveCaptureMode)
                        },
                        onStopGameplay = {
                            networkGameplayController.stop()
                            exitGamepadMode()
                        },
                        onBack = {
                            networkGameplayController.shutdown()
                            showNetworkDiagnostic = false
                            showSettings = true
                        },
                    )
                } else if (showSettings) {
                    SettingsScreen(
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceInfo = deviceInfo,
                        hidState = hidState,
                        physicalGamepadState = physicalGamepadState,
                        physicalControllerConnected = physicalControllerConnected,
                        mappingAvailable = mappingInput != null,
                        sessionOrientationMode = sessionOrientationMode,
                        invertedTouchpadScroll = invertedTouchpadScroll,
                        onEditTouchscreenLayout = {
                            returnToSettingsAfterLayoutEditor = true
                            showSettings = false
                            showTouchscreenLayoutEditor = true
                        },
                        onConfigureGamepadMapping = {
                            if (mappingInput != null) {
                                returnToSettingsAfterGamepadMapping = true
                                showSettings = false
                                showGamepadMapping = true
                            }
                        },
                        onSessionOrientationModeChanged = SessionOrientationStore::set,
                        onInvertedTouchpadScrollChanged = TouchpadSettingsStore::setInvertedScroll,
                        onOpenNetworkDiagnostic = {
                            showSettings = false
                            showNetworkDiagnostic = true
                        },
                        onOpenBluetoothDesktopDiagnostic = {
                            showSettings = false
                            showBluetoothDesktopDiagnostic = true
                        },
                        onLanguageSettings = if (Build.VERSION.SDK_INT >= 33) ({
                            runCatching {
                                startActivity(
                                    Intent(
                                        Settings.ACTION_APP_LOCALE_SETTINGS,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }.onFailure {
                                Toast.makeText(this, R.string.language_description, Toast.LENGTH_LONG).show()
                            }
                        }) else null,
                        onCopyDiagnostics = {
                            val report = DiagnosticReport.create(
                                BuildConfig.VERSION_NAME,
                                deviceInfo,
                                hidState,
                                physicalGamepadState,
                            )
                            getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("BridgePad diagnostics", report))
                            Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
                        },
                        onShareDiagnostics = {
                            val report = DiagnosticReport.create(
                                BuildConfig.VERSION_NAME,
                                deviceInfo,
                                hidState,
                                physicalGamepadState,
                            )
                            startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "BridgePad diagnostic report")
                                        putExtra(Intent.EXTRA_TEXT, report)
                                    },
                                    getString(R.string.share_diagnostics),
                                ),
                            )
                        },
                        onBack = { showSettings = false },
                    )
                } else if (showGamepadMapping && mappingInput != null) {
                    GamepadMappingScreen(
                        input = mappingInput,
                        onSave = { mapping ->
                            mappingInput.deviceKey.let { key ->
                                GamepadMappingStore.save(key, mapping)
                            }
                            if (effectiveCaptureMode == PhysicalCaptureMode.BACKGROUND_USB) {
                                DirectUsbGamepadStore.applyMapping(mapping)
                            } else {
                                gamepadController.reloadMappings()
                            }
                            showGamepadMapping = false
                            if (returnToSettingsAfterGamepadMapping) showSettings = true
                            returnToSettingsAfterGamepadMapping = false
                        },
                        onCancel = {
                            showGamepadMapping = false
                            if (returnToSettingsAfterGamepadMapping) showSettings = true
                            returnToSettingsAfterGamepadMapping = false
                        },
                    )
                } else if (sessionUiState.surface == SessionSurface.TOUCH_CONTROLLER) {
                    TouchscreenGamepadScreen(
                        layout = touchscreenLayout,
                        onOpenKeyboard = {
                            sessionUiViewModel.dispatch(SessionUiEvent.KeyboardOpened)
                        },
                        onExit = {
                            sessionUiViewModel.dispatch(SessionUiEvent.SurfaceClosed)
                        },
                    )
                } else if (sessionUiState.surface == SessionSurface.MOUSE_TOUCHPAD) {
                    MouseTouchpadScreen(
                        onOpenKeyboard = {
                            sessionUiViewModel.dispatch(SessionUiEvent.KeyboardOpened)
                        },
                        onExit = {
                            sessionUiViewModel.dispatch(SessionUiEvent.SurfaceClosed)
                        },
                    )
                } else if (sessionUiState.surface == SessionSurface.KEYBOARD) {
                    GameplayKeyboardScreen(
                        state = gameplayKeyboardState,
                        onClose = {
                            sessionUiViewModel.dispatch(SessionUiEvent.KeyboardClosed)
                        },
                    )
                } else HomeScreen(
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    bluetoothEnabled = bluetoothEnabled,
                    hidCompatible = isBluetoothHidPotentiallyAvailable(),
                    hidState = hidState,
                    physicalGamepadState = physicalGamepadState,
                    physicalCaptureMode = effectiveCaptureMode,
                    sessionDraft = sessionDraft,
                    outputAdapters = sessionCoordinator.catalog,
                    destinationType = effectiveDestinationType,
                    connectionMethod = effectiveConnectionMethod,
                    directUsbState = directUsbState,
                    mappingAvailable = mappingInput != null,
                    onDestinationChanged = { destination ->
                        if (destinationTypeName != destination.name) {
                            destinationTypeName = destination.name
                            connectionMethodName = null
                            selectedAddress = null
                            selectedNetworkDesktopId = null
                            pairNewPcSelected = false
                            useDirectBluetooth = false
                        }
                    },
                    onSelectBluetooth = {
                        if (connectionMethodName != ConnectionMethod.BLUETOOTH.name) {
                            connectionMethodName = ConnectionMethod.BLUETOOTH.name
                            selectedAddress = null
                            selectedNetworkDesktopId = null
                            pairNewPcSelected = false
                            useDirectBluetooth = false
                            networkDesktopCoordinator.clearPairingStatus()
                        }
                    },
                    onSelectWifi = {
                        if (connectionMethodName != ConnectionMethod.WIFI.name) {
                            connectionMethodName = ConnectionMethod.WIFI.name
                            selectedAddress = null
                            pairNewPcSelected = false
                            useDirectBluetooth = false
                            networkDesktopCoordinator.clearPairingStatus()
                        }
                    },
                    pairedHosts = pairedHosts,
                    selectedAddress = selectedAddress,
                    pairNewPcSelected = pairNewPcSelected,
                    preparingConnection = pendingDestination != null,
                    onSelectHost = { address ->
                        val selectingNewPc = address == null
                        if (selectedAddress != address || pairNewPcSelected != selectingNewPc) {
                            selectedAddress = address
                            pairNewPcSelected = selectingNewPc
                            useDirectBluetooth = false
                        }
                    },
                    discoveredDesktops = discoveredDesktops,
                    trustedDesktops = trustedDesktops,
                    selectedNetworkDesktopId = selectedNetworkDesktopId,
                    networkPairingStatus = networkPairingStatus,
                    networkGameplayStatus = networkGameplayStatus,
                    bluetoothDesktopGameplayStatus = bluetoothDesktopGameplayStatus,
                    networkDiscoveryError = networkDiscoveryError,
                    onSelectNetworkDesktop = { selectedNetworkDesktopId = it },
                    onPairNetworkDesktop = { peerId, code ->
                        networkDesktopCoordinator.pair(peerId, code)
                    },
                    onForgetNetworkDesktop = { peerId ->
                        networkDesktopCoordinator.forget(peerId)
                        if (selectedNetworkDesktopId == peerId) selectedNetworkDesktopId = null
                    },
                    onRepairNetworkDesktop = { peerId ->
                        networkDesktopCoordinator.prepareRepair(peerId)
                        if (selectedNetworkDesktopId == peerId) selectedNetworkDesktopId = null
                    },
                    onDismissNetworkPairingStatus = networkDesktopCoordinator::clearPairingStatus,
                    onPhysicalCaptureModeChanged = { mode ->
                        captureModeName = mode.name
                        sessionCoordinator.selectPhysicalCaptureMode(mode)
                    },
                    onPrepareBluetooth = {
                        pairNewPcSelected = false
                        selectedAddress = null
                        connectionGate = null
                        pendingDestination = DestinationSelection.CHOOSE_PC
                    },
                    onUseDirectBluetooth = {
                        val address = selectedAddress ?: return@HomeScreen
                        useDirectBluetooth = true
                        sessionCoordinator.stop()
                        connectionGate = null
                        pendingDestination = DestinationSelection.requestFor(
                            selectedAddress = address,
                            pairNewPcSelected = false,
                            bluetoothReady = bluetoothEnabled,
                        )
                    },
                    onPlay = {
                        if (effectiveConnectionMethod == ConnectionMethod.WIFI) {
                            selectedNetworkDesktopId?.let { peerId ->
                                sessionUiViewModel.dispatch(
                                    SessionUiEvent.ConnectionRequested(ConnectionMethod.WIFI),
                                )
                                networkDesktopCoordinator.startGameplay(peerId, effectiveCaptureMode)
                            }
                            return@HomeScreen
                        }
                        val bluetoothPermissionsReady = hasBluetoothPermission()
                        bluetoothPermissionGranted = bluetoothPermissionsReady
                        val bluetoothReady = isBluetoothEnabled()
                        bluetoothEnabled = bluetoothReady
                        sessionCoordinator.updateState {
                            it.reconcileBluetoothAvailability(
                                enabled = bluetoothReady,
                                permissionGranted = bluetoothPermissionsReady,
                            )
                        }
                        val currentHosts = readPairedHosts()
                        pairedHosts = currentHosts
                        if (SessionSetup.canConnect(
                                draft = sessionDraft,
                                adapters = sessionCoordinator.catalog,
                                connectionAvailable = bluetoothReady,
                                availableTargetIds = currentHosts.map { it.address },
                            )) {
                            connectionGate = null
                            val address = selectedAddress
                            if (address != null && !useDirectBluetooth) {
                                pendingDestination = null
                                if (hidState.sessionActive) sessionCoordinator.stop()
                                sessionUiViewModel.dispatch(
                                    SessionUiEvent.ConnectionRequested(ConnectionMethod.BLUETOOTH),
                                )
                                sessionCoordinator.start(
                                    adapterId = OutputAdapterIds.DESKTOP_BLUETOOTH,
                                    destination = requireNotNull(effectiveDestinationType),
                                    physicalCaptureMode = effectiveCaptureMode,
                                )
                                sessionCoordinator.connect(address)
                            } else {
                                pendingDestination = DestinationSelection.requestFor(
                                    address,
                                    pairNewPcSelected,
                                    bluetoothReady,
                                )
                            }
                        }
                    },
                    onConfigureGamepadMapping = {
                        if (mappingInput != null) {
                            returnToSettingsAfterGamepadMapping = false
                            showGamepadMapping = true
                        }
                    },
                    onEditTouchscreenLayout = {
                        returnToSettingsAfterLayoutEditor = false
                        showTouchscreenLayoutEditor = true
                    },
                    sessionOrientationMode = sessionOrientationMode,
                    onSessionOrientationModeChanged = SessionOrientationStore::set,
                    onOpenTouchController = {
                        sessionUiViewModel.dispatch(
                            SessionUiEvent.SurfaceSelected(SessionSurface.TOUCH_CONTROLLER),
                        )
                    },
                    onOpenMouseTouchpad = {
                        sessionUiViewModel.dispatch(
                            SessionUiEvent.SurfaceSelected(SessionSurface.MOUSE_TOUCHPAD),
                        )
                    },
                    onStopHid = {
                        gameplayKeyboardState.reset()
                        captureModeName = PhysicalCaptureMode.COMPATIBILITY.name
                        sessionCoordinator.preparePhysicalCapture(null)
                        destinationTypeName = null
                        connectionMethodName = null
                        selectedAddress = null
                        selectedNetworkDesktopId = null
                        pairNewPcSelected = false
                        useDirectBluetooth = false
                        pendingDestination = null
                        connectionGate = null
                        sessionUiViewModel.dispatch(SessionUiEvent.SessionEnded)
                        sessionCoordinator.stop()
                        if (
                            networkGameplayStatus !is NetworkGamepadStatus.Stopped &&
                            !showNetworkDiagnostic
                        ) {
                            networkDesktopCoordinator.stopGameplay()
                        }
                    },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        networkDesktopCoordinator.startDiscovery()
        gamepadController.start()
    }

    override fun onStop() {
        TouchGamepadStore.neutralize()
        gamepadController.stop()
        networkDesktopCoordinator.stopDiscovery()
        super.onStop()
    }

    override fun onDestroy() {
        val bridgePadApplication = application as BridgePadApplication
        val sessionCoordinator = bridgePadApplication.sessionCoordinator
        val networkStatus = bridgePadApplication.networkGameplayController.status.value
        val networkSessionRunning = networkStatus is NetworkGamepadStatus.Connecting ||
            networkStatus is NetworkGamepadStatus.Reconnecting ||
            networkStatus is NetworkGamepadStatus.Active
        val bluetoothDesktopStatus = bridgePadApplication.bluetoothDesktopGameplayController.status.value
        val bluetoothDesktopSessionRunning =
            bluetoothDesktopStatus is BluetoothDesktopGamepadStatus.Connecting ||
                bluetoothDesktopStatus is BluetoothDesktopGamepadStatus.Reconnecting ||
                bluetoothDesktopStatus is BluetoothDesktopGamepadStatus.Active
        if (isFinishing && !sessionCoordinator.state.value.sessionActive &&
            !networkSessionRunning && !bluetoothDesktopSessionRunning
        ) {
            sessionCoordinator.preparePhysicalCapture(null)
        }
        super.onDestroy()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepadController.handleKeyEvent(event) || super.dispatchKeyEvent(event)

    @SuppressLint("RestrictedApi")
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadController.handleMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    private fun enterGamepadMode(orientationMode: SessionOrientationMode) {
        TouchGamepadStore.neutralize()
        requestedOrientation = when (orientationMode) {
            SessionOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            SessionOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            SessionOrientationMode.REVERSE_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            SessionOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            SessionOrientationMode.REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitGamepadMode() {
        TouchGamepadStore.deactivate()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun isBluetoothHidPotentiallyAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            getSystemService(BluetoothManager::class.java)?.adapter != null

    @SuppressLint("MissingPermission")
    private fun isBluetoothEnabled(): Boolean =
        hasBluetoothPermission() && runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun readPairedHosts(): List<PairedHost> {
        if (!hasBluetoothPermission()) return emptyList()
        return runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
                .map { PairedHost(it.address, it.name ?: getString(R.string.unnamed_pc)) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val NEW_PC = DestinationSelection.NEW_PC
        private const val PREFERENCES_NAME = "bridgepad_preferences"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
