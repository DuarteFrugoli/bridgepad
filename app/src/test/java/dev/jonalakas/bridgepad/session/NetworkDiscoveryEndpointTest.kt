package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.protocol.PeerId
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkDiscoveryEndpointTest {
    @Test
    fun discoveriesForSameDesktopAccumulateUniqueEndpoints() {
        val previous = desktop("192.168.15.3")
        val current = desktop("10.232.206.43", listOf("fe80::1%usb0"))

        assertEquals(
            listOf("10.232.206.43", "fe80::1%usb0", "192.168.15.3"),
            current.mergedWith(previous).endpointHosts,
        )
    }

    @Test
    fun preferredEndpointMovesToFrontWithoutLosingAlternatives() {
        val discovered = desktop("192.168.15.3", listOf("10.232.206.43"))

        assertEquals(
            listOf("10.232.206.43", "192.168.15.3"),
            discovered.preferring("10.232.206.43").endpointHosts,
        )
    }

    private fun desktop(host: String, alternates: List<String> = emptyList()) = DiscoveredDesktop(
        peerId = PeerId(1, 2),
        peerIdHex = "00000000000000010000000000000002",
        name = "BridgePad Desktop",
        host = host,
        port = 39_393,
        certificateSha256 = "00".repeat(32),
        serviceName = "BridgePad Desktop",
        alternateHosts = alternates,
    )
}
