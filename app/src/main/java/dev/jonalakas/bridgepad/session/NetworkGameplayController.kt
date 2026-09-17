package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.transport.network.NetworkGamepadClient
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.transport.network.NetworkFailureReason
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NetworkGameplayController(
    private val inputRouter: InputRouter,
    private val scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow<NetworkGamepadStatus>(NetworkGamepadStatus.Stopped)
    val status: StateFlow<NetworkGamepadStatus> = mutableStatus.asStateFlow()
    private var client: NetworkGamepadClient? = null
    private var inputSubscription: InputSubscription? = null
    private var pointerJob: Job? = null
    private var generation = 0L

    @Synchronized
    fun start(
        request: NetworkGamepadRequest,
        physicalCaptureMode: PhysicalCaptureMode,
    ) {
        stopCurrent(immediate = true)
        val currentGeneration = ++generation
        val nextClient = NetworkGamepadClient(request) { update ->
            handleClientStatus(currentGeneration, update)
        }
        client = nextClient
        inputSubscription = inputRouter.observe(physicalCaptureMode) { routed ->
            nextClient.send(routed.gamepad)
        }
        pointerJob = scope.launch {
            while (isActive) {
                inputRouter.peekPointer()?.let { pointer ->
                    inputRouter.acknowledgePointer(nextClient.sendPointer(pointer))
                }
                inputRouter.peekKeyboard()?.let { keyboard ->
                    inputRouter.acknowledgeKeyboard(nextClient.sendKeyboard(keyboard))
                }
                delay(POINTER_POLL_MILLIS)
            }
        }
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

    @Synchronized
    fun reportFailure(reason: NetworkFailureReason, detail: String) {
        stopCurrent(immediate = true)
        mutableStatus.value = NetworkGamepadStatus.Failed(reason, detail)
    }

    private fun stopCurrent(immediate: Boolean) {
        releaseInputPipeline()
        client?.let { active ->
            if (immediate) active.closeImmediately() else active.stop()
        }
        client = null
        mutableStatus.value = NetworkGamepadStatus.Stopped
    }

    @Synchronized
    private fun handleClientStatus(currentGeneration: Long, update: NetworkGamepadStatus) {
        if (generation != currentGeneration) return
        mutableStatus.value = update
        if (update is NetworkGamepadStatus.Failed || update is NetworkGamepadStatus.Stopped) {
            releaseInputPipeline()
            client = null
        }
    }

    private fun releaseInputPipeline() {
        inputSubscription?.cancel()
        inputSubscription = null
        pointerJob?.cancel()
        pointerJob = null
        inputRouter.clearPointer()
        inputRouter.clearKeyboard()
    }

    private companion object {
        const val POINTER_POLL_MILLIS = 10L
    }
}
