package com.hisa.data.nostr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class NostrClient @Inject constructor(
    relayUrls: List<String>,
    private val context: Context
) {
    @Volatile
    private var relayUrls: List<String> = relayUrls
    /**
     * Dynamically update the relay list and reconnect.
     */
    @Synchronized
    fun updateRelays(newRelays: List<String>) {
        // Normalize incoming list: trim, remove blanks, dedupe
        val cleaned = newRelays.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        // Compare as sets so order or duplication doesn't prevent real updates
        if (cleaned.toSet() == relayUrls.toSet()) {
            Timber.d("updateRelays called but relay set unchanged: %s", cleaned)
            return
        }

        Timber.i("Updating relays -> %s", cleaned)

        // Disconnect from current relays
        disconnect()

        // Update relay list (atomic swap)
        relayUrls = cleaned

        // Connect to new relays
        connect()

        // Active subscriptions stay in-memory and are replayed by each socket's onOpen,
        // where they can be prioritized instead of being queued in arbitrary map order.
    }

    /**
     * Returns the number of configured relays (may include relays not currently connected).
     */
    fun configuredRelayCount(): Int {
        return relayUrls.size
    }

    /**
     * Return the currently configured relay URLs as an immutable list.
     */
    fun configuredRelays(): List<String> {
        return relayUrls.toList()
    }

    /**
     * Returns the number of currently connected relays (open websockets).
     */
    fun connectedRelayCount(): Int {
        return webSockets.size
    }
    // The NostrClient is provided as a Hilt singleton. Avoid keeping a static
    // Context-holding instance to prevent leaks and allow DI to manage lifecycle.
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, String>() // id -> filterJson
    // Assignment of subscription id to a single relay to avoid sending REQ to all relays
    private val subscriptionAssignment = ConcurrentHashMap<String, String>() // id -> relayUrl
    private val roundRobinIndex = AtomicInteger(0)
    private val publishAssignment = ConcurrentHashMap<String, String>() // eventId -> relayUrl
    private val relayPublishLastSent = ConcurrentHashMap<String, Long>()
    private val relayRequiresAuth = ConcurrentHashMap<String, Boolean>()
    private var retryCount = 0
    private val maxRetries = 5
    private val retryDelay = 3000L // ms
    private val pendingMessages = Collections.synchronizedList(mutableListOf<String>())
    // Throttle map to avoid sending too many REQ messages to the same relay in a short time
    private val lastSubscriptionSent = ConcurrentHashMap<String, Long>()
    private val SUBSCRIPTION_MIN_INTERVAL_MS = 80L

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Expose transient error messages to UI via a StateFlow. UI can observe and decide
    // how/when to present them. We keep the last error string or null.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val messageHandlers = Collections.synchronizedList(mutableListOf<(String) -> Unit>())

    // New: expose an incoming messages SharedFlow so consumers can collect parsed/raw messages
    // in a lifecycle-aware coroutine instead of registering raw handlers.
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val incomingMessages: SharedFlow<String> = _incomingMessages

    private enum class SubscriptionPriority(val rank: Int) {
        DIRECT_MESSAGES(0),
        MARKETPLACE(1),
        USER_RELAYS(2),
        METADATA(3),
        OTHER(4)
    }

    private fun sendMessageInternal(message: String, shouldQueue: Boolean = true) {
        if (webSockets.isNotEmpty() && _connectionState.value == ConnectionState.CONNECTED) {
            webSockets.forEach { (relayUrl, ws) ->
                ws.send(message)
                Timber.d("Sent message to %s: %s", relayUrl, message)
            }
        } else if (shouldQueue) {
            Timber.w("No active WebSockets, queuing message: %s", message)
            pendingMessages.add(message)
            connect()
        } else {
            Timber.w("No active WebSockets, message skipped (no queue)")
        }
    }

    fun sendMessage(message: String) {
        sendMessageInternal(message, shouldQueue = true)
    }
    
    fun closeSubscription(subscriptionId: String) {
        val closeMsg = JSONArray().apply {
            put("CLOSE")
            put(subscriptionId)
        }
        sendMessageInternal(closeMsg.toString(), shouldQueue = false)
        subscriptions.remove(subscriptionId)
    }

    fun registerMessageHandler(handler: (String) -> Unit) {
    // Deprecated: prefer collecting `incomingMessages` SharedFlow.
    messageHandlers.add(handler)
    }

    fun unregisterMessageHandler(handler: (String) -> Unit) {
    // Deprecated: prefer collecting `incomingMessages` SharedFlow.
    messageHandlers.remove(handler)
    }

    fun clearMessageHandlers() {
        messageHandlers.clear()
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        var openCount = 0
        var errorCount = 0
        relayUrls.forEach { relayUrl ->
            try {
                val request = Request.Builder().url(relayUrl).build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Timber.d("WebSocket opened: %s", relayUrl)
                        webSockets[relayUrl] = webSocket
                        // Mark connected as soon as we have at least one open websocket
                        _connectionState.value = ConnectionState.CONNECTED
                        retryCount = 0

                        // Send any pending messages immediately (to all currently open websockets)
                        val iterator = pendingMessages.iterator()
                        while (iterator.hasNext()) {
                            val message = iterator.next()
                            webSockets.values.forEach { it.send(message) }
                            Timber.d("Sent pending message: %s", message)
                            iterator.remove()
                        }

                        resubscribeRelay(relayUrl, webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch(Dispatchers.Default) {
                            try {
                                // Inspect certain control messages to mark relay state (auth-required, rate limits)
                                try {
                                    val arr = org.json.JSONArray(text)
                                    val t = arr.optString(0)
                                    if (t == "NOTICE") {
                                        val notice = arr.optString(1, "")
                                        if (notice.contains("auth-required", true) || notice.contains("authentication required", true)) {
                                            relayRequiresAuth[relayUrl] = true
                                            Timber.w("Relay %s requires auth: %s", relayUrl, notice)
                                            // Reassign any subscriptions previously assigned to this relay
                                            scope.launch {
                                                try {
                                                    val reassigned = subscriptionAssignment.entries.filter { it.value == relayUrl }.map { it.key }
                                                    reassigned.forEach { subId ->
                                                        subscriptionAssignment.remove(subId)
                                                        val filter = subscriptions[subId]
                                                        if (filter != null) {
                                                            Timber.d("Reassigning subscription %s away from auth-only relay %s", subId, relayUrl)
                                                            sendSubscription(subId, filter)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Timber.w(e, "Failed to reassign subscriptions after auth-required for %s", relayUrl)
                                                }
                                            }
                                        }
                                    } else if (t == "OK") {
                                        val reason = arr.optString(3, "")
                                        if (reason.contains("auth-required", true) || reason.contains("authentication required", true)) {
                                            relayRequiresAuth[relayUrl] = true
                                            Timber.w("Relay %s reported auth-required on OK: %s", relayUrl, reason)
                                            scope.launch {
                                                try {
                                                    val reassigned = subscriptionAssignment.entries.filter { it.value == relayUrl }.map { it.key }
                                                    reassigned.forEach { subId ->
                                                        subscriptionAssignment.remove(subId)
                                                        val filter = subscriptions[subId]
                                                        if (filter != null) {
                                                            Timber.d("Reassigning subscription %s away from auth-only relay %s (OK)", subId, relayUrl)
                                                            sendSubscription(subId, filter)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Timber.w(e, "Failed to reassign subscriptions after auth-required OK for %s", relayUrl)
                                                }
                                            }
                                        } else {
                                            // clear auth flag when relay accepts events
                                            relayRequiresAuth.remove(relayUrl)
                                        }
                                    } else if (t == "CLOSED") {
                                        val reason = arr.optString(2, "")
                                        if (reason.contains("auth-required", true) || reason.contains("authentication required", true)) {
                                            relayRequiresAuth[relayUrl] = true
                                            Timber.w("Relay %s closed with auth-required: %s", relayUrl, reason)
                                            scope.launch {
                                                try {
                                                    val reassigned = subscriptionAssignment.entries.filter { it.value == relayUrl }.map { it.key }
                                                    reassigned.forEach { subId ->
                                                        subscriptionAssignment.remove(subId)
                                                        val filter = subscriptions[subId]
                                                        if (filter != null) {
                                                            Timber.d("Reassigning subscription %s away from auth-only relay %s (CLOSED)", subId, relayUrl)
                                                            sendSubscription(subId, filter)
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Timber.w(e, "Failed to reassign subscriptions after auth-required CLOSE for %s", relayUrl)
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    // not a JSON array or unparseable — ignore for relay state
                                }

                                // Emit into the centralized incoming messages flow first
                                try {
                                    _incomingMessages.emit(text)
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to emit incoming message into SharedFlow")
                                }

                                // Keep existing callback list for backwards compatibility
                                messageHandlers.forEach { handler ->
                                    handler(text)
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Error handling message")
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val errorMessage = "WebSocket error ($relayUrl): ${t.localizedMessage}"
                        Timber.e(t, "WebSocket error (%s)", relayUrl)
                        // Expose the error to observers; UI can decide how to display it.
                        _lastError.value = "Connection error: ${t.localizedMessage}"
                        webSockets.remove(relayUrl)
                        errorCount++
                        if (errorCount == relayUrls.size) {
                            _connectionState.value = ConnectionState.ERROR
                            attemptReconnect()
                        }
                        // Log additional connection details for debugging
                        Timber.d("Connection details - URL: %s, Response: %s", relayUrl, response?.message)
                        if (response != null) {
                            Timber.d("Response code: %d, Protocol: %s", response.code, response.protocol)
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.w("WebSocket closed (%s): %d %s", relayUrl, code, reason)
                        webSockets.remove(relayUrl)
                        if (webSockets.isEmpty()) {
                            _connectionState.value = ConnectionState.DISCONNECTED
                            attemptReconnect()
                        }
                    }
                })
            } catch (e: IllegalArgumentException) {
        Timber.e(e, "Invalid URL: %s", relayUrl)
        _lastError.value = "Invalid URL: $relayUrl. Please enter a valid URL."
            }
        }
    }

    fun disconnect() {
        webSockets.forEach { (relayUrl, ws) ->
            ws.close(1000, "Normal closure")
        }
        webSockets.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendSubscription(id: String, filterJson: String) {
    subscriptions[id] = filterJson
    Timber.d(
        "sendSubscription called: id=%s priority=%s filter=%s",
        id,
        subscriptionPriority(filterJson),
        filterJson
    )

        try {
            val reqString = buildReqString(id, filterJson) ?: return
            Timber.d("Prepared REQ for subscription id=%s: %s", id, reqString)

            if (webSockets.isNotEmpty() && _connectionState.value == ConnectionState.CONNECTED) {
                val priority = subscriptionPriority(filterJson)
                webSockets.entries
                    .filter { (relayUrl, _) -> relayRequiresAuth[relayUrl] != true }
                    .let { entries -> relayTargetsFor(priority, id, entries) }
                    .forEachIndexed { relayIndex, (relayUrl, ws) ->
                        val delayMs = initialSendDelay(priority, relayIndex)
                        sendReqToRelay(
                            relayUrl = relayUrl,
                            webSocket = ws,
                            reqString = reqString,
                            source = "subscription",
                            minDelayMs = delayMs,
                            priority = priority
                        )
                }
            } else {
                Timber.w("No active WebSockets, attempting to connect...")
                pendingMessages.add(reqString)
                connect()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sendSubscription")
        }
    }

    private fun resubscribeRelay(relayUrl: String, webSocket: WebSocket) {
        if (relayRequiresAuth[relayUrl] == true) return

        subscriptions.entries
            .sortedWith(
                compareBy<Map.Entry<String, String>> { subscriptionPriority(it.value).rank }
                    .thenBy { it.key }
            )
            .forEachIndexed { index, (id, filters) ->
                try {
                    val priority = subscriptionPriority(filters)
                    if (!shouldReplayOnRelay(priority, id, relayUrl)) return@forEachIndexed
                    val reqString = buildReqString(id, filters) ?: return@forEachIndexed
                    sendReqToRelay(
                        relayUrl = relayUrl,
                        webSocket = webSocket,
                        reqString = reqString,
                        source = "resubscribe",
                        minDelayMs = resubscribeDelay(priority, index),
                        priority = priority
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to resubscribe on open for %s", relayUrl)
                }
            }
    }

    private fun buildReqString(id: String, filterJson: String): String? {
        return try {
            JSONArray().apply {
                put("REQ")
                put(id)
                when {
                    filterJson.startsWith("[") -> {
                        val filtersArray = JSONArray(filterJson)
                        for (i in 0 until filtersArray.length()) {
                            put(filtersArray.get(i))
                        }
                    }
                    filterJson.startsWith("{") -> put(JSONObject(filterJson))
                    else -> {
                        Timber.e("Invalid filter JSON format for subscription %s", id)
                        return null
                    }
                }
            }.toString()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build REQ for subscription %s", id)
            null
        }
    }

    private fun sendReqToRelay(
        relayUrl: String,
        webSocket: WebSocket,
        reqString: String,
        source: String,
        minDelayMs: Long,
        priority: SubscriptionPriority
    ) {
        val sendNow = {
            try {
                webSocket.send(reqString)
                lastSubscriptionSent[relayUrl] = System.currentTimeMillis()
                Timber.d("Sent %s REQ to %s priority=%s: %s", source, relayUrl, priority, reqString)
            } catch (e: Exception) {
                Timber.w(e, "Failed to send %s REQ to %s", source, relayUrl)
            }
        }

        val now = System.currentTimeMillis()
        val last = lastSubscriptionSent[relayUrl] ?: 0L
        val throttleDelay = if (priority == SubscriptionPriority.DIRECT_MESSAGES) {
            0L
        } else {
            (SUBSCRIPTION_MIN_INTERVAL_MS - (now - last)).coerceAtLeast(0L)
        }
        val wait = maxOf(minDelayMs, throttleDelay)
        if (wait <= 0L) {
            sendNow()
        } else {
            scope.launch {
                kotlinx.coroutines.delay(wait)
                sendNow()
            }
        }
    }

    private fun initialSendDelay(priority: SubscriptionPriority, relayIndex: Int): Long {
        return when (priority) {
            SubscriptionPriority.DIRECT_MESSAGES -> relayIndex * 15L
            SubscriptionPriority.MARKETPLACE -> 40L + relayIndex * 25L
            SubscriptionPriority.USER_RELAYS -> 80L + relayIndex * 25L
            SubscriptionPriority.METADATA -> 160L + relayIndex * 35L
            SubscriptionPriority.OTHER -> 120L + relayIndex * 30L
        }
    }

    private fun relayTargetsFor(
        priority: SubscriptionPriority,
        subscriptionId: String,
        entries: List<Map.Entry<String, WebSocket>>
    ): List<Map.Entry<String, WebSocket>> {
        if (entries.isEmpty()) return entries
        val sorted = entries.sortedBy { it.key }
        return when (priority) {
            SubscriptionPriority.METADATA -> listOf(sorted[Math.floorMod(subscriptionId.hashCode(), sorted.size)])
            SubscriptionPriority.USER_RELAYS -> sorted.take(minOf(2, sorted.size))
            else -> entries
        }
    }

    private fun shouldReplayOnRelay(
        priority: SubscriptionPriority,
        subscriptionId: String,
        relayUrl: String
    ): Boolean {
        val openRelayUrls = webSockets.keys.sorted()
        if (openRelayUrls.isEmpty()) return true
        return when (priority) {
            SubscriptionPriority.METADATA -> {
                val selected = openRelayUrls[Math.floorMod(subscriptionId.hashCode(), openRelayUrls.size)]
                relayUrl == selected
            }
            SubscriptionPriority.USER_RELAYS -> relayUrl in openRelayUrls.take(minOf(2, openRelayUrls.size))
            else -> true
        }
    }

    private fun resubscribeDelay(priority: SubscriptionPriority, index: Int): Long {
        return when (priority) {
            SubscriptionPriority.DIRECT_MESSAGES -> index * 15L
            SubscriptionPriority.MARKETPLACE -> 60L + index * 25L
            SubscriptionPriority.USER_RELAYS -> 100L + index * 30L
            SubscriptionPriority.METADATA -> 220L + index * 45L
            SubscriptionPriority.OTHER -> 160L + index * 40L
        }
    }

    private fun subscriptionPriority(filterJson: String): SubscriptionPriority {
        val kinds = mutableSetOf<Int>()

        fun collectKinds(obj: JSONObject) {
            val arr = obj.optJSONArray("kinds") ?: return
            for (i in 0 until arr.length()) {
                val kind = arr.optInt(i, Int.MIN_VALUE)
                if (kind != Int.MIN_VALUE) kinds.add(kind)
            }
        }

        try {
            val trimmed = filterJson.trim()
            if (trimmed.startsWith("[")) {
                val filters = JSONArray(trimmed)
                for (i in 0 until filters.length()) {
                    filters.optJSONObject(i)?.let(::collectKinds)
                }
            } else if (trimmed.startsWith("{")) {
                collectKinds(JSONObject(trimmed))
            }
        } catch (_: Exception) {
            return SubscriptionPriority.OTHER
        }

        return when {
            1059 in kinds || 14 in kinds -> SubscriptionPriority.DIRECT_MESSAGES
            kinds.any { it == 30402 || it == 30017 || it == 30018 } -> SubscriptionPriority.MARKETPLACE
            10002 in kinds -> SubscriptionPriority.USER_RELAYS
            0 in kinds -> SubscriptionPriority.METADATA
            else -> SubscriptionPriority.OTHER
        }
    }

    /**
     * Apply a batch of subscriptions (used by SubscriptionManager to reapply persisted subscriptions)
     */
    fun applySubscriptions(subs: Map<String, String>) {
        subs.forEach { (id, filter) ->
            try {
                sendSubscription(id, filter)
            } catch (e: Exception) {
                Timber.e(e, "Failed to apply subscription id=%s", id)
            }
        }
    }

    fun sendKindSubscription(id: String, kind: Int, author: String? = null) {
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(kind))
            if (author != null) put("authors", JSONArray().put(author))
        }
        sendSubscription(id, filter.toString())
    }

    fun publishEvent(event: NostrEvent) {
        try {
            val eventJson = event.toJson()
            val reqArray = JSONArray().apply {
                put("EVENT")
                put(eventJson)
            }
            val reqString = reqArray.toString()

            // Publish to all connected relays (except those marked as requiring auth).
            val relayList = webSockets.keys.filter { relay -> relayRequiresAuth[relay] != true }
            if (relayList.isEmpty()) {
                Timber.w("No writable relays available, queuing publish for event=%s", event.id)
                pendingMessages.add(reqString)
                connect()
                return
            }

            // Verify event id/signature before sending — help catch id creation bugs early
            try {
                val verification = com.hisa.data.nostr.EventVerifier.verifyEvent(eventJson.toString())
                if (!verification.idMatches) {
                    Timber.w("Event id mismatch for event=%s given=%s computed=%s — aborting publish", event.id, event.id, verification.computedId)
                    return
                }
                if (!verification.signatureValid) {
                    Timber.w("Event signature invalid for event=%s — will still attempt publish (relay may reject)", event.id)
                }
            } catch (e: Exception) {
                Timber.w(e, "Event verification failed for event=%s", event.id)
            }

            relayList.forEach { relayUrl ->
                val ws = webSockets[relayUrl]
                if (ws == null) return@forEach
                try {
                    val now = System.currentTimeMillis()
                    val last = relayPublishLastSent[relayUrl] ?: 0L
                    val elapsed = now - last
                    val jitter = kotlin.random.Random.nextLong(0, 200)
                    if (elapsed >= SUBSCRIPTION_MIN_INTERVAL_MS) {
                        ws.send(reqString)
                        relayPublishLastSent[relayUrl] = System.currentTimeMillis()
                        publishAssignment[event.id] = relayUrl
                        Timber.d("Published event %s to %s", event.id, relayUrl)
                    } else {
                        val wait = (SUBSCRIPTION_MIN_INTERVAL_MS - elapsed).coerceAtLeast(0L) + jitter
                        scope.launch {
                            kotlinx.coroutines.delay(wait)
                            try {
                                ws.send(reqString)
                                relayPublishLastSent[relayUrl] = System.currentTimeMillis()
                                publishAssignment[event.id] = relayUrl
                                Timber.d("Delayed publish event %s to %s after %dms", event.id, relayUrl, wait)
                            } catch (ex: Exception) {
                                Timber.e(ex, "Delayed publish failed for %s", relayUrl)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to publish to %s", relayUrl)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to publish event")
        }
    }

    fun close() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    webSockets.forEach { (_, ws) ->
                        ws.close(1000, "Client closed connection")
                    }
                    webSockets.clear()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    clearMessageHandlers()
                    subscriptions.clear()
                } catch (e: Exception) {
                    Timber.e(e, "Error during close")
                }
            }
        }
    }

    private fun attemptReconnect() {
        if (retryCount < maxRetries) {
            retryCount++
            scope.launch {
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.delay(retryDelay * retryCount)
                    connect()
                }
            }
        } else {
            Timber.e("Max retries reached. Giving up.")
        }
    }

}
