package org.cloud.sonic.android.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(bindingKey: String, token: String) {
        val tokens = readTokens()
        tokens[bindingKey] = token
        writeTokens(tokens)
    }

    fun get(bindingKey: String): String = readTokens()[bindingKey].orEmpty()

    fun remove(bindingKey: String) {
        val tokens = readTokens()
        if (tokens.remove(bindingKey) != null) writeTokens(tokens)
    }

    fun migrateLegacyToken(bindingKey: String): Boolean {
        if (get(bindingKey).isNotBlank()) {
            prefs.edit().remove(KEY_LEGACY_IV).remove(KEY_LEGACY_VALUE).apply()
            return true
        }
        val legacyToken = decrypt(
            prefs.getString(KEY_LEGACY_IV, null),
            prefs.getString(KEY_LEGACY_VALUE, null)
        )
        if (legacyToken.isBlank()) return false
        save(bindingKey, legacyToken)
        prefs.edit().remove(KEY_LEGACY_IV).remove(KEY_LEGACY_VALUE).apply()
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun readTokens(): MutableMap<String, String> {
        val json = decrypt(prefs.getString(KEY_TOKENS_IV, null), prefs.getString(KEY_TOKENS_VALUE, null))
        if (json.isBlank()) return mutableMapOf()
        return runCatching {
            val type = object : TypeToken<MutableMap<String, String>>() {}.type
            gson.fromJson<MutableMap<String, String>>(json, type) ?: mutableMapOf()
        }.getOrElse {
            prefs.edit().remove(KEY_TOKENS_IV).remove(KEY_TOKENS_VALUE).apply()
            mutableMapOf()
        }
    }

    private fun writeTokens(tokens: Map<String, String>) {
        if (tokens.isEmpty()) {
            prefs.edit().remove(KEY_TOKENS_IV).remove(KEY_TOKENS_VALUE).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(gson.toJson(tokens).toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_TOKENS_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKENS_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun decrypt(ivValue: String?, encryptedValue: String?): String {
        val iv = ivValue ?: return ""
        val value = encryptedValue ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "sonic_link_secure_tokens"
        private const val KEY_ALIAS = "sonic_link_mobile_device_token"
        private const val KEY_LEGACY_IV = "device_token_iv"
        private const val KEY_LEGACY_VALUE = "device_token_value"
        private const val KEY_TOKENS_IV = "device_tokens_iv"
        private const val KEY_TOKENS_VALUE = "device_tokens_value"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
