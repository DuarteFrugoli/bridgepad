package dev.jonalakas.bridgepad.diagnostics

import android.os.Build

object AndroidDeviceInfoProvider {
    fun get(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orEmpty().trim(),
        model = Build.MODEL.orEmpty().trim(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
        sdkLevel = Build.VERSION.SDK_INT,
    )
}
