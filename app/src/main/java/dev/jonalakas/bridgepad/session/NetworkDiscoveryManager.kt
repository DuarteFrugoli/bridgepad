package dev.jonalakas.bridgepad.session

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
    val certificateSha256: String,
    val serviceName: String,
)

@Suppress("DEPRECATION")
class NetworkDiscoveryManager(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val multicastLock = context.applicationContext
        .getSystemService(WifiManager::class.java)
        .createMulticastLock("BridgePad-network-discovery")
        .apply { setReferenceCounted(false) }
    private val mutableDesktops = MutableStateFlow<List<DiscoveredDesktop>>(emptyList())
    val desktops: StateFlow<List<DiscoveredDesktop>> = mutableDesktops.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val serviceToPeer = mutableMapOf<String, String>()
    private val resolutionQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            mutableError.value = null
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(this@NetworkDiscoveryManager) {
                if (serviceInfo.serviceType.startsWith(SERVICE_TYPE)) {
                    resolutionQueue.removeAll { it.serviceName == serviceInfo.serviceName }
                    resolutionQueue.addLast(serviceInfo)
                    resolveNextLocked()
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(this@NetworkDiscoveryManager) {
                val peerId = serviceToPeer.remove(serviceInfo.serviceName) ?: return
                mutableDesktops.value = mutableDesktops.value.filterNot { it.peerIdHex == peerId }
            }
        }

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            mutableError.value = "discovery_start_failed:$errorCode"
            started = false
            runCatching { nsdManager.stopServiceDiscovery(this) }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            mutableError.value = "discovery_stop_failed:$errorCode"
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        runCatching { multicastLock.acquire() }
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { failure ->
            started = false
            if (multicastLock.isHeld) multicastLock.release()
            mutableError.value = "discovery_start_failed:${failure.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        if (multicastLock.isHeld) multicastLock.release()
        resolutionQueue.clear()
        resolving = false
        serviceToPeer.clear()
        mutableDesktops.value = emptyList()
    }

    @SuppressLint("NewApi")
    @Synchronized
    private fun resolveNextLocked() {
        if (resolving) return
        val service = resolutionQueue.pollFirst() ?: return
        resolving = true
        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@NetworkDiscoveryManager) {
                    resolving = false
                    mutableError.value = "resolve_failed:$errorCode"
                    resolveNextLocked()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                synchronized(this@NetworkDiscoveryManager) {
                    resolving = false
                    parse(serviceInfo)?.let { desktop ->
                        serviceToPeer[serviceInfo.serviceName] = desktop.peerIdHex
                        mutableDesktops.value = (
                            mutableDesktops.value.filterNot { it.peerIdHex == desktop.peerIdHex } + desktop
                            ).sortedBy { it.name.lowercase() }
                        mutableError.value = null
                    }
                    resolveNextLocked()
                }
            }
        })
    }

    private fun parse(service: NsdServiceInfo): DiscoveredDesktop? = runCatching {
        val peerIdHex = service.attribute("id").lowercase()
        require(peerIdHex.length == 32)
        val fingerprint = service.attribute("fp")
        require(fingerprint.length == 64)
        require(service.attribute("v") == "1")
        val host = requireNotNull(service.host?.hostAddress)
        DiscoveredDesktop(
            peerId = peerIdHex.toPeerId(),
            peerIdHex = peerIdHex,
            name = service.serviceName,
            host = host,
            port = service.port,
            certificateSha256 = fingerprint,
            serviceName = service.serviceName,
        )
    }.getOrNull()

    private fun NsdServiceInfo.attribute(name: String): String =
        attributes[name]?.toString(Charsets.UTF_8) ?: error("missing $name")

    private fun String.toPeerId(): PeerId {
        val bytes = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun longAt(offset: Int): Long = (0 until 8).fold(0L) { result, index ->
            (result shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
        return PeerId(longAt(0), longAt(8))
    }

    private companion object {
        const val SERVICE_TYPE = "_bridgepad._tcp."
    }
}
