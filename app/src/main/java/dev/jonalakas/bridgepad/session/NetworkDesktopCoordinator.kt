package dev.jonalakas.bridgepad.session

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.core.session.ConnectionMethod
import dev.jonalakas.bridgepad.transport.network.NetworkAuthenticationException
import dev.jonalakas.bridgepad.transport.network.NetworkCredentials
import dev.jonalakas.bridgepad.transport.network.NetworkDesktopReachabilityProbe
import dev.jonalakas.bridgepad.transport.network.NetworkFailureReason
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkMediaRequest
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.transport.network.NetworkPairingClient
import dev.jonalakas.bridgepad.transport.network.NetworkPairingRequest
import dev.jonalakas.bridgepad.transport.network.PairingRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.cert.CertificateException

enum class NetworkPairingFailure {
    CODE_REJECTED,
    IDENTITY_CHANGED,
    DESKTOP_UNAVAILABLE,
    INVALID_CODE,
    UNKNOWN,
}

sealed interface NetworkPairingStatus {
    data object Idle : NetworkPairingStatus
    data class Pairing(val peerIdHex: String) : NetworkPairingStatus
    data class Success(val peerIdHex: String) : NetworkPairingStatus
    data class Failed(
        val peerIdHex: String,
        val reason: NetworkPairingFailure,
        val diagnostic: String,
    ) : NetworkPairingStatus
}

enum class DesktopAvailabilityStatus { CHECKING, ONLINE, OFFLINE }

data class DesktopAvailability(
    val status: DesktopAvailabilityStatus,
    val verifiedHost: String? = null,
)

class NetworkDesktopCoordinator(
    context: Context,
    private val gameplay: NetworkGameplayController,
    private val scope: CoroutineScope,
) {
    private val applicationContext = context.applicationContext
    private val discovery = NetworkDiscoveryManager(applicationContext)
    private val trustedStore = TrustedDesktopStore(applicationContext)
    private val applicationName = "${Build.MANUFACTURER} ${Build.MODEL}"
        .trim()
        .ifBlank { "Android" }
        .utf8Prefix(64)
    private val mutablePairingStatus = MutableStateFlow<NetworkPairingStatus>(NetworkPairingStatus.Idle)

    val discoveredDesktops: StateFlow<List<DiscoveredDesktop>> = discovery.desktops
    private val mutableAvailability = MutableStateFlow<Map<String, DesktopAvailability>>(emptyMap())
    val desktopAvailability: StateFlow<Map<String, DesktopAvailability>> =
        mutableAvailability.asStateFlow()
    val discoveryDiagnosticEvents: StateFlow<List<DiscoveryDiagnosticEvent>> =
        discovery.diagnosticEvents
    val trustedDesktops: StateFlow<List<TrustedDesktop>> = trustedStore.desktops
    val discoveryError: StateFlow<String?> = discovery.error
    val pairingStatus: StateFlow<NetworkPairingStatus> = mutablePairingStatus.asStateFlow()
    val gameplayStatus: StateFlow<NetworkGamepadStatus> = gameplay.status
    @Volatile
    private var activePeerIdHex: String? = null
    private var availabilityJob: Job? = null
    @Volatile private var availabilityGeneration = 0L
    private var availabilityMethod = ConnectionMethod.WIFI

    init {
        scope.launch {
            discovery.desktops.collectLatest { desktops ->
                val activePeer = activePeerIdHex ?: return@collectLatest
                val discovered = desktops.firstOrNull { it.peerIdHex == activePeer }
                    ?: return@collectLatest
                val trusted = trustedStore.desktops.value.firstOrNull {
                    it.peerIdHex == activePeer
                }
                trustedStore.updateEndpoint(discovered)
                gameplay.updateEndpoints(
                    (discovered.endpointHosts + trusted?.endpointHosts.orEmpty()).distinct(),
                )
            }
        }
    }

    @Synchronized
    fun startDiscovery() {
        discovery.start()
        if (availabilityJob?.isActive != true) restartAvailabilityMonitoring()
    }

    @Synchronized
    fun setDiscoveryRoute(connectionMethod: ConnectionMethod) {
        require(connectionMethod == ConnectionMethod.WIFI || connectionMethod == ConnectionMethod.USB)
        availabilityMethod = connectionMethod
        if (availabilityJob?.isActive == true) {
            restartAvailabilityMonitoring()
        } else {
            resetAvailabilityToChecking()
        }
    }

    @Synchronized
    fun stopDiscovery() {
        availabilityGeneration++
        availabilityJob?.cancel()
        availabilityJob = null
        discovery.recordAvailability("reachability monitoring stopped")
        discovery.stop()
    }

    private fun restartAvailabilityMonitoring() {
        availabilityGeneration++
        availabilityJob?.cancel()
        resetAvailabilityToChecking()
        val generation = availabilityGeneration
        val connectionMethod = availabilityMethod
        discovery.recordAvailability("reachability monitoring started via $connectionMethod")
        availabilityJob = scope.launch(Dispatchers.IO) {
            monitorAvailability(generation, connectionMethod)
        }
    }

    private fun resetAvailabilityToChecking() {
        mutableAvailability.value = availabilityTargets().associate {
            it.peerIdHex to DesktopAvailability(DesktopAvailabilityStatus.CHECKING)
        }
    }

    private suspend fun monitorAvailability(
        generation: Long,
        connectionMethod: ConnectionMethod,
    ) {
        // Give Compose one visible frame of CHECKING even on a very fast LAN.
        delay(500)
        while (kotlin.coroutines.coroutineContext.isActive && generation == availabilityGeneration) {
            val targets = availabilityTargets()
            kotlin.coroutines.coroutineContext.ensureActive()
            if (generation != availabilityGeneration) return
            val previousAvailability = mutableAvailability.value
            mutableAvailability.value = targets.associate { target ->
                target.peerIdHex to (previousAvailability[target.peerIdHex]
                    ?: DesktopAvailability(DesktopAvailabilityStatus.CHECKING))
            }
            val results = coroutineScope {
                targets.map { target ->
                    async(Dispatchers.IO) {
                        target to NetworkDesktopReachabilityProbe.firstReachableHost(
                            target.hosts,
                            target.port,
                            target.certificateSha256,
                            acceptLocalAddress = { localAddress ->
                                NetworkRouteClassifier.matches(connectionMethod, localAddress)
                            },
                        )
                    }
                }.awaitAll()
            }
            results.forEach { (target, host) ->
                kotlin.coroutines.coroutineContext.ensureActive()
                if (generation != availabilityGeneration) return
                val next = DesktopAvailability(
                    if (host == null) DesktopAvailabilityStatus.OFFLINE
                    else DesktopAvailabilityStatus.ONLINE,
                    host,
                )
                val previous = mutableAvailability.value[target.peerIdHex]
                if (previous != next) {
                    mutableAvailability.value = mutableAvailability.value + (target.peerIdHex to next)
                    discovery.recordAvailability(
                        "reachability ${target.name} ${next.status} via $connectionMethod " +
                            "host=${host ?: "none"}",
                    )
                }
            }
            delay(3_000)
        }
    }

    private fun availabilityTargets(): List<AvailabilityTarget> {
        val discovered = discovery.desktops.value.associateBy { it.peerIdHex }
        val trusted = trustedStore.desktops.value
        val trustedIds = trusted.mapTo(hashSetOf()) { it.peerIdHex }
        val trustedTargets = trusted.map { saved ->
            val current = discovered[saved.peerIdHex]
            AvailabilityTarget(
                saved.peerIdHex,
                saved.name,
                (current?.endpointHosts.orEmpty() + saved.endpointHosts).distinct(),
                current?.port ?: saved.port,
                saved.certificateSha256,
            )
        }
        return trustedTargets + discovered.values
            .filterNot { it.peerIdHex in trustedIds }
            .map { current ->
                AvailabilityTarget(
                    current.peerIdHex, current.name, current.endpointHosts,
                    current.port, current.certificateSha256,
                )
            }
    }

    private data class AvailabilityTarget(
        val peerIdHex: String,
        val name: String,
        val hosts: List<String>,
        val port: Int,
        val certificateSha256: String,
    )

    fun markDiscoveryDiagnostics() = discovery.markDiagnostics()

    fun clearDiscoveryDiagnostics() = discovery.clearDiagnostics()

    fun pair(peerIdHex: String, pairingCode: String) {
        val desktop = discovery.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (desktop == null) {
            mutablePairingStatus.value = NetworkPairingStatus.Failed(
                peerIdHex,
                NetworkPairingFailure.DESKTOP_UNAVAILABLE,
                "Desktop is no longer discoverable",
            )
            return
        }
        mutablePairingStatus.value = NetworkPairingStatus.Pairing(peerIdHex)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    pairUsingDiscoveredEndpoints(desktop, pairingCode)
                }
            }.onSuccess { success ->
                trustedStore.save(
                    desktop.preferring(success.host),
                    success.result.serverName,
                    success.result.sharedSecret,
                )
                mutablePairingStatus.value = NetworkPairingStatus.Success(peerIdHex)
            }.onFailure { error ->
                Log.e("BridgePadNetwork", "Network pairing failed for $peerIdHex", error)
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                mutablePairingStatus.value = NetworkPairingStatus.Failed(
                    peerIdHex = peerIdHex,
                    reason = when {
                        causes.any { it is IllegalArgumentException } ->
                            NetworkPairingFailure.INVALID_CODE
                        causes.any { it is PairingRejectedException } ->
                            NetworkPairingFailure.CODE_REJECTED
                        causes.any { it is NetworkAuthenticationException || it is CertificateException } ->
                            NetworkPairingFailure.IDENTITY_CHANGED
                        causes.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException } ->
                            NetworkPairingFailure.DESKTOP_UNAVAILABLE
                        else -> NetworkPairingFailure.UNKNOWN
                    },
                    diagnostic = error.cause?.message ?: error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    fun clearPairingStatus() {
        mutablePairingStatus.value = NetworkPairingStatus.Idle
    }

    fun forget(peerIdHex: String) {
        trustedStore.forget(peerIdHex)
        if ((pairingStatus.value as? NetworkPairingStatus.Success)?.peerIdHex == peerIdHex) {
            clearPairingStatus()
        }
    }

    fun prepareRepair(peerIdHex: String) {
        activePeerIdHex = null
        gameplay.stop()
        GameplaySessionService.stop(applicationContext)
        trustedStore.forget(peerIdHex)
        clearPairingStatus()
    }

    fun startGameplay(
        peerIdHex: String,
        captureMode: PhysicalCaptureMode,
        connectionMethod: ConnectionMethod,
        gamepadEnabled: Boolean = true,
    ) {
        require(connectionMethod == ConnectionMethod.WIFI || connectionMethod == ConnectionMethod.USB)
        val trusted = trustedStore.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (trusted == null) {
            gameplay.reportFailure(NetworkFailureReason.AUTHENTICATION_REJECTED, "Desktop is not trusted")
            return
        }
        val discovered = discovery.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (discovered != null &&
            !trusted.certificateSha256.equals(discovered.certificateSha256, ignoreCase = true)
        ) {
            gameplay.reportFailure(
                NetworkFailureReason.CERTIFICATE_CHANGED,
                "Discovered certificate does not match the trusted desktop",
            )
            return
        }
        activePeerIdHex = peerIdHex
        if (discovered != null) trustedStore.updateEndpoint(discovered)
        val verifiedHost = mutableAvailability.value[peerIdHex]?.verifiedHost
        val endpointHosts = (
            listOfNotNull(verifiedHost) + discovered?.endpointHosts.orEmpty() + trusted.endpointHosts
            ).distinct()
        if (endpointHosts.isEmpty()) {
            gameplay.reportFailure(
                NetworkFailureReason.DESKTOP_UNAVAILABLE,
                "Trusted desktop has no usable endpoint",
            )
            return
        }
        gameplay.start(
            request = NetworkGamepadRequest(
                host = endpointHosts.first(),
                alternateHosts = endpointHosts.drop(1),
                port = discovered?.port ?: trusted.port,
                certificateSha256 = trusted.certificateSha256,
                credentials = NetworkCredentials(
                    clientPeerId = trustedStore.clientPeerId,
                    serverPeerId = trusted.peerId,
                    sharedSecret = trusted.sharedSecret,
                ),
                connectTimeoutMillis = 1_000,
                reconnectAttempts = null,
                requiredLocalRoute = { localAddress ->
                    NetworkRouteClassifier.matches(connectionMethod, localAddress)
                },
                gamepadTransport = IpGamepadTransportStore.selected.value,
            ),
            physicalCaptureMode = captureMode,
            gamepadEnabled = gamepadEnabled,
        )
        GameplaySessionService.startNetwork(
            applicationContext,
            captureMode,
            connectionMethod,
            peerIdHex,
            gamepadEnabled,
        )
    }

    fun stopGameplay() {
        activePeerIdHex = null
        gameplay.stop()
        GameplaySessionService.stop(applicationContext)
    }

    fun mediaRequest(
        peerIdHex: String,
        connectionMethod: ConnectionMethod? = null,
    ): NetworkMediaRequest? {
        val trusted = trustedStore.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
            ?: return null
        val discovered = discovery.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (discovered != null &&
            !trusted.certificateSha256.equals(discovered.certificateSha256, ignoreCase = true)
        ) {
            return null
        }
        if (discovered != null) trustedStore.updateEndpoint(discovered)
        val hosts = (
            listOfNotNull(mutableAvailability.value[peerIdHex]?.verifiedHost) +
                discovered?.endpointHosts.orEmpty() + trusted.endpointHosts
            ).distinct()
        if (hosts.isEmpty()) return null
        return NetworkMediaRequest(
            host = hosts.first(),
            alternateHosts = hosts.drop(1),
            port = discovered?.mediaPort ?: (trusted.port + 1).coerceAtMost(65_535),
            certificateSha256 = trusted.certificateSha256,
            credentials = NetworkCredentials(
                clientPeerId = trustedStore.clientPeerId,
                serverPeerId = trusted.peerId,
                sharedSecret = trusted.sharedSecret,
            ),
            // Media startup re-resolves the discovered endpoints while routes
            // settle. A short attempt prevents an old Wi-Fi address from
            // blocking discovery of a newly-created USB tethering route.
            connectTimeoutMillis = 1_000,
            requiredLocalRoute = connectionMethod?.let { method ->
                { localAddress -> NetworkRouteClassifier.matches(method, localAddress) }
            },
        )
    }

    fun inputComparisonRequest(
        peerIdHex: String,
        connectionMethod: ConnectionMethod,
    ): NetworkGamepadRequest? {
        val media = mediaRequest(peerIdHex, connectionMethod) ?: return null
        val trusted = trustedStore.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
            ?: return null
        val discovered = discovery.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        return NetworkGamepadRequest(
            host = media.host,
            alternateHosts = media.alternateHosts,
            port = discovered?.port ?: trusted.port,
            certificateSha256 = media.certificateSha256,
            credentials = media.credentials,
            connectTimeoutMillis = media.connectTimeoutMillis,
            reconnectAttempts = 0,
            requiredLocalRoute = media.requiredLocalRoute,
        )
    }

    fun shutdown() {
        activePeerIdHex = null
        stopDiscovery()
        mutableAvailability.value = emptyMap()
        gameplay.shutdown()
        GameplaySessionService.stop(applicationContext)
    }

    private fun pairUsingDiscoveredEndpoints(
        desktop: DiscoveredDesktop,
        pairingCode: String,
    ): PairingEndpointSuccess {
        var lastFailure: Throwable? = null
        (listOfNotNull(mutableAvailability.value[desktop.peerIdHex]?.verifiedHost) +
            desktop.endpointHosts).distinct().forEach { host ->
            try {
                val result = NetworkPairingClient.pair(
                    NetworkPairingRequest(
                        host = host,
                        port = desktop.port,
                        certificateSha256 = desktop.certificateSha256,
                        expectedServerPeerId = desktop.peerId,
                        clientPeerId = trustedStore.clientPeerId,
                        clientName = applicationName,
                        pairingCode = pairingCode,
                    ),
                )
                return PairingEndpointSuccess(host, result)
            } catch (failure: Throwable) {
                if (failure is IllegalArgumentException || failure is PairingRejectedException) {
                    throw failure
                }
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("Discovered desktop has no network endpoint")
    }
}

private data class PairingEndpointSuccess(
    val host: String,
    val result: dev.jonalakas.bridgepad.transport.network.NetworkPairingResult,
)

private fun String.utf8Prefix(maxBytes: Int): String {
    var end = 0
    while (end < length) {
        val next = end + Character.charCount(codePointAt(end))
        if (substring(0, next).toByteArray(Charsets.UTF_8).size > maxBytes) break
        end = next
    }
    return substring(0, end)
}
