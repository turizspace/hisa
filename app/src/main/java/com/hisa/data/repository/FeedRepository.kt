package com.hisa.data.repository

import com.hisa.data.cache.FeedCacheStore
import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.util.normalizeCategory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

@Singleton
class FeedRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository,
    private val feedCacheStore: FeedCacheStore,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val EMIT_DEBOUNCE_MS = 300L
        private const val CACHE_PERSIST_DEBOUNCE_MS = 1_000L
    }

    private val _services = MutableStateFlow<List<ServiceListing>>(emptyList())
    val services: StateFlow<List<ServiceListing>> = _services

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var subscriptionListenerId: String? = null
    private val subscriptionGeneration = AtomicLong(0L)
    private val servicesByReplaceableKey = ConcurrentHashMap<String, ServiceListing>()
    private val pendingProfilePubkeys = ConcurrentHashMap.newKeySet<String>()
    private val emitLock = Any()

    @Volatile
    private var emitJob: Job? = null
    @Volatile
    private var cachePersistJob: Job? = null

    @Volatile
    private var started = false
    @Volatile
    private var initialSnapshotComplete = false

    init {
        appScope.launch(Dispatchers.IO) {
            restoreCachedServices()
        }
    }

    private fun restoreCachedServices() {
        val cachedServices = feedCacheStore.readServices()
        if (cachedServices.isNotEmpty() && servicesByReplaceableKey.isEmpty()) {
            servicesByReplaceableKey.clear()
            cachedServices.forEach { service ->
                servicesByReplaceableKey[serviceKey(service)] = service
            }
            emitSnapshot()
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        startSubscription(subscriptionGeneration.incrementAndGet())
    }

    fun refresh() {
        subscriptionGeneration.incrementAndGet()
        subscriptionListenerId?.let(subscriptionManager::unsubscribe)
        subscriptionListenerId = null
        started = false
        pendingProfilePubkeys.clear()
        synchronized(emitLock) {
            initialSnapshotComplete = false
            emitJob?.cancel()
            emitJob = null
            cachePersistJob?.cancel()
            cachePersistJob = null
        }
        ensureStarted()
    }

    private fun startSubscription(generation: Long) {
        initialSnapshotComplete = false
        _isLoading.value = true
        nostrClient.connect()
        subscriptionListenerId = subscriptionManager.subscribe(
            filter = SubscriptionManager.filterNIP99(limit = 200),
            onEvent = { event ->
                if (subscriptionGeneration.get() == generation) {
                    ServiceEventParser.parse(event)?.let { service ->
                        upsertService(service, generation)
                    }
                }
            },
            onEndOfStoredEvents = {
                if (subscriptionGeneration.get() == generation) {
                    initialSnapshotComplete = true
                    emitSnapshot(generation)
                    persistCachedServices(generation)
                    _isLoading.value = false
                }
            }
        )
    }

    private fun upsertService(service: ServiceListing, generation: Long) {
        if (subscriptionGeneration.get() != generation) return
        ServiceRepository.cacheService(service)

        val key = serviceKey(service)
        val existing = servicesByReplaceableKey[key]
        if (existing != null && !isNewerReplacement(service, existing)) {
            return
        }

        servicesByReplaceableKey[key] = service
        if (service.pubkey.isNotBlank()) {
            pendingProfilePubkeys.add(service.pubkey)
        }
        if (initialSnapshotComplete) {
            scheduleEmit(generation)
            scheduleCachePersist(generation)
        }
    }

    private fun scheduleEmit(generation: Long) {
        synchronized(emitLock) {
            if (emitJob?.isActive == true) return
            emitJob = appScope.launch(Dispatchers.Default) {
                delay(EMIT_DEBOUNCE_MS)
                if (subscriptionGeneration.get() == generation) emitSnapshot(generation)
            }
        }
    }

    private fun scheduleCachePersist(generation: Long) {
        synchronized(emitLock) {
            if (cachePersistJob?.isActive == true) return
            cachePersistJob = appScope.launch(Dispatchers.Default) {
                delay(CACHE_PERSIST_DEBOUNCE_MS)
                if (subscriptionGeneration.get() == generation) persistCachedServices(generation)
            }
        }
    }

    private fun persistCachedServices(generation: Long? = null) {
        if (generation != null && subscriptionGeneration.get() != generation) return
        synchronized(emitLock) {
            cachePersistJob = null
        }
        feedCacheStore.writeServices(servicesByReplaceableKey.values.toList())
    }

    private fun emitSnapshot(generation: Long? = null) {
        if (generation != null && subscriptionGeneration.get() != generation) return
        synchronized(emitLock) {
            emitJob = null
        }

        val updated = servicesByReplaceableKey.values.sortedByDescending { it.createdAt }
        // A relay can reach EOSE before returning any events. Keep the last
        // known-good snapshot instead of turning a transient empty response
        // into an empty feed and overwriting the disk cache.
        if (updated.isEmpty() && _services.value.isNotEmpty()) return
        _services.value = updated
        _categories.value = updated.flatMap { listing ->
            listing.rawTags
                .filter { it.isNotEmpty() && it[0] == "t" }
                .mapNotNull { it.getOrNull(1) as? String }
                .map(::normalizeCategory)
        }
            .distinct()
            .filter { it.toIntOrNull() == null }
            .sorted()

        val profilePubkeys = pendingProfilePubkeys.toSet()
        if (profilePubkeys.isNotEmpty()) {
            pendingProfilePubkeys.removeAll(profilePubkeys)
            profileRepository.ensureProfiles(profilePubkeys)
        }
    }

    private fun serviceKey(service: ServiceListing): String {
        return service.dTag?.takeIf { it.isNotBlank() }
            ?.let { "30402:${service.pubkey}:$it" }
            ?: service.eventId
    }

    private fun isNewerReplacement(candidate: ServiceListing, existing: ServiceListing): Boolean {
        return candidate.createdAt > existing.createdAt ||
            (candidate.createdAt == existing.createdAt && candidate.eventId < existing.eventId)
    }
}
