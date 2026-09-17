package dev.jonalakas.bridgepad.output.hid

import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.session.OutputAdapterDescriptor
import dev.jonalakas.bridgepad.core.session.OutputAdapterId

/**
 * A device personality registered with Android's Bluetooth HID host API.
 *
 * PC HID implementations use this contract without coupling their descriptors
 * or host callbacks to the input pipeline.
 */
interface BluetoothHidProfile {
    val adapter: OutputAdapterDescriptor
    val serviceName: String
    val description: String
    val provider: String
    val subclass: Byte
    val reportDescriptor: ByteArray

    fun encodeGamepad(state: VirtualGamepadState): HidReport
    fun encodePointer(report: PointerReport): HidReport?
    fun encodeKeyboard(input: KeyboardInput): List<HidReport>

    fun onGetReport(type: Byte, id: Byte, bufferSize: Int): HidHostRequestResult =
        HidHostRequestResult.Ignored

    fun onSetReport(type: Byte, id: Byte, data: ByteArray): HidHostRequestResult =
        HidHostRequestResult.Ignored

    fun onSetProtocol(protocol: Byte): HidHostRequestResult = HidHostRequestResult.Ignored
    fun onInterruptData(reportId: Byte, data: ByteArray) = Unit
    fun onVirtualCableUnplug() = Unit
}

data class HidReport(val id: Int, val payload: ByteArray)

sealed interface HidHostRequestResult {
    data object Ignored : HidHostRequestResult
    data object Accepted : HidHostRequestResult
    data class Reply(val payload: ByteArray) : HidHostRequestResult
    data class Rejected(val errorCode: Byte) : HidHostRequestResult
}

class BluetoothHidProfileRegistry(profiles: Collection<BluetoothHidProfile>) {
    private val byId = profiles.associateBy { it.adapter.id }

    init {
        require(byId.size == profiles.size) { "Bluetooth HID profile ids must be unique." }
    }

    fun find(id: OutputAdapterId): BluetoothHidProfile? = byId[id]
    fun descriptors(): List<OutputAdapterDescriptor> = byId.values.map { it.adapter }
}
