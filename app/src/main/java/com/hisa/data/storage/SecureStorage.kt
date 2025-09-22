package com.hisa.data.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple secure storage wrapper using AndroidX Security EncryptedSharedPreferences.
 * Stores small secrets like X25519 private key.
 */
@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "secure_storage_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // If EncryptedSharedPreferences isn't available (e.g., unit tests), fall back to regular prefs
            context.getSharedPreferences("secure_storage_fallback", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val KEY_X25519_PRIVATE = "x25519_private"
    }

    fun storeX25519PrivateKey(hex: String) {
        prefs.edit().putString(KEY_X25519_PRIVATE, hex).apply()
    }

    fun getX25519PrivateKey(): String? {
        return prefs.getString(KEY_X25519_PRIVATE, null)
    }

    // Read the stored nsec value from the same secure prefs that AuthViewModel uses.
    // Returns the bech32 nsec (e.g. "nsec1...") or null if not found.
    fun getNsec(): String? {
        return try {
            // Try to open the same encrypted shared prefs name used in AuthViewModel
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val authPrefs = EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            authPrefs.getString("nsec", null)
        } catch (e: Exception) {
            // Fallback to the same fallback filename used elsewhere in the app
            try {
                val fallback = context.getSharedPreferences("secure_prefs_fallback", android.content.Context.MODE_PRIVATE)
                fallback.getString("nsec", null)
            } catch (ex: Exception) {
                null
            }
        }
    }

    fun clearX25519PrivateKey() {
        prefs.edit().remove(KEY_X25519_PRIVATE).apply()
    }
}
