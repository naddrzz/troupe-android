package dev.troupe.android.servers

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Encrypts secrets at rest. [associated] binds a ciphertext to its owner (the server id).
 * Both calls throw [GeneralSecurityException] when the keyset is unusable (e.g. the Keystore key
 * was lost); [decrypt] also when [cipher] is corrupt or bound to another owner. Callers treat an
 * unreadable secret as absent.
 */
interface TokenCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plain: String, associated: String): ByteArray

    @Throws(GeneralSecurityException::class)
    fun decrypt(cipher: ByteArray, associated: String): String
}

/** Tink AES256-GCM; the keyset is itself encrypted by an Android Keystore master key. */
class TinkTokenCipher(context: Context) : TokenCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        try {
            AndroidKeysetManager.Builder()
                .withSharedPref(context, "troupe_keyset", "troupe_keyset_prefs")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://troupe_master_key")
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        } catch (e: IOException) {
            throw GeneralSecurityException("keyset unreadable", e)
        }
    }

    override fun encrypt(plain: String, associated: String): ByteArray =
        aead.encrypt(plain.toByteArray(Charsets.UTF_8), associated.toByteArray(Charsets.UTF_8))

    override fun decrypt(cipher: ByteArray, associated: String): String =
        String(aead.decrypt(cipher, associated.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
}
