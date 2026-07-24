package com.hisa.data.repository

import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.SubscriptionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ServiceRepository {
    private val serviceCache = mutableMapOf<String, ServiceListing>()

    fun cacheService(service: ServiceListing) {
        serviceCache[service.eventId] = service
    }

    fun getCachedService(eventId: String): ServiceListing? = serviceCache[eventId]

    fun getAllCachedServices(): List<ServiceListing> = serviceCache.values.toList()

    fun getServiceByEventId(eventId: String): ServiceListing? {
        val cached = getCachedService(eventId)
        if (cached != null) return cached

        if (eventId == "demo") {
            return ServiceListing(
                eventId = "demo",
                title = "Demo Service",
                summary = "This is a demo service for preview.",
                content = "This is a longer demo description for the service. It shows how content will appear in the details.",
                price = "1000",
                tags = listOf("tag1", "tag2"),
                pubkey = "demo_pubkey",
                createdAt = System.currentTimeMillis() / 1000
            )
        }
        return null
    }

    suspend fun fetchServiceByEventId(
        eventId: String,
        authorPubkey: String?,
        subscriptionManager: SubscriptionManager
    ): ServiceListing? = withContext(Dispatchers.IO) {
        getCachedService(eventId)?.let { return@withContext it }

        var result: ServiceListing? = null
        val finished = CompletableDeferred<Unit>()
        val filter = JSONObject().apply {
            put("ids", JSONArray().put(eventId))
            put("kinds", JSONArray().put(30402))
            authorPubkey?.takeIf { it.isNotBlank() }?.let { put("authors", JSONArray().put(it)) }
            put("limit", 1)
        }

        val subId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { event ->
                if (event.id == eventId) {
                    ServiceEventParser.parse(event.toJson().toString())?.let { service ->
                        cacheService(service)
                        result = service
                    }
                }
            },
            onEndOfStoredEvents = {
                if (!finished.isCompleted) {
                    finished.complete(Unit)
                }
            },
            autoCloseOnEose = true
        )

        try {
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(5)) {
                finished.await()
            }
        } finally {
            runCatching { subscriptionManager.unsubscribe(subId) }
        }

        return@withContext result
    }

    fun parseServiceEvent(eventJson: String): ServiceListing? = ServiceEventParser.parse(eventJson)
}
