package com.hisa.data.cache

import android.content.Context
import com.hisa.data.model.ServiceListing
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FeedCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "feed_cache",
        fallbackPrefsName = "feed_cache_fallback"
    )

    fun readServices(): List<ServiceListing> {
        val json = sharedPreferences.getString(KEY_SERVICES, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString<List<ServiceListing>>(json)
        }.getOrDefault(emptyList())
    }

    fun writeServices(services: List<ServiceListing>) {
        val json = Json.encodeToString(services)
        sharedPreferences.edit()
            .putString(KEY_SERVICES, json)
            .apply()
    }

    fun clear() {
        sharedPreferences.edit().remove(KEY_SERVICES).apply()
    }

    companion object {
        private const val KEY_SERVICES = "services"
    }
}
