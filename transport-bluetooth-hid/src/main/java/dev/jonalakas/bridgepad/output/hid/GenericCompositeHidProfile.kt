package dev.jonalakas.bridgepad.output.hid

import android.bluetooth.BluetoothHidDevice
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.core.session.DestinationType
import dev.jonalakas.bridgepad.core.session.OutputAdapterDescriptor
import dev.jonalakas.bridgepad.core.session.OutputAdapterIds
import dev.jonalakas.bridgepad.core.session.TargetSelectionMode

/** Generic gamepad and relative-mouse profile used by Windows/Linux hosts. */
object GenericCompositeHidProfile : BluetoothHidProfile {
    override val adapter = OutputAdapterDescriptor(
        id = OutputAdapterIds.GENERIC_BLUETOOTH_HID,
        connectionMethod = ConnectionMethod.BLUETOOTH,
        supportedDestinations = setOf(DestinationType.PC),
        targetSelectionMode = TargetSelectionMode.PAIRED_OR_NEW,
    )
    override val serviceName = "BridgePad"
    override val description = "BridgePad Bluetooth HID gamepad and mouse bridge"
    override val provider = "BridgePad"
    override val subclass = (
        BluetoothHidDevice.SUBCLASS1_MOUSE.toInt() or
            BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()
        ).toByte()
    override val reportDescriptor: ByteArray
        get() = GamepadHidDescriptor.bytes

    override fun encodeGamepad(state: VirtualGamepadState) =
        HidReport(GamepadHidDescriptor.REPORT_ID, HidReportEncoder.encode(state))

    override fun encodePointer(report: PointerReport) = HidReport(
        GamepadHidDescriptor.MOUSE_REPORT_ID,
        GamepadHidDescriptor.mouseReport(report.buttons, report.deltaX, report.deltaY),
    )
}
