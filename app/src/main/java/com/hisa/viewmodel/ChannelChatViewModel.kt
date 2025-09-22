package com.hisa.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ChannelMessage
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.model.Metadata
import com.hisa.data.repository.MetadataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

class ChannelChatViewModel(
    private val channelId: String,
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val privateKey: ByteArray?,
    private val userPubkey: String,
    private val externalSignerPubkey: String? = null,
    private val externalSignerPackage: String? = null
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChannelMessage>>(emptyList())
    val messages: StateFlow<List<ChannelMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _profileMetadata = MutableStateFlow<Map<String, Metadata>>(emptyMap())
    val profileMetadata: StateFlow<Map<String, Metadata>> = _profileMetadata.asStateFlow()

    private val metadataRepository = MetadataRepository.instance
    // Track profile metadata subscription id (used to fetch kind=0 events for authors)
    // We'll recreate this subscription when new missing pubkeys are discovered.

    private val messagesMap = ConcurrentHashMap<String, ChannelMessage>()
    private var channelSubscriptionId: String? = null
    private var metadataSubscriptionId: String? = null
    private var loadingTimeoutJob: Job? = null
    // Track EOSE responses to know when relays have finished delivering stored events
    private val eoseReceivedFromRelays = ConcurrentHashMap<String, Boolean>()
    private var expectedEoseCount = 0

    init {
        startChannelSubscription()
    }
    
    private fun handleChannelCreation(event: NostrEvent) {
        try {
            Log.d("ChannelChatViewModel", "Processing channel creation event: ${event.id}")
            // Handle channel creation metadata if needed
        } catch (e: Exception) {
            Log.e("ChannelChatViewModel", "Failed to handle channel creation", e)
        }
    }

    private fun handleChannelMetadata(event: NostrEvent) {
        try {
            Log.d("ChannelChatViewModel", "Processing channel metadata event: ${event.id}")
            // Process channel metadata updates
            // This ensures we have the latest channel information
        } catch (e: Exception) {
            Log.e("ChannelChatViewModel", "Failed to handle channel metadata", e)
        }
    }

    private fun startChannelSubscription() {
        viewModelScope.launch {
            try {
                // Clear any existing subscription
                channelSubscriptionId?.let {
                    Log.d("ChannelChatViewModel", "Clearing existing subscription: $it")
                    subscriptionManager.unsubscribe(it)
                    channelSubscriptionId = null
                }
                
                // Clear existing messages and reset state
                messagesMap.clear()
                _messages.value = emptyList()
                _isLoading.value = true
                
                // Cancel any existing timeout job
                loadingTimeoutJob?.cancel()
                
                // Start new subscription
                delay(100) // Brief delay to ensure cleanup is complete
                subscribeToChannelMessages()
                
            } catch (e: Exception) {
                Log.e("ChannelChatViewModel", "Error in startChannelSubscription", e)
                _isLoading.value = false
            }
        }
    }

    private fun subscribeToChannelMessages() {
        viewModelScope.launch {
            try {
                Log.d("ChannelChatViewModel", "Subscribing to messages and metadata for channel: $channelId")
                
                // Create filters for both channel metadata (40, 41) and messages (42)
                val metadataFilter = JSONObject().apply {
                    put("kinds", JSONArray().apply { 
                        put(40)  // Channel creation
                        put(41)  // Channel metadata
                    })
                    put("ids", JSONArray().apply { put(channelId) })
                }
                
                val messagesFilter = JSONObject().apply {
                    put("kinds", JSONArray().apply { put(42) })  // Channel message kind
                    put("#e", JSONArray().apply { put(channelId) })  // Channel reference
                    put("limit", 100)  // Limit to last 100 messages initially
                }

                Timber.tag("ChannelChatViewModel").d("Created metadata filter: $metadataFilter")
                Timber.tag("ChannelChatViewModel").d("Created messages filter: $messagesFilter")

                // Subscribe to channel metadata first
                val metadataSubId = subscriptionManager.subscribe(
                    filter = metadataFilter,
                    onEvent = { event ->
                        Log.d("ChannelChatViewModel", "Received metadata event: kind=${event.kind}, id=${event.id}")
                        when (event.kind) {
                            40 -> handleChannelCreation(event)
                            41 -> handleChannelMetadata(event)
                        }
                    },
                    onEndOfStoredEvents = {
                        Log.d("ChannelChatViewModel", "Received EOSE for channel metadata ${channelId}")
                    }
                )
                
                // Then subscribe to channel messages
                channelSubscriptionId = subscriptionManager.subscribe(
                    filter = messagesFilter,
                    onEvent = { event ->
                        Log.d("ChannelChatViewModel", "Received message event: kind=${event.kind}, id=${event.id}")
                        if (event.kind == 42) {  // Verify it's a channel message
                            handleChannelMessage(event)
                        }
                    },
                    onEndOfStoredEvents = {
                        Log.d("ChannelChatViewModel", "Received EOSE for channel messages ${channelId}. Current message count: ${messagesMap.size}")
                        // Mark that one relay sent EOSE for this subscription
                        val relayKey = "eose_${channelId}_${System.nanoTime()}"
                        eoseReceivedFromRelays[relayKey] = true

                        // If we have received EOSE from all expected relays, stop loading. Otherwise wait for others or timeout.
                        val received = eoseReceivedFromRelays.size
                        if (expectedEoseCount <= 0) {
                            // If we don't know expected count, compute from client configured relays
                            expectedEoseCount = try { nostrClient.configuredRelayCount() } catch (e: Exception) { 1 }
                        }

                        if (received >= expectedEoseCount || messagesMap.isNotEmpty()) {
                            _isLoading.value = false
                            loadingTimeoutJob?.cancel()
                        } else {
                            Log.d("ChannelChatViewModel", "EOSE received ($received/$expectedEoseCount). Waiting for more or timeout.")
                        }
                    }
                )

                // Start a fallback timeout to avoid stuck loading state if EOSE never arrives
                loadingTimeoutJob?.cancel()
                loadingTimeoutJob = viewModelScope.launch {
                    try {
                        // Scale timeout with relay count; at least 3s, up to 15s
                        val relayCount = try { nostrClient.configuredRelayCount() } catch (e: Exception) { 1 }
                        val timeout = (3000L + (relayCount * 1500L)).coerceAtMost(15_000L)
                        delay(timeout)
                        if (_isLoading.value) {
                            Log.w("ChannelChatViewModel", "Loading timeout reached for channel: $channelId after ${timeout}ms, clearing loading state")
                            _isLoading.value = false
                        }
                    } catch (e: Exception) {
                        // Job cancelled or error, ignore
                    }
                }
            } catch (e: Exception) {
                Log.e("ChannelChatViewModel", "Failed to subscribe to channel messages", e)
                _isLoading.value = false
            }
        }
    }

    private fun handleChannelMessage(event: NostrEvent) {
        try {
            Log.d("ChannelChatViewModel", "Received channel message event: ${event.id}, kind=${event.kind}, createdAt=${event.createdAt}")
            
            // Find the root channel reference - first try with root marker, then without
            val rootChannelId = event.tags.find {
                it.size >= 4 && it[0] == "e" && it[3] == "root"
            }?.get(1) ?: event.tags.find {
                it.size >= 2 && it[0] == "e"
            }?.get(1) ?: run {
                Log.d("ChannelChatViewModel", "No root channel reference found in event: ${event.id}")
                return
            }
            
            // Verify this message belongs to our current channel
            if (rootChannelId != channelId) {
                Log.d("ChannelChatViewModel", "Skipping message for different channel. Got ${rootChannelId}, expected ${channelId}")
                return
            }

            // Find reply reference if it exists
            val replyTo = event.tags.find {
                it.size >= 4 && it[0] == "e" && it[3] == "reply"
            }?.get(1)

            // Parse content from JSON or use raw content if not JSON
            val messageContent = try {
                val jsonContent = JSONObject(event.content)
                jsonContent.optString("content", event.content)
            } catch (e: Exception) {
                event.content
            }

            val message = ChannelMessage(
                id = event.id,
                pubkey = event.pubkey,
                channelId = rootChannelId,
                content = messageContent,
                authorPubkey = event.pubkey,
                recipientPubkeys = emptyList<String>(), // Channel messages are public broadcasts
                createdAt = event.createdAt,
                replyTo = replyTo,
                mentions = event.tags.filter { it[0] == "p" }.map { it[1] }
            )

            messagesMap[event.id] = message
            updateMessagesList()
        } catch (e: Exception) {
            Log.e("ChannelChatViewModel", "Failed to handle channel message", e)
        }
    }

    private fun updateMessagesList() {
        try {
            val sortedMessages = messagesMap.values
                .filter { it.channelId == channelId } // Ensure we only show messages for current channel
                .sortedBy { it.createdAt }
            _messages.value = sortedMessages
            
            if (sortedMessages.isNotEmpty()) {
                // If we have messages, we can consider the loading complete
                _isLoading.value = false
                loadingTimeoutJob?.cancel() // Cancel the timeout since we have messages
            }
        
            // Fetch metadata for all message authors by subscribing to kind=0 events for missing pubkeys
            val pubkeys = sortedMessages.map { it.authorPubkey }.distinct()
            val missing = pubkeys.filter { !_profileMetadata.value.containsKey(it) }
            if (missing.isNotEmpty()) {
                try {
                    // If there's an existing metadata subscription for profiles, clear it and create a new one
                    metadataSubscriptionId?.let {
                        Log.d("ChannelChatViewModel", "Unsubscribing previous metadata subscription: $it")
                        subscriptionManager.unsubscribe(it)
                        metadataSubscriptionId = null
                    }

                    val authorsArray = org.json.JSONArray().apply {
                        missing.forEach { put(it) }
                    }
                    val profileFilter = org.json.JSONObject().apply {
                        put("kinds", org.json.JSONArray().apply { put(0) })
                        put("authors", authorsArray)
                    }

                    val subId = subscriptionManager.subscribe(
                        filter = profileFilter,
                        onEvent = { event ->
                            try {
                                if (event.kind == 0) {
                                    val content = event.content
                                    val metadata = try {
                                        Json { ignoreUnknownKeys = true }.decodeFromString<com.hisa.data.model.Metadata>(content)
                                    } catch (e: Exception) {
                                        Log.w("ChannelChatViewModel", "Failed to decode metadata JSON for ${event.pubkey}: ${e.localizedMessage}")
                                        null
                                    }
                                    metadata?.let { meta ->
                                        _profileMetadata.value = _profileMetadata.value + (event.pubkey to meta)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ChannelChatViewModel", "Error processing profile metadata event", e)
                            }
                        },
                        onEndOfStoredEvents = {
                            Log.d("ChannelChatViewModel", "EOSE for profile metadata subscription for channel $channelId")
                        }
                    )
                    metadataSubscriptionId = subId
                } catch (e: Exception) {
                    Log.e("ChannelChatViewModel", "Failed to subscribe for profile metadata", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ChannelChatViewModel", "Error updating messages list", e)
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {
            try {
                Log.d("ChannelChatViewModel", "Attempting to send message to channel: $channelId")
                val messageContent = JSONObject().apply {
                    put("content", content)
                    put("created_at", System.currentTimeMillis() / 1000)
                }.toString()
                Log.d("ChannelChatViewModel", "Created message content: $messageContent")

                // First sign the event to get all the necessary fields
                    val signedEvent = NostrEventSigner.signEvent(
                    kind = 42, // Channel message kind
                    content = messageContent,
                    tags = listOf(com.hisa.data.nostr.NostrChannelUtils.buildETag(channelId, "", "root")), // Reference to channel with root marker
                    pubkey = userPubkey,
                        privKey = privateKey,
                    externalSignerPubkey = externalSignerPubkey,
                    externalSignerPackage = externalSignerPackage,
                    contentResolver = null
                )

                // Create NostrEvent from the signed event JSON
                val event = NostrEvent(
                    id = signedEvent.getString("id"),
                    pubkey = signedEvent.getString("pubkey"),
                    createdAt = signedEvent.getLong("created_at"),
                    kind = signedEvent.getInt("kind"),
                    tags = (0 until signedEvent.getJSONArray("tags").length()).map { i ->
                        val tagArr = signedEvent.getJSONArray("tags").getJSONArray(i)
                        (0 until tagArr.length()).map { tagArr.getString(it) }
                    },
                    content = signedEvent.getString("content"),
                    sig = signedEvent.getString("sig")
                )

                // Optimistic UI update: insert the message locally before publishing so the sender sees it immediately
                try {
                    val parsedContent = try {
                        val jsonContent = org.json.JSONObject(event.content)
                        jsonContent.optString("content", event.content)
                    } catch (e: Exception) {
                        event.content
                    }

                    val optimisticMessage = com.hisa.data.model.ChannelMessage(
                        id = event.id,
                        pubkey = event.pubkey,
                        channelId = channelId,
                        content = parsedContent,
                        authorPubkey = event.pubkey,
                        recipientPubkeys = emptyList(),
                        createdAt = event.createdAt,
                        replyTo = event.tags.find { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1),
                        mentions = event.tags.filter { it[0] == "p" }.mapNotNull { it.getOrNull(1) }
                    )

                    messagesMap[event.id] = optimisticMessage
                    updateMessagesList()
                } catch (e: Exception) {
                    Log.e("ChannelChatViewModel", "Failed to add optimistic message", e)
                }

                Log.d("ChannelChatViewModel", "Successfully signed event, publishing to relay")
                // Publish the signed event
                nostrClient.publishEvent(event)
                Log.d("ChannelChatViewModel", "Successfully published event to relay")
            } catch (e: Exception) {
                Log.e("ChannelChatViewModel", "Failed to send message", e)
                throw e
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cleanupSubscriptions()
    }

    fun cleanupSubscriptions() {
        loadingTimeoutJob?.cancel()
        
        // Cleanup message subscription
        channelSubscriptionId?.let { 
            Log.d("ChannelChatViewModel", "Cleaning up message subscription: $it")
            subscriptionManager.unsubscribe(it)
            channelSubscriptionId = null
        }
        
        // Cleanup metadata subscription
        metadataSubscriptionId?.let {
            Log.d("ChannelChatViewModel", "Cleaning up metadata subscription: $it")
            subscriptionManager.unsubscribe(it)
            metadataSubscriptionId = null
        }
        
        messagesMap.clear()
        _messages.value = emptyList()
        _isLoading.value = true
    }

    class Factory(
        private val channelId: String,
        private val nostrClient: NostrClient,
        private val subscriptionManager: SubscriptionManager,
        private val privateKey: ByteArray?,
        private val userPubkey: String,
        private val externalSignerPubkey: String? = null,
        private val externalSignerPackage: String? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChannelChatViewModel(
                channelId = channelId,
                nostrClient = nostrClient,
                subscriptionManager = subscriptionManager,
                privateKey = privateKey,
                userPubkey = userPubkey,
                externalSignerPubkey = externalSignerPubkey,
                externalSignerPackage = externalSignerPackage
            ) as T
        }
    }
}
