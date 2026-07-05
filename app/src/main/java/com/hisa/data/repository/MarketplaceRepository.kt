package com.hisa.data.repository

import com.hisa.data.model.Stall
import com.hisa.data.cache.StallCacheStore
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrMarketplaceParser
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
import kotlinx.coroutines.launch

@Singleton
class MarketplaceRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository,
    private val stallCacheStore: StallCacheStore,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val EMIT_DEBOUNCE_MS = 120L
    }

    private val _stalls = MutableStateFlow<List<Stall>>(emptyList())
    val stalls: StateFlow<List<Stall>> = _stalls

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var subscriptionListenerId: String? = null
    private val stallsByKey = ConcurrentHashMap<String, Stall>()
    private val pendingProfilePubkeys = ConcurrentHashMap.newKeySet<String>()
    private val emitLock = Any()

    @Volatile
    private var emitJob: Job? = null

    @Volatile
    private var started = false

    init {
        restoreCachedStalls()
    }

    private fun restoreCachedStalls() {
        val cachedStalls = stallCacheStore.readStalls()
        if (cachedStalls.isNotEmpty()) {
            stallsByKey.clear()
            cachedStalls.forEach { stall ->
                stallsByKey[NostrMarketplaceParser.stallKey(stall.id, stall.ownerPubkey)] = stall
            }
            emitSnapshot()
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        startSubscription()
    }

    private fun startSubscription() {
        _isLoading.value = true
        nostrClient.connect()
        subscriptionListenerId = subscriptionManager.subscribe(
            filter = SubscriptionManager.filterNIP15Stalls(limit = 200),
            onEvent = { event ->
                val stall = NostrMarketplaceParser.parseStall(event) ?: return@subscribe
                upsertStall(stall)
            },
            onEndOfStoredEvents = {
                emitSnapshot()
                _isLoading.value = false
            }
        )
    }

    private fun upsertStall(stall: Stall) {
        val key = NostrMarketplaceParser.stallKey(stall.id, stall.ownerPubkey)
        val existing = stallsByKey[key]
        if (existing != null && stall.createdAt < existing.createdAt) {
            return
        }

        stallsByKey[key] = stall
        if (stall.ownerPubkey.isNotBlank()) {
            pendingProfilePubkeys.add(stall.ownerPubkey)
        }
        scheduleEmit()
    }

    private fun scheduleEmit() {
        synchronized(emitLock) {
            if (emitJob?.isActive == true) return
            emitJob = appScope.launch(Dispatchers.Default) {
                delay(EMIT_DEBOUNCE_MS)
                emitSnapshot()
            }
        }
    }

    private fun emitSnapshot() {
        synchronized(emitLock) {
            emitJob = null
        }

        val updated = stallsByKey.values.sortedByDescending { it.createdAt }
        _stalls.value = updated
        stallCacheStore.writeStalls(updated)

        val profilePubkeys = pendingProfilePubkeys.toSet()
        if (profilePubkeys.isNotEmpty()) {
            pendingProfilePubkeys.removeAll(profilePubkeys)
            profileRepository.ensureProfiles(profilePubkeys)
        }
    }
}
