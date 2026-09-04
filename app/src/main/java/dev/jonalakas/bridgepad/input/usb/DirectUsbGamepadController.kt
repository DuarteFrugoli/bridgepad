package dev.jonalakas.bridgepad.input.usb

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
import dev.jonalakas.bridgepad.diagnostics.SessionLog
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import java.util.concurrent.atomic.AtomicBoolean

class DirectUsbGamepadController(
    private val context: Context,
    private val onStatus: (active: Boolean, message: String, error: Boolean) -> Unit,
) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val running = AtomicBoolean(false)
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var worker: Thread? = null
    private var pendingPermissionDevice: UsbDevice? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init { UsbGamepadMappingStore.initialize(context) }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = usbDeviceFrom(intent) ?: pendingPermissionDevice
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
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(permissionReceiver, filter)
    }

    fun start() {
        if (running.get()) return
        val candidate = manager.deviceList.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }
        }
        if (candidate == null) {
            onStatus(false, "No USB HID gamepad was found. Connect one or use Compatibility mode.", true)
            return
        }
        if (manager.hasPermission(candidate)) open(candidate) else {
            pendingPermissionDevice = candidate
            onStatus(false, "Approve USB access to enable background input.", false)
            val intent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.requestPermission(candidate, intent)
        }
    }

    private fun open(device: UsbDevice) {
        stop()
        val hidInterface = (0 until device.interfaceCount).map(device::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        val endpoint = hidInterface?.let { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        }
        if (hidInterface == null || endpoint == null) return fail("The USB gamepad has no readable HID input endpoint.")
        val opened = manager.openDevice(device) ?: return fail("Android could not open the USB gamepad.")
        if (!opened.claimInterface(hidInterface, true)) { opened.close(); return fail("Android could not claim the USB gamepad interface.") }
        val descriptor = ByteArray(1024)
        val descriptorSize = opened.controlTransfer(0x81, 0x06, 0x2200, hidInterface.id, descriptor, descriptor.size, 1500)
        if (descriptorSize <= 0) { opened.releaseInterface(hidInterface); opened.close(); return fail("The gamepad did not provide a readable HID descriptor.") }
        val parser = runCatching { UsbHidReportParser(descriptor.copyOf(descriptorSize)) }.getOrElse {
            opened.releaseInterface(hidInterface); opened.close(); return fail(it.message ?: "Unsupported HID descriptor.")
        }
        connection = opened
        claimedInterface = hidInterface
        running.set(true)
        val deviceKey = "${device.vendorId}:${device.productId}:${descriptor.copyOf(descriptorSize).contentHashCode()}"
        DirectUsbGamepadStore.set(
            DirectUsbState(
                active = true,
                deviceName = device.productName ?: "USB gamepad",
                deviceKey = deviceKey,
            ),
        )
        onStatus(true, "Background USB input is active. You can turn off the screen or leave BridgePad.", false)
        SessionLog.record("USB", "Direct USB HID capture started")
        worker = Thread(
            { readLoop(opened, endpoint, parser, device.deviceId, deviceKey, device.productName ?: "USB gamepad") },
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
                fail("USB gamepad disconnected. All controls were released.")
                return
            }
            if (length > 0) runCatching { parser.decode(buffer.copyOf(length)) }.onSuccess { rawGamepad ->
                val gamepad = UsbGamepadMappingStore.load(deviceKey).apply(rawGamepad)
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
                        ),
                    )
                }
            }.onFailure { fail("USB report parsing failed; returning to Compatibility mode.") }
        }
    }

    fun stop() {
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

    private fun fail(message: String) { stop(); SessionLog.record("USB_ERROR", message); onStatus(false, message, true) }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun finishPermissionRequest(device: UsbDevice?) {
        if (device != null && manager.hasPermission(device)) {
            pendingPermissionDevice = null
            open(device)
        } else {
            pendingPermissionDevice = null
            onStatus(false, "USB permission was denied. Compatibility mode is still available.", true)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "dev.jonalakas.bridgepad.USB_PERMISSION"
        private const val PERMISSION_CONFIRMATION_DELAY_MS = 250L
    }
}
