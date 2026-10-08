package dev.jonalakas.bridgepad.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.BridgePadApplication
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterIds
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.transport.bluetooth.desktop.BluetoothDesktopGamepadStatus
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * One Android lifecycle host for every BridgePad Desktop gameplay transport.
 *
 * The protocol clients remain transport-specific. This service owns everything
 * that must survive leaving the activity: foreground priority, CPU/Wi-Fi locks,
 * direct USB input capture and process-death restoration.
 */
class GameplaySessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var resources: GameplaySessionResources
    private lateinit var recordStore: GameplaySessionRecordStore
    private var statusJob: Job? = null
    private var activeRecord: GameplaySessionRecord? = null
    private var sawRunningSession = false

    override fun onCreate() {
        super.onCreate()
        lifecycleActive = true
        resources = GameplaySessionResources(this, "$packageName:gameplay-session")
        recordStore = GameplaySessionRecordStore(this)
        createNotificationChannel()
        startAsForeground(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopActiveTransport()
            recordStore.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        val record = when {
            intent?.action == ACTION_START -> intent.toGameplaySessionRecord()
            intent == null -> recordStore.load()
            else -> null
        }
        if (record == null) {
            recordStore.clear()
            stopSelf()
            return START_NOT_STICKY
        }

        configure(record)
        if (intent == null) {
            // A persisted record represents a session that had already run
            // before Android reclaimed the process. Treat a failed restoration
            // as terminal instead of leaving a permanent idle notification.
            sawRunningSession = true
            restoreTransportIfNeeded(record)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        lifecycleActive = false
        statusJob?.cancel()
        statusJob = null
        serviceScope.cancel()
        resources.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun configure(record: GameplaySessionRecord) {
        val changedSession = activeRecord?.sameTransportAs(record) != true
        activeRecord = record
        recordStore.save(record)
        resources.start(
            captureMode = record.captureMode,
            keepWifiAwake = record.connectionMethod == ConnectionMethod.WIFI,
        )
        if (changedSession || statusJob == null) observeTransport(record.transport)
        updateNotification()
    }

    private fun observeTransport(transport: GameplayTransport) {
        statusJob?.cancel()
        sawRunningSession = false
        val application = application as BridgePadApplication
        statusJob = serviceScope.launch {
            when (transport) {
                GameplayTransport.NETWORK -> application.networkGameplayController.status.collect { status ->
                    if (status.isRunning()) sawRunningSession = true
                    updateNotification()
                    if (sawRunningSession && status.isTerminal()) finishLifecycleHost()
                }
                GameplayTransport.BLUETOOTH_DESKTOP ->
                    application.bluetoothDesktopGameplayController.status.collect { status ->
                        if (status.isRunning()) sawRunningSession = true
                        updateNotification()
                        if (sawRunningSession && status.isTerminal()) finishLifecycleHost()
                    }
            }
        }
    }

    private fun restoreTransportIfNeeded(record: GameplaySessionRecord) {
        val application = application as BridgePadApplication
        when (record.transport) {
            GameplayTransport.NETWORK -> {
                if (!application.networkGameplayController.status.value.isRunning()) {
                    application.networkDesktopCoordinator.startGameplay(
                        peerIdHex = record.destinationId,
                        captureMode = record.captureMode,
                        connectionMethod = record.connectionMethod,
                    )
                }
            }
            GameplayTransport.BLUETOOTH_DESKTOP -> {
                if (!application.bluetoothDesktopGameplayController.status.value.isRunning()) {
                    val started = application.sessionCoordinator.start(
                        adapterId = OutputAdapterIds.DESKTOP_BLUETOOTH,
                        destination = DestinationType.PC,
                        physicalCaptureMode = record.captureMode,
                    )
                    if (!started || !application.sessionCoordinator.connect(record.destinationId)) {
                        finishLifecycleHost()
                    }
                }
            }
        }
    }

    private fun stopActiveTransport() {
        val application = application as BridgePadApplication
        when (activeRecord?.transport) {
            GameplayTransport.NETWORK -> application.networkGameplayController.stop()
            GameplayTransport.BLUETOOTH_DESKTOP -> application.sessionCoordinator.stop()
            null -> Unit
        }
    }

    private fun finishLifecycleHost() {
        recordStore.clear()
        stopSelf()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gameplay_session_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val record = activeRecord
        val application = application as BridgePadApplication
        val reconnectingMessage = when (record?.transport) {
            GameplayTransport.NETWORK ->
                (application.networkGameplayController.status.value as? NetworkGamepadStatus.Reconnecting)
                    ?.let { status ->
                        status.maximumAttempts?.let { maximumAttempts ->
                            getString(R.string.wifi_reconnecting, status.attempt, maximumAttempts)
                        } ?: getString(R.string.network_waiting_to_reconnect)
                    }
            GameplayTransport.BLUETOOTH_DESKTOP ->
                (application.bluetoothDesktopGameplayController.status.value as?
                    BluetoothDesktopGamepadStatus.Reconnecting)?.let { status ->
                    getString(
                        R.string.bluetooth_desktop_reconnecting,
                        status.attempt,
                        status.maximumAttempts,
                    )
                }
            null -> null
        }
        val message = reconnectingMessage ?: when {
            record?.connectionMethod == ConnectionMethod.USB && resources.isDirectUsbRequested ->
                getString(R.string.notification_usb_session_background_input_active)
            record?.connectionMethod == ConnectionMethod.USB ->
                getString(R.string.notification_usb_session_active)
            record?.connectionMethod == ConnectionMethod.WIFI && resources.isDirectUsbRequested ->
                getString(R.string.notification_wifi_background_usb_active)
            record?.connectionMethod == ConnectionMethod.WIFI ->
                getString(R.string.notification_wifi_session_active)
            resources.isDirectUsbRequested ->
                getString(R.string.notification_background_usb_active)
            else -> getString(R.string.notification_automatic_input_active)
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

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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

    private fun Intent.toGameplaySessionRecord(): GameplaySessionRecord? {
        val transport = getStringExtra(EXTRA_TRANSPORT)
            ?.let { runCatching { GameplayTransport.valueOf(it) }.getOrNull() }
            ?: return null
        val captureMode = getStringExtra(EXTRA_CAPTURE_MODE)
            ?.let { runCatching { PhysicalCaptureMode.valueOf(it) }.getOrNull() }
            ?: return null
        val connectionMethod = getStringExtra(EXTRA_CONNECTION_METHOD)
            ?.let { runCatching { ConnectionMethod.valueOf(it) }.getOrNull() }
            ?: return null
        val destinationId = getStringExtra(EXTRA_DESTINATION_ID)?.takeIf(String::isNotBlank)
            ?: return null
        return GameplaySessionRecord(transport, captureMode, connectionMethod, destinationId)
    }

    companion object {
        private const val ACTION_START = "dev.jonalakas.bridgepad.session.START_GAMEPLAY"
        private const val ACTION_STOP = "dev.jonalakas.bridgepad.session.STOP_GAMEPLAY"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val EXTRA_CONNECTION_METHOD = "connection_method"
        private const val EXTRA_DESTINATION_ID = "destination_id"
        private const val CHANNEL_ID = "bridgepad_gameplay_session"
        private const val NOTIFICATION_ID = 1_002
        @Volatile
        private var lifecycleActive = false

        fun startNetwork(
            context: Context,
            captureMode: PhysicalCaptureMode,
            connectionMethod: ConnectionMethod,
            peerIdHex: String,
        ) {
            require(connectionMethod == ConnectionMethod.WIFI || connectionMethod == ConnectionMethod.USB)
            start(
                context,
                GameplaySessionRecord(
                    GameplayTransport.NETWORK,
                    captureMode,
                    connectionMethod,
                    peerIdHex,
                ),
            )
        }

        fun startBluetoothDesktop(
            context: Context,
            captureMode: PhysicalCaptureMode,
            deviceAddress: String,
        ) {
            start(
                context,
                GameplaySessionRecord(
                    GameplayTransport.BLUETOOTH_DESKTOP,
                    captureMode,
                    ConnectionMethod.BLUETOOTH,
                    deviceAddress,
                ),
            )
        }

        fun updateCaptureMode(context: Context, captureMode: PhysicalCaptureMode) {
            if (!lifecycleActive) return
            val store = GameplaySessionRecordStore(context)
            val record = store.load()?.copy(captureMode = captureMode) ?: return
            start(context, record)
        }

        fun stop(context: Context) {
            GameplaySessionRecordStore(context).clear()
            context.stopService(Intent(context, GameplaySessionService::class.java))
        }

        private fun start(context: Context, record: GameplaySessionRecord) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GameplaySessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TRANSPORT, record.transport.name)
                    .putExtra(EXTRA_CAPTURE_MODE, record.captureMode.name)
                    .putExtra(EXTRA_CONNECTION_METHOD, record.connectionMethod.name)
                    .putExtra(EXTRA_DESTINATION_ID, record.destinationId),
            )
        }
    }
}

private enum class GameplayTransport {
    NETWORK,
    BLUETOOTH_DESKTOP,
}

private data class GameplaySessionRecord(
    val transport: GameplayTransport,
    val captureMode: PhysicalCaptureMode,
    val connectionMethod: ConnectionMethod,
    val destinationId: String,
) {
    fun sameTransportAs(other: GameplaySessionRecord): Boolean =
        transport == other.transport && destinationId == other.destinationId
}

private class GameplaySessionRecordStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(record: GameplaySessionRecord) {
        preferences.edit()
            .putString(KEY_TRANSPORT, record.transport.name)
            .putString(KEY_CAPTURE_MODE, record.captureMode.name)
            .putString(KEY_CONNECTION_METHOD, record.connectionMethod.name)
            .putString(KEY_DESTINATION_ID, record.destinationId)
            .apply()
    }

    fun load(): GameplaySessionRecord? {
        val transport = preferences.getString(KEY_TRANSPORT, null)
            ?.let { runCatching { GameplayTransport.valueOf(it) }.getOrNull() }
            ?: return null
        val captureMode = preferences.getString(KEY_CAPTURE_MODE, null)
            ?.let { runCatching { PhysicalCaptureMode.valueOf(it) }.getOrNull() }
            ?: return null
        val connectionMethod = preferences.getString(KEY_CONNECTION_METHOD, null)
            ?.let { runCatching { ConnectionMethod.valueOf(it) }.getOrNull() }
            ?: return null
        val destinationId = preferences.getString(KEY_DESTINATION_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return GameplaySessionRecord(transport, captureMode, connectionMethod, destinationId)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "gameplay_session_lifecycle"
        const val KEY_TRANSPORT = "transport"
        const val KEY_CAPTURE_MODE = "capture_mode"
        const val KEY_CONNECTION_METHOD = "connection_method"
        const val KEY_DESTINATION_ID = "destination_id"
    }
}

private fun NetworkGamepadStatus.isRunning(): Boolean =
    this is NetworkGamepadStatus.Connecting ||
        this is NetworkGamepadStatus.Reconnecting ||
        this is NetworkGamepadStatus.Active

private fun NetworkGamepadStatus.isTerminal(): Boolean =
    this is NetworkGamepadStatus.Stopped || this is NetworkGamepadStatus.Failed

private fun BluetoothDesktopGamepadStatus.isRunning(): Boolean =
    this is BluetoothDesktopGamepadStatus.Connecting ||
        this is BluetoothDesktopGamepadStatus.Reconnecting ||
        this is BluetoothDesktopGamepadStatus.Active

private fun BluetoothDesktopGamepadStatus.isTerminal(): Boolean =
    this is BluetoothDesktopGamepadStatus.Stopped || this is BluetoothDesktopGamepadStatus.Failed
