package dev.jonalakas.bridgepad.output.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.GamepadOutputTransport
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.ports.TransportCapabilities
import dev.jonalakas.bridgepad.core.session.OutputTransportType

/** Bluetooth HID adapter for the generic logical-output contract. */
@SuppressLint("MissingPermission")
class BluetoothHidOutputTransport(
    private val hidDevice: () -> BluetoothHidDevice?,
    private val connectedHost: () -> BluetoothDevice?,
    private val resolveHost: (String) -> BluetoothDevice?,
) : GamepadOutputTransport {
    override val type = OutputTransportType.BLUETOOTH_HID
    override val capabilities = TransportCapabilities(
        gamepad = true,
        pointer = true,
        worksInBackground = true,
    )

    override fun connect(destinationId: String): Boolean {
        val device = resolveHost(destinationId) ?: return false
        return hidDevice()?.connect(device) == true
    }

    override fun sendGamepad(state: VirtualGamepadState): Boolean {
        val device = connectedHost() ?: return false
        return hidDevice()?.sendReport(
            device,
            GamepadHidDescriptor.REPORT_ID,
            HidReportEncoder.encode(state),
        ) == true
    }

    override fun sendPointer(report: PointerReport): Boolean {
        val device = connectedHost() ?: return false
        return hidDevice()?.sendReport(
            device,
            GamepadHidDescriptor.MOUSE_REPORT_ID,
            GamepadHidDescriptor.mouseReport(report.buttons, report.deltaX, report.deltaY),
        ) == true
    }

    override fun disconnect() {
        val device = connectedHost() ?: return
        hidDevice()?.disconnect(device)
    }
}
