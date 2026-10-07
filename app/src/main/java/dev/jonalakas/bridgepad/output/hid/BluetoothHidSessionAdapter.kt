package dev.jonalakas.bridgepad.output.hid

import android.content.Context
import androidx.core.content.ContextCompat
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.ports.OutputSessionAdapter
import dev.jonalakas.bridgepad.session.OutputSessionOwner

/** Starts the Android service that hosts one Bluetooth HID device profile. */
class BluetoothHidSessionAdapter(
    context: Context,
    private val profile: BluetoothHidProfile,
    private val outputSessionOwner: OutputSessionOwner,
) : OutputSessionAdapter {
    private val applicationContext = context.applicationContext
    private var ownership: OutputSessionOwner.Lease? = null

    override val descriptor
        get() = profile.adapter

    override fun start(
        destination: DestinationType,
        physicalCaptureMode: PhysicalCaptureMode,
    ) {
        require(destination in descriptor.supportedDestinations) {
            "${descriptor.id.value} does not support $destination."
        }
        ownership = outputSessionOwner.claim(OutputSessionOwner.Kind.BLUETOOTH_HID) {
            stopForOwnershipChange()
        }
        ContextCompat.startForegroundService(
            applicationContext,
            command(BluetoothHidService.ACTION_START)
                .putExtra(BluetoothHidService.EXTRA_OUTPUT_ADAPTER_ID, descriptor.id.value)
                .putExtra(BluetoothHidService.EXTRA_DESTINATION_TYPE, destination.name)
                .putExtra(
                    BluetoothHidService.EXTRA_PHYSICAL_CAPTURE_MODE,
                    physicalCaptureMode.name,
                ),
        )
    }

    override fun connect(destinationId: String) {
        applicationContext.startService(
            command(BluetoothHidService.ACTION_CONNECT)
                .putExtra(BluetoothHidService.EXTRA_ADDRESS, destinationId),
        )
    }

    override fun stop() {
        val activeOwnership = ownership
        stopForOwnershipChange()
        activeOwnership?.release()
    }

    override fun updatePhysicalCapture(physicalCaptureMode: PhysicalCaptureMode) {
        applicationContext.startService(
            command(
                if (physicalCaptureMode == PhysicalCaptureMode.BACKGROUND_USB) {
                    BluetoothHidService.ACTION_ENABLE_BACKGROUND_USB
                } else {
                    BluetoothHidService.ACTION_ENABLE_COMPATIBILITY_INPUT
                },
            ),
        )
    }

    override fun pairingWindowStarted(durationSeconds: Int) {
        applicationContext.startService(
            command(BluetoothHidService.ACTION_DISCOVERABILITY_STARTED)
                .putExtra(BluetoothHidService.EXTRA_DISCOVERABLE_DURATION, durationSeconds),
        )
    }

    private fun command(action: String) = BluetoothHidService.intent(applicationContext, action)

    private fun stopForOwnershipChange() {
        applicationContext.startService(command(BluetoothHidService.ACTION_STOP))
        ownership = null
    }
}
