package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.localization.LocalizedMessage
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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.MainActivity
import dev.jonalakas.bridgepad.BridgePadApplication
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterId
import dev.jonalakas.bridgepad.core.session.OutputAdapterIds
import dev.jonalakas.bridgepad.core.output.OutputScheduler
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.session.InputRouter
import dev.jonalakas.bridgepad.session.InputSubscription
import dev.jonalakas.bridgepad.session.RoutedInputState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("MissingPermission")
class BluetoothHidService : Service() {
    private var adapter: BluetoothAdapter? = null
    @Volatile
    private var hidDevice: BluetoothHidDevice? = null
    @Volatile
    private var connectedDevice: BluetoothDevice? = null
    private val profileRegistry = BluetoothHidProfileRegistry(listOf(GenericCompositeHidProfile))
    @Volatile
    private var activeProfile: BluetoothHidProfile = GenericCompositeHidProfile
    private var activeDestination = DestinationType.PC
    private val outputTransport = BluetoothHidOutputTransport(
        hidDevice = { hidDevice },
        connectedHost = { connectedDevice },
        resolveHost = { address -> adapter?.getRemoteDevice(address) },
        profile = { activeProfile },
    )
    private var requestedHostAddress: String? = null
    private var lastHostAddress: String? = null
    @Volatile
    private var shuttingDown = false
    private val handler = Handler(Looper.getMainLooper())
    private val outputThread = HandlerThread("BridgePad-HID-Output").apply { start() }
    private val outputHandler = Handler(outputThread.looper)
    private val outputRunning = AtomicBoolean(false)
    private val transportLock = Any()
    private val outputScheduler = OutputScheduler(OUTPUT_RATE_HZ)
    private lateinit var inputRouter: InputRouter
    private var inputSubscription: InputSubscription? = null
    private var discoverabilityTimeout: Runnable? = null
    private var pendingConnection: Runnable? = null
    private var lastCaptureState: CaptureState? = null
    private val lastObservedInputCount = AtomicLong(0L)
    private val inputEventsSinceConnection = AtomicLong(0L)
    private val pendingInputTimestampNanos = AtomicLong(NO_INPUT_TIMESTAMP)
    private var metricsStartedNanos = 0L
    private var reportsSinceConnection = 0L
    private var lastMetricsUpdateNanos = 0L
    private var latestLatencyMs: Float? = null
    private var expectedOutputTickNanos = 0L
    private var maxOutputDelayNanos = 0L
    private val outputTick = object : Runnable {
        override fun run() {
            if (shuttingDown || !outputRunning.get()) return
            recordOutputDelay()
            sendScheduledReport()
            sendMouseReport()
            if (!shuttingDown && outputRunning.get()) {
                outputHandler.postDelayed(this, OUTPUT_INTERVAL_MS)
            }
        }
    }

    private data class CaptureState(
        val active: Boolean,
        val message: LocalizedMessage?,
        val error: Boolean,
    )

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                        SessionLog.record("BLUETOOTH", "Bluetooth was turned off")
                        finishSession(HidSessionStatus.IDLE, LocalizedMessage(R.string.hid_bluetooth_off))
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
                                        LocalizedMessage(R.string.hid_paired_connecting)
                                    } else {
                                        LocalizedMessage(R.string.hid_paired_select)
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
            finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_profile_disconnected))
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
                update(HidSessionStatus.READY, LocalizedMessage(R.string.hid_ready))
            } else {
                connectedDevice = null
                finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_registration_lost))
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (shuttingDown) return
            val pairingConnection =
                requestedHostAddress == null &&
                    HidSessionStore.state.value.pairingModeActive &&
                    state in setOf(BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED) &&
                    device.bondState != BluetoothDevice.BOND_NONE
            if (pairingConnection) {
                requestedHostAddress = device.address
                lastHostAddress = device.address
                cancelDiscoverabilityMessage()
                refreshPairedHosts()
                HidSessionStore.update { it.copy(pairingModeActive = false) }
                SessionLog.record("CONNECTION", "Incoming HID host authorized during explicit pairing")
            }
            val wasRequested = device.address == requestedHostAddress
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> if (wasRequested) {
                    update(
                        HidSessionStatus.CONNECTING,
                        LocalizedMessage(R.string.hid_connecting, device.safeName()),
                    )
                }
                BluetoothProfile.STATE_CONNECTED -> {
                    if (wasRequested) {
                        acceptConnectedDevice(device)
                    } else {
                        hidDevice?.disconnect(device)
                        update(
                            HidSessionStatus.READY,
                            LocalizedMessage(R.string.hid_choose_pc),
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = connectedDevice?.address == device.address
                    if (!wasRequested && !wasConnected) return
                    if (wasConnected) connectionLost(device) else connectionFailed(device)
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            handleHostRequest(device, type, id, activeProfile.onGetReport(type, id, bufferSize))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            handleHostRequest(device, type, id, activeProfile.onSetReport(type, id, data))
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            handleHostRequest(device, null, null, activeProfile.onSetProtocol(protocol))
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            activeProfile.onInterruptData(reportId, data)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            activeProfile.onVirtualCableUnplug()
        }
    }

    private fun handleHostRequest(
        device: BluetoothDevice,
        type: Byte?,
        id: Byte?,
        result: HidHostRequestResult,
    ) {
        when (result) {
            HidHostRequestResult.Ignored,
            HidHostRequestResult.Accepted -> Unit
            is HidHostRequestResult.Rejected -> hidDevice?.reportError(device, result.errorCode)
            is HidHostRequestResult.Reply -> {
                if (type != null && id != null) hidDevice?.replyReport(device, type, id, result.payload)
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
                message = LocalizedMessage(R.string.hid_starting),
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
        inputRouter = (application as BridgePadApplication).inputRouter
        observeInputRouter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requestedAdapterId = intent.getStringExtra(EXTRA_OUTPUT_ADAPTER_ID)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::OutputAdapterId)
                    ?: OutputAdapterIds.GENERIC_BLUETOOTH_HID
                val requestedDestination = intent.getStringExtra(EXTRA_DESTINATION_TYPE)
                    ?.let { runCatching { DestinationType.valueOf(it) }.getOrNull() }
                    ?: DestinationType.PC
                val requestedProfile = profileRegistry.find(requestedAdapterId)
                if (requestedProfile == null ||
                    !requestedProfile.adapter.supports(requestedDestination, ConnectionMethod.BLUETOOTH)
                ) {
                    finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_registration_rejected))
                    return START_NOT_STICKY
                }
                activeProfile = requestedProfile
                activeDestination = requestedDestination
                val touchSelected = intent.getBooleanExtra(EXTRA_TOUCH_INPUT_SELECTED, true)
                val captureMode = intent.getStringExtra(EXTRA_PHYSICAL_CAPTURE_MODE)
                    ?.let { runCatching { PhysicalCaptureMode.valueOf(it) }.getOrNull() }
                    ?: PhysicalCaptureMode.COMPATIBILITY
                HidSessionStore.update {
                    it.copy(
                        touchInputSelected = touchSelected,
                        physicalCaptureMode = captureMode,
                        directUsbActive = inputRouter.current.directUsbActive,
                        destinationType = activeDestination,
                        connectionMethod = ConnectionMethod.BLUETOOTH,
                        outputAdapterId = activeProfile.adapter.id,
                    )
                }
                inputRouter.select(
                    if (touchSelected) InputMode.TOUCHSCREEN else InputMode.PHYSICAL_GAMEPAD,
                    captureMode,
                )
                updateNotification()
                startHid()
            }
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDRESS))
            ACTION_RECONNECT -> reconnect()
            ACTION_ENABLE_BACKGROUND_USB -> {
                HidSessionStore.update { it.copy(physicalCaptureMode = PhysicalCaptureMode.BACKGROUND_USB) }
                inputRouter.select(InputMode.PHYSICAL_GAMEPAD, PhysicalCaptureMode.BACKGROUND_USB)
                outputScheduler.submit(inputRouter.current.gamepad)
            }
            ACTION_ENABLE_COMPATIBILITY_INPUT -> {
                inputRouter.select(InputMode.PHYSICAL_GAMEPAD, PhysicalCaptureMode.COMPATIBILITY)
                outputScheduler.submit(inputRouter.current.gamepad)
                HidSessionStore.update {
                    it.copy(
                        physicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY,
                        directUsbActive = false,
                        message = LocalizedMessage(R.string.compatibility_input_active),
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!shuttingDown) updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val stateWasFinalized = shuttingDown
        shuttingDown = true
        cancelDiscoverabilityMessage()
        cancelPendingConnection()
        stopOutputPipeline()
        inputSubscription?.cancel()
        inputSubscription = null
        outputThread.quitSafely()
        releaseProfile()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        if (!stateWasFinalized) {
            HidSessionStore.update { HidSessionState(message = LocalizedMessage(R.string.hid_ended)) }
        }
        super.onDestroy()
    }

    private fun startHid() {
        SessionLog.record("SESSION", "Bluetooth HID session start requested")
        val currentAdapter = adapter
        if (currentAdapter == null) {
            finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_no_adapter))
            return
        }
        if (!currentAdapter.isEnabled) {
            finishSession(HidSessionStatus.IDLE, LocalizedMessage(R.string.hid_bluetooth_off))
            return
        }
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.STARTING,
                bluetoothEnabled = true,
                message = LocalizedMessage(R.string.hid_requesting_profile),
            )
        }
        val requested = currentAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        if (!requested) finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_no_profile))
    }

    private fun selectInput(touchSelected: Boolean) {
        inputRouter.clearPointer()
        if (connectedDevice != null) {
            synchronized(transportLock) { outputTransport.sendGamepad(VirtualGamepadState()) }
        }
        HidSessionStore.update {
            it.copy(
                touchInputSelected = touchSelected,
                message = if (touchSelected) {
                    LocalizedMessage(R.string.hid_touch_selected)
                } else {
                    LocalizedMessage(R.string.hid_physical_selected)
                },
                feedbackLevel = HidFeedbackLevel.INFO,
            )
        }
        inputRouter.select(
            if (touchSelected) InputMode.TOUCHSCREEN else InputMode.PHYSICAL_GAMEPAD,
            HidSessionStore.state.value.physicalCaptureMode,
        )
        updateNotification()
        outputScheduler.submit(inputRouter.current.gamepad)
        SessionLog.record("INPUT", if (touchSelected) "Touchscreen input selected" else "Physical gamepad input selected")
    }

    private fun registerHidApp() {
        update(HidSessionStatus.REGISTERING, LocalizedMessage(R.string.hid_registering))
        val settings = BluetoothHidDeviceAppSdpSettings(
            activeProfile.serviceName,
            activeProfile.description,
            activeProfile.provider,
            activeProfile.subclass,
            activeProfile.reportDescriptor,
        )
        val accepted = hidDevice?.registerApp(settings, null, null, mainExecutor, callback) == true
        if (!accepted) {
            finishSession(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_registration_rejected))
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
                        LocalizedMessage(R.string.hid_already_connected)
                    } else {
                        LocalizedMessage(R.string.connection_busy)
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
                    LocalizedMessage(R.string.hid_connecting, device.safeName()),
                )
                return
            }
        }
        update(
            HidSessionStatus.CONNECTING,
            LocalizedMessage(R.string.hid_connecting, device.safeName()),
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
                    message = LocalizedMessage(R.string.hid_no_previous),
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
                LocalizedMessage(R.string.hid_connecting, device.safeName()),
            )
            else -> {
                update(HidSessionStatus.CONNECTING, LocalizedMessage(R.string.hid_connecting, device.safeName()))
                if (!outputTransport.connect(activeDestination, device.address)) {
                    connectionFailed(device)
                }
            }
        }
    }

    private fun connectionFailed(device: BluetoothDevice) {
        cancelPendingConnection()
        requestedHostAddress = null
        connectedDevice = null
        SessionLog.record("CONNECTION", "HID connection attempt did not reach CONNECTED")
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.READY,
                connectedHost = null,
                connectedHostAddress = null,
                canReconnect = lastHostAddress != null,
                message = LocalizedMessage(R.string.hid_connection_failed, device.safeName()),
                feedbackLevel = HidFeedbackLevel.WARNING,
            )
        }
    }

    private fun connectionLost(device: BluetoothDevice) {
        cancelPendingConnection()
        stopOutputPipeline()
        requestedHostAddress = null
        connectedDevice = null
        SessionLog.record("CONNECTION", "Established HID connection was lost")
        HidSessionStore.update {
            it.copy(
                status = HidSessionStatus.READY,
                connectedHost = null,
                connectedHostAddress = null,
                canReconnect = lastHostAddress != null,
                message = LocalizedMessage(R.string.hid_connection_lost, device.safeName()),
                feedbackLevel = HidFeedbackLevel.WARNING,
            )
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
                connectedHostAddress = device.address,
                canReconnect = false,
                message = LocalizedMessage(R.string.hid_connected),
                feedbackLevel = HidFeedbackLevel.INFO,
            )
        }
        syncCaptureState(inputRouter.current, force = true)
        startOutputPipeline()
    }

    private fun observeInputRouter() {
        val initial = inputRouter.current
        lastObservedInputCount.set(initial.inputEventCount)
        outputScheduler.submit(initial.gamepad)
        inputSubscription = inputRouter.observe { state ->
            outputScheduler.submit(state.gamepad)
            val previousCount = lastObservedInputCount.getAndSet(state.inputEventCount)
            val newEvents = (state.inputEventCount - previousCount).coerceAtLeast(0L)
            if (newEvents > 0L && connectedDevice != null) {
                inputEventsSinceConnection.addAndGet(newEvents)
                state.lastInputTimestampNanos?.let(pendingInputTimestampNanos::set)
            }
            syncCaptureState(state)
        }
    }

    private fun syncCaptureState(state: RoutedInputState, force: Boolean = false) {
        val capture = CaptureState(state.directUsbActive, state.captureMessage, state.captureError)
        val previous = lastCaptureState
        if (!force && capture == previous) return
        lastCaptureState = capture
        HidSessionStore.update { session ->
            val showCaptureMessage = capture.error ||
                (session.status == HidSessionStatus.CONNECTED &&
                    session.physicalCaptureMode == PhysicalCaptureMode.BACKGROUND_USB)
            session.copy(
                directUsbActive = capture.active,
                message = if (showCaptureMessage) capture.message ?: session.message else session.message,
                feedbackLevel = if (capture.error) HidFeedbackLevel.WARNING else session.feedbackLevel,
            )
        }
        if (previous?.active != capture.active && !shuttingDown) {
            handler.post {
                if (!shuttingDown) updateNotification()
            }
        }
    }

    private fun startOutputPipeline() {
        outputRunning.set(false)
        outputHandler.removeCallbacks(outputTick)
        outputScheduler.stop()
        outputScheduler.submit(inputRouter.current.gamepad)
        metricsStartedNanos = monotonicNanos()
        lastMetricsUpdateNanos = metricsStartedNanos
        lastObservedInputCount.set(inputRouter.current.inputEventCount)
        inputEventsSinceConnection.set(0L)
        reportsSinceConnection = 0
        pendingInputTimestampNanos.set(NO_INPUT_TIMESTAMP)
        latestLatencyMs = null
        expectedOutputTickNanos = metricsStartedNanos
        maxOutputDelayNanos = 0L
        outputRunning.set(true)
        outputHandler.post(outputTick)
    }

    private fun sendScheduledReport() {
        if (connectedDevice == null) return
        val now = monotonicNanos()
        val state = outputScheduler.poll(now) ?: return
        val sent = synchronized(transportLock) { outputTransport.sendGamepad(state) }
        if (!sent) {
            update(HidSessionStatus.ERROR, LocalizedMessage(R.string.hid_report_failed))
            return
        }
        reportsSinceConnection++
        val timestamp = pendingInputTimestampNanos.getAndSet(NO_INPUT_TIMESTAMP)
        if (timestamp != NO_INPUT_TIMESTAMP) {
            latestLatencyMs = (now - timestamp).coerceAtLeast(0L) / 1_000_000f
        }
        if (now - lastMetricsUpdateNanos >= METRICS_UPDATE_INTERVAL_NANOS) {
            val elapsedSeconds = ((now - metricsStartedNanos).coerceAtLeast(1L)) / 1_000_000_000f
            HidSessionStore.update {
                it.copy(
                    inputRateHz = inputEventsSinceConnection.get() / elapsedSeconds,
                    outputRateHz = reportsSinceConnection / elapsedSeconds,
                    lastLatencyMs = latestLatencyMs ?: it.lastLatencyMs,
                    maxOutputDelayMs = maxOutputDelayNanos / 1_000_000f,
                )
            }
            lastMetricsUpdateNanos = now
            maxOutputDelayNanos = 0L
        }
    }

    private fun sendMouseReport() {
        if (connectedDevice == null) return
        val report = inputRouter.consumePointer() ?: return
        val sent = synchronized(transportLock) { outputTransport.sendPointer(report) }
        if (!sent) {
            SessionLog.record("MOUSE", "A mouse report could not be sent")
        }
    }

    private fun stopOutputPipeline() {
        outputRunning.set(false)
        outputHandler.removeCallbacks(outputTick)
        outputScheduler.stop()
        pendingInputTimestampNanos.set(NO_INPUT_TIMESTAMP)
        inputRouter.clearPointer()
    }

    private fun recordOutputDelay() {
        val now = monotonicNanos()
        if (expectedOutputTickNanos > 0L) {
            maxOutputDelayNanos = maxOf(maxOutputDelayNanos, (now - expectedOutputTickNanos).coerceAtLeast(0L))
        }
        expectedOutputTickNanos = now + OUTPUT_INTERVAL_NANOS
    }

    private fun monotonicNanos(): Long = SystemClock.uptimeMillis() * 1_000_000L

    private fun stopHid() {
        if (shuttingDown) return
        shuttingDown = true
        cancelPendingConnection()
        stopOutputPipeline()
        if (connectedDevice != null) {
            synchronized(transportLock) {
                outputTransport.sendGamepad(VirtualGamepadState())
                outputTransport.disconnect()
            }
        }
        SessionLog.record("SESSION", "Session ended safely with a neutral report")
        releaseProfile()
        HidSessionStore.update { HidSessionState(message = LocalizedMessage(R.string.hid_stopped)) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishSession(status: HidSessionStatus, message: LocalizedMessage) {
        if (shuttingDown) return
        shuttingDown = true
        SessionLog.record(if (status == HidSessionStatus.ERROR) "ERROR" else "SESSION", message.resolve(this))
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
                message = LocalizedMessage(R.string.hid_visible, durationSeconds),
                feedbackLevel = HidFeedbackLevel.WARNING,
            )
        }
        val timeout = Runnable {
            HidSessionStore.update {
                it.copy(
                    pairingModeActive = false,
                    message = LocalizedMessage(R.string.hid_visibility_ended),
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
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun update(status: HidSessionStatus, message: LocalizedMessage) {
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
            getString(R.string.notification_channel),
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
        const val EXTRA_PHYSICAL_CAPTURE_MODE = "physical_capture_mode"
        const val EXTRA_OUTPUT_ADAPTER_ID = "output_adapter_id"
        const val EXTRA_DESTINATION_TYPE = "destination_type"

        private const val CHANNEL_ID = "bluetooth_hid_session"
        private const val NOTIFICATION_ID = 1001
        private const val AUTOMATIC_CONNECTION_GRACE_PERIOD_MS = 1_500L
        private const val POST_PAIR_CONNECTION_DELAY_MS = 500L
        private const val OUTPUT_RATE_HZ = 100
        private const val OUTPUT_INTERVAL_MS = 10L
        private const val OUTPUT_INTERVAL_NANOS = OUTPUT_INTERVAL_MS * 1_000_000L
        private const val METRICS_UPDATE_INTERVAL_NANOS = 500_000_000L
        private const val NO_INPUT_TIMESTAMP = -1L
        fun intent(context: Context, action: String) =
            Intent(context, BluetoothHidService::class.java).setAction(action)
    }
}
