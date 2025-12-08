package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.repository.ServiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {
    private val _services = MutableStateFlow<List<ServiceListing>>(emptyList())
        val services: StateFlow<List<ServiceListing>> = _services

    private val _categories = MutableStateFlow<List<String>>(emptyList())
    val categories: StateFlow<List<String>> = _categories
    
    // Persist selected category so FeedTab can restore it after navigation
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

        private val _isLoading = MutableStateFlow(true)
        val isLoading: StateFlow<Boolean> = _isLoading

        // Track whether we've subscribed already to avoid duplicate subscriptions
        private var isSubscribed = false

        /**
         * Public method to subscribe to the feed. Safe to call multiple times; will only subscribe once.
         */
        fun subscribeToFeed() {
            if (isSubscribed) return
            isSubscribed = true
            connectAndFetch()
        }

    private var feedSubscriptionId: String? = null

    private fun connectAndFetch() {
        viewModelScope.launch {
            _isLoading.value = true
            _services.value = emptyList() // Reset services list
            nostrClient.connect()
            
            // Monitor connection state
            viewModelScope.launch {
                nostrClient.connectionState.collect { state ->
                    when (state) {
                        NostrClient.ConnectionState.CONNECTED -> {
                            // Use SubscriptionManager.subscribe so dedupe/throttling applies
                            if (feedSubscriptionId == null) {
                                val filterObj = SubscriptionManager.filterNIP99()
                                feedSubscriptionId = subscriptionManager.subscribe(
                                    filter = filterObj,
                                    onEvent = { event: NostrEvent ->
                                        try {
                                            // Reconstruct a JSONObject similar to the raw event for parsing
                                            val eventJsonObj = org.json.JSONObject().apply {
                                                put("id", event.id)
                                                put("pubkey", event.pubkey)
                                                put("created_at", event.createdAt)
                                                put("kind", event.kind)
                                                // tags as array of arrays
                                                val tagsArray = org.json.JSONArray()
                                                event.tags.forEach { tagList ->
                                                    val inner = org.json.JSONArray()
                                                    tagList.forEach { inner.put(it) }
                                                    tagsArray.put(inner)
                                                }
                                                put("tags", tagsArray)
                                                put("content", event.content)
                                                put("sig", event.sig)
                                            }
                                            val service = ServiceRepository.parseServiceEvent(eventJsonObj.toString())
                                            if (service != null) {
                                                val exists = _services.value.any { it.eventId == service.eventId && it.pubkey == service.pubkey }
                                                if (!exists) {
                                                    val updatedList = _services.value + service
                                                    _services.value = updatedList
                                                    // Update categories derived from topic tags ("t" tags)
                                                    val allTags = updatedList.flatMap { svc ->
                                                        svc.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                                                            .mapNotNull { it.getOrNull(1) as? String }
                                                    }
                                                        .distinct()
                                                        .filter { tag -> tag.toIntOrNull() == null }
                                                        .sorted()
                                                    _categories.value = allTags
                                                    _isLoading.value = false
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("FeedViewModel", "Error handling event: ${e.message}", e)
                                        }
                                    },
                                    onEndOfStoredEvents = {}
                                )
                            }
                        }
                        NostrClient.ConnectionState.ERROR -> {
                            _isLoading.value = false // Show error state
                        }
                        else -> {
                            // Keep loading for other states
                        }
                    }
                }
            }
        }
    }
    fun refreshFeed() {
        viewModelScope.launch {
            _isLoading.value = true
            _services.value = emptyList() // Clear current services
            _categories.value = emptyList()
            // Resubscribe to get fresh data (unsubscribe then subscribe again)
            feedSubscriptionId?.let { subscriptionManager.unsubscribe(it); feedSubscriptionId = null }
            val filterObj = SubscriptionManager.filterNIP99()
            feedSubscriptionId = subscriptionManager.subscribe(
                filter = filterObj,
                onEvent = { event: NostrEvent ->
                    try {
                        val eventJsonObj = org.json.JSONObject().apply {
                            put("id", event.id)
                            put("pubkey", event.pubkey)
                            put("created_at", event.createdAt)
                            put("kind", event.kind)
                            val tagsArray = org.json.JSONArray()
                            event.tags.forEach { tagList ->
                                val inner = org.json.JSONArray()
                                tagList.forEach { inner.put(it) }
                                tagsArray.put(inner)
                            }
                            put("tags", tagsArray)
                            put("content", event.content)
                            put("sig", event.sig)
                        }
                        val service = ServiceRepository.parseServiceEvent(eventJsonObj.toString())
                        if (service != null) {
                            val exists = _services.value.any { it.eventId == service.eventId && it.pubkey == service.pubkey }
                            if (!exists) {
                                val updatedList = _services.value + service
                                _services.value = updatedList
                                val allTags = updatedList.flatMap { svc ->
                                    svc.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                                        .mapNotNull { it.getOrNull(1) as? String }
                                }
                                    .distinct()
                                    .filter { tag -> tag.toIntOrNull() == null }
                                    .sorted()
                                _categories.value = allTags
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("FeedViewModel", "Error handling refreshed event: ${e.message}", e)
                    }
                },
                onEndOfStoredEvents = {}
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        feedSubscriptionId?.let { subscriptionManager.unsubscribe(it); feedSubscriptionId = null }
    }
}
