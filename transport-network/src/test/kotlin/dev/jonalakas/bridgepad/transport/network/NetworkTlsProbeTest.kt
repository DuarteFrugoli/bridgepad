package dev.jonalakas.bridgepad.transport.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkTlsProbeTest {
    @Test
    fun fingerprint_acceptsCommonSeparatorsAndNormalizesCase() {
        val compact = "0123456789abcdef".repeat(4)
        val separated = compact.chunked(2).joinToString(":")

        assertEquals(compact.uppercase(), normalizeFingerprint(separated))
        assertEquals(compact.uppercase(), normalizeFingerprint("  $compact  "))
    }

    @Test
    fun fingerprint_rejectsWrongLengthAndNonHexCharacters() {
        assertThrows(IllegalArgumentException::class.java) { normalizeFingerprint("ab") }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeFingerprint("z".repeat(64))
        }
    }

    @Test
    fun latencySummary_usesNearestRankAtRequestedPercentiles() {
        val samples = (1L..101L).map { it * 1_000_000 }

        assertEquals(LatencySummary(51.0, 96.0, 100.0), summarizeNanoseconds(samples))
    }
}
