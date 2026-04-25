package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.toNostrEvent
import com.hisa.data.repository.FeedRepository
import com.hisa.util.cleanPubkeyFormat
import com.hisa.util.hexToByteArrayOrNull
import com.hisa.util.normalizeNostrPubkey
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class ShopViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val feedRepository: FeedRepository
) : ViewModel() {

    private val ownerPubkey = MutableStateFlow<String?>(null)

    val services: StateFlow<List<com.hisa.data.model.ServiceListing>> = combine(
        feedRepository.services,
        ownerPubkey
    ) { services, owner ->
        if (owner.isNullOrBlank()) {
            emptyList()
        } else {
            services.filter { it.pubkey == owner }.sortedByDescending { it.createdAt }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun subscribeToOwner(ownerHex: String) {
        ownerPubkey.value = normalizeNostrPubkey(ownerHex) ?: cleanPubkeyFormat(ownerHex)
        feedRepository.ensureStarted()
    }

    /**
     * Publish a NIP-09 deletion request (kind 5) referencing the provided service.
     * If `privateKeyHex` is null, the call will attempt external signing.
     */
    fun requestDeleteService(
        service: com.hisa.data.model.ServiceListing,
        privateKeyHex: String?,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val dTag = service.rawTags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) as? String
                val targetEventId = service.eventId

                val tags = mutableListOf<List<String>>()
                if (!targetEventId.isNullOrBlank()) tags.add(listOf("e", targetEventId))
                if (!dTag.isNullOrBlank()) {
                    tags.add(listOf("a", "30402:${service.pubkey}:$dTag"))
                }
                tags.add(listOf("k", "30402"))

                val privBytes = hexToByteArrayOrNull(privateKeyHex, 32)

                val signed = NostrEventSigner.signEvent(
                    kind = 5,
                    content = "",
                    tags = tags,
                    pubkey = service.pubkey,
                    privKey = privBytes
                )

                nostrClient.publishEvent(signed.toNostrEvent())
                onResult(true, null)
            } catch (e: Exception) {
                Timber.e(e, "Failed to publish deletion request")
                onResult(false, e.message)
            }
        }
    }
}
