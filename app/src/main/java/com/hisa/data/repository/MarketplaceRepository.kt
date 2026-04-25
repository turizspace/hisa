package com.hisa.data.repository

import com.hisa.data.model.Stall
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrMarketplaceParser
import com.hisa.data.nostr.SubscriptionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

@Singleton
class MarketplaceRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository
) {
    private val _stalls = MutableStateFlow<List<Stall>>(emptyList())
    val stalls: StateFlow<List<Stall>> = _stalls

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var subscriptionListenerId: String? = null

    @Volatile
    private var started = false

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
                profileRepository.ensureProfiles(setOf(stall.ownerPubkey))
            },
            onEndOfStoredEvents = {
                _isLoading.value = false
            }
        )
    }

    private fun upsertStall(stall: Stall) {
        _stalls.update { current ->
            val next = current.associateBy {
                NostrMarketplaceParser.stallKey(it.id, it.ownerPubkey)
            }.toMutableMap()

            val key = NostrMarketplaceParser.stallKey(stall.id, stall.ownerPubkey)
            val existing = next[key]
            if (existing == null || stall.createdAt >= existing.createdAt) {
                next[key] = stall
            }

            next.values.sortedByDescending { it.createdAt }
        }
    }
}
