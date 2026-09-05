package dev.jonalakas.bridgepad.input.usb

import dev.jonalakas.bridgepad.localization.LocalizedMessage
import dev.jonalakas.bridgepad.R
import dev.jonalakas.bridgepad.input.mapping.GamepadMappingStore
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import java.util.concurrent.atomic.AtomicBoolean

class DirectUsbGamepadController(
    private val context: Context,
    private val onStatus: (active: Boolean, message: LocalizedMessage, error: Boolean) -> Unit,
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val running = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var worker: Thread? = null
    private var pendingPermissionDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = usbDeviceFrom(intent) ?: pendingPermissionDevice
            if (!captureRequested.get()) {
                pendingPermissionDevice = null
                return
            }
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                pendingPermissionDevice = null
                open(device)
            } else {
                mainHandler.postDelayed({ finishPermissionRequest(device) }, PERMISSION_CONFIRMATION_DELAY_MS)
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun start() {
        captureRequested.set(true)
        if (running.get() || pendingPermissionDevice != null) return
        val candidate = manager.deviceList.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }
        }
        if (candidate == null) {
            onStatus(false, LocalizedMessage(R.string.usb_no_gamepad), true)
            return
        }
        if (manager.hasPermission(candidate)) open(candidate) else {
            pendingPermissionDevice = candidate
            onStatus(false, LocalizedMessage(R.string.usb_approve), false)
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.requestPermission(candidate, intent)
        }
    }

    private fun open(device: UsbDevice) {
        if (!captureRequested.get()) return
        closeConnection()
        val hidInterface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        val endpoint = hidInterface?.let { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        }
        if (hidInterface == null || endpoint == null) return fail(LocalizedMessage(R.string.usb_no_endpoint))
        val opened = manager.openDevice(device) ?: return fail(LocalizedMessage(R.string.usb_open_failed))
        if (!opened.claimInterface(hidInterface, true)) { opened.close(); return fail(LocalizedMessage(R.string.usb_claim_failed)) }
        val descriptor = ByteArray(1024)
        val descriptorSize = opened.controlTransfer(0x81, 0x06, 0x2200, hidInterface.id, descriptor, descriptor.size, 1500)
        if (descriptorSize <= 0) { opened.releaseInterface(hidInterface); opened.close(); return fail(LocalizedMessage(R.string.usb_no_descriptor)) }
        val parser = runCatching { UsbHidReportParser(descriptor.copyOf(descriptorSize)) }.getOrElse {
            opened.releaseInterface(hidInterface); opened.close(); return fail(LocalizedMessage(R.string.usb_unsupported))
        }
        connection = opened
        claimedInterface = hidInterface
        running.set(true)
        val deviceKey = "${device.vendorId}:${device.productId}:${descriptor.copyOf(descriptorSize).contentHashCode()}"
        DirectUsbGamepadStore.set(
            DirectUsbState(
                active = true,
                deviceName = device.productName ?: context.getString(R.string.usb_gamepad),
                deviceKey = deviceKey,
                statusMessage = LocalizedMessage(R.string.background_usb_active),
            ),
        )
        onStatus(true, LocalizedMessage(R.string.background_usb_active), false)
        SessionLog.record("USB", "Direct USB HID capture started")
        worker = Thread(
            { readLoop(opened, endpoint, parser, device.deviceId, deviceKey, device.productName ?: context.getString(R.string.usb_gamepad)) },
            "BridgePad-USB-HID",
        ).also { it.start() }
    }

    private fun readLoop(connection: UsbDeviceConnection, endpoint: UsbEndpoint, parser: UsbHidReportParser, deviceId: Int, deviceKey: String, name: String) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var count = 0L
        var previous = VirtualGamepadState()
        while (running.get()) {
            val length = connection.bulkTransfer(endpoint, buffer, buffer.size, 1000)
            if (length < 0 && manager.deviceList.values.none { it.deviceId == deviceId }) {
                fail(LocalizedMessage(R.string.usb_disconnected))
                return
            }
            if (length > 0) runCatching { parser.decode(buffer.copyOf(length)) }.onSuccess { rawGamepad ->
                val gamepad = GamepadMappingStore.load(deviceKey).apply(rawGamepad)
                if (rawGamepad != previous) {
                    previous = rawGamepad
                    count++
                    DirectUsbGamepadStore.set(
                        DirectUsbState(
                            active = true,
                            deviceName = name,
                            deviceKey = deviceKey,
                            rawGamepad = rawGamepad,
                            gamepad = gamepad,
                            inputEventCount = count,
                            lastInputTimestampNanos = SystemClock.uptimeMillis() * 1_000_000L,
                            statusMessage = LocalizedMessage(R.string.background_usb_active),
                        ),
                    )
                }
            }.onFailure { fail(LocalizedMessage(R.string.usb_parse_failed)) }
        }
    }

    fun stop() {
        captureRequested.set(false)
        pendingPermissionDevice = null
        closeConnection()
    }

    private fun closeConnection() {
        running.set(false)
        worker?.interrupt(); worker = null
        claimedInterface?.let { connection?.releaseInterface(it) }
        connection?.close(); connection = null; claimedInterface = null
        DirectUsbGamepadStore.clear()
    }

    fun unregister() {
        mainHandler.removeCallbacksAndMessages(null)
        pendingPermissionDevice = null
        stop()
        runCatching { context.unregisterReceiver(permissionReceiver) }
    }

    private fun fail(message: LocalizedMessage) { stop(); SessionLog.record("USB_ERROR", message.resolve(context)); onStatus(false, message, true) }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun finishPermissionRequest(device: UsbDevice?) {
        if (!captureRequested.get()) {
            pendingPermissionDevice = null
        } else if (device != null && manager.hasPermission(device)) {
            pendingPermissionDevice = null
            open(device)
        } else {
            pendingPermissionDevice = null
            onStatus(false, LocalizedMessage(R.string.usb_denied), true)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jonalakas.bridgepad.USB_PERMISSION"
        private const val PERMISSION_CONFIRMATION_DELAY_MS = 250L
    }
}
