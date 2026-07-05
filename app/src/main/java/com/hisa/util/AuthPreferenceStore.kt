package com.hisa.util

import android.content.Context
import android.content.SharedPreferences

object AuthPreferenceStore {
    const val NSEC_KEY = "nsec"
    const val RELAYS_KEY = "relays"
    const val DARK_THEME_KEY = "dark_theme"
    const val EXTERNAL_SIGNER_PUBKEY_KEY = "external_signer_pubkey"
    const val EXTERNAL_SIGNER_PACKAGE_KEY = "external_signer_package"

    fun prefs(context: Context): SharedPreferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun readNsec(context: Context): String? = SecurePreferencesHelper.readString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = NSEC_KEY,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun writeNsec(context: Context, nsec: String) = SecurePreferencesHelper.writeString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = NSEC_KEY,
        value = nsec,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun readRelays(context: Context): String? = SecurePreferencesHelper.readString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = RELAYS_KEY,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun writeRelays(context: Context, relays: List<String>) = SecurePreferencesHelper.writeString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = RELAYS_KEY,
        value = relays.joinToString("\n"),
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun readDarkTheme(context: Context, defaultValue: Boolean = false): Boolean = SecurePreferencesHelper.readBoolean(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = DARK_THEME_KEY,
        defaultValue = defaultValue,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun writeDarkTheme(context: Context, value: Boolean) = SecurePreferencesHelper.writeBoolean(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = DARK_THEME_KEY,
        value = value,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun readExternalSignerPubkey(context: Context): String? = SecurePreferencesHelper.readString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = EXTERNAL_SIGNER_PUBKEY_KEY,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun writeExternalSignerPubkey(context: Context, pubkey: String) = SecurePreferencesHelper.writeString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = EXTERNAL_SIGNER_PUBKEY_KEY,
        value = pubkey,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun readExternalSignerPackage(context: Context): String? = SecurePreferencesHelper.readString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = EXTERNAL_SIGNER_PACKAGE_KEY,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun writeExternalSignerPackage(context: Context, packageName: String) = SecurePreferencesHelper.writeString(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        key = EXTERNAL_SIGNER_PACKAGE_KEY,
        value = packageName,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )

    fun clearAuth(context: Context) = SecurePreferencesHelper.clear(
        context = context,
        prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
        fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
    )
}
