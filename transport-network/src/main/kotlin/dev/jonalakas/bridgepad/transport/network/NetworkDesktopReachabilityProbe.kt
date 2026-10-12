package dev.jonalakas.bridgepad.transport.network

/** Confirms that a Desktop endpoint accepts TLS with its expected certificate. */
object NetworkDesktopReachabilityProbe {
    fun firstReachableHost(
        hosts: List<String>,
        port: Int,
        certificateSha256: String,
        timeoutMillis: Int = 750,
    ): String? {
        for (host in hosts.filter(String::isNotBlank).distinct()) {
            val reachable = runCatching {
                openPinnedTlsSocket(
                    host = host,
                    port = port,
                    certificateSha256 = certificateSha256,
                    connectTimeoutMillis = timeoutMillis,
                    readTimeoutMillis = timeoutMillis,
                ).use { it.startHandshake() }
            }.isSuccess
            if (reachable) return host
        }
        return null
    }
}
