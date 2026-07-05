package com.hisa.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePreferencesHelper {
    const val AUTH_PREFS_NAME = "secure_prefs"
    const val AUTH_PREFS_FALLBACK = "secure_prefs_fallback"
    const val SECURE_STORAGE_PREFS_NAME = "secure_storage_prefs"
    const val SECURE_STORAGE_FALLBACK = "secure_storage_fallback"
    const val CONVERSATIONS_PREFS_NAME = "conversations_prefs"

    fun create(
        context: Context,
        prefsName: String,
        fallbackPrefsName: String? = null
    ): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
            context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
        }
    }

    fun readString(
        context: Context,
        prefsName: String,
        key: String,
        fallbackPrefsName: String? = null
    ): String? {
        val prefs = create(context, prefsName, fallbackPrefsName)
        val primaryValue = prefs.getString(key, null)
        if (!primaryValue.isNullOrBlank()) {
            return primaryValue
        }
        val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
        return context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE).getString(key, null)
    }

    fun writeString(
        context: Context,
        prefsName: String,
        key: String,
        value: String,
        fallbackPrefsName: String? = null
    ) {
        val prefs = create(context, prefsName, fallbackPrefsName)
        prefs.edit().putString(key, value).apply()

        val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
        context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    fun readBoolean(
        context: Context,
        prefsName: String,
        key: String,
        defaultValue: Boolean,
        fallbackPrefsName: String? = null
    ): Boolean {
        val prefs = create(context, prefsName, fallbackPrefsName)
        val primaryValue = prefs.getBoolean(key, defaultValue)
        if (primaryValue != defaultValue) {
            return primaryValue
        }
        val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
        return context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE).getBoolean(key, defaultValue)
    }

    fun writeBoolean(
        context: Context,
        prefsName: String,
        key: String,
        value: Boolean,
        fallbackPrefsName: String? = null
    ) {
        val prefs = create(context, prefsName, fallbackPrefsName)
        prefs.edit().putBoolean(key, value).apply()

        val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
        context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    fun clear(
        context: Context,
        prefsName: String,
        fallbackPrefsName: String? = null
    ) {
        create(context, prefsName, fallbackPrefsName).edit().clear().apply()

        val fallbackName = fallbackPrefsName ?: "${prefsName}_fallback"
        context.getSharedPreferences(fallbackName, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
