package com.hisa.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.cache.ProfileCache
import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import com.hisa.util.normalizeNostrPubkey
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
    private val signingService: NostrSigningService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val pubkey: String = requireNotNull(savedStateHandle.get<String>("pubkey")) {
        "pubkey parameter is required"
    }.let { normalizeNostrPubkey(it) ?: it }

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
        object Saving : SaveStatus()
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

    fun updateMetadata(
        metadata: Metadata,
        privateKeyHex: String?,
        pubkey: String,
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null
    ) {
        viewModelScope.launch {
            _saveStatus.value = SaveStatus.Saving
            try {
                val targetPubkey = normalizeNostrPubkey(pubkey)
                    ?: normalizeNostrPubkey(this@ProfileViewModel.pubkey)
                    ?: throw IllegalArgumentException("Invalid profile pubkey")
                val signingContext = signingService.resolveSigningContext(
                    pubkeyHint = targetPubkey,
                    privateKeyHexHint = privateKeyHex,
                    externalSignerPubkeyHint = externalSignerPubkey,
                    externalSignerPackageHint = externalSignerPackage
                )
                val signerPubkey = signingContext.requirePubkey()
                if (targetPubkey != signerPubkey) {
                    error("Active signer does not match this profile")
                }

                val content = Json.encodeToString(metadata)
                signingService.signAndPublish(
                    nostrClient = nostrClient,
                    signingContext = signingContext,
                    kind = 0,
                    content = content,
                    tags = emptyList()
                )

                _metadata.value = metadata
                val newHistory = if (_allMetadata.value.lastOrNull() == metadata) {
                    _allMetadata.value
                } else {
                    _allMetadata.value + metadata
                }
                _allMetadata.value = newHistory
                profileCache.cacheProfile(targetPubkey, metadata)
                profileCache.cacheProfileHistory(targetPubkey, newHistory)
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
