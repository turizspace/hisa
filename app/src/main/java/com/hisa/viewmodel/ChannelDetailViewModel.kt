package com.hisa.viewmodel
import android.util.Log
import com.hisa.viewmodel.AuthViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ChannelMessage
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrFilter
import com.hisa.data.nostr.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.hisa.data.model.Channel
import com.hisa.data.nostr.NostrEventSigner

class ChannelDetailViewModel(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val channelId: String,
    private val privateKey: ByteArray?,
    private val pubkey: String
) : ViewModel() {
    private val _messages = MutableStateFlow<List<ChannelMessage>>(emptyList())
    val messages: StateFlow<List<ChannelMessage>> = _messages.asStateFlow()

    private val _channel = MutableStateFlow<Channel?>(null)
    val channel: StateFlow<Channel?> = _channel.asStateFlow()

    private var channelSubscriptionId: String? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadMessages(channelId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Subscribe to channel messages
                // Subscribe to all channel messages for this channel (no 'since' filter)
                subscriptionManager.subscribeToChannelMessages(
                    channelId = channelId,
                    onEvent = { event ->
                        Log.d("ChannelDetailViewModel", "[NIP-28] Received channel message event: id=${event.id}, kind=${event.kind}, pubkey=${event.pubkey}, content=${event.content}, tags=${event.tags}")
                        handleChannelMessage(event)
                    }
                )

                // Subscribe to moderation events
                subscriptionManager.subscribeToModeration { event ->
                    Log.d("ChannelDetailViewModel", "[NIP-28] Received moderation event: id=${event.id}, kind=${event.kind}, pubkey=${event.pubkey}, content=${event.content}, tags=${event.tags}")
                    when (event.kind) {
                        43 -> handleHideMessage(event)
                        44 -> handleMuteUser(event)
                    }
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadChannel(channelId: String) {
        viewModelScope.launch {
            try {
                // Subscribe to channel creation/metadata events and update channel state when matching channelId
                channelSubscriptionId = subscriptionManager.subscribeToChannels { event ->
                    try {
                        when (event.kind) {
                            40 -> {
                                // Channel creation event: id is the channel id
                                if (event.id == channelId) {
                                    val metadata = parseChannelMetadata(event.content)
                                    val channel = Channel(
                                        id = event.id,
                                        name = metadata.name,
                                        about = metadata.about,
                                        picture = metadata.picture,
                                        relays = metadata.relays,
                                        creatorPubkey = event.pubkey,
                                        categories = event.tags.filter { it.firstOrNull() == "t" }.map { it[1] },
                                        createdAt = event.createdAt
                                    )
                                    _channel.value = channel
                                }
                            }
                            41 -> {
                                // Metadata update: find referenced channel id in e tag
                                val channelRef = event.tags.find { it.firstOrNull() == "e" }?.getOrNull(1) ?: return@subscribeToChannels
                                if (channelRef == channelId) {
                                    val metadata = parseChannelMetadata(event.content)
                                    val existing = _channel.value
                                    if (existing != null) {
                                        _channel.value = existing.copy(
                                            name = metadata.name,
                                            about = metadata.about,
                                            picture = metadata.picture,
                                            relays = metadata.relays,
                                            categories = event.tags.filter { it.firstOrNull() == "t" }.map { it[1] }
                                        )
                                    } else {
                                        // If we don't have an existing object, create one using the channelRef
                                        val channel = Channel(
                                            id = channelRef,
                                            name = metadata.name,
                                            about = metadata.about,
                                            picture = metadata.picture,
                                            relays = metadata.relays,
                                            creatorPubkey = event.pubkey,
                                            categories = event.tags.filter { it.firstOrNull() == "t" }.map { it[1] },
                                            createdAt = event.createdAt
                                        )
                                        _channel.value = channel
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChannelDetailViewModel", "Error handling channel event", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChannelDetailViewModel", "Failed to subscribe to channel metadata: ${e.localizedMessage}")
            }
        }
    }

    private fun parseChannelMetadata(content: String): ChannelMetadata {
        val json = JSONObject(content)
        return ChannelMetadata(
            name = json.optString("name", ""),
            about = json.optString("about", ""),
            picture = json.optString("picture", ""),
            relays = json.optJSONArray("relays")?.let { array ->
                List(array.length()) { array.getString(it) }
            } ?: emptyList()
        )
    }

    private data class ChannelMetadata(
        val name: String,
        val about: String,
        val picture: String,
        val relays: List<String>
    )

    fun sendMessage(channelId: String, content: String, replyTo: String? = null) {
        viewModelScope.launch {
            try {
                val tags = mutableListOf<List<String>>()
                // Prefer the first configured relay for this channel when present; otherwise empty string
                val relay = _channel.value?.relays?.firstOrNull() ?: ""

                // Root tag referencing the kind-40 channel creation event
                tags.add(com.hisa.data.nostr.NostrChannelUtils.buildETag(channelId, relay, "root"))

                if (replyTo != null) {
                    val replyMessage = _messages.value.find { it.id == replyTo }
                    if (replyMessage != null) {
                        // Reply tag referencing the parent kind-42 event id
                        tags.add(com.hisa.data.nostr.NostrChannelUtils.buildETag(replyTo, relay, "reply"))
                        // Include p tag for the author of the replied-to message (append relay when available)
                        tags.add(
                            if (relay.isNotEmpty()) listOf("p", replyMessage.authorPubkey, relay)
                            else listOf("p", replyMessage.authorPubkey)
                        )
                    }
                }
                val eventJson = NostrEventSigner.signEvent(
                    kind = 42,
                    content = content,
                    tags = tags,
                    pubkey = pubkey,
                    privKey = if (privateKey != null && privateKey.isNotEmpty()) privateKey else null
                )
                val event = NostrEvent(
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
                // Optimistic update so the sent message appears immediately in UI
                try {
                    val parsedContent = try {
                        val jsonContent = JSONObject(event.content)
                        jsonContent.optString("content", event.content)
                    } catch (e: Exception) {
                        event.content
                    }

                    val optimisticMessage = ChannelMessage(
                        id = event.id,
                        pubkey = event.pubkey,
                        channelId = channelId,
                        content = parsedContent,
                        authorPubkey = event.pubkey,
                        recipientPubkeys = emptyList(),
                        createdAt = event.createdAt,
                        replyTo = event.tags.find { it.size >= 4 && it[0] == "e" && it[3] == "reply" }?.get(1),
                        mentions = event.tags.filter { it[0] == "p" }.map { it[1] }
                    )

                    val current = _messages.value.toMutableList()
                    current.add(optimisticMessage)
                    current.sortBy { it.createdAt }
                    _messages.value = current
                } catch (e: Exception) {
                    Log.e("ChannelDetailViewModel", "Failed to add optimistic message", e)
                }

                nostrClient.publishEvent(event)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun hideMessage(messageId: String, reason: String? = null) {
        viewModelScope.launch {
            try {
                val jsonContent = reason?.let {
                    JSONObject().put("reason", it).toString()
                } ?: ""
                val eventJson = NostrEventSigner.signEvent(
                    kind = 43,
                    content = jsonContent,
                    tags = listOf(listOf("e", messageId)),
                    pubkey = pubkey,
                    privKey = if (privateKey != null && privateKey.isNotEmpty()) privateKey else null
                )
                val event = NostrEvent(
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
                nostrClient.publishEvent(event)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun muteUser(pubkey: String, reason: String? = null) {
        viewModelScope.launch {
            try {
                val jsonContent = reason?.let {
                    JSONObject().put("reason", it).toString()
                } ?: ""
                val eventJson = NostrEventSigner.signEvent(
                    kind = 44,
                    content = jsonContent,
                    tags = listOf(listOf("p", pubkey)),
                    pubkey = pubkey,
                    privKey = privateKey
                )
                val event = NostrEvent(
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
                nostrClient.publishEvent(event)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun handleChannelMessage(event: NostrEvent) {
        val rootChannelId = event.tags.find { 
            it.size >= 4 && it[0] == "e" && it[3] == "root" 
        }?.get(1) ?: return

        val replyTo = event.tags.find {
            it.size >= 4 && it[0] == "e" && it[3] == "reply"
        }?.get(1)

        val message = ChannelMessage(
            id = event.id,
            channelId = rootChannelId,
            content = event.content,
            authorPubkey = event.pubkey,
            pubkey = event.pubkey, // For channel messages, pubkey is the same as authorPubkey
            recipientPubkeys = emptyList<String>(), // Channel messages are public broadcasts
            createdAt = event.createdAt,
            replyTo = replyTo, // We already have this from the event tags
            mentions = event.tags.filter { it[0] == "p" }.map { it[1] } // Add mentions from "p" tags
        )

        val currentMessages = _messages.value.toMutableList()
        currentMessages.add(message)
        currentMessages.sortBy { it.createdAt }
        _messages.value = currentMessages
    }

    private fun handleHideMessage(event: NostrEvent) {
        val messageId = event.tags.find { it[0] == "e" }?.get(1) ?: return
        val currentMessages = _messages.value.toMutableList()
        currentMessages.removeAll { it.id == messageId }
        _messages.value = currentMessages
    }

    private fun handleMuteUser(event: NostrEvent) {
        val mutedPubkey = event.tags.find { it[0] == "p" }?.get(1) ?: return
        val currentMessages = _messages.value.toMutableList()
        currentMessages.removeAll { it.authorPubkey == mutedPubkey }
        _messages.value = currentMessages
    }

    override fun onCleared() {
        super.onCleared()
        channelSubscriptionId?.let { subscriptionManager.unsubscribe(it) }
    }

    init {
        // Start loading channel metadata and messages automatically for the provided channelId
        loadChannel(channelId)
        loadMessages(channelId)
    }
}
