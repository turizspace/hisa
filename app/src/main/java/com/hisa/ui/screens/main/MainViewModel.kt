package com.hisa.ui.screens.main

import androidx.lifecycle.viewModelScope
import com.hisa.base.BaseViewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) : BaseViewModel() {
    // Hold a strong reference to the registered handler so it can be unregistered
    private var messageHandlerRef: ((String) -> Unit)? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    init {
        // Observe Nostr connection state
        viewModelScope.launch {
            nostrClient.connectionState.collect { state ->
                _isConnected.value = state == NostrClient.ConnectionState.CONNECTED
            }
        }

        // Set up message handler
    // Message handling is now centralized in SubscriptionManager. No direct registration here.
    }

    private fun handleNostrMessage(message: String) {
        // Implement message handling logic
    }

    fun connect() = launchWithErrorHandling {
        nostrClient.connect()
    }

    fun disconnect() = launchWithErrorHandling {
        nostrClient.disconnect()
    }

    override fun onCleared() {
        super.onCleared()
        // Unregister message handler to avoid leaks / duplicate handlers
    // Nothing to unregister; SubscriptionManager handles message dispatch.
        disconnect()
    }

    // Hold a strong reference to the registered handler so it can be unregistered
}
