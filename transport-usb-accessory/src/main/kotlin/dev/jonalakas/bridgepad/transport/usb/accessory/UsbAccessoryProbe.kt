package dev.jonalakas.bridgepad.transport.usb.accessory

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.protocol.BridgeMessage
import dev.jonalakas.bridgepad.protocol.BridgePacket
import dev.jonalakas.bridgepad.protocol.BridgePacketStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.ceil

const val USB_ACCESSORY_SAMPLE_COUNT = 250
const val USB_ACCESSORY_RATE_HZ = 125

data class UsbAccessoryProbeResult(
    val samples: Int,
    val lostSamples: Int,
    val reportRateHz: Double,
    val rttP50Millis: Double,
    val rttP95Millis: Double,
    val rttP99Millis: Double,
)

class UsbAccessoryProbe(context: Context) {
    private val applicationContext = context.applicationContext
    private val usbManager = applicationContext.getSystemService(UsbManager::class.java)
        ?: error("USB manager is unavailable")

    fun connectedAccessory(): UsbAccessory? = usbManager.accessoryList?.singleOrNull()

    fun run(): UsbAccessoryProbeResult {
        val accessory = connectedAccessory()
            ?: error("BridgePad Desktop has not placed this phone in USB accessory mode")
        ensurePermission(accessory)
        val descriptor = usbManager.openAccessory(accessory)
            ?: error("Android could not open the USB accessory")
        val input = DataInputStream(FileInputStream(descriptor.fileDescriptor))
        val output = FileOutputStream(descriptor.fileDescriptor)
        val outstanding = ConcurrentHashMap<Long, Long>()
        val latencies = Collections.synchronizedList(ArrayList<Long>(USB_ACCESSORY_SAMPLE_COUNT))
        val reader = thread(name = "BridgePad-USB-probe-reader", isDaemon = true) {
            runCatching {
                while (!Thread.currentThread().isInterrupted) {
                    val packet = BridgePacketStream.read(input)
                    val pong = packet.message as? BridgeMessage.Pong ?: continue
                    outstanding.remove(pong.nonce)?.let { sentAt ->
                        latencies += System.nanoTime() - sentAt
                    }
                }
            }
        }
        val started = System.nanoTime()
        var nextSend = started
        try {
            repeat(USB_ACCESSORY_SAMPLE_COUNT) { sequence ->
                val waitNanos = nextSend - System.nanoTime()
                if (waitNanos > 0) TimeUnit.NANOSECONDS.sleep(waitNanos)
                val nonce = System.nanoTime()
                outstanding[nonce] = nonce
                BridgePacketStream.write(
                    output,
                    BridgePacket(
                        sessionId = USB_PROBE_SESSION_ID,
                        sequence = sequence.toLong(),
                        timestampMicros = nonce / 1_000L,
                        message = BridgeMessage.Ping(nonce),
                    ),
                )
                nextSend += 1_000_000_000L / USB_ACCESSORY_RATE_HZ
            }
            val responseDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESPONSE_GRACE_MILLIS)
            while (latencies.size < USB_ACCESSORY_SAMPLE_COUNT && System.nanoTime() < responseDeadline) {
                Thread.sleep(5)
            }
        } finally {
            runCatching { descriptor.close() }
            reader.interrupt()
            reader.join(READER_SHUTDOWN_MILLIS)
        }
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        val sortedLatencies = synchronized(latencies) { latencies.sorted() }
        return UsbAccessoryProbeResult(
            samples = sortedLatencies.size,
            lostSamples = USB_ACCESSORY_SAMPLE_COUNT - sortedLatencies.size,
            reportRateHz = if (elapsedSeconds > 0) sortedLatencies.size / elapsedSeconds else 0.0,
            rttP50Millis = percentileMillis(sortedLatencies, 50),
            rttP95Millis = percentileMillis(sortedLatencies, 95),
            rttP99Millis = percentileMillis(sortedLatencies, 99),
        )
    }

    private fun ensurePermission(accessory: UsbAccessory) {
        if (usbManager.hasPermission(accessory)) return
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val permissionIntent = PendingIntent.getBroadcast(
                applicationContext,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(applicationContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(accessory, permissionIntent)
            check(latch.await(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "USB permission request timed out"
            }
            check(granted) { "USB accessory permission was denied" }
        } finally {
            runCatching { applicationContext.unregisterReceiver(receiver) }
        }
    }

    private fun percentileMillis(sorted: List<Long>, percentile: Int): Double {
        if (sorted.isEmpty()) return 0.0
        val index = (ceil(sorted.size * percentile / 100.0).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index] / 1_000_000.0
    }

    private companion object {
        const val ACTION_USB_PERMISSION =
            "dev.jonalakas.bridgepad.transport.usb.accessory.USB_PERMISSION"
        const val USB_PROBE_SESSION_ID = 0x5553L
        const val RESPONSE_GRACE_MILLIS = 2_000L
        const val PERMISSION_TIMEOUT_SECONDS = 30L
        const val READER_SHUTDOWN_MILLIS = 500L
    }
}
