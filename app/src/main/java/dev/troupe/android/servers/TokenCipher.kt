package dev.troupe.android.servers

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/** Encrypts secrets at rest. [associated] binds a ciphertext to its owner (the server id). */
interface TokenCipher {
    fun encrypt(plain: String, associated: String): ByteArray
    fun decrypt(cipher: ByteArray, associated: String): String
}

/** Tink AES256-GCM; the keyset is itself encrypted by an Android Keystore master key. */
class TinkTokenCipher(context: Context) : TokenCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "troupe_keyset", "troupe_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://troupe_master_key")
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: String, associated: String): ByteArray =
        aead.encrypt(plain.toByteArray(Charsets.UTF_8), associated.toByteArray(Charsets.UTF_8))

    override fun decrypt(cipher: ByteArray, associated: String): String =
        String(aead.decrypt(cipher, associated.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
}
