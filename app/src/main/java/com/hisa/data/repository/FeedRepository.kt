package com.hisa.data.repository

import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

@Singleton
class FeedRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository
) {
    private val _services = MutableStateFlow<List<ServiceListing>>(emptyList())
    val services: StateFlow<List<ServiceListing>> = _services

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories

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

    fun refresh() {
        subscriptionListenerId?.let(subscriptionManager::unsubscribe)
        subscriptionListenerId = null
        started = false
        _services.value = emptyList()
        _categories.value = emptyList()
        ensureStarted()
    }

    private fun startSubscription() {
        _isLoading.value = true
        nostrClient.connect()
        subscriptionListenerId = subscriptionManager.subscribe(
            filter = SubscriptionManager.filterNIP99(limit = 200),
            onEvent = { event ->
                val service = ServiceRepository.parseServiceEvent(event.toJson().toString()) ?: return@subscribe
                upsertService(service)
                profileRepository.ensureProfiles(setOf(service.pubkey))
            },
            onEndOfStoredEvents = {
                _isLoading.value = false
            }
        )
    }

    private fun upsertService(service: ServiceListing) {
        ServiceRepository.cacheService(service)

        _services.update { current ->
            val next = current.associateBy { it.eventId }.toMutableMap()
            val existing = next[service.eventId]
            if (existing == null || service.createdAt >= existing.createdAt) {
                next[service.eventId] = service
            }
            val updated = next.values.sortedByDescending { it.createdAt }
            _categories.value = updated.flatMap { listing ->
                listing.rawTags
                    .filter { it.isNotEmpty() && it[0] == "t" }
                    .mapNotNull { it.getOrNull(1) as? String }
            }
                .distinct()
                .filter { it.toIntOrNull() == null }
                .sorted()
            updated
        }
    }
}
