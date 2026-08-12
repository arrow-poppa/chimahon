package eu.kanade.tachiyomi.ui.dictionary

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chimahon.anki.AnkiProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Stores BYOK provider keys encrypted by a non-exportable Android Keystore key. */
class AiSecretStore(
    private val preferenceStore: PreferenceStore,
) {

    @Synchronized
    fun get(provider: String): String {
        val pref = encryptedPreference(provider)
        val encoded = pref.get()
        if (encoded.isBlank()) return ""
        return runCatching { decrypt(encoded) }
            .onFailure { pref.delete() }
            .getOrDefault("")
    }

    @Synchronized
    fun set(provider: String, apiKey: String) {
        val pref = encryptedPreference(provider)
        if (apiKey.isBlank()) {
            pref.delete()
        } else {
            pref.set(encrypt(apiKey.trim()))
        }
    }

    fun hasKey(provider: String): Boolean = get(provider).isNotBlank()

    private fun encryptedPreference(provider: String) = preferenceStore.getString(
        Preference.privateKey("pref_dictionary_ai_key_${normalizedProvider(provider)}"),
        "",
    )

    private fun normalizedProvider(provider: String): String = when (provider) {
        AnkiProfile.AI_PROVIDER_GEMINI -> "gemini"
        AnkiProfile.AI_PROVIDER_OPENAI_COMPATIBLE -> "openai_compatible"
        else -> "openai"
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(cipher.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "v1:$iv:$payload"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == "v1")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "chimahon_ai_byok_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
