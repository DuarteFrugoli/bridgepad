package dev.jonalakas.bridgepad.session

import android.content.Context
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.core.session.OutputAdapterCatalog
import dev.jonalakas.bridgepad.core.session.OutputAdapterId
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.ports.OutputSessionAdapter
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Application-level coordinator used by presentation code.
 *
 * UI chooses a destination, connection and input. This class resolves those
 * choices to an adapter; presentation never talks to a concrete service.
 */
class SessionCoordinator(
    context: Context,
    adapters: Collection<OutputSessionAdapter>,
) {
    private val applicationContext = context.applicationContext
    private val adaptersById = adapters.associateBy { it.descriptor.id }
    private var activeAdapterId: OutputAdapterId? = null

    val state: StateFlow<SessionState> = SessionStore.state
    val catalog = OutputAdapterCatalog(adapters.map(OutputSessionAdapter::descriptor))

    init {
        require(adaptersById.size == adapters.size) { "Output session adapter ids must be unique." }
    }

    fun start(
        adapterId: OutputAdapterId,
        destination: DestinationType,
        inputMode: InputMode,
        physicalCaptureMode: PhysicalCaptureMode?,
    ): Boolean {
        val adapter = adaptersById[adapterId] ?: return false
        if (destination !in adapter.descriptor.supportedDestinations) return false
        activeAdapterId = adapterId
        adapter.start(destination, inputMode, physicalCaptureMode)
        return true
    }

    fun connect(destinationId: String): Boolean {
        val adapter = activeAdapter() ?: return false
        adapter.connect(destinationId)
        return true
    }

    fun stop() {
        activeAdapter()?.stop()
        activeAdapterId = null
    }

    fun selectInput(inputMode: InputMode) {
        if (inputMode == InputMode.TOUCHSCREEN) preparePhysicalCapture(null)
        activeAdapter()?.updateInput(inputMode, state.value.physicalCaptureMode)
    }

    fun selectPhysicalCaptureMode(mode: PhysicalCaptureMode) {
        preparePhysicalCapture(mode)
        activeAdapter()?.updateInput(InputMode.PHYSICAL_GAMEPAD, mode)
    }

    fun pairingWindowStarted(durationSeconds: Int) {
        activeAdapter()?.pairingWindowStarted(durationSeconds)
    }

    fun preparePhysicalCapture(mode: PhysicalCaptureMode?) {
        if (mode == PhysicalCaptureMode.BACKGROUND_USB) {
            DirectUsbCaptureManager.start(applicationContext)
        } else {
            DirectUsbCaptureManager.stop()
        }
    }

    fun updateState(transform: (SessionState) -> SessionState) = SessionStore.update(transform)

    private fun activeAdapter(): OutputSessionAdapter? {
        val id = activeAdapterId ?: state.value.outputAdapterId
        return id?.let(adaptersById::get)
    }
}
