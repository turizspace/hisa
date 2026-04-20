package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.repository.ServiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import com.hisa.util.cleanPubkeyFormat
import com.hisa.util.hexToByteArrayOrNull
import com.hisa.util.normalizeNostrPubkey
import org.json.JSONArray
import com.hisa.data.nostr.toNostrEvent

@HiltViewModel
class ShopViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) : ViewModel() {

    private val _services = MutableStateFlow<List<ServiceListing>>(emptyList())
    val services: StateFlow<List<ServiceListing>> = _services

    private var subscriptionId: String? = null
    private var isSubscribed = false

    fun subscribeToOwner(ownerHex: String) {
        if (isSubscribed) return
        isSubscribed = true
        viewModelScope.launch {
            nostrClient.connect()
            val normalizedOwner = normalizeNostrPubkey(ownerHex) ?: cleanPubkeyFormat(ownerHex)

            // Try to fetch preferred relays for the owner first, then subscribe using those relays
            val prefEventDeferred = CompletableDeferred<com.hisa.data.nostr.NostrEvent?>()
            val prefSubId = subscriptionManager.subscribeToPreferredRelays(normalizedOwner, onEvent = { ev ->
                try { prefEventDeferred.complete(ev) } catch (_: Exception) {}
            }, onEndOfStoredEvents = {})

            // Wait briefly for preferred-relays event (non-blocking) and apply them if present
            val prefEvent = withTimeoutOrNull(3000L) { prefEventDeferred.await() }
            if (prefEvent != null) {
                try {
                    val content = prefEvent.content ?: ""
                    val relays = content.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (relays.isNotEmpty()) {
                        try { nostrClient.updateRelays(relays) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            try { subscriptionManager.unsubscribe(prefSubId) } catch (_: Exception) {}

            // Log current relays and the filter we will use
            try { Timber.i("ShopViewModel: configured relays=%s", nostrClient.configuredRelays()) } catch (_: Exception) {}

            val filter = SubscriptionManager.filterNIP99().apply {
                try {
                    put("authors", JSONArray().put(normalizedOwner))
                } catch (_: Exception) {}
            }
            try { Timber.i("ShopViewModel: subscribing with filter=%s", filter.toString()) } catch (_: Exception) {}

            var incomingLogCount = 0
            subscriptionId = subscriptionManager.subscribe(filter, onEvent = { event: NostrEvent ->
                try {
                    incomingLogCount += 1
                    // Log basic event info for the first few events to aid debugging
                    if (incomingLogCount <= 5) {
                        val snippet = (event.content ?: "").take(200).replace("\n", " ")
                        Timber.i("ShopViewModel: incoming event #%d id=%s kind=%d pubkey=%s snippet=%s", incomingLogCount, event.id, event.kind, event.pubkey, snippet)
                    }
                } catch (_: Exception) {}
                try {
                    val eventJsonObj = org.json.JSONObject().apply {
                        put("id", event.id)
                        put("pubkey", event.pubkey)
                        put("created_at", event.createdAt)
                        put("kind", event.kind)
                        val tagsArray = org.json.JSONArray()
                        event.tags.forEach { tagList ->
                            val inner = org.json.JSONArray()
                            tagList.forEach { inner.put(it) }
                            tagsArray.put(inner)
                        }
                        put("tags", tagsArray)
                        put("content", event.content)
                        put("sig", event.sig)
                    }
                    val service = ServiceRepository.parseServiceEvent(eventJsonObj.toString())
                    if (service != null) {
                        val exists = _services.value.any { it.eventId == service.eventId && it.pubkey == service.pubkey }
                        if (!exists) {
                            _services.value = _services.value + service
                            ServiceRepository.cacheService(service)
                        }
                    }
                } catch (_: Exception) {
                }
            })
        }
    }

    /**
     * Publish a NIP-09 deletion request (kind 5) referencing the provided service.
     * If `privateKeyHex` is null, the call will attempt external signing.
     */
    fun requestDeleteService(service: ServiceListing, privateKeyHex: String?, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            try {
                val dTag = service.rawTags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) as? String
                val targetEventId = service.eventId

                val tags = mutableListOf<List<String>>()
                // Include e tag(s) referencing the specific event id
                if (!targetEventId.isNullOrBlank()) tags.add(listOf("e", targetEventId))
                // If we have a replaceable identifier, include an 'a' tag per NIP-09 to reference the replaceable namespace
                if (!dTag.isNullOrBlank()) {
                    tags.add(listOf("a", "30402:${service.pubkey}:$dTag"))
                }
                // Include k tag indicating kinds referenced
                tags.add(listOf("k", "30402"))

                val content = "" // optional reason; UI can be extended to collect a reason

                val privBytes = hexToByteArrayOrNull(privateKeyHex, 32)

                val signed = com.hisa.data.nostr.NostrEventSigner.signEvent(
                    kind = 5,
                    content = content,
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

    override fun onCleared() {
        super.onCleared()
        subscriptionId?.let { subscriptionManager.unsubscribe(it); subscriptionId = null }
    }
}
