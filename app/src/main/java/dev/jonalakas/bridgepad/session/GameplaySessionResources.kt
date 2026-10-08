package dev.jonalakas.bridgepad.session

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager

/**
 * Owns the Android resources that make one gameplay session reliable off-screen.
 *
 * Output transports keep their protocol-specific lifecycle, but they all use
 * this same resource contract instead of independently owning USB capture,
 * CPU wakefulness and Wi-Fi performance locks.
 */
class GameplaySessionResources(
    context: Context,
    private val wakeLockTag: String,
) {
    private val applicationContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var directUsbRequested = false

    val isDirectUsbRequested: Boolean
        @Synchronized get() = directUsbRequested

    @Synchronized
    fun start(
        captureMode: PhysicalCaptureMode,
        keepWifiAwake: Boolean,
    ) {
        acquireWakeLock()
        configureWifiLock(keepWifiAwake)
        updateCaptureMode(captureMode)
    }

    @Synchronized
    fun updateCaptureMode(captureMode: PhysicalCaptureMode) {
        val useDirectUsb = captureMode == PhysicalCaptureMode.BACKGROUND_USB
        if (useDirectUsb) {
            DirectUsbCaptureManager.acquire(applicationContext, wakeLockTag)
        } else if (directUsbRequested) {
            DirectUsbCaptureManager.release(wakeLockTag)
        }
        directUsbRequested = useDirectUsb
    }

    @Synchronized
    fun close() {
        if (directUsbRequested) DirectUsbCaptureManager.release(wakeLockTag)
        directUsbRequested = false
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = applicationContext.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    @Suppress("DEPRECATION")
    private fun configureWifiLock(enabled: Boolean) {
        if (!enabled) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            return
        }
        if (wifiLock?.isHeld == true) return
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, wakeLockTag)
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }
}
