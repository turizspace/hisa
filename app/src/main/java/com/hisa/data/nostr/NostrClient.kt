package com.hisa.data.nostr

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

        // Store current subscriptions before disconnecting
        val currentSubscriptions = subscriptions.toMap()

        // Disconnect from current relays
        disconnect()

        // Update relay list (atomic swap)
        relayUrls = cleaned

        // Connect to new relays
        connect()

        // Resubscribe to all previous subscriptions
        currentSubscriptions.forEach { (id, filterJson) ->
            sendSubscription(id, filterJson)
        }
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
    private var retryCount = 0
    private val maxRetries = 5
    private val retryDelay = 3000L // ms
    private val pendingMessages = Collections.synchronizedList(mutableListOf<String>())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Expose transient error messages to UI via a StateFlow. UI can observe and decide
    // how/when to present them. We keep the last error string or null.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val messageHandlers = Collections.synchronizedList(mutableListOf<(String) -> Unit>())

    // New: expose an incoming messages SharedFlow so consumers can collect parsed/raw messages
    // in a lifecycle-aware coroutine instead of registering raw handlers.
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<String> = _incomingMessages

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

                        // Resubscribe to all active subscriptions immediately so this connection
                        // starts receiving events without waiting for all relays to connect.
                        subscriptions.forEach { (id, filters) ->
                            try {
                                val reqArray = org.json.JSONArray().apply {
                                    put("REQ")
                                    put(id)
                                    try {
                                        val filtersArray = org.json.JSONArray(filters)
                                        for (i in 0 until filtersArray.length()) {
                                            put(filtersArray.get(i))
                                        }
                                    } catch (e: Exception) {
                                        put(org.json.JSONObject(filters))
                                    }
                                }
                                val reqString = reqArray.toString()
                                webSocket.send(reqString)
                                Timber.d("Sent resubscribe REQ to %s: %s", relayUrl, reqString)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to resubscribe on open for %s", relayUrl)
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch(Dispatchers.Default) {
                            try {
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
    Timber.d("sendSubscription called: id=%s filter=%s", id, filterJson)

        try {
            val reqArray = JSONArray().apply {
                put("REQ")
                put(id)
                when {
                    filterJson.startsWith("[") -> {
                        val filtersArray = JSONArray(filterJson)
                        for (i in 0 until filtersArray.length()) {
                            put(filtersArray.get(i))
                        }
                    }
                    filterJson.startsWith("{") -> {
                        put(JSONObject(filterJson))
                    }
                    else -> {
                        Timber.e("Invalid filter JSON format")
                        return
                    }
                }
            }

            val reqString = reqArray.toString()
            Timber.d("Prepared REQ for subscription id=%s: %s", id, reqString)

            if (webSockets.isNotEmpty() && _connectionState.value == ConnectionState.CONNECTED) {
                webSockets.forEach { (relayUrl, ws) ->
                    ws.send(reqString)
                    Timber.d("Sent subscription to %s: %s", relayUrl, reqString)
                    scheduleResubscribeWorker(context, relayUrl, id, filterJson)
                }
            } else {
                Timber.w("No active WebSockets, attempting to connect...")
                connect()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sendSubscription")
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
            sendMessage(reqArray.toString())
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

    private fun scheduleResubscribeWorker(context: Context, relayUrl: String, id: String, filters: String) {
        // Consolidate: enqueue a single coarse fallback job to resubscribe later if needed.
        val workRequest = OneTimeWorkRequestBuilder<NostrResubscribeWorker>()
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "nostr_resubscribe_fallback",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    class NostrResubscribeWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
        override fun doWork(): Result {
            val relayUrl = inputData.getString("relayUrl") ?: return Result.failure()
            val id = inputData.getString("id") ?: return Result.failure()
            val filters = inputData.getString("filters") ?: return Result.failure()
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(relayUrl).build()
                val ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val reqArray = JSONArray()
                        reqArray.put("REQ")
                        reqArray.put(id)
                        try {
                            val filtersArray = JSONArray(filters)
                            for (i in 0 until filtersArray.length()) {
                                reqArray.put(filtersArray.getJSONObject(i))
                            }
                        } catch (e: Exception) {
                            try {
                                val filterObj = JSONObject(filters)
                                reqArray.put(filterObj)
                            } catch (e2: Exception) {
                                Timber.e(e2, "Invalid filter JSON")
                                webSocket.close(1000, null)
                                return
                            }
                        }
                        val req = reqArray.toString()
                        webSocket.send(req)
                        webSocket.close(1000, null)
                    }
                })
                return Result.success()
            } catch (e: Exception) {
                Timber.e(e, "Failed to resubscribe")
                return Result.retry()
            }
        }
    }
}
