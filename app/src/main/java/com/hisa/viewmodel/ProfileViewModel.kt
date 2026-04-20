package com.hisa.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.cache.ProfileCache
import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.toNostrEvent
import com.hisa.util.hexToByteArrayOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val profileMetadataJson = Json { ignoreUnknownKeys = true }

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: com.hisa.data.nostr.SubscriptionManager,
    private val profileCache: ProfileCache,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val pubkey: String = requireNotNull(savedStateHandle.get<String>("pubkey")) {
        "pubkey parameter is required"
    }

    // Store all kind:0 metadata events for the pubkey
    private val _allMetadata = MutableStateFlow<List<Metadata>>(emptyList())
    val allMetadata: StateFlow<List<Metadata>> = _allMetadata

    // Backing state for latest metadata
    private val _metadata = MutableStateFlow<Metadata?>(null)
    val metadata: StateFlow<Metadata?> = _metadata

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus

    sealed class SaveStatus {
        object Idle : SaveStatus()
        object Success : SaveStatus()
        data class Error(val message: String) : SaveStatus()
    }

    private var profileSubscriptionId: String? = null

    init {
        // Load from cache first
        loadFromCache()
        // Then fetch from network
        fetchMetadata()
    }

    private fun loadFromCache() {
        val cachedMetadata = profileCache.getCachedProfile(pubkey)
        val cachedHistory = profileCache.getCachedProfileHistory(pubkey)
        
        if (cachedMetadata != null) {
            _metadata.value = cachedMetadata
        }
        if (cachedHistory.isNotEmpty()) {
            _allMetadata.value = cachedHistory
        }
    }

    fun refreshMetadata() {
        fetchMetadata()
    }

    private fun fetchMetadata() {
        viewModelScope.launch {
            try {
                profileSubscriptionId?.let { subscriptionManager.unsubscribe(it) }
                profileSubscriptionId = null
                // Only connect if not already connected
                if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                    nostrClient.connect()
                }
                // Subscribe to kind:0 events for this pubkey (after connection is ready)
                val filter = org.json.JSONObject().apply {
                    put("kinds", org.json.JSONArray().put(0))
                    put("authors", org.json.JSONArray().put(pubkey))
                }
                // Subscribe using SubscriptionManager so dedupe/throttling applies
                profileSubscriptionId = subscriptionManager.subscribe(filter, onEvent = { event ->
                    try {
                        if (event.kind == 0 && event.pubkey == pubkey) {
                            val content = event.content
                            val meta = try {
                                profileMetadataJson.decodeFromString<Metadata>(content)
                            } catch (e: Exception) {
                                android.util.Log.w("ProfileViewModel", "Failed to decode metadata content for pubkey $pubkey: ${e.localizedMessage}")
                                null
                            }
                            if (meta != null) {
                                if (_allMetadata.value.none { it == meta }) {
                                    val newHistory = _allMetadata.value + meta
                                    _allMetadata.value = newHistory
                                    _metadata.value = meta
                                    profileCache.cacheProfile(pubkey, meta)
                                    profileCache.cacheProfileHistory(pubkey, newHistory)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ProfileViewModel", "Error handling profile event: ${e.localizedMessage}")
                    }
                }, onEndOfStoredEvents = {
                    // no-op
                })
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Failed to fetch metadata: ${e.localizedMessage}")
                _saveStatus.value = SaveStatus.Error("Failed to fetch metadata: ${e.localizedMessage}")
            }
        }
    }

    fun updateMetadata(metadata: Metadata, privateKeyHex: String, pubkey: String) {
        _metadata.value = metadata
        val newHistory = _allMetadata.value + metadata
        _allMetadata.value = newHistory
        
        // Update cache
        profileCache.cacheProfile(pubkey, metadata)
        profileCache.cacheProfileHistory(pubkey, newHistory)
        
        publishMetadata(metadata, privateKeyHex, pubkey)
    }

    private fun publishMetadata(meta: Metadata, privateKeyHex: String, pubkey: String) {
        viewModelScope.launch {
            try {
                val content = Json.encodeToString(meta)
                val privateKey = hexToByteArrayOrNull(privateKeyHex, 32)
                    ?: error("Private key is missing!")
                val eventJson = com.hisa.data.nostr.NostrEventSigner.signEvent(
                    kind = 0,
                    content = content,
                    tags = emptyList(),
                    pubkey = pubkey,
                    privKey = privateKey
                )
                nostrClient.connect()
                nostrClient.publishEvent(eventJson.toNostrEvent())
                _saveStatus.value = SaveStatus.Success
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error("Failed to save: ${e.localizedMessage}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            profileSubscriptionId?.let { subscriptionManager.unsubscribe(it) }
        } catch (e: Exception) {
            android.util.Log.w("ProfileViewModel", "Failed to unsubscribe profile subscription: ${e.localizedMessage}")
        }
    }

    fun clearSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
    }
}
