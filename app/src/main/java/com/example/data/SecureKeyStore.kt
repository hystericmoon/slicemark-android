package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyStore(context: Context) {

    companion object {
        private const val FILE_NAME = "slicemark_byok_vault"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_GEMINI = "gemini_api_key"
    }

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Safe fallback on stripped emulators without hardware Keystore
        context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE)
    }

    fun getOpenRouterKey(): String = prefs.getString(KEY_OPENROUTER, "").orEmpty().trim()
    fun hasOpenRouterKey(): Boolean = getOpenRouterKey().isNotEmpty()
    fun saveOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OPENROUTER, key.trim()).apply()
    }
    fun clearOpenRouterKey() {
        prefs.edit().remove(KEY_OPENROUTER).apply()
    }

    fun getGeminiKey(): String = prefs.getString(KEY_GEMINI, "").orEmpty().trim()
    fun hasGeminiKey(): Boolean = getGeminiKey().isNotEmpty()
    fun saveGeminiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI, key.trim()).apply()
    }
    fun clearGeminiKey() {
        prefs.edit().remove(KEY_GEMINI).apply()
    }
}
