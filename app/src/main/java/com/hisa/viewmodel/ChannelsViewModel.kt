package com.hisa.viewmodel
import com.hisa.viewmodel.AuthViewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Channel
import com.hisa.data.model.ChannelEvent
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.NostrFilter
import com.hisa.data.nostr.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ChannelsViewModel(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val privateKey: ByteArray?,
    private val pubkey: String
) : ViewModel() {
    fun refreshChannels() {
        subscribeToChannelEvents()
    }
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _categories = MutableStateFlow<Set<String>>(emptySet())
    val categories: StateFlow<Set<String>> = _categories.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val channelsMap = ConcurrentHashMap<String, Channel>()
    // Track unique participant pubkeys per channel
    private val participantsMap = ConcurrentHashMap<String, MutableSet<String>>()
    private val _participantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val participantCounts: StateFlow<Map<String, Int>> = _participantCounts.asStateFlow()
    // track per-channel subscription ids so we can unsubscribe when needed
    private val channelMessageSubs = ConcurrentHashMap<String, String>()

    init {
        subscribeToChannelEvents()
    }

    private var channelsSubscriptionId: String? = null
    // Guard to avoid multiple subscriptions
    private var isSubscribed = false

    private fun subscribeToChannelEvents() {
        viewModelScope.launch {
            try {

                // Prevent duplicate subscriptions
                if (isSubscribed) {

                    return@launch
                }
                // Use the SubscriptionManager helper which creates the proper NIP-28 filter
                channelsSubscriptionId = subscriptionManager.subscribeToChannels { event ->
                    try {

                        when (event.kind) {
                            40 -> handleChannelCreateEvent(event)
                            41 -> handleChannelMetadataEvent(event)

                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChannelsViewModel", "Error handling channel event: ${e.localizedMessage}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChannelsViewModel", "Failed to subscribe to channel events: ${e.localizedMessage}")
            }
            // Per-channel message subscriptions are created when channels are discovered (see handleChannelCreateEvent)
            isSubscribed = true
        }
    }

    /**
     * Ensure that we are subscribed to channel events. Safe to call multiple times.
     */
    fun ensureSubscribed() {
        if (isSubscribed) return
        subscribeToChannelEvents()
    }

    override fun onCleared() {
        super.onCleared()
        channelsSubscriptionId?.let { subscriptionManager.unsubscribe(it) }
        // Unsubscribe all per-channel message subscriptions
        channelMessageSubs.values.forEach { subId ->
            try { subscriptionManager.unsubscribe(subId) } catch (_: Exception) {}
        }
        channelMessageSubs.clear()
    }

    private fun handleChannelCreateEvent(event: NostrEvent) {
        try {

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
            channelsMap[event.id] = channel

            updateChannelsList()
            updateCategories()
            // Start a messages subscription for this channel to track participants
            try {
                val subId = subscriptionManager.subscribeToChannelMessages(channel.id) { msgEvent ->
                    try {
                        val rootChannelId = msgEvent.tags.find {
                            it.size >= 4 && it[0] == "e" && it[3] == "root"
                        }?.get(1) ?: msgEvent.tags.find {
                            it.size >= 2 && it[0] == "e"
                        }?.get(1)
                        if (rootChannelId == channel.id) {
                            val set = participantsMap.getOrPut(channel.id) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
                            if (msgEvent.pubkey.isNotBlank()) {
                                set.add(msgEvent.pubkey)
                                _participantCounts.value = participantsMap.mapValues { it.value.size }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChannelsViewModel", "Error in per-channel message handler: ${e.localizedMessage}")
                    }
                }
                channelMessageSubs[channel.id] = subId
            } catch (e: Exception) {
                android.util.Log.e("ChannelsViewModel", "Failed to subscribe to messages for channel ${channel.id}: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            android.util.Log.e("ChannelsViewModel", "Error in handleChannelCreateEvent", e)
        }
    }

    private fun handleChannelMetadataEvent(event: NostrEvent) {
        try {

            val metadata = parseChannelMetadata(event.content)
            val channelId = event.tags.find { it.firstOrNull() == "e" }?.getOrNull(1) ?: return
            // Only accept metadata updates from the channel creator
            val channel = channelsMap[channelId] ?: return
            if (channel.creatorPubkey != event.pubkey) return

            channelsMap[channelId] = channel.copy(
                name = metadata.name,
                about = metadata.about,
                picture = metadata.picture,
                relays = metadata.relays,
                categories = event.tags.filter { it.firstOrNull() == "t" }.map { it[1] }
            )

            updateChannelsList()
            updateCategories()
        } catch (e: Exception) {
            android.util.Log.e("ChannelsViewModel", "Error in handleChannelMetadataEvent", e)
        }
    }

    private fun parseChannelMetadata(content: String): ChannelMetadata {
        return try {
            val json = JSONObject(content)
            val name = json.optString("name", "")
            val about = json.optString("about", "")
            val picture = json.optString("picture", "")

            val relays = mutableListOf<String>()
            val relaysArray = json.optJSONArray("relays")
            if (relaysArray != null) {
                for (i in 0 until relaysArray.length()) {
                    relays.add(relaysArray.optString(i, ""))
                }
            } else {
                // some metadata may include relays as a single string or omit it entirely; ignore if absent
            }

            ChannelMetadata(
                name = name,
                about = about,
                picture = picture,
                relays = relays.filter { it.isNotBlank() }
            )
        } catch (e: Exception) {
            android.util.Log.w("ChannelsViewModel", "parseChannelMetadata: failed to parse metadata: ${e.localizedMessage}")
            ChannelMetadata(name = "", about = "", picture = "", relays = emptyList())
        }
    }

    private data class ChannelMetadata(
        val name: String,
        val about: String,
        val picture: String,
        val relays: List<String>
    )

    fun handleChannelEvent(event: ChannelEvent) {
        when (event) {
            is ChannelEvent.ChannelCreate -> {
                channelsMap[event.channel.id] = event.channel
                updateChannelsList()
                updateCategories()
            }
            is ChannelEvent.MetadataUpdate -> {
                channelsMap[event.channelId]?.let { existingChannel ->
                    channelsMap[event.channelId] = existingChannel.copy(
                        name = event.name ?: existingChannel.name,
                        about = event.about ?: existingChannel.about,
                        picture = event.picture ?: existingChannel.picture,
                        relays = event.relays ?: existingChannel.relays
                    )
                    updateChannelsList()
                }
            }
            else -> {} // Handle other events as needed
        }
    }

    private fun updateChannelsList() {
        // Ensure participant counts contains entries for all channels (default 0)
        val counts = participantsMap.mapValues { it.value.size }.toMutableMap()
        channelsMap.keys.forEach { id -> if (!counts.containsKey(id)) counts[id] = 0 }
        _participantCounts.value = counts.toMap()

        // Sort channels by participant count (popularity) descending, then by name ascending
        _channels.value = channelsMap.values.toList().sortedWith(
            compareByDescending<Channel> { counts[it.id] ?: 0 }
                .thenBy { it.name }
        )
        // Ensure we have per-channel subscriptions for all known channels
        channelsMap.keys.forEach { id ->
            if (!channelMessageSubs.containsKey(id)) {
                try {
                    val subId = subscriptionManager.subscribeToChannelMessages(id) { msgEvent ->
                        try {
                            val rootChannelId = msgEvent.tags.find {
                                it.size >= 4 && it[0] == "e" && it[3] == "root"
                            }?.get(1) ?: msgEvent.tags.find {
                                it.size >= 2 && it[0] == "e"
                            }?.get(1)
                            if (rootChannelId == id) {
                                val set = participantsMap.getOrPut(id) { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
                                if (msgEvent.pubkey.isNotBlank()) {
                                    set.add(msgEvent.pubkey)
                                    _participantCounts.value = participantsMap.mapValues { it.value.size }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("ChannelsViewModel", "Error in per-channel subscription handler: ${e.localizedMessage}")
                        }
                    }
                    channelMessageSubs[id] = subId
                } catch (e: Exception) {
                    android.util.Log.e("ChannelsViewModel", "Failed to create per-channel subscription for $id: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun updateCategories() {
        _categories.value = channelsMap.values
            .flatMap { it.categories }
            .toSet()
    }

    fun createChannel(
        name: String,
        about: String,
        picture: String,
        categories: List<String>,
        relays: List<String>,
        userPubkey: String? = null
    ) {
        viewModelScope.launch {
            try {
                val metadata = ChannelMetadata(
                    name = name,
                    about = about,
                    picture = picture,
                    relays = relays
                )

                val tags = mutableListOf<List<String>>()
                categories.forEach { tags.add(listOf("t", it)) }
                relays.forEach { tags.add(listOf("relay", it)) }

                val metadataJson = JSONObject().apply {
                    put("name", metadata.name)
                    put("about", metadata.about)
                    put("picture", metadata.picture)
                    put("relays", JSONArray(metadata.relays))
                }

                val eventJson = NostrEventSigner.signEvent(
                    kind = 40,
                    content = metadataJson.toString(),
                    tags = tags,
                    pubkey = userPubkey ?: pubkey,
                    privKey = if (privateKey != null && privateKey.isNotEmpty()) privateKey else null
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
                nostrClient.publishEvent(nostrEvent)
            } catch (e: Exception) {
                android.util.Log.e("ChannelsViewModel", "Error in createChannel", e)
            }
        }
    }

    fun updateChannelMetadata(
        channelId: String,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        relays: List<String>? = null,
        categories: List<String>? = null
    ) {
        viewModelScope.launch {
            try {
                val channel = channelsMap[channelId] ?: return@launch

                val metadata = ChannelMetadata(
                    name = name ?: channel.name,
                    about = about ?: channel.about,
                    picture = picture ?: channel.picture,
                    relays = relays ?: channel.relays
                )

                val tags = mutableListOf<List<String>>()
                // Add root reference to original channel
                tags.add(listOf("e", channelId, channel.relays.firstOrNull() ?: "", "root"))
                // Add categories
                (categories ?: channel.categories).forEach {
                    tags.add(listOf("t", it))
                }

                val metadataJson = JSONObject().apply {
                    put("name", metadata.name)
                    put("about", metadata.about)
                    put("picture", metadata.picture)
                    put("relays", metadata.relays)
                }

                val eventJson = NostrEventSigner.signEvent(
                    kind = 41,
                    content = metadataJson.toString(),
                    tags = tags,
                    pubkey = pubkey,
                    privKey = if (privateKey != null && privateKey.isNotEmpty()) privateKey else null
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
                nostrClient.publishEvent(nostrEvent)
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}