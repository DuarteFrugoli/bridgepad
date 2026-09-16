package dev.jonalakas.bridgepad.transport.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NetworkAuthenticationTest {
    @Test
    fun pbkdf2UsesTheProtocolSha256Parameters() {
        val derived = derivePairingKey("password", "saltsaltsaltsalt".toByteArray(), 1)

        assertEquals(
            "b13d6697e99cd6d1745da097ee03e4be501341e76fe9161a788de3d4cd0be219",
            derived.toHex(),
        )
    }

    @Test
    fun pairingCodeAcceptsFormattingButRequiresTwelveDigits() {
        assertEquals("123456789012", normalizePairingCode("1234-5678-9012"))
        assertThrows(IllegalArgumentException::class.java) { normalizePairingCode("1234") }
    }

    @Test
    fun clientAndServerProofsUseDifferentRoles() {
        val key = ByteArray(32) { it.toByte() }
        val transcript = ByteArray(128) { (it * 3).toByte() }

        assertFalse(
            hmacSha256(key, roleTranscript("bridgepad-pair-client-v1", transcript))
                .contentEquals(
                    hmacSha256(key, roleTranscript("bridgepad-pair-server-v1", transcript)),
                ),
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
