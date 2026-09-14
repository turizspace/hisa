package com.hisa.data.repository

import com.hisa.data.cache.ProfileCache
import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Singleton
class ProfileRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileCache: ProfileCache,
    private val metadataRepository: MetadataRepository,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val PROFILE_CHUNK_SIZE = 50
        private const val FLUSH_DELAY_MS = 75L
        private const val PROFILE_RELAY_CONCURRENCY = 4
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _profiles = MutableStateFlow<Map<String, Metadata>>(emptyMap())
    val profiles: StateFlow<Map<String, Metadata>> = _profiles
    private val profileFlows = ConcurrentHashMap<String, MutableStateFlow<Metadata?>>()

    fun profileFlow(pubkey: String): StateFlow<Metadata?> {
        val normalized = pubkey.trim().lowercase()
        return profileFlows.getOrPut(normalized) {
            MutableStateFlow(
                _profiles.value[normalized] ?: profileCache.getCachedProfile(normalized)
            )
        }
    }

    private val latestProfileTimestamps = ConcurrentHashMap<String, Long>()
    private val subscribedPubkeys = ConcurrentHashMap.newKeySet<String>()
    private val pendingPubkeys = ConcurrentHashMap.newKeySet<String>()
    private val metadataSubscriptionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var flushJob: Job? = null
    private val refreshGeneration = AtomicLong(0L)

    data class ProfileRefreshResult(
        val successful: Set<String>,
        val failed: Set<String>
    )

    fun ensureProfiles(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return

        nostrClient.refreshStoredRelays()

        var shouldFlush = false
        pubkeys.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it != "unknown" }
            .map(String::lowercase)
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

    /** Retry metadata lookup after additional relays have been discovered. */
    fun refreshProfiles(pubkeys: Set<String>) {
        refreshProfiles(pubkeys, refreshGeneration.incrementAndGet())
    }

    private fun refreshProfiles(pubkeys: Set<String>, generation: Long) {
        val normalized = pubkeys.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it != "unknown" }
            .map(String::lowercase)
            .toSet()
        if (normalized.isEmpty()) return

        subscribedPubkeys.removeAll(normalized)
        pendingPubkeys.removeAll(normalized)
        if (refreshGeneration.get() == generation) ensureProfiles(normalized)
    }

    suspend fun ensureFreshProfiles(pubkeys: Set<String>): ProfileRefreshResult {
        val normalized = pubkeys.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it != "unknown" }
            .map(String::lowercase)
            .toSet()
        if (normalized.isEmpty()) return ProfileRefreshResult(emptySet(), emptySet())

        val generation = refreshGeneration.incrementAndGet()
        val limiter = Semaphore(PROFILE_RELAY_CONCURRENCY)
        val initialResults = coroutineScope {
            normalized.map { pubkey ->
                async(Dispatchers.IO) {
                    limiter.withPermit {
                        val cached = profileCache.getCachedProfile(pubkey)
                        if (cached != null) {
                            updateProfile(
                                pubkey = pubkey,
                                metadata = cached,
                                createdAt = latestProfileTimestamps[pubkey] ?: 0L,
                                persist = false
                            )
                            return@withPermit true
                        }
                        runCatching {
                            metadataRepository.getMetadataForPubkey(
                                pubkey = pubkey,
                                refreshRelays = false
                            )
                        }.getOrNull()?.let { metadata ->
                            updateProfile(
                                pubkey = pubkey,
                                metadata = metadata,
                                createdAt = latestProfileTimestamps[pubkey] ?: 0L,
                                persist = true
                            )
                            true
                        } ?: false
                    }.let { pubkey to it }
                }
            }.awaitAll()
        }

        val successful = initialResults
            .filter { it.second }
            .mapTo(mutableSetOf()) { it.first }
        val failed = normalized - successful
        if (successful.isNotEmpty() && refreshGeneration.get() == generation) {
            refreshProfiles(successful, generation)
        }
        return ProfileRefreshResult(successful, failed)
    }

    fun getCachedProfile(pubkey: String): Metadata? {
        val normalized = pubkey.trim().lowercase()
        return profiles.value[normalized] ?: profileCache.getCachedProfile(normalized)
    }

    private fun scheduleFlush() {
        synchronized(this) {
            if (flushJob?.isActive == true) return
            flushJob = appScope.launch(Dispatchers.IO) {
                delay(FLUSH_DELAY_MS)
                flushPendingPubkeys(refreshGeneration.get())
            }
        }
    }

    private fun flushPendingPubkeys(generation: Long) {
        if (refreshGeneration.get() != generation) return
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
                        if (refreshGeneration.get() == generation) handleProfileEvent(event)
                    },
                    autoCloseOnEose = true
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
            pubkey = event.pubkey.lowercase(),
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
        profileFlows[pubkey]?.value = metadata

        if (persist) {
            profileCache.cacheProfile(pubkey, metadata)
        }
    }

}
