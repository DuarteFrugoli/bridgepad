package dev.jonalakas.bridgepad.diagnostics

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkLevel: Int,
) {
    val displayModel: String
        get() = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Unknown device" }
}
