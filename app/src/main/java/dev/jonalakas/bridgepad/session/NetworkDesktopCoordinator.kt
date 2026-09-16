package dev.jonalakas.bridgepad.session

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jonalakas.bridgepad.core.session.PhysicalCaptureMode
import dev.jonalakas.bridgepad.transport.network.NetworkAuthenticationException
import dev.jonalakas.bridgepad.transport.network.NetworkCredentials
import dev.jonalakas.bridgepad.transport.network.NetworkFailureReason
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadRequest
import dev.jonalakas.bridgepad.transport.network.NetworkGamepadStatus
import dev.jonalakas.bridgepad.transport.network.NetworkPairingClient
import dev.jonalakas.bridgepad.transport.network.NetworkPairingRequest
import dev.jonalakas.bridgepad.transport.network.PairingRejectedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

class NetworkDesktopCoordinator(
    context: Context,
    private val gameplay: NetworkGameplayController,
    private val scope: CoroutineScope,
) {
    private val discovery = NetworkDiscoveryManager(context)
    private val trustedStore = TrustedDesktopStore(context)
    private val applicationName = "${Build.MANUFACTURER} ${Build.MODEL}"
        .trim()
        .ifBlank { "Android" }
        .utf8Prefix(64)
    private val mutablePairingStatus = MutableStateFlow<NetworkPairingStatus>(NetworkPairingStatus.Idle)

    val discoveredDesktops: StateFlow<List<DiscoveredDesktop>> = discovery.desktops
    val trustedDesktops: StateFlow<List<TrustedDesktop>> = trustedStore.desktops
    val discoveryError: StateFlow<String?> = discovery.error
    val pairingStatus: StateFlow<NetworkPairingStatus> = mutablePairingStatus.asStateFlow()
    val gameplayStatus: StateFlow<NetworkGamepadStatus> = gameplay.status

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

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
                    NetworkPairingClient.pair(
                        NetworkPairingRequest(
                            host = desktop.host,
                            port = desktop.port,
                            certificateSha256 = desktop.certificateSha256,
                            expectedServerPeerId = desktop.peerId,
                            clientPeerId = trustedStore.clientPeerId,
                            clientName = applicationName,
                            pairingCode = pairingCode,
                        ),
                    )
                }
            }.onSuccess { result ->
                trustedStore.save(desktop, result.serverName, result.sharedSecret)
                mutablePairingStatus.value = NetworkPairingStatus.Success(peerIdHex)
            }.onFailure { error ->
                Log.e("BridgePadNetwork", "Wi-Fi pairing failed for $peerIdHex", error)
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
        gameplay.stop()
        trustedStore.forget(peerIdHex)
        clearPairingStatus()
    }

    fun startGameplay(peerIdHex: String, captureMode: PhysicalCaptureMode) {
        val trusted = trustedStore.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (trusted == null) {
            gameplay.reportFailure(NetworkFailureReason.AUTHENTICATION_REJECTED, "Desktop is not trusted")
            return
        }
        val discovered = discovery.desktops.value.firstOrNull { it.peerIdHex == peerIdHex }
        if (discovered == null) {
            gameplay.reportFailure(NetworkFailureReason.DESKTOP_UNAVAILABLE, "Desktop is offline")
            return
        }
        if (!trusted.certificateSha256.equals(discovered.certificateSha256, ignoreCase = true)) {
            gameplay.reportFailure(
                NetworkFailureReason.CERTIFICATE_CHANGED,
                "Discovered certificate does not match the trusted desktop",
            )
            return
        }
        trustedStore.updateEndpoint(discovered)
        gameplay.start(
            request = NetworkGamepadRequest(
                host = discovered.host,
                port = discovered.port,
                certificateSha256 = trusted.certificateSha256,
                credentials = NetworkCredentials(
                    clientPeerId = trustedStore.clientPeerId,
                    serverPeerId = trusted.peerId,
                    sharedSecret = trusted.sharedSecret,
                ),
            ),
            physicalCaptureMode = captureMode,
        )
    }

    fun stopGameplay() = gameplay.stop()

    fun shutdown() {
        discovery.stop()
        gameplay.shutdown()
    }
}

private fun String.utf8Prefix(maxBytes: Int): String {
    var end = 0
    while (end < length) {
        val next = end + Character.charCount(codePointAt(end))
        if (substring(0, next).toByteArray(Charsets.UTF_8).size > maxBytes) break
        end = next
    }
    return substring(0, end)
}
