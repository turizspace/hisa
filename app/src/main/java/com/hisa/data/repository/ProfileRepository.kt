package com.hisa.data.repository

import com.hisa.data.cache.ProfileCache
import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class ProfileRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileCache: ProfileCache,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val PROFILE_CHUNK_SIZE = 25
        private const val FLUSH_DELAY_MS = 75L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _profiles = MutableStateFlow<Map<String, Metadata>>(emptyMap())
    val profiles: StateFlow<Map<String, Metadata>> = _profiles

    private val latestProfileTimestamps = ConcurrentHashMap<String, Long>()
    private val subscribedPubkeys = ConcurrentHashMap.newKeySet<String>()
    private val pendingPubkeys = ConcurrentHashMap.newKeySet<String>()
    private val metadataSubscriptionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var flushJob: Job? = null

    fun ensureProfiles(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return

        var shouldFlush = false
        pubkeys.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it != "unknown" }
            .distinct()
            .forEach { pubkey ->
                profileCache.getCachedProfile(pubkey)?.let { cached ->
                    updateProfile(pubkey = pubkey, metadata = cached, createdAt = latestProfileTimestamps[pubkey] ?: 0L, persist = false)
                }

                if (!subscribedPubkeys.contains(pubkey) && pendingPubkeys.add(pubkey)) {
                    shouldFlush = true
                }
            }

        if (shouldFlush) {
            scheduleFlush()
        }
    }

    fun getCachedProfile(pubkey: String): Metadata? =
        profiles.value[pubkey] ?: profileCache.getCachedProfile(pubkey)

    private fun scheduleFlush() {
        synchronized(this) {
            if (flushJob?.isActive == true) return
            flushJob = appScope.launch(Dispatchers.IO) {
                delay(FLUSH_DELAY_MS)
                flushPendingPubkeys()
            }
        }
    }

    private fun flushPendingPubkeys() {
        val requestedPubkeys = pendingPubkeys.toList()
        pendingPubkeys.removeAll(requestedPubkeys.toSet())

        requestedPubkeys
            .filter { !subscribedPubkeys.contains(it) }
            .chunked(PROFILE_CHUNK_SIZE)
            .forEach { chunk ->
                if (chunk.isEmpty()) return@forEach

                val filter = org.json.JSONObject().apply {
                    put("kinds", org.json.JSONArray().put(0))
                    put("authors", org.json.JSONArray().apply {
                        chunk.forEach { put(it) }
                    })
                    put("limit", chunk.size * 2)
                }

                nostrClient.connect()
                val listenerId = subscriptionManager.subscribe(
                    filter = filter,
                    onEvent = { event ->
                        handleProfileEvent(event)
                    }
                )

                metadataSubscriptionIds.add(listenerId)
                subscribedPubkeys.addAll(chunk)
            }
    }

    private fun handleProfileEvent(event: NostrEvent) {
        if (event.kind != 0) return

        val metadata = try {
            json.decodeFromString<Metadata>(event.content)
        } catch (_: Exception) {
            return
        }

        updateProfile(
            pubkey = event.pubkey,
            metadata = metadata,
            createdAt = event.createdAt,
            persist = true
        )
    }

    private fun updateProfile(
        pubkey: String,
        metadata: Metadata,
        createdAt: Long,
        persist: Boolean
    ) {
        val currentTimestamp = latestProfileTimestamps[pubkey] ?: Long.MIN_VALUE
        if (createdAt < currentTimestamp) return

        latestProfileTimestamps[pubkey] = createdAt
        _profiles.update { current ->
            val existing = current[pubkey]
            if (existing == metadata) current else current + (pubkey to metadata)
        }

        if (persist) {
            profileCache.cacheProfile(pubkey, metadata)
        }
    }
}
