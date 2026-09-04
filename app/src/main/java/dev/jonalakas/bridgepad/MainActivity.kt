package dev.jonalakas.bridgepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import dev.jonalakas.bridgepad.input.usb.UsbGamepadMappingStore
import dev.jonalakas.bridgepad.output.hid.BluetoothHidService
import dev.jonalakas.bridgepad.output.hid.HidSessionStore
import dev.jonalakas.bridgepad.output.hid.HidFeedbackLevel
import dev.jonalakas.bridgepad.ui.home.HomeScreen
import dev.jonalakas.bridgepad.ui.home.InputMode
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.gamepad.MouseTouchpadScreen
import dev.jonalakas.bridgepad.ui.onboarding.OnboardingScreen
import dev.jonalakas.bridgepad.ui.mapping.UsbMappingScreen
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
                var showUsbMapping by rememberSaveable { mutableStateOf(false) }
                var onboardingComplete by rememberSaveable {
                    mutableStateOf(preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false))
                }
                var inputModeName by rememberSaveable { mutableStateOf(InputMode.TOUCHSCREEN.name) }
                val inputMode = InputMode.valueOf(inputModeName)
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    bluetoothPermissionGranted = hasBluetoothPermission()
                    if (!bluetoothPermissionGranted) {
                        SessionLog.record("PERMISSION", "Bluetooth permission was not granted")
                        HidSessionStore.update {
                            it.copy(
                                message = "Bluetooth permission is required. You can grant it when you are ready.",
                                feedbackLevel = HidFeedbackLevel.WARNING,
                            )
                        }
                    }
                }
                val discoverableLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode > 0) {
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
                        HidSessionStore.update {
                            it.copy(
                                message = "Phone visibility was not enabled. Tap Pair new PC to try again.",
                                feedbackLevel = HidFeedbackLevel.WARNING,
                            )
                        }
                    }
                }
                val hidState by HidSessionStore.state.collectAsState()
                val physicalGamepadState by PhysicalGamepadStore.state.collectAsState()
                val directUsbState by DirectUsbGamepadStore.state.collectAsState()

                if (!onboardingComplete) {
                    OnboardingScreen(
                        onContinue = {
                            preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                            onboardingComplete = true
                        },
                    )
                } else if (showUsbMapping) {
                    UsbMappingScreen(
                        usbState = directUsbState,
                        onSave = { mapping ->
                            directUsbState.deviceKey?.let { key ->
                                UsbGamepadMappingStore.initialize(this)
                                UsbGamepadMappingStore.save(key, mapping)
                                DirectUsbGamepadStore.applyMapping(mapping)
                            }
                            showUsbMapping = false
                        },
                        onCancel = { showUsbMapping = false },
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
                        onExit = {
                            showMouseTouchpad = false
                            exitGamepadMode()
                        },
                    )
                } else HomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceInfo = deviceInfo,
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    hidCompatible = isBluetoothHidPotentiallyAvailable(),
                    hidState = hidState,
                    physicalGamepadState = physicalGamepadState,
                    inputMode = inputMode,
                    onInputModeChanged = { mode ->
                        inputModeName = mode.name
                        if (mode == InputMode.PHYSICAL_GAMEPAD) TouchGamepadStore.deactivate()
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
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onStartHid = {
                        ContextCompat.startForegroundService(
                            this,
                            BluetoothHidService.intent(this, BluetoothHidService.ACTION_START)
                                .putExtra(
                                    BluetoothHidService.EXTRA_TOUCH_INPUT_SELECTED,
                                    inputMode == InputMode.TOUCHSCREEN,
                                ),
                        )
                    },
                    onConnect = { address ->
                        startService(
                            BluetoothHidService.intent(this, BluetoothHidService.ACTION_CONNECT)
                                .putExtra(BluetoothHidService.EXTRA_ADDRESS, address),
                        )
                    },
                    onReconnect = {
                        startService(
                            BluetoothHidService.intent(this, BluetoothHidService.ACTION_RECONNECT),
                        )
                    },
                    onEnableCompatibilityInput = {
                        startService(BluetoothHidService.intent(this, BluetoothHidService.ACTION_ENABLE_COMPATIBILITY_INPUT))
                    },
                    onEnableBackgroundUsb = {
                        startService(BluetoothHidService.intent(this, BluetoothHidService.ACTION_ENABLE_BACKGROUND_USB))
                    },
                    onConfigureUsbMapping = { showUsbMapping = true },
                    onPairNewPc = {
                        discoverableLauncher.launch(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(
                                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                120,
                            ),
                        )
                    },
                    onOpenTouchController = {
                        showTouchController = true
                    },
                    onOpenMouseTouchpad = {
                        showMouseTouchpad = true
                    },
                    onStopHid = {
                        startService(BluetoothHidService.intent(this, BluetoothHidService.ACTION_STOP))
                    },
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
                                "Share BridgePad diagnostics",
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        gamepadController.handleKeyEvent(event) || super.dispatchKeyEvent(event)

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

    companion object {
        private const val PREFERENCES_NAME = "bridgepad_preferences"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
