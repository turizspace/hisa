package com.hisa.data.repository

import com.hisa.data.model.Metadata
import com.hisa.data.nostr.NostrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.takeWhile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Singleton

@Singleton
class MetadataRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: com.hisa.data.nostr.SubscriptionManager
) {
    companion object {
        @Volatile
        private var _instance: MetadataRepository? = null
        val instance: MetadataRepository
            get() = _instance ?: throw IllegalStateException(
                "MetadataRepository instance is not initialized. Make sure it's injected by Hilt."
            )

        internal fun setInstance(repository: MetadataRepository) {
            _instance = repository
        }
    }

    init {
        setInstance(this)
    }
    suspend fun getMetadataForPubkey(pubkey: String, beforeTimestamp: Long? = null): Metadata? = withContext(Dispatchers.IO) {
        var result: Metadata? = null
        val filterObj = org.json.JSONObject().apply {
            put("kinds", org.json.JSONArray().put(0))
            put("authors", org.json.JSONArray().put(pubkey))
            beforeTimestamp?.let { put("until", it) }
        }

        val events = mutableListOf<Pair<Long, String>>()
        val finished = CompletableDeferred<Unit>()

        // Subscribe via SubscriptionManager so dedupe/throttling/backoff is applied
        val subId = subscriptionManager.subscribe(filterObj,
            onEvent = { event ->
                try {
                    if (event.kind == 0) {
                        events.add(Pair(event.createdAt, event.content))
                    }
                } catch (e: Exception) {
                    println("[MetadataRepository] Error handling event callback: ${e.localizedMessage}")
                }
            },
            onEndOfStoredEvents = {
                // Signal that EOSE arrived
                if (!finished.isCompleted) finished.complete(Unit)
            }
        )

        try {
            // Wait for EOSE or timeout
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
                finished.await()
            }

            val chosen = events
                .filter { (createdAt, _) -> beforeTimestamp?.let { createdAt <= it } ?: true }
                .maxByOrNull { it.first }

            if (chosen != null) {
                val content = chosen.second
                result = try {
                    Json { ignoreUnknownKeys = true }.decodeFromString<Metadata>(content)
                } catch (e: Exception) {
                    println("[MetadataRepository] Failed to decode Metadata: ${e.localizedMessage}")
                    null
                }
            } else {
                println("[MetadataRepository] No matching metadata events found for $pubkey")
            }

            println("[MetadataRepository] Returning result: $result")
            return@withContext result
        } finally {
            try {
                subscriptionManager.unsubscribe(subId)
            } catch (e: Exception) {
                println("[MetadataRepository] Failed to unsubscribe $subId: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Fetch all kind=0 metadata events for a pubkey (until EOSE or timeout) and return
     * a list of pairs (createdAt, Metadata) so callers can choose the appropriate
     * historical snapshot locally without issuing multiple subscriptions.
     */
    suspend fun getAllMetadataEventsForPubkey(pubkey: String): List<Pair<Long, Metadata>> = withContext(Dispatchers.IO) {
        val events = mutableListOf<Pair<Long, Metadata>>()
        val subId = "meta_all_${UUID.randomUUID().toString().take(8)}"
        val filterObj = kotlinx.serialization.json.buildJsonObject {
            put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(0))))
            put("authors", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(pubkey))))
        }
        val filter = filterObj.toString()
        println("[MetadataRepository] Subscribing (all) for pubkey: $pubkey with filter: $filter id=$subId")

        nostrClient.sendSubscription(subId, filter)
        try {
            var ended = false
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
                nostrClient.incomingMessages.takeWhile { !ended }.collect { message ->
                    try {
                        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(message)
                        if (parsed is kotlinx.serialization.json.JsonArray && parsed.size > 0) {
                            val msgType = parsed[0].jsonPrimitive.content
                            if (msgType == "EVENT" && parsed.size > 2) {
                                val incomingSubId = parsed[1].jsonPrimitive.content
                                if (incomingSubId != subId) return@collect
                                val obj = parsed[2].jsonObject
                                val kindVal = obj["kind"]?.toString()
                                if (kindVal == "0") {
                                    val createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                                    try {
                                        val meta = Json { ignoreUnknownKeys = true }.decodeFromString<Metadata>(content)
                                        events.add(Pair(createdAt, meta))
                                    } catch (e: Exception) {
                                        println("[MetadataRepository] Failed to decode Metadata in batch: ${e.localizedMessage}")
                                    }
                                }
                            } else if (msgType == "EOSE" && parsed.size > 1) {
                                val incomingSubId = parsed[1].jsonPrimitive.content
                                if (incomingSubId == subId) {
                                    ended = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        println("[MetadataRepository] Exception parsing message (batch): ${e.localizedMessage}")
                    }
                }
            }
        } finally {
            try {
                nostrClient.closeSubscription(subId)
            } catch (e: Exception) {
                println("[MetadataRepository] Failed to close subscription $subId: ${e.localizedMessage}")
            }
        }

        // Sort events by createdAt ascending so callers can pick latest <= timestamp
        val sorted = events.sortedBy { it.first }
        println("[MetadataRepository] Returning ${sorted.size} metadata events for $pubkey")
        return@withContext sorted
    }

    /**
     * Fetch the latest kind=0 metadata content (raw JSON string) for a pubkey, if available.
     * This returns the raw content string (not decoded) so callers can inspect custom fields.
     */
    suspend fun getLatestMetadataRaw(pubkey: String): String? = withContext(Dispatchers.IO) {
        val subId = "meta_raw_${UUID.randomUUID().toString().take(8)}"
        val filterObj = kotlinx.serialization.json.buildJsonObject {
            put("kinds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(0))))
            put("authors", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(pubkey))))
        }
        val filter = filterObj.toString()
        nostrClient.sendSubscription(subId, filter)
        try {
            var latestContent: String? = null
            var ended = false
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
                nostrClient.incomingMessages.takeWhile { !ended }.collect { message ->
                    try {
                        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(message)
                        if (parsed is kotlinx.serialization.json.JsonArray && parsed.size > 0) {
                            val msgType = parsed[0].jsonPrimitive.content
                            if (msgType == "EVENT" && parsed.size > 2) {
                                val incomingSubId = parsed[1].jsonPrimitive.content
                                if (incomingSubId != subId) return@collect
                                val obj = parsed[2].jsonObject
                                val kindVal = obj["kind"]?.toString()
                                if (kindVal == "0") {
                                    val content = obj["content"]?.jsonPrimitive?.content ?: ""
                                    latestContent = content
                                }
                            } else if (msgType == "EOSE" && parsed.size > 1) {
                                val incomingSubId = parsed[1].jsonPrimitive.content
                                if (incomingSubId == subId) ended = true
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            try { nostrClient.closeSubscription(subId) } catch (_: Exception) { }
            return@withContext latestContent
        } finally {
            // nothing else
        }
    }
}
