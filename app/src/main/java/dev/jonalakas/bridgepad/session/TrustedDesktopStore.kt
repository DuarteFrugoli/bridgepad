package dev.jonalakas.bridgepad.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.jonalakas.bridgepad.protocol.BridgeAuthentication
import dev.jonalakas.bridgepad.protocol.PeerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TrustedDesktop(
    val peerId: PeerId,
    val peerIdHex: String,
    val name: String,
    val certificateSha256: String,
    val sharedSecret: ByteArray,
    val lastHost: String,
    val alternateHosts: List<String>,
    val port: Int,
) {
    val endpointHosts: List<String>
        get() = (listOf(lastHost) + alternateHosts).distinct()
}

class TrustedDesktopStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableDesktops = MutableStateFlow(load())
    val desktops: StateFlow<List<TrustedDesktop>> = mutableDesktops.asStateFlow()
    val clientPeerId: PeerId by lazy(::loadOrCreateClientPeerId)

    @Synchronized
    fun save(
        discovered: DiscoveredDesktop,
        serverName: String,
        sharedSecret: ByteArray,
    ) {
        require(sharedSecret.size == BridgeAuthentication.SHARED_SECRET_SIZE)
        val id = discovered.peerIdHex
        preferences.edit()
            .putString(KEY_IDS, (ids() + id).sorted().joinToString(","))
            .putString(key(id, "name"), serverName)
            .putString(key(id, "fingerprint"), discovered.certificateSha256)
            .putString(key(id, "secret"), encrypt(sharedSecret))
            .putString(key(id, "host"), discovered.host)
            .putStringSet(key(id, "hosts"), discovered.endpointHosts.toSet())
            .putInt(key(id, "port"), discovered.port)
            .apply()
        mutableDesktops.value = load()
    }

    @Synchronized
    fun updateEndpoint(discovered: DiscoveredDesktop) {
        if (discovered.peerIdHex !in ids()) return
        preferences.edit()
            .putString(key(discovered.peerIdHex, "host"), discovered.host)
            .putStringSet(
                key(discovered.peerIdHex, "hosts"),
                (
                    preferences.getStringSet(key(discovered.peerIdHex, "hosts"), emptySet())
                        .orEmpty() + discovered.endpointHosts
                    ).toSet(),
            )
            .putInt(key(discovered.peerIdHex, "port"), discovered.port)
            .apply()
        mutableDesktops.value = load()
    }

    @Synchronized
    fun forget(peerIdHex: String) {
        val remaining = ids() - peerIdHex
        preferences.edit()
            .putString(KEY_IDS, remaining.sorted().joinToString(","))
            .remove(key(peerIdHex, "name"))
            .remove(key(peerIdHex, "fingerprint"))
            .remove(key(peerIdHex, "secret"))
            .remove(key(peerIdHex, "host"))
            .remove(key(peerIdHex, "hosts"))
            .remove(key(peerIdHex, "port"))
            .apply()
        mutableDesktops.value = load()
    }

    private fun load(): List<TrustedDesktop> = ids().mapNotNull { id ->
        runCatching {
            val bytes = id.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            fun longAt(offset: Int): Long = (0 until 8).fold(0L) { result, index ->
                (result shl 8) or (bytes[offset + index].toLong() and 0xff)
            }
            val lastHost = requireNotNull(preferences.getString(key(id, "host"), null))
            TrustedDesktop(
                peerId = PeerId(longAt(0), longAt(8)),
                peerIdHex = id,
                name = requireNotNull(preferences.getString(key(id, "name"), null)),
                certificateSha256 = requireNotNull(
                    preferences.getString(key(id, "fingerprint"), null),
                ),
                sharedSecret = decrypt(requireNotNull(preferences.getString(key(id, "secret"), null))),
                lastHost = lastHost,
                alternateHosts = preferences.getStringSet(key(id, "hosts"), emptySet())
                    .orEmpty()
                    .filterNot { it == lastHost },
                port = preferences.getInt(key(id, "port"), 39_393),
            )
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    private fun ids(): Set<String> = preferences.getString(KEY_IDS, null)
        ?.split(',')
        ?.filter(String::isNotBlank)
        ?.toSet()
        .orEmpty()

    private fun loadOrCreateClientPeerId(): PeerId {
        preferences.getString(KEY_CLIENT_ID, null)?.let { stored ->
            val values = stored.split(':')
            if (values.size == 2) {
                return PeerId(values[0].toLong(16), values[1].toLong(16))
            }
        }
        val random = SecureRandom()
        val created = PeerId(
            random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1),
            random.nextLong().and(Long.MAX_VALUE),
        )
        preferences.edit()
            .putString(KEY_CLIENT_ID, "${created.high.toString(16)}:${created.low.toString(16)}")
            .apply()
        return created
    }

    private fun encrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): ByteArray {
        val encoded = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(128, encoded.copyOfRange(0, IV_SIZE)),
        )
        return cipher.doFinal(encoded.copyOfRange(IV_SIZE, encoded.size))
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun key(id: String, field: String) = "desktop.$id.$field"

    private companion object {
        const val PREFERENCES = "bridgepad_trusted_desktops"
        const val KEY_IDS = "desktop_ids"
        const val KEY_CLIENT_ID = "client_peer_id"
        const val KEY_ALIAS = "bridgepad.network.trust.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
