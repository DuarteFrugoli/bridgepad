package dev.jonalakas.bridgepad

import android.app.Application
import dev.jonalakas.bridgepad.input.usb.DirectUsbCaptureManager
import dev.jonalakas.bridgepad.ui.gamepad.layout.TouchscreenLayoutStore
import dev.jonalakas.bridgepad.session.InputRouter
import dev.jonalakas.bridgepad.session.SessionCoordinator
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

    override fun onCreate() {
        super.onCreate()
        DirectUsbCaptureManager.initialize(this)
        TouchscreenLayoutStore.initialize(this)
        inputRouter = InputRouter(applicationScope)
        sessionCoordinator = SessionCoordinator(
            context = this,
            adapters = listOf(BluetoothHidSessionAdapter(this, GenericCompositeHidProfile)),
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
