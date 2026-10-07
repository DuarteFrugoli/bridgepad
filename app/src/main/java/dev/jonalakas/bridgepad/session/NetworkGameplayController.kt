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
    private val outputSessionOwner: OutputSessionOwner,
) {
    private val mutableStatus = MutableStateFlow<NetworkGamepadStatus>(NetworkGamepadStatus.Stopped)
    val status: StateFlow<NetworkGamepadStatus> = mutableStatus.asStateFlow()
    private var client: NetworkGamepadClient? = null
    private var inputSubscription: InputSubscription? = null
    private var pointerJob: Job? = null
    private var generation = 0L
    private var ownership: OutputSessionOwner.Lease? = null

    fun start(
        request: NetworkGamepadRequest,
        physicalCaptureMode: PhysicalCaptureMode,
    ) {
        stopCurrent(immediate = true)
        val nextOwnership = outputSessionOwner.claim(OutputSessionOwner.Kind.NETWORK_DESKTOP) {
            stopForOwnershipChange()
        }
        val currentGeneration = synchronized(this) { ++generation }
        val nextClient = NetworkGamepadClient(request) { update ->
            handleClientStatus(currentGeneration, update)
        }
        synchronized(this) {
            ownership = nextOwnership
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
        }
        nextClient.start()
    }

    fun stop() {
        stopCurrent(immediate = false)
    }

    @Synchronized
    fun updateEndpoints(hosts: List<String>) {
        client?.updateEndpoints(hosts)
    }

    fun shutdown() {
        stopCurrent(immediate = true)
    }

    fun reportFailure(reason: NetworkFailureReason, detail: String) {
        stopCurrent(immediate = true)
        mutableStatus.value = NetworkGamepadStatus.Failed(reason, detail)
    }

    private fun stopCurrent(immediate: Boolean) {
        val activeClient = synchronized(this) {
            generation++
            releaseInputPipeline()
            val active = client
            client = null
            ownership?.release()
            ownership = null
            mutableStatus.value = NetworkGamepadStatus.Stopped
            active
        }
        activeClient?.stopAndAwait(immediate)
    }

    @Synchronized
    private fun handleClientStatus(currentGeneration: Long, update: NetworkGamepadStatus) {
        if (generation != currentGeneration) return
        mutableStatus.value = update
        if (update is NetworkGamepadStatus.Failed || update is NetworkGamepadStatus.Stopped) {
            releaseInputPipeline()
            client = null
            ownership?.release()
            ownership = null
        }
    }

    private fun stopForOwnershipChange() {
        stopCurrent(immediate = true)
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
