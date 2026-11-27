package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyStore(private val context: Context) {

    companion object {
        private const val FILE_NAME = "slicemark_byok_vault"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_GEMINI = "gemini_api_key"
    }

    private val prefs: SharedPreferences? = createEncryptedPrefs()

    private fun createEncryptedPrefs(): SharedPreferences? {
        return runCatching {
            openEncryptedPrefs()
        }.getOrElse {
            // If Keystore keyset became invalid (e.g., after OS reset), clear corrupted vault and re-initialize encrypted store.
            runCatching {
                context.deleteSharedPreferences(FILE_NAME)
                openEncryptedPrefs()
            }.getOrNull()
        }
    }

    private fun openEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getOpenRouterKey(): String = prefs?.getString(KEY_OPENROUTER, "").orEmpty().trim()
    fun hasOpenRouterKey(): Boolean = getOpenRouterKey().isNotEmpty()
    fun saveOpenRouterKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) clearOpenRouterKey()
        else prefs?.edit()?.putString(KEY_OPENROUTER, trimmed)?.apply()
    }
    fun clearOpenRouterKey() {
        prefs?.edit()?.remove(KEY_OPENROUTER)?.apply()
    }

    fun getGeminiKey(): String = prefs?.getString(KEY_GEMINI, "").orEmpty().trim()
    fun hasGeminiKey(): Boolean = getGeminiKey().isNotEmpty()
    fun saveGeminiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) clearGeminiKey()
        else prefs?.edit()?.putString(KEY_GEMINI, trimmed)?.apply()
    }
    fun clearGeminiKey() {
        prefs?.edit()?.remove(KEY_GEMINI)?.apply()
    }
}
