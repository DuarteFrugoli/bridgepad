package dev.jonalakas.bridgepad.output.hid

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.R

@SuppressLint("MissingPermission")
class BluetoothHidService : Service() {
    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            connectedDevice = null
            update(HidSessionStatus.ERROR, "Android disconnected the HID Device profile.")
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) {
                refreshPairedHosts()
                update(HidSessionStatus.READY, "HID gamepad registered. Select a paired PC.")
            } else {
                connectedDevice = null
                update(HidSessionStatus.ERROR, "Android did not keep the HID registration.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> update(
                    HidSessionStatus.CONNECTING,
                    "Connecting to ${device.safeName()}…",
                )
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    HidSessionStore.update {
                        it.copy(
                            status = HidSessionStatus.CONNECTED,
                            connectedHost = device.safeName(),
                            message = "Connected. The test button can now send HID reports.",
                        )
                    }
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
            ACTION_STOP -> stopHid()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseProfile()
        HidSessionStore.update { HidSessionState() }
        super.onDestroy()
    }

    private fun startHid() {
        val currentAdapter = adapter
        if (currentAdapter == null) {
            update(HidSessionStatus.ERROR, "This device has no Bluetooth adapter.")
            return
        }
        if (!currentAdapter.isEnabled) {
            HidSessionStore.update {
                it.copy(
                    status = HidSessionStatus.ERROR,
                    bluetoothEnabled = false,
                    message = "Turn Bluetooth on and try again.",
                )
            }
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
        if (!requested) update(HidSessionStatus.ERROR, "HID Device profile is unavailable.")
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
        if (!accepted) update(HidSessionStatus.ERROR, "Android rejected the HID registration request.")
    }

    private fun refreshPairedHosts() {
        val hosts = adapter?.bondedDevices.orEmpty()
            .map { PairedHost(it.address, it.safeName()) }
            .sortedBy { it.name.lowercase() }
        HidSessionStore.update { it.copy(pairedHosts = hosts, bluetoothEnabled = true) }
    }

    private fun connect(address: String?) {
        if (address.isNullOrBlank() || hidDevice == null) return
        val device = adapter?.getRemoteDevice(address) ?: return
        update(HidSessionStatus.CONNECTING, "Requesting connection to ${device.safeName()}…")
        if (hidDevice?.connect(device) != true) {
            update(HidSessionStatus.ERROR, "Android rejected the connection request.")
        }
    }

    private fun sendTestReport(pressed: Boolean) {
        val device = connectedDevice ?: return
        val report = GamepadHidDescriptor.southButtonReport(pressed)
        if (hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, report) != true) {
            update(HidSessionStatus.ERROR, "The HID test report could not be sent.")
        }
    }

    private fun stopHid() {
        connectedDevice?.let { device ->
            hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, GamepadHidDescriptor.neutralReport())
            hidDevice?.disconnect(device)
        }
        releaseProfile()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseProfile() {
        hidDevice?.unregisterApp()
        hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        connectedDevice = null
    }

    private fun update(status: HidSessionStatus, message: String) {
        HidSessionStore.update { it.copy(status = status, message = message) }
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
        const val ACTION_STOP = "dev.jonalakas.bridgepad.hid.STOP"
        const val EXTRA_ADDRESS = "host_address"

        private const val CHANNEL_ID = "bluetooth_hid_session"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, action: String) =
            Intent(context, BluetoothHidService::class.java).setAction(action)
    }
}
