package dev.jonalakas.bridgepad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.jonalakas.bridgepad.diagnostics.AndroidDeviceInfoProvider
import dev.jonalakas.bridgepad.ui.home.HomeScreen
import dev.jonalakas.bridgepad.ui.theme.BridgePadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val deviceInfo = AndroidDeviceInfoProvider.get()

        setContent {
            BridgePadTheme {
                HomeScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    deviceInfo = deviceInfo,
                )
            }
        }
    }
}
