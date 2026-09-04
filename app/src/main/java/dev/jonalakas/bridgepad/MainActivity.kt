package dev.jonalakas.bridgepad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
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
import dev.jonalakas.bridgepad.input.android.AndroidGamepadController
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.output.hid.BluetoothHidService
import dev.jonalakas.bridgepad.output.hid.HidSessionStore
import dev.jonalakas.bridgepad.ui.home.HomeScreen
import dev.jonalakas.bridgepad.ui.gamepad.TouchscreenGamepadScreen
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

class MainActivity : ComponentActivity() {
    private lateinit var gamepadController: AndroidGamepadController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gamepadController = AndroidGamepadController(this)

        val deviceInfo = AndroidDeviceInfoProvider.get()

        setContent {
            BridgePadTheme {
                var bluetoothPermissionGranted by remember { mutableStateOf(hasBluetoothPermission()) }
                var showTouchController by rememberSaveable { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    bluetoothPermissionGranted = hasBluetoothPermission()
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
                    }
                }
                val hidState by HidSessionStore.state.collectAsState()
                val physicalGamepadState by PhysicalGamepadStore.state.collectAsState()

                if (showTouchController) {
                    LaunchedEffect(Unit) { enterGamepadMode() }
                    TouchscreenGamepadScreen(
                        hidState = hidState,
                        onExit = {
                            showTouchController = false
                            exitGamepadMode()
                        },
                    )
                } else HomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceInfo = deviceInfo,
                    bluetoothPermissionGranted = bluetoothPermissionGranted,
                    hidState = hidState,
                    physicalGamepadState = physicalGamepadState,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                    onStartHid = {
                        ContextCompat.startForegroundService(
                            this,
                            BluetoothHidService.intent(this, BluetoothHidService.ACTION_START),
                        )
                    },
                    onConnect = { address ->
                        startService(
                            BluetoothHidService.intent(this, BluetoothHidService.ACTION_CONNECT)
                                .putExtra(BluetoothHidService.EXTRA_ADDRESS, address),
                        )
                    },
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
                    onStopHid = {
                        startService(BluetoothHidService.intent(this, BluetoothHidService.ACTION_STOP))
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
}
