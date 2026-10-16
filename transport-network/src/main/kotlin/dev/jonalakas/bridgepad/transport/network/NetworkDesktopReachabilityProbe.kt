package dev.jonalakas.bridgepad.transport.network

import java.net.InetAddress

/** Confirms that a Desktop endpoint accepts TLS with its expected certificate. */
object NetworkDesktopReachabilityProbe {
    fun firstReachableHost(
        hosts: List<String>,
        port: Int,
        certificateSha256: String,
        timeoutMillis: Int = 750,
        acceptLocalAddress: (InetAddress) -> Boolean = { true },
    ): String? {
        for (host in hosts.filter(String::isNotBlank).distinct()) {
            val reachable = runCatching {
                openPinnedTlsSocket(
                    host = host,
                    port = port,
                    certificateSha256 = certificateSha256,
                    connectTimeoutMillis = timeoutMillis,
                    readTimeoutMillis = timeoutMillis,
                ).use { socket ->
                    socket.startHandshake()
                    acceptLocalAddress(socket.localAddress)
                }
            }.getOrDefault(false)
            if (reachable) return host
        }
        return null
    }
}
