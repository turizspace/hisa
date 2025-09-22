package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
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
                            // Only subscribe once we're connected
                            nostrClient.registerMessageHandler { message ->
                                try {
                                    val arr = JSONArray(message)
                                    if (arr.length() > 2 && arr.getString(0) == "EVENT") {
                                        val eventJson = arr.getJSONObject(2).toString()
                                        val service = ServiceRepository.parseServiceEvent(eventJson)
                                        if (service != null) {
                                           
                                            // Check if service already exists before adding
                                            val exists = _services.value.any { 
                                                it.eventId == service.eventId && it.pubkey == service.pubkey 
                                            }
                                            if (!exists) {
                                                val updatedList = _services.value + service
                                                _services.value = updatedList
                                                // Update categories derived from topic tags ("t" tags)
                                                val allTags = updatedList.flatMap { svc ->
                                                    svc.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                                                        .mapNotNull { it.getOrNull(1) as? String }
                                                }
                                                    .distinct()
                                                    // Remove tags that are strictly integer values
                                                    .filter { tag -> tag.toIntOrNull() == null }
                                                    .sorted()
                                                _categories.value = allTags
                                                _isLoading.value = false // Data received, stop loading
                                            } else {
                                            }
                                        }
                                    } else if (arr.length() > 1 && arr.getString(0) == "EOSE") {
                                        // End of stored events
                                        if (_services.value.isEmpty()) {
                                            _isLoading.value = false // No data available
                                        }
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("FeedViewModel", "Error parsing message: ${e.message}", e)
                                }
                            }
                            // Subscribe to NIP-99 events
                            nostrClient.sendSubscription("feed", SubscriptionManager.filterNIP99().toString())
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
            
            // Resubscribe to get fresh data (this will automatically replace the existing subscription)
            nostrClient.sendSubscription("feed", SubscriptionManager.filterNIP99().toString())
        }
    }
}
