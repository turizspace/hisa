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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import com.hisa.util.cleanPubkeyFormat
import org.bitcoinj.core.Bech32
import org.json.JSONArray

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
            // ensure connection handled in client state; subscribe through SubscriptionManager
            // Normalize owner pubkey: accept hex or bech32 npub
            fun npubToHex(npub: String): String? {
                return try {
                    val bech = Bech32.decode(npub)
                    if (bech.hrp != "npub") return null
                    val data = bech.data
                    var acc = 0
                    var bits = 0
                    val out = mutableListOf<Byte>()
                    for (v in data) {
                        val value = v.toInt() and 0xff
                        acc = (acc shl 5) or value
                        bits += 5
                        while (bits >= 8) {
                            bits -= 8
                            out.add(((acc shr bits) and 0xff).toByte())
                        }
                    }
                    if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) return null
                    out.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    null
                }
            }

            val normalizedOwner = when {
                ownerHex.startsWith("npub", true) -> npubToHex(ownerHex) ?: cleanPubkeyFormat(ownerHex)
                else -> cleanPubkeyFormat(ownerHex)
            }

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

    override fun onCleared() {
        super.onCleared()
        subscriptionId?.let { subscriptionManager.unsubscribe(it); subscriptionId = null }
    }
}
