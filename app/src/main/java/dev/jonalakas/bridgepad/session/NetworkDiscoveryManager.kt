package dev.jonalakas.bridgepad.session

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.jonalakas.bridgepad.protocol.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

data class DiscoveredDesktop(
    val peerId: PeerId,
    val peerIdHex: String,
    val name: String,
    val host: String,
    val port: Int,
    val mediaPort: Int = (port + 1).coerceAtMost(65_535),
    val certificateSha256: String,
    val serviceName: String,
    val alternateHosts: List<String> = emptyList(),
) {
    val endpointHosts: List<String>
        get() = (listOf(host) + alternateHosts).distinct()

    fun preferring(preferredHost: String): DiscoveredDesktop = copy(
        host = preferredHost,
        alternateHosts = endpointHosts.filterNot { it == preferredHost },
    )

    fun mergedWith(previous: DiscoveredDesktop?): DiscoveredDesktop {
        if (previous == null) return this
        return copy(
            alternateHosts = (endpointHosts + previous.endpointHosts)
                .distinct()
                .filterNot { it == host },
        )
    }
}

data class DiscoveryDiagnosticEvent(
    val timestampMillis: Long,
    val elapsedRealtimeMillis: Long,
    val generation: Long,
    val detail: String,
)

@Suppress("DEPRECATION")
class NetworkDiscoveryManager(context: Context) {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val multicastLock = context.applicationContext
        .getSystemService(WifiManager::class.java)
        .createMulticastLock("BridgePad-network-discovery")
        .apply { setReferenceCounted(false) }
    private val mutableDesktops = MutableStateFlow<List<DiscoveredDesktop>>(emptyList())
    val desktops: StateFlow<List<DiscoveredDesktop>> = mutableDesktops.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableDiagnosticEvents = MutableStateFlow<List<DiscoveryDiagnosticEvent>>(emptyList())
    val diagnosticEvents: StateFlow<List<DiscoveryDiagnosticEvent>> =
        mutableDiagnosticEvents.asStateFlow()
    private val serviceToPeer = mutableMapOf<String, String>()
    private val resolutionQueue = ArrayDeque<NsdServiceInfo>()
    private val lifecycle = DiscoveryLifecycle()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var generation = 0L

    @Synchronized
    fun start() {
        val command = lifecycle.requestStart()
        trace("start requested: $command; ${networkSummary()}")
        if (command == DiscoveryLifecycle.Command.START) startLocked()
    }

    @Synchronized
    fun stop() {
        val command = lifecycle.requestStop()
        trace("stop requested: $command")
        if (command != DiscoveryLifecycle.Command.STOP) return
        val current = listener ?: return finishStopLocked(allowRestart = false)
        runCatching { nsdManager.stopServiceDiscovery(current) }
            .onFailure { failure ->
                trace("stopServiceDiscovery threw ${failure.javaClass.simpleName}: ${failure.message}")
                mutableError.value = "discovery_stop_failed:${failure.javaClass.simpleName}"
                finishStopLocked(allowRestart = false)
            }
    }

    @Synchronized
    fun markDiagnostics() {
        trace("MARK: ${networkSummary()}; visible=${mutableDesktops.value.size}; lock=${multicastLock.isHeld}")
    }

    @Synchronized
    fun clearDiagnostics() {
        mutableDiagnosticEvents.value = emptyList()
        markDiagnostics()
    }

    @Synchronized
    fun recordAvailability(detail: String) {
        trace(detail)
    }

    @Synchronized
    private fun startLocked() {
        val currentGeneration = ++generation
        val current = createListener(currentGeneration)
        listener = current
        if (!multicastLock.isHeld) runCatching { multicastLock.acquire() }
            .onFailure { trace("multicast lock failed: ${it.javaClass.simpleName}: ${it.message}") }
        trace("discoverServices requested; lock=${multicastLock.isHeld}; ${networkSummary()}")
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, current)
        }.onFailure { failure ->
            trace("discoverServices threw ${failure.javaClass.simpleName}: ${failure.message}")
            if (listener === current) {
                listener = null
                lifecycle.startFailed()
                clearCycleLocked(clearDesktops = true)
                releaseMulticastLockLocked()
                mutableError.value = "discovery_start_failed:${failure.javaClass.simpleName}"
            }
        }
    }

    private fun createListener(currentGeneration: Long) = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onDiscoveryStarted type=$serviceType current=${listener === this}")
                if (listener !== this) return
                lifecycle.started()
                mutableError.value = null
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onServiceFound name=${serviceInfo.serviceName} type=${serviceInfo.serviceType} current=${listener === this}")
                if (listener !== this || !serviceInfo.serviceType.startsWith(SERVICE_TYPE)) return
                resolutionQueue.removeAll { it.serviceName == serviceInfo.serviceName }
                resolutionQueue.addLast(serviceInfo)
                trace("resolution queued name=${serviceInfo.serviceName} queue=${resolutionQueue.size} resolving=$resolving")
                resolveNextLocked(currentGeneration)
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onServiceLost name=${serviceInfo.serviceName} current=${listener === this}; ${networkSummary()}")
                if (listener !== this) return
                val peerId = serviceToPeer.remove(serviceInfo.serviceName)
                if (peerId == null) {
                    trace("loss ignored: no resolved peer for ${serviceInfo.serviceName}")
                    return
                }
                mutableDesktops.value = mutableDesktops.value.filterNot { it.peerIdHex == peerId }
                trace("desktop removed name=${serviceInfo.serviceName} peer=${peerId.take(12)} visible=${mutableDesktops.value.size}")
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onDiscoveryStopped type=$serviceType current=${listener === this}")
                if (listener === this) finishStopLocked(allowRestart = true)
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onStartDiscoveryFailed type=$serviceType code=$errorCode current=${listener === this}")
                if (listener !== this) return
                listener = null
                lifecycle.startFailed()
                clearCycleLocked(clearDesktops = true)
                releaseMulticastLockLocked()
                mutableError.value = "discovery_start_failed:$errorCode"
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            synchronized(this@NetworkDiscoveryManager) {
                trace("onStopDiscoveryFailed type=$serviceType code=$errorCode current=${listener === this}")
                if (listener !== this) return
                mutableError.value = "discovery_stop_failed:$errorCode"
                if (lifecycle.stopFailed() == DiscoveryLifecycle.Command.STOP) {
                    runCatching { nsdManager.stopServiceDiscovery(this) }
                        .onFailure { finishStopLocked(allowRestart = false) }
                }
            }
        }
    }

    /** Preserve resolved PCs across quick Activity stop/start transitions. */
    private fun finishStopLocked(allowRestart: Boolean) {
        listener = null
        val command = lifecycle.stopped()
        val shouldRestart = allowRestart && command == DiscoveryLifecycle.Command.START
        trace("stop completed: restart=$shouldRestart; clearDesktops=${!shouldRestart}")
        clearCycleLocked(clearDesktops = !shouldRestart)
        if (shouldRestart) startLocked() else releaseLockIfIdleLocked()
    }

    private fun clearCycleLocked(clearDesktops: Boolean) {
        trace("cycle cleared: queued=${resolutionQueue.size} resolving=$resolving visible=${mutableDesktops.value.size}")
        resolutionQueue.clear()
        resolving = false
        serviceToPeer.clear()
        if (clearDesktops) mutableDesktops.value = emptyList()
    }

    private fun releaseLockIfIdleLocked() {
        if (!lifecycle.wantsDiscovery()) releaseMulticastLockLocked()
    }

    private fun releaseMulticastLockLocked() {
        if (multicastLock.isHeld) {
            multicastLock.release()
            trace("multicast lock released")
        }
    }

    @SuppressLint("NewApi")
    @Synchronized
    private fun resolveNextLocked(currentGeneration: Long) {
        if (resolving) return
        val service = resolutionQueue.pollFirst() ?: return
        resolving = true
        trace("resolveService requested name=${service.serviceName}")
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@NetworkDiscoveryManager) {
                        trace("onResolveFailed name=${serviceInfo.serviceName} code=$errorCode current=${generation == currentGeneration && listener != null}")
                        if (generation != currentGeneration || listener == null) return
                        resolving = false
                        mutableError.value = "resolve_failed:$errorCode"
                        resolveNextLocked(currentGeneration)
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    synchronized(this@NetworkDiscoveryManager) {
                        val hosts = runCatching { resolvedHosts(serviceInfo) }
                            .getOrElse { listOf("unavailable:${it.javaClass.simpleName}") }
                        trace("onServiceResolved name=${serviceInfo.serviceName} hosts=$hosts port=${serviceInfo.port} txt=${serviceInfo.attributes.keys.sorted()} current=${generation == currentGeneration && listener != null}")
                        if (generation != currentGeneration || listener == null) return
                        resolving = false
                        parse(serviceInfo)?.let { desktop ->
                            serviceToPeer[serviceInfo.serviceName] = desktop.peerIdHex
                            val merged = desktop.mergedWith(
                                mutableDesktops.value.firstOrNull { it.peerIdHex == desktop.peerIdHex },
                            )
                            mutableDesktops.value = (
                                mutableDesktops.value.filterNot { it.peerIdHex == desktop.peerIdHex } + merged
                                ).sortedBy { it.name.lowercase() }
                            mutableError.value = null
                            trace("desktop visible name=${desktop.name} peer=${desktop.peerIdHex.take(12)} hosts=${desktop.endpointHosts}")
                        }
                        resolveNextLocked(currentGeneration)
                    }
                }
            })
        } catch (failure: RuntimeException) {
            trace("resolveService threw ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
    }

    private fun parse(service: NsdServiceInfo): DiscoveredDesktop? = runCatching {
        val peerIdHex = service.attribute("id").lowercase()
        require(peerIdHex.length == 32)
        val fingerprint = service.attribute("fp")
        require(fingerprint.length == 64)
        require(service.attribute("v") == "1")
        val resolvedHosts = resolvedHosts(service)
        val legacyHost = service.host?.hostAddress
        val host = legacyHost?.takeIf { it in resolvedHosts } ?: resolvedHosts.first()
        DiscoveredDesktop(
            peerId = peerIdHex.toPeerId(),
            peerIdHex = peerIdHex,
            name = service.serviceName,
            host = host,
            port = service.port,
            mediaPort = service.optionalAttribute("mp")?.toIntOrNull()
                ?.takeIf { it in 1..65_535 }
                ?: (service.port + 1).coerceAtMost(65_535),
            certificateSha256 = fingerprint,
            serviceName = service.serviceName,
            alternateHosts = resolvedHosts.filterNot { it == host },
        )
    }.onFailure { failure ->
        trace("resolved service discarded name=${service.serviceName}: ${failure.javaClass.simpleName}: ${failure.message}")
    }.getOrNull()

    private fun resolvedHosts(service: NsdServiceInfo): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.hostAddresses.mapNotNull { it.hostAddress }
        } else {
            listOfNotNull(service.host?.hostAddress)
        }.distinct()

    private fun networkSummary(): String = runCatching {
        val active = connectivityManager.activeNetwork
        val networks = connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "other"
            }
            val addresses = connectivityManager.getLinkProperties(network)?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                .orEmpty()
            "$type${if (network == active) "*" else ""}=${addresses.joinToString()}"
        }
        "networks=[${networks.joinToString("; ")}]"
    }.getOrElse { "networks unavailable: ${it.javaClass.simpleName}" }

    private fun trace(detail: String) {
        val event = DiscoveryDiagnosticEvent(
            timestampMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            generation = generation,
            detail = detail,
        )
        mutableDiagnosticEvents.value = (mutableDiagnosticEvents.value + event).takeLast(MAX_DIAGNOSTIC_EVENTS)
        Log.d("BridgePadDiscovery", "generation=${event.generation} $detail")
    }

    private fun NsdServiceInfo.attribute(name: String): String =
        attributes[name]?.toString(Charsets.UTF_8) ?: error("missing $name")

    private fun NsdServiceInfo.optionalAttribute(name: String): String? =
        attributes[name]?.toString(Charsets.UTF_8)

    private fun String.toPeerId(): PeerId {
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun longAt(offset: Int): Long = (0 until 8).fold(0L) { result, index ->
            (result shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
        return PeerId(longAt(0), longAt(8))
    }

    private companion object {
        const val SERVICE_TYPE = "_bridgepad._tcp."
        const val MAX_DIAGNOSTIC_EVENTS = 300
    }
}
