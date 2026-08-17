package com.hisa.data.cache

import android.content.Context
import com.hisa.data.model.ServiceListing
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

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
        if (sharedPreferences.getInt(KEY_CACHE_VERSION, 0) != CACHE_VERSION) {
            return emptyList()
        }

        val json = sharedPreferences.getString(KEY_SERVICES, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString<List<CachedServiceListing>>(json)
                .map { cached ->
                    ServiceListing(
                        eventId = cached.eventId,
                        dTag = cached.dTag,
                        title = cached.title,
                        summary = cached.summary,
                        content = cached.content,
                        price = cached.price,
                        tags = cached.tags,
                        pubkey = cached.pubkey,
                        rawTags = emptyList(),
                        rawEvent = null,
                        createdAt = cached.createdAt
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun writeServices(services: List<ServiceListing>) {
        val sanitized = services
            .take(MAX_CACHED_SERVICES)
            .map { it.toCachedService() }

        runCatching {
            val json = Json.encodeToString(sanitized)
            sharedPreferences.edit()
                .putString(KEY_SERVICES, json)
                .putInt(KEY_CACHE_VERSION, CACHE_VERSION)
                .apply()
        }.onFailure { error ->
            Timber.w(error, "Failed to cache feed services")
        }
    }

    fun clear() {
        sharedPreferences.edit()
            .remove(KEY_SERVICES)
            .remove(KEY_CACHE_VERSION)
            .apply()
    }

    companion object {
        private const val KEY_SERVICES = "services"
        private const val KEY_CACHE_VERSION = "cache_version"
        private const val CACHE_VERSION = 2
        private const val MAX_CACHED_SERVICES = 100
    }

    @Serializable
    private data class CachedServiceListing(
        val eventId: String,
        val dTag: String?,
        val title: String,
        val summary: String,
        val content: String?,
        val price: String,
        val tags: List<String>,
        val pubkey: String,
        val createdAt: Long
    )

    private fun ServiceListing.toCachedService(): CachedServiceListing {
        return CachedServiceListing(
            eventId = eventId,
            dTag = dTag,
            title = title.take(240),
            summary = summary.take(2000),
            content = content?.take(4000),
            price = price.take(120),
            tags = tags.take(20),
            pubkey = pubkey,
            createdAt = createdAt
        )
    }
}
