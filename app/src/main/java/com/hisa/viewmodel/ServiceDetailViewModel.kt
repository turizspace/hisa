package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ServiceListing
import com.hisa.data.repository.ServiceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServiceDetailViewModel : ViewModel() {
    private val _service = MutableStateFlow<ServiceListing?>(null)
    val service: StateFlow<ServiceListing?> = _service
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _rawEvent = MutableStateFlow<String?>(null)
    val rawEvent: StateFlow<String?> = _rawEvent

    fun loadService(eventId: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val service = ServiceRepository.getServiceByEventId(eventId)
                _service.value = service
                
                // Store and log raw event for debugging
                service?.rawEvent?.let { rawEvent ->
                    try {
                        val prettyJson = org.json.JSONObject(rawEvent).toString(4)
                        _rawEvent.value = prettyJson
                        android.util.Log.d("ServiceDetail", "Fetched Event JSON:\n$prettyJson")
                    } catch (e: Exception) {
                        _rawEvent.value = rawEvent
                        android.util.Log.e("ServiceDetail", "Error formatting JSON: ${e.message}")
                        android.util.Log.d("ServiceDetail", "Raw event: $rawEvent")
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
