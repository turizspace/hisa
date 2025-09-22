package com.hisa.ui.screens.create


import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Channel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrChannelUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

@HiltViewModel
class CreateChannelViewModel @Inject constructor(
    private val nostrClient: NostrClient
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var userPubkey: String? = null

    fun createChannel(
        name: String,
        about: String,
        picture: String,
        relays: List<String>,
        categories: List<String> = emptyList(),
        privateKey: String,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                Log.i("CreateChannelVM", "Creating channel: $name, relays=$relays, categories=$categories")
                val pkBytes = privateKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (pkBytes.isEmpty()) {
                    Log.e("CreateChannelVM", "Private key not found, cannot sign event.")
                    _error.value = "Private key not found."
                    return@launch
                }
                val pubkey = userPubkey ?: run {
                    Log.e("CreateChannelVM", "User pubkey not set.")
                    _error.value = "User pubkey not set."
                    return@launch
                }
                if (!pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                    Log.e("CreateChannelVM", "User pubkey is not a valid 64-character hex string: $pubkey")
                    _error.value = "User pubkey is not a valid hex string."
                    return@launch
                }
                val event = NostrChannelUtils.createChannel(
                    name = name,
                    about = about,
                    picture = picture,
                    relays = relays,
                    categories = categories,
                    privateKey = pkBytes,
                    pubkey = pubkey
                )
                Log.i("CreateChannelVM", "Channel event signed: ${event.toJson()}")
                nostrClient.publishEvent(event)
                Log.i("CreateChannelVM", "Channel event published to relays.")
                onSuccess(event.id)
            } catch (e: Exception) {
                Log.e("CreateChannelVM", "Error creating channel: ${e.message}", e)
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
