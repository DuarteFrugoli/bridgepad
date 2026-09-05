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
import dev.jonalakas.bridgepad.core.session.SessionStatus as HidSessionStatus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.jonalakas.bridgepad.diagnostics.AndroidDeviceInfoProvider
import dev.jonalakas.bridgepad.diagnostics.DiagnosticReport
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.input.android.AndroidGamepadController
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore
import dev.jonalakas.bridgepad.output.hid.BluetoothHidService
import dev.jonalakas.bridgepad.session.SessionStore as HidSessionStore
import dev.jonalakas.bridgepad.session.FeedbackLevel as HidFeedbackLevel
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.ui.home.HomeScreen
import dev.jonalakas.bridgepad.ui.home.DestinationSelection
import dev.jonalakas.bridgepad.session.SessionSetup
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.gamepad.MouseTouchpadScreen
import dev.jonalakas.bridgepad.ui.onboarding.OnboardingScreen
import dev.jonalakas.bridgepad.ui.mapping.GamepadMappingInput
import dev.jonalakas.bridgepad.ui.mapping.GamepadMappingScreen
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

class MainActivity : ComponentActivity() {
    private lateinit var gamepadController: AndroidGamepadController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gamepadController = AndroidGamepadController(this)

        val deviceInfo = AndroidDeviceInfoProvider.get()
        val preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        setContent {
            BridgePadTheme {
                var bluetoothPermissionGranted by remember { mutableStateOf(hasBluetoothPermission()) }
                var showTouchController by rememberSaveable { mutableStateOf(false) }
                var showMouseTouchpad by rememberSaveable { mutableStateOf(false) }
                var showGamepadMapping by rememberSaveable { mutableStateOf(false) }
                var onboardingComplete by rememberSaveable {
                    mutableStateOf(preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false))
                }
                var inputModeName by rememberSaveable {
                    mutableStateOf<String?>(null)
                }
                var bluetoothSelected by rememberSaveable {
                    mutableStateOf(false)
                }
                var captureModeName by rememberSaveable { mutableStateOf<String?>(null) }
                var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
                var pairNewPcSelected by rememberSaveable { mutableStateOf(false) }
                var showDestinationPicker by rememberSaveable { mutableStateOf(false) }
                var pendingDestination by rememberSaveable { mutableStateOf<String?>(null) }
                var connectionGate by rememberSaveable { mutableStateOf<String?>(null) }
                var openAfterConnection by rememberSaveable { mutableStateOf(false) }
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
                        HidSessionStore.update { it.copy(message = LocalizedMessage(R.string.bluetooth_required), feedbackLevel = HidFeedbackLevel.WARNING) }
                    }
                    pairedHosts = readPairedHosts()
                }
                val inputMode = inputModeName?.let(InputMode::valueOf)
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
                        HidSessionStore.update {
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
                    if (!HidSessionStore.state.value.sessionActive) {
                        openAfterConnection = false
                    } else if (result.resultCode > 0) {
                        startService(
                            BluetoothHidService.intent(
                                this,
                                BluetoothHidService.ACTION_DISCOVERABILITY_STARTED,
                            ).putExtra(
                                BluetoothHidService.EXTRA_DISCOVERABLE_DURATION,
                                result.resultCode,
                            ),
                        )
                    } else {
                        openAfterConnection = false
                        HidSessionStore.update {
                            it.copy(
                                message = LocalizedMessage(R.string.visibility_not_enabled),
                                feedbackLevel = HidFeedbackLevel.WARNING,
                            )
                        }
                    }
                }
                val hidState by HidSessionStore.state.collectAsState()
                val physicalGamepadState by PhysicalGamepadStore.state.collectAsState()
                val directUsbState by DirectUsbGamepadStore.state.collectAsState()
                val effectiveInputMode = if (hidState.sessionActive) {
                    if (hidState.touchInputSelected) InputMode.TOUCHSCREEN else InputMode.PHYSICAL_GAMEPAD
                } else inputMode
                val effectiveCaptureMode = if (hidState.sessionActive) {
                    hidState.physicalCaptureMode
                } else {
                    captureModeName?.let(PhysicalCaptureMode::valueOf)
                }
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
                    null -> null
                }

                LaunchedEffect(showGamepadMapping, mappingInput) {
                    if (showGamepadMapping && mappingInput == null) showGamepadMapping = false
                }

                LaunchedEffect(bluetoothEnabled, bluetoothPermissionGranted) {
                    if (!bluetoothEnabled || !bluetoothPermissionGranted) {
                        pairNewPcSelected = false
                        showDestinationPicker = false
                    }
                }
                LaunchedEffect(connectionGate) {
                    if (connectionGate == "bluetooth_starting") {
                        kotlinx.coroutines.delay(15_000)
                        if (!isBluetoothEnabled()) {
                            pendingDestination = null
                            connectionGate = null
                            HidSessionStore.update { it.copy(message = LocalizedMessage(R.string.bluetooth_required), feedbackLevel = HidFeedbackLevel.WARNING) }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    // A restored Activity must not wait forever for a service lost with the process.
                    if (connectionGate == "service" && !HidSessionStore.state.value.sessionActive) {
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
                            showDestinationPicker = true
                        }
                        destination != NEW_PC && pairedHosts.none { it.address == destination } -> {
                            pendingDestination = null
                            connectionGate = null
                            HidSessionStore.update { it.copy(message = LocalizedMessage(R.string.selected_pc_unavailable), feedbackLevel = HidFeedbackLevel.WARNING) }
                        }
                        connectionGate == "service" && !hidState.sessionActive && hidState.message != null -> {
                            pendingDestination = null
                            connectionGate = null
                        }
                        hidState.status == HidSessionStatus.READY -> {
                            pendingDestination = null
                            connectionGate = null
                            openAfterConnection = true
                            if (destination == NEW_PC) {
                                discoverableLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                                )
                            } else {
                                startService(BluetoothHidService.intent(this@MainActivity, BluetoothHidService.ACTION_CONNECT)
                                    .putExtra(BluetoothHidService.EXTRA_ADDRESS, destination))
                            }
                        }
                        connectionGate == null && !hidState.sessionActive -> {
                            connectionGate = "service"
                            HidSessionStore.update { it.copy(message = null, status = HidSessionStatus.IDLE) }
                            ContextCompat.startForegroundService(
                                this@MainActivity,
                                BluetoothHidService.intent(this@MainActivity, BluetoothHidService.ACTION_START)
                                    .putExtra(BluetoothHidService.EXTRA_TOUCH_INPUT_SELECTED, inputMode == InputMode.TOUCHSCREEN)
                                    .putExtra(
                                        BluetoothHidService.EXTRA_PHYSICAL_CAPTURE_MODE,
                                        effectiveCaptureMode?.name,
                                    ),
                            )
                        }
                        hidState.status == HidSessionStatus.ERROR && hidState.sessionActive && connectionGate != "restart" -> {
                            connectionGate = "restart"
                            startService(BluetoothHidService.intent(this@MainActivity, BluetoothHidService.ACTION_STOP))
                        }
                    }
                }
                LaunchedEffect(hidState.status, hidState.sessionActive, effectiveInputMode, openAfterConnection) {
                    if (hidState.status == HidSessionStatus.CONNECTED && openAfterConnection) {
                        openAfterConnection = false
                        showTouchController = effectiveInputMode == InputMode.TOUCHSCREEN
                        showMouseTouchpad = effectiveInputMode == InputMode.PHYSICAL_GAMEPAD
                        hidState.connectedHostAddress?.let { address ->
                            selectedAddress = address
                            pairNewPcSelected = false
                        }
                    }
                    if (!hidState.sessionActive || hidState.status == HidSessionStatus.ERROR) {
                        openAfterConnection = false
                    }
                    if (hidState.status != HidSessionStatus.CONNECTED && (showTouchController || showMouseTouchpad)) {
                        showTouchController = false
                        showMouseTouchpad = false
                        exitGamepadMode()
                    }
                }

                if (!onboardingComplete) {
                    OnboardingScreen(
                        onContinue = {
                            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                            onboardingComplete = true
                        },
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
                        },
                        onCancel = { showGamepadMapping = false },
                    )
                } else if (showTouchController) {
                    LaunchedEffect(Unit) { enterGamepadMode() }
                    TouchscreenGamepadScreen(
                        hidState = hidState,
                        onExit = {
                            showTouchController = false
                            exitGamepadMode()
                        },
                    )
                } else if (showMouseTouchpad) {
                    LaunchedEffect(Unit) { enterGamepadMode() }
                    MouseTouchpadScreen(
                        hidState = hidState,
                        onExit = {
                            showMouseTouchpad = false
                            exitGamepadMode()
                        },
                    )
                } else HomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceInfo = deviceInfo,
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    bluetoothEnabled = bluetoothEnabled,
                    hidCompatible = isBluetoothHidPotentiallyAvailable(),
                    hidState = hidState,
                    physicalGamepadState = physicalGamepadState,
                    inputMode = effectiveInputMode,
                    physicalCaptureMode = effectiveCaptureMode,
                    directUsbState = directUsbState,
                    mappingAvailable = mappingInput != null,
                    bluetoothSelected = hidState.sessionActive || bluetoothSelected,
                    onSelectBluetooth = {
                        bluetoothSelected = true
                    },
                    pairedHosts = pairedHosts,
                    selectedAddress = selectedAddress,
                    pairNewPcSelected = pairNewPcSelected,
                    showDestinationPicker = showDestinationPicker,
                    onDismissDestinationPicker = { showDestinationPicker = false },
                    onPickDestination = { address ->
                        selectedAddress = address
                        pairNewPcSelected = address == null
                        showDestinationPicker = false
                        connectionGate = null
                        pendingDestination = null
                    },
                    preparingConnection = pendingDestination != null,
                    onSelectHost = { address ->
                        selectedAddress = address
                        pairNewPcSelected = address == null
                    },
                    onInputModeChanged = { mode ->
                        inputModeName = mode.name
                        if (mode == InputMode.PHYSICAL_GAMEPAD) TouchGamepadStore.deactivate()
                        if (mode == InputMode.TOUCHSCREEN && !hidState.sessionActive) {
                            captureModeName = null
                            DirectUsbCaptureManager.stop()
                        }
                        if (hidState.sessionActive) {
                            startService(
                                BluetoothHidService.intent(this, BluetoothHidService.ACTION_SELECT_INPUT)
                                    .putExtra(
                                        BluetoothHidService.EXTRA_TOUCH_INPUT_SELECTED,
                                        mode == InputMode.TOUCHSCREEN,
                                    ),
                            )
                        }
                    },
                    onPhysicalCaptureModeChanged = { mode ->
                        captureModeName = mode.name
                        when (mode) {
                            PhysicalCaptureMode.COMPATIBILITY -> DirectUsbCaptureManager.stop()
                            PhysicalCaptureMode.BACKGROUND_USB -> DirectUsbCaptureManager.start(this)
                        }
                        if (hidState.sessionActive) {
                            startService(
                                BluetoothHidService.intent(
                                    this,
                                    if (mode == PhysicalCaptureMode.BACKGROUND_USB) {
                                        BluetoothHidService.ACTION_ENABLE_BACKGROUND_USB
                                    } else {
                                        BluetoothHidService.ACTION_ENABLE_COMPATIBILITY_INPUT
                                    },
                                ),
                            )
                        }
                    },
                    onPrepareBluetooth = {
                        pairNewPcSelected = false
                        connectionGate = null
                        pendingDestination = DestinationSelection.CHOOSE_PC
                    },
                    onPlay = {
                        val bluetoothReady = isBluetoothEnabled()
                        bluetoothEnabled = bluetoothReady
                        val currentHosts = readPairedHosts()
                        pairedHosts = currentHosts
                        if (SessionSetup.canConnect(
                                effectiveInputMode,
                                effectiveCaptureMode,
                                hidState.sessionActive || bluetoothSelected,
                                bluetoothReady,
                                selectedAddress,
                                pairNewPcSelected,
                                currentHosts.map { it.address },
                            )) {
                            connectionGate = null
                            pendingDestination = DestinationSelection.requestFor(selectedAddress, pairNewPcSelected, bluetoothReady)
                        }
                    },
                    onConfigureGamepadMapping = { if (mappingInput != null) showGamepadMapping = true },
                    onOpenTouchController = {
                        showTouchController = true
                    },
                    onOpenMouseTouchpad = {
                        showMouseTouchpad = true
                    },
                    onStopHid = {
                        inputModeName = null
                        captureModeName = null
                        DirectUsbCaptureManager.stop()
                        bluetoothSelected = false
                        selectedAddress = null
                        pairNewPcSelected = false
                        showDestinationPicker = false
                        pendingDestination = null
                        connectionGate = null
                        openAfterConnection = false
                        if (hidState.sessionActive) startService(BluetoothHidService.intent(this, BluetoothHidService.ACTION_STOP))
                    },
                    onLanguageSettings = if (Build.VERSION.SDK_INT >= 33) ({
                        runCatching {
                            startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName")))
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
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        gamepadController.start()
    }

    override fun onStop() {
        TouchGamepadStore.neutralize()
        gamepadController.stop()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && !HidSessionStore.state.value.sessionActive) {
            DirectUsbCaptureManager.stop()
        }
        super.onDestroy()
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepadController.handleKeyEvent(event) || super.dispatchKeyEvent(event)

    @SuppressLint("RestrictedApi")
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        gamepadController.handleMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    private fun enterGamepadMode() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
