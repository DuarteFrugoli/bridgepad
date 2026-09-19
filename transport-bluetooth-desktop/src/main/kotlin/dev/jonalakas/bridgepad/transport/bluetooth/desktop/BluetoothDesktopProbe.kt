package dev.jonalakas.bridgepad.transport.bluetooth.desktop

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.ceil

const val BLUETOOTH_DESKTOP_DEFAULT_SAMPLE_COUNT = 1_000
const val BLUETOOTH_DESKTOP_DEFAULT_RATE_HZ = 125

enum class BluetoothDesktopTransport {
    RFCOMM,
    BLE_GATT,
}

data class BluetoothDesktopProbeRequest(
    val deviceAddress: String,
    val transport: BluetoothDesktopTransport,
    val sampleCount: Int = BLUETOOTH_DESKTOP_DEFAULT_SAMPLE_COUNT,
    val targetRateHz: Int = BLUETOOTH_DESKTOP_DEFAULT_RATE_HZ,
) {
    init {
        require(sampleCount in 10..10_000)
        require(targetRateHz in 1..500)
    }
}

data class BluetoothDesktopProbeResult(
    val transport: BluetoothDesktopTransport,
    val connectMillis: Double,
    val samplesAttempted: Int,
    val samplesAccepted: Int,
    val samplesReceived: Int,
    val rejectedWrites: Int,
    val lossPercent: Double,
    val reportRateHz: Double,
    val maximumReceiveGapMillis: Double,
    val rttP50Millis: Double,
    val rttP95Millis: Double,
    val rttP99Millis: Double,
)

@SuppressLint("MissingPermission")
class BluetoothDesktopProbe(context: Context) {
    private val applicationContext = context.applicationContext

    fun run(request: BluetoothDesktopProbeRequest): BluetoothDesktopProbeResult {
        val adapter = applicationContext
            .getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter
            ?: error("Bluetooth is not available on this device")
        check(adapter.isEnabled) { "Bluetooth is turned off" }
        val device = adapter.getRemoteDevice(request.deviceAddress)
        check(device.bondState == BluetoothDevice.BOND_BONDED) {
            "The selected computer is not paired with this phone"
        }
        return when (request.transport) {
            BluetoothDesktopTransport.RFCOMM -> runRfcomm(device, request)
            BluetoothDesktopTransport.BLE_GATT -> runBle(device, request)
        }
    }

    private fun runRfcomm(
        device: BluetoothDevice,
        request: BluetoothDesktopProbeRequest,
    ): BluetoothDesktopProbeResult {
        val first = connectRfcomm(device)
        val collector = ProbeResponseCollector()
        val reader = thread(name = "BridgePad-RFCOMM-probe-reader", isDaemon = true) {
            runCatching {
                val input = DataInputStream(first.socket.inputStream)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = ByteArray(PACKET_SIZE)
                    input.readFully(packet)
                    collector.receive(packet)
                }
            }.onFailure(collector::connectionEnded)
        }
        val output = first.socket.outputStream
        val benchmark = benchmark(
            request = request,
            collector = collector,
            send = { packet -> runCatching { output.write(packet) }.isSuccess },
            flush = { output.flush() },
        )
        first.socket.close()
        reader.interrupt()
        reader.join(READER_SHUTDOWN_GRACE_MILLIS)
        return benchmark.toResult(
            transport = request.transport,
            connectMillis = first.connectMillis,
        )
    }

    private fun connectRfcomm(device: BluetoothDevice): RfcommLink {
        val socket = device.createRfcommSocketToServiceRecord(RFCOMM_SERVICE_UUID)
        val started = System.nanoTime()
        try {
            socket.connect()
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        return RfcommLink(socket, elapsedMillis(started))
    }

    private fun runBle(
        @Suppress("UNUSED_PARAMETER") device: BluetoothDevice,
        request: BluetoothDesktopProbeRequest,
    ): BluetoothDesktopProbeResult {
        val collector = ProbeResponseCollector()
        val first = BlePeripheralLink.open(applicationContext, collector)
        val benchmark = benchmark(
            request = request,
            collector = collector,
            send = first::write,
            flush = {},
        )
        first.close()
        return benchmark.toResult(
            transport = request.transport,
            connectMillis = first.connectMillis,
        )
    }

    private fun benchmark(
        request: BluetoothDesktopProbeRequest,
        collector: ProbeResponseCollector,
        send: (ByteArray) -> Boolean,
        flush: () -> Unit,
    ): BenchmarkSummary {
        val intervalNanos = 1_000_000_000L / request.targetRateHz
        val started = System.nanoTime()
        var next = started
        var accepted = 0
        var rejected = 0
        repeat(request.sampleCount) { sequence ->
            val now = System.nanoTime()
            if (now < next) LockSupport.parkNanos(next - now)
            val packet = encodePacket(PacketType.REPORT, sequence)
            if (send(packet)) {
                accepted += 1
            } else {
                rejected += 1
            }
            if ((sequence + 1) % request.targetRateHz == 0) {
                val pingSentAt = System.nanoTime()
                collector.pingSent(sequence, pingSentAt)
                if (!send(encodePacket(PacketType.PING, sequence, pingSentAt))) {
                    collector.discardPing(sequence)
                }
                flush()
            }
            next += intervalNanos
        }
        check(send(encodePacket(PacketType.SUMMARY_REQUEST, accepted))) {
            "Bluetooth disconnected before the benchmark summary was requested"
        }
        flush()
        val desktopSummary = collector.awaitSummary(RESPONSE_GRACE_MILLIS)
        return collector.summarize(
            attempted = request.sampleCount,
            accepted = accepted,
            rejected = rejected,
            desktopSummary = desktopSummary,
        )
    }

    private data class RfcommLink(
        val socket: BluetoothSocket,
        val connectMillis: Double,
    )

    private class BlePeripheralLink private constructor(
        private val server: BluetoothGattServer,
        private val advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        private val advertiseCallback: AdvertiseCallback,
        private val connectedDevice: AtomicReference<BluetoothDevice?>,
        private val transmit: BluetoothGattCharacteristic,
        val connectMillis: Double,
    ) {
        fun write(packet: ByteArray): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            val device = connectedDevice.get() ?: return false
            server.notifyCharacteristicChanged(
                device,
                transmit,
                false,
                packet,
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            val device = connectedDevice.get() ?: return false
            @Suppress("DEPRECATION")
            transmit.setValue(packet) && server.notifyCharacteristicChanged(device, transmit, false)
        }

        fun close() {
            advertiser.stopAdvertising(advertiseCallback)
            connectedDevice.get()?.let(server::cancelConnection)
            server.close()
        }

        companion object {
            fun open(context: Context, collector: ProbeResponseCollector?): BlePeripheralLink {
                val manager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?: error("Bluetooth manager is unavailable")
                val advertiser = manager.adapter.bluetoothLeAdvertiser
                    ?: error("This phone cannot advertise a Bluetooth LE service")
                val serviceReady = CountDownLatch(1)
                val advertisingReady = CountDownLatch(1)
                val ready = CountDownLatch(1)
                val failure = AtomicReference<String?>()
                val connectedDevice = AtomicReference<BluetoothDevice?>()
                val serverReference = AtomicReference<BluetoothGattServer?>()
                val transmit = BluetoothGattCharacteristic(
                    BLE_TRANSMIT_UUID,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ).apply {
                    addDescriptor(
                        BluetoothGattDescriptor(
                            CLIENT_CONFIGURATION_UUID,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                        ),
                    )
                }
                val receive = BluetoothGattCharacteristic(
                    BLE_RECEIVE_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
                val callback = object : BluetoothGattServerCallback() {
                    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                        if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                            failure.set("Android could not publish the BLE service (status $status)")
                        }
                        serviceReady.countDown()
                    }

                    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                        if (status != android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                            failure.compareAndSet(null, "BLE connection failed with status $status")
                            ready.countDown()
                        } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                            connectedDevice.set(device)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            connectedDevice.compareAndSet(device, null)
                        }
                    }

                    override fun onDescriptorWriteRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        descriptor: BluetoothGattDescriptor,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray,
                    ) {
                        if (descriptor.uuid == CLIENT_CONFIGURATION_UUID && !preparedWrite && offset == 0 &&
                            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        ) {
                            connectedDevice.set(device)
                            ready.countDown()
                        }
                        if (responseNeeded) {
                            serverReference.get()?.sendResponse(
                                device,
                                requestId,
                                android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value,
                            )
                        }
                    }

                    override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        characteristic: BluetoothGattCharacteristic,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray,
                    ) {
                        if (characteristic.uuid == BLE_RECEIVE_UUID && offset == 0) {
                            collector?.receive(value)
                        }
                        if (responseNeeded) {
                            serverReference.get()?.sendResponse(
                                device,
                                requestId,
                                android.bluetooth.BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value,
                            )
                        }
                    }
                }
                serverReference.set(null)
                val server = manager.openGattServer(context, callback)
                    ?: error("Android could not open a BLE GATT server")
                serverReference.set(server)
                val service = BluetoothGattService(
                    BLE_SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY,
                ).apply {
                    addCharacteristic(transmit)
                    addCharacteristic(receive)
                }
                if (!server.addService(service) || !serviceReady.await(3, TimeUnit.SECONDS)) {
                    server.close()
                    error("Android could not add the BridgePad BLE service")
                }
                failure.get()?.let { message ->
                    server.close()
                    error(message)
                }
                val advertiseCallback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        advertisingReady.countDown()
                    }

                    override fun onStartFailure(errorCode: Int) {
                        failure.set("BLE advertising failed with code $errorCode")
                        advertisingReady.countDown()
                    }
                }
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setConnectable(true)
                    .setTimeout(0)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .build()
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
                    .build()
                val started = System.nanoTime()
                advertiser.startAdvertising(settings, data, advertiseCallback)
                if (!advertisingReady.await(3, TimeUnit.SECONDS)) {
                    server.close()
                    error("Timed out while starting BLE advertising")
                }
                failure.get()?.let { message ->
                    advertiser.stopAdvertising(advertiseCallback)
                    server.close()
                    error(message)
                }
                if (!ready.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    advertiser.stopAdvertising(advertiseCallback)
                    server.close()
                    error("Timed out waiting for BridgePad Desktop to subscribe to BLE")
                }
                failure.get()?.let { message ->
                    advertiser.stopAdvertising(advertiseCallback)
                    server.close()
                    error(message)
                }
                return BlePeripheralLink(
                    server = server,
                    advertiser = advertiser,
                    advertiseCallback = advertiseCallback,
                    connectedDevice = connectedDevice,
                    transmit = transmit,
                    connectMillis = elapsedMillis(started),
                )
            }
        }
    }

    private class ProbeResponseCollector {
        private val pendingPings = ConcurrentHashMap<Int, Long>()
        private val latencies = java.util.Collections.synchronizedList(
            ArrayList<Long>(BLUETOOTH_DESKTOP_DEFAULT_SAMPLE_COUNT / BLUETOOTH_DESKTOP_DEFAULT_RATE_HZ + 1),
        )
        private val summary = AtomicReference<DesktopSummary?>()
        private val summaryReady = CountDownLatch(1)
        private val connectionFailure = AtomicReference<Throwable?>()

        fun pingSent(sequence: Int, sentAtNanos: Long) {
            pendingPings[sequence] = sentAtNanos
        }

        fun discardPing(sequence: Int) {
            pendingPings.remove(sequence)
        }

        fun receive(packet: ByteArray) {
            when (packetType(packet)) {
                PacketType.PONG -> {
                    val sequence = decodePrimaryValue(packet)
                    val sentAtNanos = pendingPings.remove(sequence) ?: return
                    latencies += System.nanoTime() - sentAtNanos
                }
                PacketType.SUMMARY_RESPONSE -> {
                    summary.set(decodeSummary(packet))
                    summaryReady.countDown()
                }
                else -> Unit
            }
        }

        fun connectionEnded(error: Throwable) {
            connectionFailure.compareAndSet(null, error)
        }

        fun awaitSummary(graceMillis: Long): DesktopSummary {
            if (!summaryReady.await(graceMillis, TimeUnit.MILLISECONDS)) {
                connectionFailure.get()?.let { throw IllegalStateException("Bluetooth connection ended", it) }
                error("Timed out waiting for BridgePad Desktop benchmark statistics")
            }
            return checkNotNull(summary.get())
        }

        fun summarize(
            attempted: Int,
            accepted: Int,
            rejected: Int,
            desktopSummary: DesktopSummary,
        ): BenchmarkSummary {
            val latencyCopy = synchronized(latencies) { latencies.sorted() }
            val received = desktopSummary.received.coerceAtMost(accepted)
            val durationSeconds = desktopSummary.durationNanos / 1_000_000_000.0
            return BenchmarkSummary(
                attempted = attempted,
                accepted = accepted,
                received = received,
                rejected = rejected,
                lossPercent = if (accepted == 0) 100.0 else (accepted - received) * 100.0 / accepted,
                reportRateHz = if (durationSeconds > 0.0) {
                    (received - 1).coerceAtLeast(0) / durationSeconds
                } else {
                    0.0
                },
                maximumGapMillis = nanosToMillis(desktopSummary.maximumGapNanos),
                p50Millis = percentileMillis(latencyCopy, 50),
                p95Millis = percentileMillis(latencyCopy, 95),
                p99Millis = percentileMillis(latencyCopy, 99),
            )
        }
    }

    private data class DesktopSummary(
        val received: Int,
        val durationNanos: Long,
        val maximumGapNanos: Long,
    )

    private data class BenchmarkSummary(
        val attempted: Int,
        val accepted: Int,
        val received: Int,
        val rejected: Int,
        val lossPercent: Double,
        val reportRateHz: Double,
        val maximumGapMillis: Double,
        val p50Millis: Double,
        val p95Millis: Double,
        val p99Millis: Double,
    ) {
        fun toResult(
            transport: BluetoothDesktopTransport,
            connectMillis: Double,
        ) = BluetoothDesktopProbeResult(
            transport = transport,
            connectMillis = connectMillis,
            samplesAttempted = attempted,
            samplesAccepted = accepted,
            samplesReceived = received,
            rejectedWrites = rejected,
            lossPercent = lossPercent,
            reportRateHz = reportRateHz,
            maximumReceiveGapMillis = maximumGapMillis,
            rttP50Millis = p50Millis,
            rttP95Millis = p95Millis,
            rttP99Millis = p99Millis,
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 12L
        private const val RESPONSE_GRACE_MILLIS = 10_000L
        private const val READER_SHUTDOWN_GRACE_MILLIS = 1_000L
        private const val PACKET_SIZE = 20
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())
        private val RFCOMM_SERVICE_UUID: UUID = UUID.fromString("7a1b8d5f-6c24-4e71-9f52-a4b8d9c30101")
        private val BLE_SERVICE_UUID: UUID = UUID.fromString("7a1b8d5f-6c24-4e71-9f52-a4b8d9c30201")
        private val BLE_TRANSMIT_UUID: UUID = UUID.fromString("7a1b8d5f-6c24-4e71-9f52-a4b8d9c30202")
        private val BLE_RECEIVE_UUID: UUID = UUID.fromString("7a1b8d5f-6c24-4e71-9f52-a4b8d9c30203")
        private val CLIENT_CONFIGURATION_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun encodePacket(
            type: PacketType,
            primaryValue: Int,
            timestampNanos: Long = 0L,
        ): ByteArray =
            ByteBuffer.allocate(PACKET_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .put(MAGIC)
                .put(1)
                .put(type.wireValue)
                .putShort(0)
                .putInt(primaryValue)
                .putLong(timestampNanos)
                .array()

        private fun packetType(packet: ByteArray): PacketType? {
            if (packet.size != PACKET_SIZE ||
                !packet.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
                packet[4] != 1.toByte()
            ) return null
            return PacketType.entries.firstOrNull { it.wireValue == packet[5] }
        }

        private fun decodePrimaryValue(packet: ByteArray): Int =
            ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getInt(8)

        private fun decodeSummary(packet: ByteArray): DesktopSummary {
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            val received = buffer.getInt(8)
            val durationMicros = Integer.toUnsignedLong(buffer.getInt(12))
            val maximumGapMicros = Integer.toUnsignedLong(buffer.getInt(16))
            return DesktopSummary(
                received = received.coerceAtLeast(0),
                durationNanos = durationMicros * 1_000L,
                maximumGapNanos = maximumGapMicros * 1_000L,
            )
        }

        private fun percentileMillis(sorted: List<Long>, percentile: Int): Double {
            if (sorted.isEmpty()) return 0.0
            val index = (ceil(sorted.size * percentile / 100.0).toInt() - 1).coerceIn(sorted.indices)
            return nanosToMillis(sorted[index])
        }

        private fun nanosToMillis(value: Long): Double = value / 1_000_000.0
        private fun elapsedMillis(startedAt: Long): Double = nanosToMillis(System.nanoTime() - startedAt)
    }

    private enum class PacketType(val wireValue: Byte) {
        REPORT(1),
        PING(2),
        PONG(3),
        SUMMARY_REQUEST(4),
        SUMMARY_RESPONSE(5),
    }
}
