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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.gamepad.SourceGamepadState
import dev.jonalakas.bridgepad.core.gamepad.VirtualAxis
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.mapping.InputMerger
import dev.jonalakas.bridgepad.core.mapping.InputOwnership
import dev.jonalakas.bridgepad.core.output.HidReportEncoder
import dev.jonalakas.bridgepad.core.output.OutputScheduler
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.input.android.PhysicalGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchGamepadSnapshot
import dev.jonalakas.bridgepad.input.touch.TouchGamepadStore
import dev.jonalakas.bridgepad.input.touch.TouchMouseStore
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadController
import dev.jonalakas.bridgepad.input.usb.DirectUsbGamepadStore
import dev.jonalakas.bridgepad.input.usb.DirectUsbState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class BluetoothHidService : Service() {
    private var adapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var requestedHostAddress: String? = null
    private var lastHostAddress: String? = null
    private var shuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outputScheduler = OutputScheduler(OUTPUT_RATE_HZ)
    private var discoverabilityTimeout: Runnable? = null
    private var pendingConnection: Runnable? = null
    private var latestPhysicalState = PhysicalGamepadState()
    private var latestTouchState = TouchGamepadSnapshot()
    private var latestDirectUsbState = DirectUsbState()
    private lateinit var directUsbController: DirectUsbGamepadController
    private var lastObservedInputCount = 0L
    private var lastObservedTouchInputCount = 0L
    private var lastObservedDirectUsbInputCount = 0L
    private var pendingInputTimestampNanos: Long? = null
    private var metricsStartedNanos = 0L
    private var inputEventsSinceConnection = 0L
    private var reportsSinceConnection = 0L
    private var lastMetricsUpdateNanos = 0L
    private val outputTick = object : Runnable {
        override fun run() {
            if (shuttingDown) return
            sendScheduledReport()
            sendMouseReport()
            handler.postDelayed(this, OUTPUT_INTERVAL_MS)
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        SessionLog.record("BLUETOOTH", "Bluetooth was turned off")
                        finishSession(HidSessionStatus.IDLE, "Bluetooth is off. Turn it on and try again.")
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    if (!shuttingDown) {
                        val pairingWasActive = HidSessionStore.state.value.pairingModeActive
                        refreshPairedHosts()
                        if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) ==
                            BluetoothDevice.BOND_BONDED
                        ) {
                            @Suppress("DEPRECATION")
                            val pairedDevice = intent.getParcelableExtra<BluetoothDevice>(
                                BluetoothDevice.EXTRA_DEVICE,
                            )
                            val newlyPairedComputer = pairedDevice?.takeIf {
                                pairingWasActive &&
                                    it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER
                            }
                            cancelDiscoverabilityMessage()
                            HidSessionStore.update {
                                it.copy(
                                    pairingModeActive = false,
                                    message = if (newlyPairedComputer != null) {
                                        "Computer paired. Connecting HID…"
                                    } else {
                                        "Computer paired. Select it from the list to connect HID."
                                    },
                                    feedbackLevel = HidFeedbackLevel.INFO,
                                )
                            }
                            if (newlyPairedComputer != null) {
                                handler.postDelayed(
                                    { connect(newlyPairedComputer.address) },
                                    POST_PAIR_CONNECTION_DELAY_MS,
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
                if (pluggedDevice != null) {
                    hidDevice?.disconnect(pluggedDevice)
                }
                update(HidSessionStatus.READY, "HID gamepad registered. Choose a paired PC or pair a new one.")
            } else {
                connectedDevice = null
                finishSession(HidSessionStatus.ERROR, "Android did not keep the HID registration.")
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (shuttingDown) return
            val wasRequested = device.address == requestedHostAddress
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> if (wasRequested) {
                    update(
                        HidSessionStatus.CONNECTING,
                        "Connecting to ${device.safeName()}…",
                    )
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    if (wasRequested) {
                        acceptConnectedDevice(device)
                    } else {
                        hidDevice?.disconnect(device)
                        update(
                            HidSessionStatus.READY,
                            "Choose a paired PC before connecting.",
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!wasRequested && connectedDevice == null) return
                    stopOutputPipeline()
                    requestedHostAddress = null
                    connectedDevice = null
                    HidSessionStore.update {
                        it.copy(
                            status = HidSessionStatus.READY,
                            connectedHost = null,
                            canReconnect = lastHostAddress != null,
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
        directUsbController = DirectUsbGamepadController(this) { active, message, error ->
            HidSessionStore.update {
                it.copy(
                    physicalCaptureMode = if (active) PhysicalCaptureMode.BACKGROUND_USB else PhysicalCaptureMode.COMPATIBILITY,
                    directUsbActive = active,
                    message = message,
                    feedbackLevel = if (error) HidFeedbackLevel.WARNING else HidFeedbackLevel.INFO,
                )
            }
            updateNotification()
        }
        directUsbController.register()
        observePhysicalGamepad()
        observeTouchGamepad()
        observeDirectUsbGamepad()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val touchSelected = intent.getBooleanExtra(EXTRA_TOUCH_INPUT_SELECTED, true)
                HidSessionStore.update { it.copy(touchInputSelected = touchSelected) }
                updateNotification()
                startHid()
            }
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDRESS))
            ACTION_RECONNECT -> reconnect()
            ACTION_ENABLE_BACKGROUND_USB -> directUsbController.start()
            ACTION_ENABLE_COMPATIBILITY_INPUT -> {
                latestDirectUsbState = DirectUsbState()
                directUsbController.stop()
                outputScheduler.submit(mergeInputSources())
                HidSessionStore.update {
                    it.copy(
                        physicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY,
                        directUsbActive = false,
                        message = "Compatibility input is active. Keep BridgePad visible and the screen on.",
                        feedbackLevel = HidFeedbackLevel.INFO,
                    )
                }
                updateNotification()
            }
            ACTION_SELECT_INPUT -> selectInput(
                intent.getBooleanExtra(EXTRA_TOUCH_INPUT_SELECTED, true),
            )
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
        cancelPendingConnection()
        stopOutputPipeline()
        if (::directUsbController.isInitialized) directUsbController.unregister()
        serviceScope.cancel()
        releaseProfile()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        if (!stateWasFinalized) {
            HidSessionStore.update { HidSessionState(message = "HID session ended.") }
        }
        super.onDestroy()
    }

    private fun startHid() {
        SessionLog.record("SESSION", "Bluetooth HID session start requested")
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

    private fun selectInput(touchSelected: Boolean) {
        TouchMouseStore.clear()
        connectedDevice?.let { device ->
            hidDevice?.sendReport(
                device,
                GamepadHidDescriptor.REPORT_ID,
                GamepadHidDescriptor.neutralReport(),
            )
        }
        HidSessionStore.update {
            it.copy(
                touchInputSelected = touchSelected,
                message = if (touchSelected) {
                    "Touchscreen input selected. Open the touchscreen controller to play."
                } else {
                    "Physical gamepad input selected. Choose a capture mode below."
                },
                feedbackLevel = HidFeedbackLevel.INFO,
            )
        }
        updateNotification()
        outputScheduler.submit(mergeInputSources())
        SessionLog.record("INPUT", if (touchSelected) "Touchscreen input selected" else "Physical gamepad input selected")
    }

    private fun registerHidApp() {
        update(HidSessionStatus.REGISTERING, "Registering the BridgePad HID gamepad…")
        val settings = BluetoothHidDeviceAppSdpSettings(
            "BridgePad",
            "BridgePad Bluetooth HID gamepad and mouse bridge",
            "BridgePad",
            (
                BluetoothHidDevice.SUBCLASS1_MOUSE.toInt() or
                    BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()
            ).toByte(),
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
        if (connectedDevice != null || pendingConnection != null || requestedHostAddress != null) {
            HidSessionStore.update {
                it.copy(
                    message = if (connectedDevice != null) {
                        "A PC is already connected. End the session before choosing another one."
                    } else {
                        "A connection attempt is already in progress."
                    },
                    feedbackLevel = HidFeedbackLevel.WARNING,
                )
            }
            return
        }
        val device = adapter?.getRemoteDevice(address) ?: return
        requestedHostAddress = address
        lastHostAddress = address
        SessionLog.record("CONNECTION", "Connection requested for a paired computer")
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
        update(
            HidSessionStatus.CONNECTING,
            "Waiting briefly for Windows to connect to ${device.safeName()}…",
        )
        cancelPendingConnection()
        val request = Runnable { connectAfterAutomaticAttempt(device) }
        pendingConnection = request
        handler.postDelayed(request, AUTOMATIC_CONNECTION_GRACE_PERIOD_MS)
    }

    private fun reconnect() {
        val address = lastHostAddress
        if (address == null) {
            HidSessionStore.update {
                it.copy(
                    message = "No previous PC is available. Select a paired PC instead.",
                    feedbackLevel = HidFeedbackLevel.WARNING,
                )
            }
            return
        }
        connect(address)
    }

    private fun connectAfterAutomaticAttempt(device: BluetoothDevice) {
        pendingConnection = null
        if (shuttingDown) return
        when (hidDevice?.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> acceptConnectedDevice(device)
            BluetoothProfile.STATE_CONNECTING -> update(
                HidSessionStatus.CONNECTING,
                "Windows is connecting to ${device.safeName()}…",
            )
            else -> {
                update(HidSessionStatus.CONNECTING, "Requesting connection to ${device.safeName()}…")
                if (hidDevice?.connect(device) != true) {
                    requestedHostAddress = null
                    update(HidSessionStatus.ERROR, "Android rejected the connection request.")
                }
            }
        }
    }

    private fun acceptConnectedDevice(device: BluetoothDevice) {
        connectedDevice = device
        requestedHostAddress = device.address
        lastHostAddress = device.address
        SessionLog.record("CONNECTION", "HID connection established")
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.CONNECTED,
                connectedHost = device.safeName(),
                canReconnect = false,
                message = "Connected. Gamepad input is being sent to the PC.",
                feedbackLevel = HidFeedbackLevel.INFO,
            )
        }
        startOutputPipeline()
    }

    private fun observePhysicalGamepad() {
        latestPhysicalState = PhysicalGamepadStore.state.value
        outputScheduler.submit(mergeInputSources())
        serviceScope.launch {
            PhysicalGamepadStore.updates.collect { state ->
                latestPhysicalState = state
                outputScheduler.submit(mergeInputSources())
                if (state.inputEventCount != lastObservedInputCount) {
                    if (
                        connectedDevice != null &&
                        !HidSessionStore.state.value.touchInputSelected &&
                        !latestDirectUsbState.active
                    ) inputEventsSinceConnection++
                    lastObservedInputCount = state.inputEventCount
                    pendingInputTimestampNanos = state.lastInputTimestampNanos
                }
            }
        }
    }

    private fun observeTouchGamepad() {
        latestTouchState = TouchGamepadStore.state.value
        lastObservedTouchInputCount = latestTouchState.inputEventCount
        outputScheduler.submit(mergeInputSources())
        serviceScope.launch {
            TouchGamepadStore.updates.collect { state ->
                latestTouchState = state
                outputScheduler.submit(mergeInputSources())
                if (state.inputEventCount != lastObservedTouchInputCount) {
                    if (connectedDevice != null && HidSessionStore.state.value.touchInputSelected) {
                        inputEventsSinceConnection++
                    }
                    lastObservedTouchInputCount = state.inputEventCount
                    pendingInputTimestampNanos = state.lastInputTimestampNanos
                }
            }
        }
    }

    private fun observeDirectUsbGamepad() {
        serviceScope.launch {
            DirectUsbGamepadStore.state.collect { state ->
                latestDirectUsbState = state
                outputScheduler.submit(mergeInputSources())
                if (state.inputEventCount != lastObservedDirectUsbInputCount) {
                    if (connectedDevice != null && !HidSessionStore.state.value.touchInputSelected) {
                        inputEventsSinceConnection++
                    }
                    lastObservedDirectUsbInputCount = state.inputEventCount
                    pendingInputTimestampNanos = state.lastInputTimestampNanos
                }
            }
        }
    }

    private fun mergeInputSources(): VirtualGamepadState {
        val touchSelected = HidSessionStore.state.value.touchInputSelected
        val primary = if (touchSelected) {
            TouchGamepadStore.sourceId
        } else if (latestDirectUsbState.active) {
            DIRECT_USB_SOURCE_ID
        } else {
            latestPhysicalState.devices.firstOrNull()?.sourceId
        }
        val ownership = if (primary == null) {
            InputOwnership()
        } else {
            InputOwnership(
                axes = VirtualAxis.entries.associateWith { primary },
                dpad = primary,
            )
        }
        val physicalSources = if (touchSelected || latestDirectUsbState.active) emptyList() else {
            latestPhysicalState.sourceStates.map { (sourceId, gamepad) -> SourceGamepadState(sourceId, gamepad) }
        }
        val touchSource = SourceGamepadState(
            TouchGamepadStore.sourceId,
            if (touchSelected) latestTouchState.gamepad else VirtualGamepadState(),
        )
        val directSource = SourceGamepadState(
            DIRECT_USB_SOURCE_ID,
            if (!touchSelected) latestDirectUsbState.gamepad else VirtualGamepadState(),
        )
        val sources = physicalSources + touchSource + directSource
        return InputMerger.merge(sources, ownership)
    }

    private fun startOutputPipeline() {
        handler.removeCallbacks(outputTick)
        outputScheduler.stop()
        outputScheduler.submit(mergeInputSources())
        metricsStartedNanos = monotonicNanos()
        lastMetricsUpdateNanos = metricsStartedNanos
        inputEventsSinceConnection = 0
        reportsSinceConnection = 0
        pendingInputTimestampNanos = null
        handler.post(outputTick)
    }

    private fun sendScheduledReport() {
        val device = connectedDevice ?: return
        val now = monotonicNanos()
        val state = outputScheduler.poll(now) ?: return
        val sent = hidDevice?.sendReport(
            device,
            GamepadHidDescriptor.REPORT_ID,
            HidReportEncoder.encode(state),
        ) == true
        if (!sent) {
            update(HidSessionStatus.ERROR, "A physical gamepad report could not be sent.")
            return
        }
        reportsSinceConnection++
        val latency = pendingInputTimestampNanos?.let { timestamp ->
            ((now - timestamp).coerceAtLeast(0L) / 1_000_000f).also {
                pendingInputTimestampNanos = null
            }
        }
        if (now - lastMetricsUpdateNanos >= METRICS_UPDATE_INTERVAL_NANOS || latency != null) {
            val elapsedSeconds = ((now - metricsStartedNanos).coerceAtLeast(1L)) / 1_000_000_000f
            HidSessionStore.update {
                it.copy(
                    inputRateHz = inputEventsSinceConnection / elapsedSeconds,
                    outputRateHz = reportsSinceConnection / elapsedSeconds,
                    lastLatencyMs = latency ?: it.lastLatencyMs,
                )
            }
            lastMetricsUpdateNanos = now
        }
    }

    private fun sendMouseReport() {
        val device = connectedDevice ?: return
        val report = TouchMouseStore.consume() ?: return
        val sent = hidDevice?.sendReport(
            device,
            GamepadHidDescriptor.MOUSE_REPORT_ID,
            GamepadHidDescriptor.mouseReport(report.buttons, report.deltaX, report.deltaY),
        ) == true
        if (!sent) {
            SessionLog.record("MOUSE", "A mouse report could not be sent")
        }
    }

    private fun stopOutputPipeline() {
        handler.removeCallbacks(outputTick)
        outputScheduler.stop()
        pendingInputTimestampNanos = null
        TouchMouseStore.clear()
    }

    private fun monotonicNanos(): Long = SystemClock.uptimeMillis() * 1_000_000L

    private fun stopHid() {
        if (shuttingDown) return
        shuttingDown = true
        cancelPendingConnection()
        stopOutputPipeline()
        connectedDevice?.let { device ->
            hidDevice?.sendReport(device, GamepadHidDescriptor.REPORT_ID, GamepadHidDescriptor.neutralReport())
            hidDevice?.disconnect(device)
        }
        SessionLog.record("SESSION", "Session ended safely with a neutral report")
        releaseProfile()
        HidSessionStore.update { HidSessionState(message = "HID session stopped.") }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishSession(status: HidSessionStatus, message: String) {
        if (shuttingDown) return
        shuttingDown = true
        SessionLog.record(if (status == HidSessionStatus.ERROR) "ERROR" else "SESSION", message)
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
        requestedHostAddress = null
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

    private fun cancelPendingConnection() {
        pendingConnection?.let(handler::removeCallbacks)
        pendingConnection = null
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

    private fun buildNotification(): android.app.Notification {
        val state = HidSessionStore.state.value
        val message = when {
            state.touchInputSelected -> getString(R.string.notification_touchscreen_active)
            state.directUsbActive -> getString(R.string.notification_background_usb_active)
            else -> getString(R.string.notification_physical_gamepad_active)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
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
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_START = "dev.jonalakas.bridgepad.hid.START"
        const val ACTION_CONNECT = "dev.jonalakas.bridgepad.hid.CONNECT"
        const val ACTION_RECONNECT = "dev.jonalakas.bridgepad.hid.RECONNECT"
        const val ACTION_ENABLE_BACKGROUND_USB = "dev.jonalakas.bridgepad.hid.ENABLE_BACKGROUND_USB"
        const val ACTION_ENABLE_COMPATIBILITY_INPUT = "dev.jonalakas.bridgepad.hid.ENABLE_COMPATIBILITY_INPUT"
        const val ACTION_SELECT_INPUT = "dev.jonalakas.bridgepad.hid.SELECT_INPUT"
        const val ACTION_REFRESH_HOSTS = "dev.jonalakas.bridgepad.hid.REFRESH_HOSTS"
        const val ACTION_DISCOVERABILITY_STARTED = "dev.jonalakas.bridgepad.hid.DISCOVERABILITY_STARTED"
        const val ACTION_STOP = "dev.jonalakas.bridgepad.hid.STOP"
        const val EXTRA_ADDRESS = "host_address"
        const val EXTRA_DISCOVERABLE_DURATION = "discoverable_duration"
        const val EXTRA_TOUCH_INPUT_SELECTED = "touch_input_selected"

        private const val CHANNEL_ID = "bluetooth_hid_session"
        private const val NOTIFICATION_ID = 1001
        private const val AUTOMATIC_CONNECTION_GRACE_PERIOD_MS = 1_500L
        private const val POST_PAIR_CONNECTION_DELAY_MS = 500L
        private const val OUTPUT_RATE_HZ = 100
        private const val OUTPUT_INTERVAL_MS = 10L
        private const val METRICS_UPDATE_INTERVAL_NANOS = 500_000_000L
        private val DIRECT_USB_SOURCE_ID = dev.jonalakas.bridgepad.core.gamepad.SourceId("direct-usb")

        fun intent(context: Context, action: String) =
            Intent(context, BluetoothHidService::class.java).setAction(action)
    }
}
