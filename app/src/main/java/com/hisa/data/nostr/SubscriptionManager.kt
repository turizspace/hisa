package com.hisa.data.nostr

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

@Singleton
class SubscriptionManager @Inject constructor(
    private val nostrClient: NostrClient,
    private val collectorScope: kotlinx.coroutines.CoroutineScope
) {
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>()
    // Persist subscription definitions to re-apply on reconnects or relay changes
    private val persistedSubscriptions = ConcurrentHashMap<String, String>()

    init {
        // Start collecting centralized incoming messages and handle them on the injected scope
        collectorScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                nostrClient.incomingMessages.collect { msg ->
                    try {
                        handleMessage(msg)
                    } catch (e: Exception) {
                        android.util.Log.e("SubscriptionManager", "Error dispatching collected message", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SubscriptionManager", "Incoming messages collection failed", e)
            }
        }
    }

    // NIP-28 Channel Subscriptions
    fun subscribeToChannels(onEvent: (NostrEvent) -> Unit): String {
        return subscribe(
            filter = createFilter {
                putKinds(listOf(40, 41)) // Channel creation and metadata
            },
            onEvent = onEvent
        )
    }

    fun subscribeToChannelMessages(
        channelId: String? = null,
        since: Long? = null,
        onEvent: (NostrEvent) -> Unit
    ): String {
        return subscribe(
            filter = createFilter {
                putKinds(listOf(42))
                channelId?.let { 
                    putTag("e", listOf(it))
                }
                since?.let { putSince(it) }
            },
            onEvent = onEvent
        )
    }

    fun subscribeToModeration(onEvent: (NostrEvent) -> Unit): String {
        return subscribe(
            filter = createFilter {
                putKinds(listOf(43, 44)) // Hide message and mute user events
            },
            onEvent = onEvent
        )
    }

    /**
     * Subscribe to incoming direct messages addressed to [userPubkey] using p-tags.
     * Only subscribes to kind 1059 as requested (gift-wrapped/encrypted DM)
     */
    fun subscribeToDirectMessages(
        userPubkey: String, 
        sinceTime: Long? = null,
        onEvent: (NostrEvent) -> Unit
    ): String {
        // Create both filters in a single subscription
        val filter = createFilter {
            put("kinds", JSONArray().put(1059))
            put("#p", JSONArray().put(userPubkey))  // Messages sent to us
            put("authors", JSONArray().put(userPubkey))  // Messages sent by us
            sinceTime?.let { put("since", it) }
        }

        return subscribe(filter, onEvent)
    }

    /**
     * Subscribe to NIP-65 preferred relays events (kind 10002) authored by [userPubkey].
     * The event content is expected to be a newline-separated list of relay URLs.
     */
    fun subscribeToPreferredRelays(
        userPubkey: String,
        onEvent: (NostrEvent) -> Unit,
        onEndOfStoredEvents: () -> Unit = {}
    ): String {
        val filter = createFilter {
            putKinds(listOf(10002))
            putAuthors(listOf(userPubkey))
        }
        return subscribe(filter, onEvent, onEndOfStoredEvents)
    }

    // General purpose subscriptions
    fun subscribe(
        filter: JSONObject,
        onEvent: (NostrEvent) -> Unit,
        onEndOfStoredEvents: () -> Unit = {}
    ): String {
        // Prevent duplicate subscriptions for the exact same filter string
        val filterKey = filter.toString()
        val existing = activeSubscriptions.entries.find { (_, info) ->
            try {
                info.filter == filterKey
            } catch (e: Exception) {
                false
            }
        }
        if (existing != null) {
            val existingId = existing.key
            return existingId
        }

    val subId = generateSubscriptionId()
        activeSubscriptions[subId] = SubscriptionInfo(filterKey, onEvent, onEndOfStoredEvents)
        persistedSubscriptions[subId] = filterKey
        nostrClient.sendSubscription(subId, filter.toString())
        return subId
    }

    /**
     * Subscribe using an array of filters (multiple filters under a single subscription id).
     * This sends a REQ with multiple filter objects so the relay will return events matching any of them.
     */
    fun subscribe(
        filtersArray: org.json.JSONArray,
        onEvent: (NostrEvent) -> Unit,
        onEndOfStoredEvents: () -> Unit = {}
    ): String {
        val filterKey = filtersArray.toString()
        val existing = activeSubscriptions.entries.find { (_, info) ->
            try {
                info.filter == filterKey
            } catch (e: Exception) {
                false
            }
        }
        if (existing != null) {
            val existingId = existing.key
            return existingId
        }

        val subId = generateSubscriptionId()
        activeSubscriptions[subId] = SubscriptionInfo(filterKey, onEvent, onEndOfStoredEvents)
    persistedSubscriptions[subId] = filterKey
    nostrClient.sendSubscription(subId, filtersArray.toString())
    return subId
    }

    fun unsubscribe(subscriptionId: String) {
    activeSubscriptions.remove(subscriptionId)
    persistedSubscriptions.remove(subscriptionId)
        try {
            // Use NostrClient.closeSubscription to ensure internal subscription tracking is cleaned
            nostrClient.closeSubscription(subscriptionId)
        } catch (e: Exception) {
            android.util.Log.e("SubscriptionManager", "Failed to close subscription on client: ${e.localizedMessage}")
            // Fallback: try sending a CLOSE message directly
            try {
                val closeMsg = JSONArray().apply {
                    put("CLOSE")
                    put(subscriptionId)
                }
                nostrClient.sendMessage(closeMsg.toString())
            } catch (ex: Exception) {
                android.util.Log.e("SubscriptionManager", "Fallback close failed: ${ex.localizedMessage}")
            }
        }
    }

    fun handleMessage(message: String) {

        try {
            val jsonArray = JSONArray(message)
            if (jsonArray.length() < 2) {
                android.util.Log.w("SubscriptionManager", "[WS INCOMING] Message too short, ignoring: $message")
                return
            }

            val msgType = jsonArray.getString(0)
            when (msgType) {
                "EVENT" -> {
                    // Support both shapes: ["EVENT", <subId>, <event>] and ["EVENT", <event>]
                    var subId: String? = null
                    val eventJson: JSONObject? = when {
                        jsonArray.opt(1) is JSONObject -> {
                            // shape: ["EVENT", {event}]
                            jsonArray.optJSONObject(1)
                        }
                        jsonArray.length() >= 3 -> {
                            // shape: ["EVENT", subId, {event}]
                            subId = jsonArray.optString(1)
                            jsonArray.optJSONObject(2)
                        }
                        else -> {
                            android.util.Log.w("SubscriptionManager", "EVENT message with unexpected shape: $message")
                            null
                        }
                    }

                    if (eventJson == null) {
                        // Nothing we can do here safely
                        return
                    }

                    // Safely parse tags (use optJSONArray to avoid exceptions)
                    val tagsList = mutableListOf<List<String>>()
                    val tagsArray = eventJson.optJSONArray("tags")
                    if (tagsArray != null) {
                        for (i in 0 until tagsArray.length()) {
                            val tagArr = tagsArray.optJSONArray(i)
                            if (tagArr != null) {
                                val inner = mutableListOf<String>()
                                for (j in 0 until tagArr.length()) {
                                    inner.add(tagArr.optString(j))
                                }
                                tagsList.add(inner)
                            }
                        }
                    }

                    val event = com.hisa.data.nostr.NostrEvent(
                        id = eventJson.optString("id"),
                        pubkey = eventJson.optString("pubkey"),
                        createdAt = eventJson.optLong("created_at"),
                        kind = eventJson.optInt("kind"),
                        tags = tagsList,
                        content = eventJson.optString("content"),
                        sig = eventJson.optString("sig")
                    )

                    try {
                        if (subId != null) {
                            val callback = activeSubscriptions[subId]?.onEvent
                            if (callback == null) {
                                android.util.Log.w("SubscriptionManager", "No callback registered for subId=$subId")
                            } else {
                                try {
                                    callback.invoke(event)
                                } catch (cbEx: Exception) {
                                    android.util.Log.e("SubscriptionManager", "Callback threw while handling eventId=${event.id} for subId=$subId", cbEx)
                                }
                            }
                        } else {
                            // No subscription id provided by the relay; dispatch to all active subscriptions
                            activeSubscriptions.values.forEach { info ->
                                try {
                                    info.onEvent.invoke(event)
                                } catch (cbEx: Exception) {
                                    android.util.Log.e("SubscriptionManager", "Callback threw while handling eventId=${event.id} for subscription", cbEx)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SubscriptionManager", "Failed to dispatch event to callback for subId=$subId", e)
                    }
                }
                "EOSE" -> {
                    val subId = jsonArray.getString(1)

                    activeSubscriptions[subId]?.onEndOfStoredEvents?.invoke()
                }
                "NOTICE" -> {
                    val notice = jsonArray.getString(1)
                    android.util.Log.w("SubscriptionManager", "[WS NOTICE] $notice")
                }
                else -> {
                    android.util.Log.w("SubscriptionManager", "[WS INCOMING] Unknown message type: $msgType")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SubscriptionManager", "[WS ERROR] Failed to parse incoming message: $message", e)
        }
    }

    private fun generateSubscriptionId(): String {
        return UUID.randomUUID().toString().take(8)
    }

    // Filter builder utility
    private fun createFilter(builder: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply(builder)
    }

    private fun JSONObject.putKinds(kinds: List<Int>) {
        put("kinds", JSONArray(kinds))
    }

    private fun JSONObject.putTag(key: String, values: List<String>) {
    // Nostr filter tag syntax expects e.g. "#e": ["<value1>", "<value2>"]
    put("#$key", JSONArray(values))
    }

    private fun JSONObject.putSince(timestamp: Long) {
        put("since", timestamp)
    }

    private fun JSONObject.putAuthors(authors: List<String>) {
        put("authors", JSONArray(authors))
    }

    private data class SubscriptionInfo(
        val filter: String,
        val onEvent: (NostrEvent) -> Unit,
        val onEndOfStoredEvents: () -> Unit = {}
    )

    // Predefined filters
    companion object {
        private fun createFilter(builder: JSONObject.() -> Unit): JSONObject {
            return JSONObject().apply(builder)
        }

        private fun JSONObject.putKinds(kinds: List<Int>) {
            put("kinds", JSONArray(kinds))
        }

        private fun JSONObject.putTag(key: String, values: List<String>) {
            put("#$key", JSONArray(values))
        }

        private fun JSONObject.putSince(timestamp: Long) {
            put("since", timestamp)
        }

        fun filterNIP99(): JSONObject = createFilter {
            putKinds(listOf(30402))
        }

        fun filterNIP17(pubkey: String): JSONObject = createFilter {
            putKinds(listOf(14))
            put("authors", JSONArray().put(pubkey))
        }

        fun filterNIP29(): JSONObject = createFilter {
            putKinds(listOf(30000))
        }

        fun filterNIP28Channels(): JSONObject = createFilter {
            putKinds(listOf(40, 41))
        }

        fun filterNIP28Messages(channelId: String? = null): JSONObject = createFilter {
            putKinds(listOf(42))
            channelId?.let { putTag("e", listOf(it)) }
        }

        fun filterNIP28Moderation(): JSONObject = createFilter {
            putKinds(listOf(43, 44))
        }
    }
}
