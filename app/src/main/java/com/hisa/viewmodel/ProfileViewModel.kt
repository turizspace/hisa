package com.hisa.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.cache.ProfileCache
import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val nostrClient: NostrClient,
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

    private fun fetchMetadata() {
        viewModelScope.launch {
            try {
                android.util.Log.d("ProfileViewModel", "Preparing to fetch profile metadata...")
                // Register message handler only once (idempotent)
                nostrClient.registerMessageHandler { message ->
                    try {
                        val element = kotlinx.serialization.json.Json.parseToJsonElement(message)
                        if (element is kotlinx.serialization.json.JsonArray) {
                            val type = element.getOrNull(0)?.toString()?.removeSurrounding("\"")
                            val subId = element.getOrNull(1)?.toString()?.removeSurrounding("\"")
                            if (type == "EVENT" && subId == "profile" && element.size > 2) {
                                val eventObj = element[2] as? kotlinx.serialization.json.JsonObject
                                if (eventObj != null && eventObj["kind"]?.toString() == "0" && eventObj["pubkey"]?.toString()?.removeSurrounding("\"") == pubkey) {
                                    val contentRaw = eventObj["content"]?.toString()?.removeSurrounding("\"") ?: run {
                                        android.util.Log.w("ProfileViewModel", "No content field in event: $eventObj")
                                        return@registerMessageHandler
                                    }
                                    android.util.Log.d("ProfileViewModel", "Raw content received: $contentRaw")
                                    val meta = try {
                                        // First try: direct parse with lenient settings
                                        try {
                                            Json { ignoreUnknownKeys = true }.decodeFromString<Metadata>(contentRaw)
                                        } catch (e: Exception) {
                                            // Second try: unescape and parse
                                            val unescaped = contentRaw.replace("\\\"", "\"")
                                                                     .replace("\"{", "{")
                                                                     .replace("}\"", "}")
                                            android.util.Log.d("ProfileViewModel", "Attempting with unescaped content: $unescaped")
                                            Json { ignoreUnknownKeys = true }.decodeFromString<Metadata>(unescaped)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("ProfileViewModel", "All attempts to decode metadata failed: ${e.localizedMessage}")
                                        null
                                    }
                                    if (meta != null) {
                                        if (_allMetadata.value.none { it == meta }) {
                                            android.util.Log.d("ProfileViewModel", "Profile metadata for pubkey $pubkey: $meta")
                                            val newHistory = _allMetadata.value + meta
                                            _allMetadata.value = newHistory
                                            _metadata.value = meta
                                            // Update cache
                                            profileCache.cacheProfile(pubkey, meta)
                                            profileCache.cacheProfileHistory(pubkey, newHistory)
                                        }
                                    }
                                } else {
                                    android.util.Log.d("ProfileViewModel", "Event is not kind 0 or pubkey mismatch: $eventObj")
                                }
                            }
                        } else if (element is kotlinx.serialization.json.JsonObject) {
                            if (element["kind"]?.toString() == "0") {
                                val content = element["content"]?.toString()?.removeSurrounding("\"") ?: run {
                                    android.util.Log.w("ProfileViewModel", "No content field in object: $element")
                                    return@registerMessageHandler
                                }
                                android.util.Log.d("ProfileViewModel", "Extracted content (object): $content")
                                val meta = try {
                                    Json.decodeFromString<Metadata>(content)
                                } catch (e: Exception) {
                                    android.util.Log.w("ProfileViewModel", "Failed to decode metadata from content: $content\nError: ${e.localizedMessage}")
                                    null
                                }
                                if (meta != null) {
                                    if (_allMetadata.value.none { it == meta }) {
                                        android.util.Log.d("ProfileViewModel", "Adding new metadata: $meta")
                                        val newHistory = _allMetadata.value + meta
                                        _allMetadata.value = newHistory
                                        _metadata.value = meta
                                        // Update cache
                                        profileCache.cacheProfile(pubkey, meta)
                                        profileCache.cacheProfileHistory(pubkey, newHistory)
                                    } else {
                                        android.util.Log.d("ProfileViewModel", "Duplicate metadata ignored: $meta")
                                    }
                                }
                            }
                        } else {
                            android.util.Log.w("ProfileViewModel", "Unknown message format: $element")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ProfileViewModel", "Error parsing message: ${e.localizedMessage}\nRaw message: $message")
                    }
                }
                // Only connect if not already connected
                if (nostrClient.connectionState.value != com.hisa.data.nostr.NostrClient.ConnectionState.CONNECTED) {
                    android.util.Log.d("ProfileViewModel", "Connecting to relays...")
                    nostrClient.connect()
                }
                // Subscribe to kind:0 events for this pubkey (after connection is ready)
                val filter = org.json.JSONObject().apply {
                    put("kinds", org.json.JSONArray().put(0))
                    put("authors", org.json.JSONArray().put(pubkey))
                }
                val filtersArray = org.json.JSONArray().put(filter)
                android.util.Log.d("ProfileViewModel", "Sending subscription for profile: $filtersArray")
                nostrClient.sendSubscription("profile", filtersArray.toString())
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
                require(!privateKeyHex.isNullOrBlank()) { "Private key is missing!" }
                val privateKey = hexStringToByteArray(privateKeyHex)
                val eventJson = com.hisa.data.nostr.NostrEventSigner.signEvent(
                    kind = 0,
                    content = content,
                    tags = emptyList(),
                    pubkey = pubkey,
                    privKey = privateKey
                )
                val nostrEvent = com.hisa.data.nostr.NostrEvent(
                    id = eventJson.getString("id"),
                    pubkey = eventJson.getString("pubkey"),
                    createdAt = eventJson.getLong("created_at"),
                    kind = eventJson.getInt("kind"),
                    tags = (0 until eventJson.getJSONArray("tags").length()).map { i ->
                        val tagArr = eventJson.getJSONArray("tags").getJSONArray(i)
                        (0 until tagArr.length()).map { tagArr.getString(it) }
                    },
                    content = eventJson.getString("content"),
                    sig = eventJson.getString("sig")
                )
                nostrClient.connect()
                nostrClient.publishEvent(nostrEvent)
                _saveStatus.value = SaveStatus.Success
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error("Failed to save: ${e.localizedMessage}")
            }
        }
    }

    // Helper to convert hex string to byte array
    private fun hexStringToByteArray(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in hex.indices step 2) {
            result[i / 2] = ((hex[i].digitToInt(16) shl 4) + hex[i + 1].digitToInt(16)).toByte()
        }
        return result
    }

    fun clearSaveStatus() {
        _saveStatus.value = SaveStatus.Idle
    }
}
