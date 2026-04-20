package com.hisa.ui.screens.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.NostrStallUtils
import com.hisa.data.nostr.toNostrEvent
import com.hisa.util.hexToByteArrayOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CreateServiceViewModel @Inject constructor(
    private val nostrClient: NostrClient
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

                val serviceTags = listOf(
                    listOf("title", title),
                    listOf("summary", summary)
                ) + tags

                val event = NostrEvent(
                    id = "", // Will be set during signing
                    pubkey = userPubkey,
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 30402, // NIP-99 Classified Listings
                    content = description,
                    tags = serviceTags,
                    sig = "" // Will be set during signing
                )

                // If privateKeyHex is null or blank, treat this as an external-signer login
                // and pass null so NostrEventSigner will delegate to ExternalSignerManager.
                val privKeyBytes = hexToByteArrayOrNull(privateKeyHex, 32)

                val eventJson = NostrEventSigner.signEvent(
                    kind = event.kind,
                    content = event.content,
                    tags = event.tags,
                    pubkey = event.pubkey,
                    privKey = privKeyBytes,
                    createdAt = event.createdAt
                )

                nostrClient.publishEvent(eventJson.toNostrEvent())

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
                val privKeyBytes = hexToByteArrayOrNull(privateKeyHex, 32)
                // extract categories from tags (t tags)
                val categories = tags.mapNotNull { t ->
                    if (t.isNotEmpty() && t[0] == "t" && t.size > 1) t[1] else null
                }

                val stallEvent = NostrStallUtils.createStall(
                    name = title,
                    about = summary,
                    picture = "",
                    relays = emptyList<String>(),
                    categories = categories,
                    location = null,
                    geohash = null,
                    privateKey = privKeyBytes,
                    pubkey = pubKey
                )

                nostrClient.publishEvent(stallEvent)

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
