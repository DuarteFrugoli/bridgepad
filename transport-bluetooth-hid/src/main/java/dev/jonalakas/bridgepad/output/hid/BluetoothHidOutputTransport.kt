package dev.jonalakas.bridgepad.output.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import dev.jonalakas.bridgepad.core.gamepad.VirtualGamepadState
import dev.jonalakas.bridgepad.core.ports.GamepadOutputTransport
import dev.jonalakas.bridgepad.core.ports.PointerReport
import dev.jonalakas.bridgepad.core.ports.KeyboardInput
import dev.jonalakas.bridgepad.core.ports.TransportCapabilities
import dev.jonalakas.bridgepad.core.session.DestinationType

/** Bluetooth HID adapter for the generic logical-output contract. */
@SuppressLint("MissingPermission")
class BluetoothHidOutputTransport(
    private val hidDevice: () -> BluetoothHidDevice?,
    private val connectedHost: () -> BluetoothDevice?,
    private val resolveHost: (String) -> BluetoothDevice?,
    private val profile: () -> BluetoothHidProfile,
) : GamepadOutputTransport {
    override val descriptor
        get() = profile().adapter
    override val capabilities = TransportCapabilities(
        gamepad = true,
        pointer = true,
        keyboard = true,
        worksInBackground = true,
    )

    override fun connect(destination: DestinationType, destinationId: String): Boolean {
        if (destination !in descriptor.supportedDestinations) return false
        val device = resolveHost(destinationId) ?: return false
        return hidDevice()?.connect(device) == true
    }

    override fun sendGamepad(state: VirtualGamepadState): Boolean {
        val device = connectedHost() ?: return false
        val report = profile().encodeGamepad(state)
        return hidDevice()?.sendReport(device, report.id, report.payload) == true
    }

    override fun sendPointer(report: PointerReport): Boolean {
        val device = connectedHost() ?: return false
        val encoded = profile().encodePointer(report) ?: return false
        return hidDevice()?.sendReport(device, encoded.id, encoded.payload) == true
    }

    override fun sendKeyboard(input: KeyboardInput): Boolean {
        val device = connectedHost() ?: return false
        val bluetoothHid = hidDevice() ?: return false
        return profile().encodeKeyboard(input).all { report ->
            bluetoothHid.sendReport(device, report.id, report.payload)
        }
    }

    override fun disconnect() {
        val device = connectedHost() ?: return
        hidDevice()?.disconnect(device)
    }
}
