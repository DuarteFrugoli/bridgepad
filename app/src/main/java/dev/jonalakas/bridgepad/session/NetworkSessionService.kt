package dev.jonalakas.bridgepad.session

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.BridgePadApplication
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Android lifecycle host that keeps an active Wi-Fi gameplay session alive off-screen. */
class NetworkSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusJob: Job? = null
    private var sawRunningSession = false
    private var ownsDirectUsbCapture = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground(buildNotification())
        acquireSessionLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        val captureMode = intent.getStringExtra(EXTRA_CAPTURE_MODE)
            ?.let { runCatching { PhysicalCaptureMode.valueOf(it) }.getOrNull() }
            ?: PhysicalCaptureMode.COMPATIBILITY
        configureDirectUsbCapture(captureMode)
        observeSessionStatus()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        statusJob?.cancel()
        statusJob = null
        serviceScope.cancel()
        val gameplay = (application as BridgePadApplication).networkGameplayController
        if (gameplay.status.value.isRunning()) gameplay.stop()
        if (ownsDirectUsbCapture) DirectUsbCaptureManager.stop()
        ownsDirectUsbCapture = false
        releaseSessionLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun configureDirectUsbCapture(captureMode: PhysicalCaptureMode) {
        val useDirectUsb = captureMode == PhysicalCaptureMode.BACKGROUND_USB
        if (useDirectUsb) {
            DirectUsbCaptureManager.start(applicationContext)
        } else if (ownsDirectUsbCapture) {
            DirectUsbCaptureManager.stop()
        }
        ownsDirectUsbCapture = useDirectUsb
        updateNotification()
    }

    private fun observeSessionStatus() {
        if (statusJob != null) return
        val gameplay = (application as BridgePadApplication).networkGameplayController
        statusJob = serviceScope.launch {
            gameplay.status.collect { status ->
                if (status.isRunning()) sawRunningSession = true
                updateNotification(status)
                if (sawRunningSession && status.isTerminal()) stopSelf()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireSessionLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:network-session")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:network-session")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseSessionLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.network_session_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(
        status: NetworkGamepadStatus = NetworkGamepadStatus.Connecting,
    ): Notification {
        val message = when (status) {
            is NetworkGamepadStatus.Reconnecting -> getString(
                R.string.wifi_reconnecting,
                status.attempt,
                status.maximumAttempts,
            )
            else -> getString(
                if (ownsDirectUsbCapture) {
                    R.string.notification_wifi_background_usb_active
                } else {
                    R.string.notification_wifi_session_active
                },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    private fun updateNotification(
        status: NetworkGamepadStatus =
            (application as BridgePadApplication).networkGameplayController.status.value,
    ) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START = "dev.jonalakas.bridgepad.network.START_SESSION"
        private const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val CHANNEL_ID = "bridgepad_network_session"
        private const val NOTIFICATION_ID = 1_002

        fun start(context: Context, captureMode: PhysicalCaptureMode) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NetworkSessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CAPTURE_MODE, captureMode.name),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NetworkSessionService::class.java))
        }
    }
}

private fun NetworkGamepadStatus.isRunning(): Boolean =
    this is NetworkGamepadStatus.Connecting ||
        this is NetworkGamepadStatus.Reconnecting ||
        this is NetworkGamepadStatus.Active

private fun NetworkGamepadStatus.isTerminal(): Boolean =
    this is NetworkGamepadStatus.Stopped || this is NetworkGamepadStatus.Failed
