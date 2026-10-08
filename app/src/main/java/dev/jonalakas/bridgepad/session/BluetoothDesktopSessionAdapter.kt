package dev.jonalakas.bridgepad.session

import android.content.Context
import dev.jonalakas.bridgepad.core.ports.OutputSessionAdapter
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterDescriptor
import dev.jonalakas.bridgepad.core.session.OutputAdapterIds
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.TargetSelectionMode

/** Session boundary for Bluetooth RFCOMM through BridgePad Desktop. */
class BluetoothDesktopSessionAdapter(
    context: Context,
    private val controller: BluetoothDesktopGameplayController,
) : OutputSessionAdapter {
    private val applicationContext = context.applicationContext
    override val descriptor = OutputAdapterDescriptor(
        id = OutputAdapterIds.DESKTOP_BLUETOOTH,
        connectionMethod = ConnectionMethod.BLUETOOTH,
        supportedDestinations = setOf(DestinationType.PC),
        targetSelectionMode = TargetSelectionMode.PAIRED_OR_NEW,
    )

    private var physicalCaptureMode = PhysicalCaptureMode.COMPATIBILITY

    override fun start(
        destination: DestinationType,
        physicalCaptureMode: PhysicalCaptureMode,
    ) {
        require(destination in descriptor.supportedDestinations)
        this.physicalCaptureMode = physicalCaptureMode
    }

    override fun connect(destinationId: String) {
        controller.start(destinationId, physicalCaptureMode)
        GameplaySessionService.startBluetoothDesktop(
            applicationContext,
            physicalCaptureMode,
            destinationId,
        )
    }

    override fun stop() {
        controller.stop()
        GameplaySessionService.stop(applicationContext)
    }

    override fun updatePhysicalCapture(physicalCaptureMode: PhysicalCaptureMode) {
        this.physicalCaptureMode = physicalCaptureMode
        controller.updatePhysicalCapture(physicalCaptureMode)
    }

    override fun pairingWindowStarted(durationSeconds: Int) = Unit
}
