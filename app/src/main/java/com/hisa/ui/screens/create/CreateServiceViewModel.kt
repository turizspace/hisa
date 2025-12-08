package com.hisa.ui.screens.create

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Event
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.hisa.data.nostr.NostrEventSigner
import org.json.JSONArray

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
                val privKeyBytes: ByteArray? = if (!privateKeyHex.isNullOrBlank()) {
                    try {
                        privateKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }

                val eventJson = NostrEventSigner.signEvent(
                    kind = event.kind,
                    content = event.content,
                    tags = event.tags,
                    pubkey = event.pubkey,
                    privKey = privKeyBytes,
                    createdAt = event.createdAt
                )

                val nostrEvent = NostrEvent(
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

                nostrClient.publishEvent(nostrEvent)

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
