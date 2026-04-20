package com.hisa.viewmodel

import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import androidx.lifecycle.ViewModel
import com.hisa.data.model.ServiceListing
import com.hisa.data.repository.ServiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ServiceDetailViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {
    private val _service = MutableStateFlow<ServiceListing?>(null)
    val service: StateFlow<ServiceListing?> = _service
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _rawEvent = MutableStateFlow<String?>(null)
    val rawEvent: StateFlow<String?> = _rawEvent

    fun loadService(eventId: String, pubkey: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                nostrClient.connect()
                val service = ServiceRepository.getServiceByEventId(eventId)
                    ?: ServiceRepository.fetchServiceByEventId(
                        eventId = eventId,
                        authorPubkey = pubkey,
                        subscriptionManager = subscriptionManager
                    )
                _service.value = service
                
                service?.rawEvent?.let { rawEvent ->
                    try {
                        _rawEvent.value = org.json.JSONObject(rawEvent).toString(4)
                    } catch (e: Exception) {
                        _rawEvent.value = rawEvent
                    }
                }
                
                _isLoading.value = false
                if (_service.value == null) _error.value = "Service not found."
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Unknown error"
                _isLoading.value = false
            }
        }
    }
}
