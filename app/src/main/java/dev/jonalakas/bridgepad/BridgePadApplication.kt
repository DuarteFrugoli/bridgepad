package dev.jonalakas.bridgepad

import android.app.Application
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager
import dev.jonalakas.bridgepad.input.touch.TouchpadSettingsStore
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayoutStore
import dev.jonalakas.bridgepad.ui.session.SessionOrientationStore
import dev.jonalakas.bridgepad.session.InputRouter
import dev.jonalakas.bridgepad.session.SessionCoordinator
import dev.jonalakas.bridgepad.session.NetworkGameplayController
import dev.jonalakas.bridgepad.session.NetworkDesktopCoordinator
import dev.jonalakas.bridgepad.session.BluetoothDesktopGameplayController
import dev.jonalakas.bridgepad.session.BluetoothDesktopSessionAdapter
import dev.jonalakas.bridgepad.output.hid.BluetoothHidSessionAdapter
import dev.jonalakas.bridgepad.output.hid.GenericCompositeHidProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Process-level composition root shared by every Android transport adapter. */
class BridgePadApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var inputRouter: InputRouter
        private set
    lateinit var sessionCoordinator: SessionCoordinator
        private set
    lateinit var networkGameplayController: NetworkGameplayController
        private set
    lateinit var networkDesktopCoordinator: NetworkDesktopCoordinator
        private set
    lateinit var bluetoothDesktopGameplayController: BluetoothDesktopGameplayController
        private set

    override fun onCreate() {
        super.onCreate()
        DirectUsbCaptureManager.initialize(this)
        TouchpadSettingsStore.initialize(this)
        TouchscreenLayoutStore.initialize(this)
        SessionOrientationStore.initialize(this)
        inputRouter = InputRouter(applicationScope)
        bluetoothDesktopGameplayController = BluetoothDesktopGameplayController(this, inputRouter)
        networkGameplayController = NetworkGameplayController(inputRouter, applicationScope)
        networkDesktopCoordinator = NetworkDesktopCoordinator(
            context = this,
            gameplay = networkGameplayController,
            scope = applicationScope,
        )
        sessionCoordinator = SessionCoordinator(
            context = this,
            adapters = listOf(
                BluetoothDesktopSessionAdapter(bluetoothDesktopGameplayController),
                BluetoothHidSessionAdapter(this, GenericCompositeHidProfile),
            ),
        )
    }

    override fun onTerminate() {
        bluetoothDesktopGameplayController.shutdown()
        networkDesktopCoordinator.shutdown()
        applicationScope.cancel()
        super.onTerminate()
    }
}
