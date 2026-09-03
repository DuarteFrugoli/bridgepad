package dev.jonalakas.bridgepad.output.hid

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.R

@SuppressLint("MissingPermission")
class BluetoothHidService : Service() {
    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var testAxisValue = 0
    private var shuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private var discoverabilityTimeout: Runnable? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        finishSession(HidSessionStatus.IDLE, "Bluetooth is off. Turn it on and try again.")
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (!shuttingDown) {
                        refreshPairedHosts()
                        if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ==
                            BluetoothDevice.BOND_BONDED
                        ) {
                            cancelDiscoverabilityMessage()
                            HidSessionStore.update {
                                it.copy(
                                    pairingModeActive = false,
                                    message = "Computer paired. Select it from the list to connect HID.",
                                    feedbackLevel = HidFeedbackLevel.INFO,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || shuttingDown) return
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE || shuttingDown) return
            hidDevice = null
            connectedDevice = null
            finishSession(HidSessionStatus.ERROR, "Android disconnected the HID Device profile.")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (shuttingDown) return
            if (registered) {
                refreshPairedHosts()
                update(HidSessionStatus.READY, "HID gamepad registered. Select a paired PC.")
            } else {
                connectedDevice = null
                finishSession(HidSessionStatus.ERROR, "Android did not keep the HID registration.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (shuttingDown) return
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> update(
                    HidSessionStatus.CONNECTING,
                    "Connecting to ${device.safeName()}…",
                )
                BluetoothProfile.STATE_CONNECTED -> {
                    acceptConnectedDevice(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    HidSessionStore.update {
                        it.copy(
                            status = HidSessionStatus.READY,
                            connectedHost = null,
                            message = "Host disconnected. Select it to reconnect.",
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        shuttingDown = false
        HidSessionStore.update {
            HidSessionState(
                status = HidSessionStatus.STARTING,
                sessionActive = true,
                message = "Starting the Bluetooth HID session…",
            )
        }
        registerBluetoothStateReceiver()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startHid()
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDRESS))
            ACTION_TEST_PRESS -> sendTestReport(pressed = true)
            ACTION_TEST_RELEASE -> sendTestReport(pressed = false)
            ACTION_TEST_AXIS -> sendAxisReport(intent.getIntExtra(EXTRA_AXIS_VALUE, 0))
            ACTION_REFRESH_HOSTS -> if (!shuttingDown) refreshPairedHosts()
            ACTION_DISCOVERABILITY_STARTED -> showDiscoverabilityMessage(
                intent.getIntExtra(EXTRA_DISCOVERABLE_DURATION, 120),
            )
            ACTION_STOP -> stopHid()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val stateWasFinalized = shuttingDown
        shuttingDown = true
        cancelDiscoverabilityMessage()
        releaseProfile()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        if (!stateWasFinalized) {
            HidSessionStore.update { HidSessionState(message = "HID session ended.") }
        }
        super.onDestroy()
    }

    private fun startHid() {
        val currentAdapter = adapter
        if (currentAdapter == null) {
            finishSession(HidSessionStatus.ERROR, "This device has no Bluetooth adapter.")
            return
        }
        if (!currentAdapter.isEnabled) {
            finishSession(HidSessionStatus.IDLE, "Bluetooth is off. Turn it on and try again.")
            return
        }
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.STARTING,
                bluetoothEnabled = true,
                message = "Requesting the Android HID Device profile…",
            )
        }
        val requested = currentAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) finishSession(HidSessionStatus.ERROR, "HID Device profile is unavailable.")
    }

    private fun registerHidApp() {
        update(HidSessionStatus.REGISTERING, "Registering the BridgePad HID gamepad…")
        val settings = BluetoothHidDeviceAppSdpSettings(
            "BridgePad",
            "BridgePad Bluetooth HID gamepad spike",
            "BridgePad",
            0x02,
            GamepadHidDescriptor.bytes,
        )
        val accepted = hidDevice?.registerApp(settings, null, null, mainExecutor, callback) == true
        if (!accepted) {
            finishSession(HidSessionStatus.ERROR, "Android rejected the HID registration request.")
        }
    }

    private fun refreshPairedHosts() {
        val hosts = adapter?.bondedDevices.orEmpty()
            .filter {
                it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
            }
            .map { PairedHost(it.address, it.safeName()) }
            .sortedBy { it.name.lowercase() }
        HidSessionStore.update { it.copy(pairedHosts = hosts, bluetoothEnabled = true) }
    }

    private fun connect(address: String?) {
        if (address.isNullOrBlank() || hidDevice == null) return
        val device = adapter?.getRemoteDevice(address) ?: return
        when (hidDevice?.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> {
                acceptConnectedDevice(device)
                return
            }
            BluetoothProfile.STATE_CONNECTING -> {
                update(
                    HidSessionStatus.CONNECTING,
                    "Windows is already connecting to ${device.safeName()}…",
                )
                return
            }
        }
        update(HidSessionStatus.CONNECTING, "Requesting connection to ${device.safeName()}…")
        if (hidDevice?.connect(device) != true) {
            update(HidSessionStatus.ERROR, "Android rejected the connection request.")
        }
    }

    private fun acceptConnectedDevice(device: BluetoothDevice) {
        connectedDevice = device
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.CONNECTED,
                connectedHost = device.safeName(),
                message = "Connected. The test button can now send HID reports.",
                feedbackLevel = HidFeedbackLevel.INFO,
            )
        }
    }

    private fun sendTestReport(pressed: Boolean) {
        val device = connectedDevice ?: return
        val report = GamepadHidDescriptor.gamepadReport(pressed, testAxisValue)
        if (hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, report) != true) {
            update(HidSessionStatus.ERROR, "The HID test report could not be sent.")
        }
    }

    private fun sendAxisReport(value: Int) {
        val device = connectedDevice ?: return
        testAxisValue = value.coerceIn(-127, 127)
        val report = GamepadHidDescriptor.gamepadReport(
            southPressed = false,
            xAxis = testAxisValue,
        )
        if (hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, report) != true) {
            update(HidSessionStatus.ERROR, "The HID axis report could not be sent.")
        }
    }

    private fun stopHid() {
        if (shuttingDown) return
        shuttingDown = true
        connectedDevice?.let { device ->
            hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, GamepadHidDescriptor.neutralReport())
            hidDevice?.disconnect(device)
        }
        releaseProfile()
        HidSessionStore.update { HidSessionState(message = "HID session stopped.") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishSession(status: HidSessionStatus, message: String) {
        if (shuttingDown) return
        shuttingDown = true
        HidSessionStore.update {
            HidSessionState(
                status = status,
                sessionActive = false,
                bluetoothEnabled = adapter?.isEnabled == true,
                message = message,
                feedbackLevel = if (status == HidSessionStatus.IDLE) {
                    HidFeedbackLevel.WARNING
                } else {
                    HidFeedbackLevel.ERROR
                },
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProfile() {
        hidDevice?.unregisterApp()
        hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        connectedDevice = null
        testAxisValue = 0
    }

    private fun showDiscoverabilityMessage(durationSeconds: Int) {
        cancelDiscoverabilityMessage()
        HidSessionStore.update {
            it.copy(
                pairingModeActive = true,
                message = "This phone is visible to nearby devices for $durationSeconds seconds. Add it from Windows Bluetooth settings.",
                feedbackLevel = HidFeedbackLevel.WARNING,
            )
        }
        val timeout = Runnable {
            HidSessionStore.update {
                it.copy(
                    pairingModeActive = false,
                    message = "Phone visibility ended. Tap Pair new PC to try again.",
                    feedbackLevel = HidFeedbackLevel.INFO,
                )
            }
        }
        discoverabilityTimeout = timeout
        handler.postDelayed(timeout, durationSeconds.coerceAtLeast(1) * 1_000L)
    }

    private fun cancelDiscoverabilityMessage() {
        discoverabilityTimeout?.let(handler::removeCallbacks)
        discoverabilityTimeout = null
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothStateReceiver() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            .apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothStateReceiver, filter)
        }
    }

    private fun update(status: HidSessionStatus, message: String) {
        HidSessionStore.update {
            it.copy(
                status = status,
                message = message,
                feedbackLevel = if (status == HidSessionStatus.ERROR) {
                    HidFeedbackLevel.ERROR
                } else {
                    HidFeedbackLevel.INFO
                },
            )
        }
    }

    private fun BluetoothDevice.safeName(): String = name?.takeIf { it.isNotBlank() } ?: address

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bluetooth HID session",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("BridgePad HID spike")
        .setContentText("Bluetooth HID session is active")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        const val ACTION_START = "dev.jonalakas.bridgepad.hid.START"
        const val ACTION_CONNECT = "dev.jonalakas.bridgepad.hid.CONNECT"
        const val ACTION_TEST_PRESS = "dev.jonalakas.bridgepad.hid.TEST_PRESS"
        const val ACTION_TEST_RELEASE = "dev.jonalakas.bridgepad.hid.TEST_RELEASE"
        const val ACTION_TEST_AXIS = "dev.jonalakas.bridgepad.hid.TEST_AXIS"
        const val ACTION_REFRESH_HOSTS = "dev.jonalakas.bridgepad.hid.REFRESH_HOSTS"
        const val ACTION_DISCOVERABILITY_STARTED = "dev.jonalakas.bridgepad.hid.DISCOVERABILITY_STARTED"
        const val ACTION_STOP = "dev.jonalakas.bridgepad.hid.STOP"
        const val EXTRA_ADDRESS = "host_address"
        const val EXTRA_DISCOVERABLE_DURATION = "discoverable_duration"
        const val EXTRA_AXIS_VALUE = "axis_value"

        private const val CHANNEL_ID = "bluetooth_hid_session"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, action: String) =
            Intent(context, BluetoothHidService::class.java).setAction(action)
    }
}
