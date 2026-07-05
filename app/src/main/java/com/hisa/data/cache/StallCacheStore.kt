package com.hisa.data.cache

import android.content.Context
import com.hisa.data.model.Stall
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StallCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "stalls_cache",
        fallbackPrefsName = "stalls_cache_fallback"
    )

    fun readStalls(): List<Stall> {
        val json = sharedPreferences.getString(KEY_STALLS, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString<List<Stall>>(json)
        }.getOrDefault(emptyList())
    }

    fun writeStalls(stalls: List<Stall>) {
        val json = Json.encodeToString(stalls)
        sharedPreferences.edit()
            .putString(KEY_STALLS, json)
            .apply()
    }

    fun clear() {
        sharedPreferences.edit().remove(KEY_STALLS).apply()
    }

    companion object {
        private const val KEY_STALLS = "stalls"
    }
}
