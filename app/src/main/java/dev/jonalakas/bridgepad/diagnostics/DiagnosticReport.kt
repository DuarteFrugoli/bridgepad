package dev.jonalakas.bridgepad.diagnostics

import dev.jonalakas.bridgepad.input.android.PhysicalGamepadState
import dev.jonalakas.bridgepad.session.SessionState as HidSessionState

object DiagnosticReport {
    fun create(
        appVersion: String,
        deviceInfo: DeviceInfo,
        hidState: HidSessionState,
        physicalGamepadState: PhysicalGamepadState,
    ): String = buildString {
        appendLine("BridgePad diagnostic report")
        appendLine("App version: $appVersion")
        appendLine("Device: ${deviceInfo.displayModel}")
        appendLine("Android: ${deviceInfo.androidVersion} (API ${deviceInfo.sdkLevel})")
        appendLine("Bluetooth enabled: ${hidState.bluetoothEnabled}")
        appendLine("HID status: ${hidState.status}")
        appendLine("HID session active: ${hidState.sessionActive}")
        appendLine("Paired computer count: ${hidState.pairedHosts.size}")
        appendLine("Physical gamepad count: ${physicalGamepadState.devices.size}")
        physicalGamepadState.devices.forEachIndexed { index, device ->
            appendLine("Gamepad ${index + 1}: ${device.name}")
            appendLine("Gamepad ${index + 1} vendor/product: ${device.vendorId}/${device.productId}")
            appendLine("Gamepad ${index + 1} axes: ${device.axes.joinToString()}")
        }
        appendLine("Input rate: ${hidState.inputRateHz} Hz")
        appendLine("Output rate: ${hidState.outputRateHz} Hz")
        appendLine("Last latency: ${hidState.lastLatencyMs ?: "not measured"} ms")
        appendLine()
        appendLine("Recent session log (Bluetooth addresses are never included):")
        SessionLog.snapshot().forEach(::appendLine)
    }
}
