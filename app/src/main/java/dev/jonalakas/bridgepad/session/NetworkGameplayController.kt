package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.InputMode
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadClient
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkGameplayController(private val inputRouter: InputRouter) {
    private val mutableStatus = MutableStateFlow<NetworkGamepadStatus>(NetworkGamepadStatus.Stopped)
    val status: StateFlow<NetworkGamepadStatus> = mutableStatus.asStateFlow()
    private var client: NetworkGamepadClient? = null
    private var inputSubscription: InputSubscription? = null
    private var generation = 0L

    @Synchronized
    fun start(request: NetworkGamepadRequest) {
        stopCurrent(immediate = true)
        val currentGeneration = ++generation
        inputRouter.select(InputMode.TOUCHSCREEN, null)
        val nextClient = NetworkGamepadClient(request) { update ->
            synchronized(this) {
                if (generation == currentGeneration) mutableStatus.value = update
            }
        }
        client = nextClient
        inputSubscription = inputRouter.observe { routed -> nextClient.send(routed.gamepad) }
        nextClient.start()
    }

    @Synchronized
    fun stop() {
        stopCurrent(immediate = false)
    }

    @Synchronized
    fun shutdown() {
        stopCurrent(immediate = true)
    }

    private fun stopCurrent(immediate: Boolean) {
        inputSubscription?.cancel()
        inputSubscription = null
        client?.let { active ->
            if (immediate) active.closeImmediately() else active.stop()
        }
        client = null
        mutableStatus.value = NetworkGamepadStatus.Stopped
    }
}
