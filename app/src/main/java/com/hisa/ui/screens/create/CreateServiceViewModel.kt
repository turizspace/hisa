package com.hisa.ui.screens.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@HiltViewModel
class CreateServiceViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val signingService: NostrSigningService
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Use the centralized NostrEventSigner for canonical NIP-01 signing (Schnorr/BIP-340)

    fun createService(
        title: String,
        summary: String,
        description: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val userPubkey = pubKey

                val mutableTags = tags.toMutableList()
                if (mutableTags.none { it.firstOrNull() == "title" }) {
                    mutableTags.add(listOf("title", title))
                }
                if (mutableTags.none { it.firstOrNull() == "summary" }) {
                    mutableTags.add(listOf("summary", summary))
                }
                val serviceTags = mutableTags.distinct()

                signingService.signAndPublish(
                    nostrClient = nostrClient,
                    kind = 30402, // NIP-99 Classified Listings
                    content = description,
                    tags = serviceTags,
                    pubkeyHint = userPubkey,
                    privateKeyHexHint = privateKeyHex
                )

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createStall(
        title: String,
        summary: String,
        description: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // extract categories from tags (t tags)
                val categories = tags.mapNotNull { t ->
                    if (t.isNotEmpty() && t[0] == "t" && t.size > 1) t[1] else null
                }

                val stallId = UUID.randomUUID().toString()
                val metadata = JSONObject().apply {
                    put("id", stallId)
                    put("name", title)
                    put("description", summary.ifBlank { description })
                    put("currency", "SATS")
                    put("shipping", JSONArray())
                    if (summary.isNotBlank() || description.isNotBlank()) {
                        put("about", summary.ifBlank { description })
                    }
                }
                val stallTags = mutableListOf<List<String>>(listOf("d", stallId))
                categories.forEach { stallTags.add(listOf("t", it)) }

                signingService.signAndPublish(
                    nostrClient = nostrClient,
                    kind = 30017,
                    content = metadata.toString(),
                    tags = stallTags,
                    pubkeyHint = pubKey,
                    privateKeyHexHint = privateKeyHex
                )

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
