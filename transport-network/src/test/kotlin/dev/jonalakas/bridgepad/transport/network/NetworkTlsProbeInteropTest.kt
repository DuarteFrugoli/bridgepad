package dev.jonalakas.bridgepad.transport.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NetworkTlsProbeInteropTest {
    @Test
    fun rustDiagnosticReceiver_completesPinnedTlsProbeWhenConfigured() {
        val host = System.getenv("BRIDGEPAD_TEST_HOST")
        val fingerprint = System.getenv("BRIDGEPAD_TEST_FINGERPRINT")
        assumeTrue("External Rust receiver was not configured", host != null && fingerprint != null)

        val result = NetworkTlsProbe.run(
            NetworkProbeRequest(
                host = requireNotNull(host),
                port = System.getenv("BRIDGEPAD_TEST_PORT")?.toIntOrNull() ?: 39_393,
                certificateSha256 = requireNotNull(fingerprint),
                samples = 20,
                rateHz = 125,
            ),
        )

        assertEquals(20, result.samples)
        assertTrue(result.tlsVersion == "TLSv1.3" || result.tlsVersion == "TLSv1.2")
        assertTrue(result.rttP99Millis >= 0.0)
    }
}
