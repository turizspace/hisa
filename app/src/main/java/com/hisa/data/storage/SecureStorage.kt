package com.hisa.data.storage

import android.content.Context
import com.hisa.util.AuthPreferenceStore
import com.hisa.util.SecurePreferencesHelper
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
        SecurePreferencesHelper.create(
            context = context,
            prefsName = SecurePreferencesHelper.SECURE_STORAGE_PREFS_NAME,
            fallbackPrefsName = SecurePreferencesHelper.SECURE_STORAGE_FALLBACK
        )
    }

    companion object {
        private const val KEY_X25519_PRIVATE = "x25519_private"
        private const val KEY_EXTERNAL_SIGNER_PUBKEY = "external_signer_pubkey"
        private const val KEY_EXTERNAL_SIGNER_PACKAGE = "external_signer_package"
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
            AuthPreferenceStore.readNsec(context)
        } catch (e: Exception) {
            null
        }
    }

    fun getExternalSignerPubkey(): String? {
        return readAuthPref(KEY_EXTERNAL_SIGNER_PUBKEY)
    }

    fun getExternalSignerPackage(): String? {
        return readAuthPref(KEY_EXTERNAL_SIGNER_PACKAGE)
    }

    private fun readAuthPref(key: String): String? {
        return try {
            when (key) {
                KEY_EXTERNAL_SIGNER_PUBKEY -> AuthPreferenceStore.readExternalSignerPubkey(context)
                KEY_EXTERNAL_SIGNER_PACKAGE -> AuthPreferenceStore.readExternalSignerPackage(context)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearX25519PrivateKey() {
        prefs.edit().remove(KEY_X25519_PRIVATE).apply()
    }
}
