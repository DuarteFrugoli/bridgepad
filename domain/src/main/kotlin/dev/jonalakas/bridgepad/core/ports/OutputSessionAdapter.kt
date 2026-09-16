package dev.jonalakas.bridgepad.core.ports

import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterDescriptor
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode

/** Android lifecycle boundary for one selectable output implementation. */
interface OutputSessionAdapter {
    val descriptor: OutputAdapterDescriptor

    fun start(
        destination: DestinationType,
        physicalCaptureMode: PhysicalCaptureMode,
    )

    fun connect(destinationId: String)
    fun stop()
    fun updatePhysicalCapture(physicalCaptureMode: PhysicalCaptureMode)
    fun pairingWindowStarted(durationSeconds: Int)
}
