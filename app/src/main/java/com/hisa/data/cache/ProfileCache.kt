package com.hisa.data.cache

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hisa.data.model.Metadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Singleton
class ProfileCache(
    context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "profile_cache",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val CACHE_EXPIRATION_TIME = 30 * 60 * 1000 // 30 minutes in milliseconds
    }

    fun getCachedProfile(pubkey: String): Metadata? {
        val jsonStr = sharedPreferences.getString(pubkey, null) ?: return null
        val timestamp = sharedPreferences.getLong("${pubkey}_timestamp", 0)
        
        // Check if cache has expired
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRATION_TIME) {
            clearCache(pubkey)
            return null
        }

        return try {
            Json.decodeFromString<Metadata>(jsonStr)
        } catch (e: Exception) {
            android.util.Log.e("ProfileCache", "Error decoding cached profile: ${e.message}")
            null
        }
    }

    fun getCachedProfileHistory(pubkey: String): List<Metadata> {
        val jsonStr = sharedPreferences.getString("${pubkey}_history", null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<Metadata>>(jsonStr)
        } catch (e: Exception) {
            android.util.Log.e("ProfileCache", "Error decoding cached profile history: ${e.message}")
            emptyList()
        }
    }

    fun cacheProfile(pubkey: String, metadata: Metadata) {
        try {
            val jsonStr = Json.encodeToString(metadata)
            sharedPreferences.edit()
                .putString(pubkey, jsonStr)
                .putLong("${pubkey}_timestamp", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ProfileCache", "Error caching profile: ${e.message}")
        }
    }

    fun cacheProfileHistory(pubkey: String, history: List<Metadata>) {
        try {
            val jsonStr = Json.encodeToString(history)
            sharedPreferences.edit()
                .putString("${pubkey}_history", jsonStr)
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ProfileCache", "Error caching profile history: ${e.message}")
        }
    }

    fun clearCache(pubkey: String) {
        sharedPreferences.edit()
            .remove(pubkey)
            .remove("${pubkey}_timestamp")
            .remove("${pubkey}_history")
            .apply()
    }
}
