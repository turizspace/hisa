package com.hisa.viewmodel

import com.hisa.util.cleanPubkeyFormat

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Message
import com.hisa.data.model.ChatroomKey
import com.hisa.data.nostr.NostrClient
import com.hisa.data.repository.ConversationRepository
import com.hisa.data.repository.MetadataRepository
import com.hisa.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import com.hisa.util.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

@HiltViewModel
@RequiresApi(Build.VERSION_CODES.O)
class MessagesViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val messageRepository: MessageRepository,
    private val metadataRepository: MetadataRepository,
    private val secureStorage: com.hisa.data.storage.SecureStorage,
    private val subscriptionManager: com.hisa.data.nostr.SubscriptionManager
) : ViewModel() {
    private data class AuthContext(
        val localPrivateKeyBytes: ByteArray?,
        val localPrivateKeyHex: String?,
        val signerPubkey: String?,
        val signerPackage: String?,
        val hasExternalSigner: Boolean
    )

    /**
     * Clears all messages from memory. Call this on logout or when switching accounts.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        seenGiftWrapEventIds.clear()
        inboxExternalDecryptRequested.set(false)
        directSubscriptionStarted = false
        directEoseReceived = false
        giftWrapProcessingCount.set(0)
        giftWrapProcessingInProgress = false
        directLoadTimeoutJob?.cancel()
        directLoadTimeoutJob = null
        _isLoading.value = false
        pendingDecryptRetryQueue.clear()
        retryJobActive = false
        // Clear direct DM subscription when clearing messages to avoid stale handlers
        directSubscriptionId?.let {
            try {
                subscriptionManager.unsubscribe(it)
                directSubscriptionId = null
            } catch (e: Exception) {
                Timber.w(e, "Failed to unsubscribe direct subscription %s during clearMessages", it)
            }
        }
    }
    
    /**
     * Retry decryption of messages that failed due to missing launcher.
     * Called when launcher becomes available.
     */
    fun retryPendingDecryptions() {
        if (pendingDecryptRetryQueue.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Timber.d("Retrying %d pending decryptions after launcher became available", pendingDecryptRetryQueue.size)
                val entries = pendingDecryptRetryQueue.toList()
                for ((eventId, pending) in entries) {
                    try {
                        Timber.d("Retrying decrypt for event %s (pending for %dms)", eventId, System.currentTimeMillis() - pending.failureTime)
                        handleGiftWrap(pending.giftWrap, pending.expectedOtherPubkey)
                        pendingDecryptRetryQueue.remove(eventId)
                    } catch (e: Exception) {
                        Timber.w(e, "Retry decrypt failed for event %s", eventId)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error retrying pending decryptions")
            }
        }
    }
    
    private var userPubkey: String = ""
    private var privateKey: String = ""
    // X25519 private key for NIP-44 encryption (32 bytes hex)
    private var x25519PrivateKey: String? = null
    // Derived X25519 public key (32 bytes hex) computed at init for matching recipient tags
    private var x25519PublicKey: String? = null
    // Always use a cleaned/canonical form of the signing pubkey for comparisons
    private val cleanedUserPubkey: String
        get() = cleanPubkeyFormat(userPubkey)
    // Subscription id for direct messages (kind 1059) so we can unsubscribe later
    private var directSubscriptionId: String? = null
    private var directSubscriptionStarted: Boolean = false
    private var directLoadTimeoutJob: Job? = null
    // Active conversation subscription ids (single REQ with two filters for both directions).
    private val conversationSubscriptionIds: MutableList<String> = mutableListOf()
    private var activeConversationPartner: String? = null
    private val seenGiftWrapEventIds = ConcurrentHashMap.newKeySet<String>()
    // Track EOSE and message processing for coordinated loading
    @Volatile
    private var directEoseReceived = false
    private val giftWrapProcessingCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var giftWrapProcessingInProgress = false
    private val giftWrapProcessingMutex = Mutex()
    private val inboxExternalDecryptRequested = AtomicBoolean(false)
    
    // Queue of gift-wrapped messages that failed to decrypt due to missing launcher
    // Retry when launcher becomes available
    private data class PendingDecryptMessage(
        val eventId: String,
        val giftWrap: JSONObject,
        val expectedOtherPubkey: String?,
        val failureTime: Long = System.currentTimeMillis()
    )
    private val pendingDecryptRetryQueue = ConcurrentHashMap<String, PendingDecryptMessage>()
    private var retryJobActive = false
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages
    // Buffer plain (kind 14) messages that arrive while initial loading is active
    private val pendingPlainMessages = ConcurrentLinkedQueue<org.json.JSONObject>()
    // Sampling counters to throttle high-frequency logs
    private val dmEventCounter = AtomicLong(0)
    private val subscriptionEventCounter = AtomicLong(0)
    private val bufferLogCounter = AtomicLong(0)
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    // Errors from send operations (observable by UI)
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError
    fun clearSendError() {
        _sendError.value = null
    }
    

    /**
     * Register the message handler for incoming events (if not already registered).
     */
    private fun registerMessageHandler() {
        nostrClient.registerMessageHandler { message ->
            try {
                val arr = JSONArray(message)
                if (arr.length() > 2 && arr.getString(0) == "EVENT") {
                    val eventObj = arr.getJSONObject(2)
                    val kind = eventObj.optInt("kind")
                    // Only process DM-related kinds
                    if (kind == 12 || kind == 14 || kind == 1059) {
                        val eventJson = eventObj.toString()
                        if (dmEventCounter.incrementAndGet() % 500L == 0L) {
                            Timber.d("Received DM event (sample): %s", eventJson)
                        }
                        when (kind) {
                            12 -> handleGiftWrap(eventObj) // NIP-59 Gift Wrap
                            14 -> { // NIP-17 Direct Message (legacy, if any)
                                // Plain NIP-17 (kind 14) messages: buffer during initial load to avoid incremental UI
                                if (_isLoading.value) {
                                    pendingPlainMessages.add(eventObj)
                                    if (bufferLogCounter.incrementAndGet() % 200L == 0L) {
                                        Timber.d("Buffered plain DM during loading (sample): %s", eventObj.optString("id"))
                                    }
                                } else {
                                    val msg = MessageRepository.parseMessage(eventJson)
                                    if (dmEventCounter.get() % 200L == 0L) {
                                        Timber.d("Parsed direct message (sample): %s", msg)
                                    }
                                    if (msg != null && msg.recipientPubkeys.contains(userPubkey)) {
                                        _messages.value = _messages.value + msg
                                        if (dmEventCounter.get() % 200L == 0L) {
                                            Timber.d("Added direct message to state (sample): %s", msg.id)
                                        }
                                    }
                                }
                            }
                            1059 -> handleGiftWrap(eventObj) // NIP-17 Encrypted DM (NIP-59 Gift Wrap)
                        }
                    } else {
                        // Ignore non-DM events (no log)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MessagesViewModel", "Failed to process message", e)
            }
        }
    }

    fun bindSessionAuthState(sessionPubkey: String?, sessionPrivateKeyHex: String?) {
        val normalizedSessionPub = normalizeSigningPubkey(sessionPubkey)
        if (normalizedSessionPub.matches(Regex("[0-9a-f]{64}"))) {
            userPubkey = normalizedSessionPub
        }
        val normalizedPriv = sessionPrivateKeyHex?.trim()?.lowercase()
        if (!normalizedPriv.isNullOrBlank() && normalizedPriv.matches(Regex("[0-9a-f]{64}"))) {
            privateKey = normalizedPriv
        }
        viewModelScope.launch(Dispatchers.IO) {
            tryRestoreExternalSignerFromStorage()
        }
    }

    private suspend fun tryRestoreExternalSignerFromStorage(): Boolean {
        val configuredPkg = com.hisa.data.nostr.ExternalSignerManager.getConfiguredPackage()
        val configuredPub = com.hisa.data.nostr.ExternalSignerManager.getConfiguredPubkey()
        if (!configuredPkg.isNullOrBlank() && !configuredPub.isNullOrBlank()) return true

        val storedPkg = secureStorage.getExternalSignerPackage()
        val storedPub = normalizeSigningPubkey(secureStorage.getExternalSignerPubkey())
        if (storedPkg.isNullOrBlank() || !storedPub.matches(Regex("[0-9a-f]{64}"))) return false

        return try {
            com.hisa.data.nostr.ExternalSignerManager.ensureConfigured(storedPub, storedPkg, null)
            true
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore external signer from storage")
            false
        }
    }

    private fun resolveLocalPrivateKeyFromStorageOrSession(): Pair<ByteArray?, String?> {
        val fromNsec = secureStorage.getNsec()?.let { nsec ->
            kotlin.runCatching { com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec) }.getOrNull()
        }?.takeIf { it.size == 32 }
        if (fromNsec != null) {
            val hex = fromNsec.joinToString("") { "%02x".format(it) }
            return Pair(fromNsec, hex)
        }

        val fromHex = if (privateKey.matches(Regex("[0-9a-fA-F]{64}"))) {
            kotlin.runCatching { hexStringToBytes(privateKey) }.getOrNull()
        } else null
        return Pair(fromHex, privateKey.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) })
    }

    private suspend fun resolveAuthContext(refreshOnce: Boolean = true): AuthContext {
        var (localBytes, localHex) = resolveLocalPrivateKeyFromStorageOrSession()
        if (localBytes == null && refreshOnce) {
            kotlin.runCatching { initialize() }
            val refreshed = resolveLocalPrivateKeyFromStorageOrSession()
            localBytes = refreshed.first
            localHex = refreshed.second
        }

        var signerPackage = com.hisa.data.nostr.ExternalSignerManager.getConfiguredPackage()
        var signerPubkey = normalizeSigningPubkey(com.hisa.data.nostr.ExternalSignerManager.getConfiguredPubkey())
        if (signerPackage.isNullOrBlank() || !signerPubkey.matches(Regex("[0-9a-f]{64}"))) {
            if (tryRestoreExternalSignerFromStorage()) {
                signerPackage = com.hisa.data.nostr.ExternalSignerManager.getConfiguredPackage()
                signerPubkey = normalizeSigningPubkey(com.hisa.data.nostr.ExternalSignerManager.getConfiguredPubkey())
            } else {
                signerPackage = secureStorage.getExternalSignerPackage()
                signerPubkey = normalizeSigningPubkey(secureStorage.getExternalSignerPubkey())
            }
        }

        val hasExternalSigner = !signerPackage.isNullOrBlank() && signerPubkey.matches(Regex("[0-9a-f]{64}"))
        return AuthContext(
            localPrivateKeyBytes = localBytes,
            localPrivateKeyHex = localHex,
            signerPubkey = signerPubkey.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
            signerPackage = signerPackage,
            hasExternalSigner = hasExternalSigner
        )
    }

    init {
        // Kick off initialization: derive user pubkey from stored nsec and ensure
        // an X25519 private key exists for NIP-44 encryption.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                initialize()
            } catch (e: Exception) {
                Timber.e(e, "Error during MessagesViewModel initialization")
            }
        }
    }

    fun ensureSubscribed() {
        viewModelScope.launch(Dispatchers.IO) {
            if (directSubscriptionStarted) return@launch
            if (userPubkey.isBlank()) {
                initialize()
            }
            if (userPubkey.isBlank()) {
                Timber.w("Cannot subscribe to direct messages: user pubkey unavailable")
                _isLoading.value = false
                return@launch
            }

            // Wait for external signer launcher to be ready before subscribing
            // This prevents a race condition where messages arrive before the launcher is registered
            val authContext = resolveAuthContext(refreshOnce = false)
            if (authContext.hasExternalSigner && authContext.localPrivateKeyBytes == null) {
                Timber.d("Using external signer: waiting for launcher registration before subscribing to DMs")
                val launcherReady = com.hisa.data.nostr.ExternalSignerManager.waitForLauncherReady(timeoutMs = 10_000)
                if (!launcherReady) {
                    Timber.w("Timeout waiting for external signer launcher registration")
                    // Continue anyway - will try with whatever launcher state we have
                }
            }

            directSubscriptionStarted = true
            directEoseReceived = false
            giftWrapProcessingCount.set(0)
            giftWrapProcessingInProgress = false
            _isLoading.value = true
            directLoadTimeoutJob?.cancel()
            directLoadTimeoutJob = viewModelScope.launch(Dispatchers.IO) {
                delay(4000)
                finalizeDirectMessageLoading("timeout")
            }

            try {
                var assignedSubId = ""
                val subId = subscriptionManager.subscribeToDirectMessages(
                    userPubkey = userPubkey,
                    sinceTime = DIRECT_DM_SINCE,
                    limitPerDirection = DIRECT_DM_LIMIT_PER_FILTER,
                    onEvent = { nostrEvent ->
                        try {
                            // Increment counter: a new message is incoming
                            giftWrapProcessingCount.incrementAndGet()
                            giftWrapProcessingInProgress = true
                            
                            val obj = JSONObject().apply {
                                put("id", nostrEvent.id)
                                put("pubkey", nostrEvent.pubkey)
                                put("created_at", nostrEvent.createdAt)
                                put("kind", nostrEvent.kind)
                                put("tags", JSONArray(nostrEvent.tags.map { tagParts -> JSONArray(tagParts) }))
                                put("content", nostrEvent.content)
                                put("sig", nostrEvent.sig)
                            }
                            Timber.d("Direct DM subscription callback for subId=%s eventId=%s (totalIncoming=%d)", assignedSubId, nostrEvent.id, giftWrapProcessingCount.get())
                            handleGiftWrap(obj, null)
                        } catch (e: Exception) {
                            Timber.e(e, "Error handling subscribed direct message event")
                            giftWrapProcessingCount.decrementAndGet()
                            checkIfAllMessagesProcessed()
                        }
                    },
                    onEndOfStoredEvents = {
                        // Main subscription completed - record that EOSE was received
                        directEoseReceived = true
                        Timber.d("Direct messages EOSE received. Messages arriving: %d", giftWrapProcessingCount.get())
                        // Check if all processing is done
                        checkIfAllMessagesProcessed()
                    }
                )
                directSubscriptionId = subId
                assignedSubId = subId
                Timber.i(
                    "Subscribed to direct messages for userPubkey=%s subId=%s since=%d limitPerFilter=%d",
                    userPubkey,
                    subId,
                    DIRECT_DM_SINCE,
                    DIRECT_DM_LIMIT_PER_FILTER
                )
            } catch (e: Exception) {
                directSubscriptionStarted = false
                _isLoading.value = false
                Timber.e(e, "Failed to subscribe to direct messages for userPubkey=%s", userPubkey)
            }
        }
    }

    private fun checkIfAllMessagesProcessed() {
        // Simple pattern: just like FeedViewModel
        // Can only finalize if:
        // 1. EOSE has been received (all messages from relay arrived)
        // 2. All message processing is complete (giftWrapProcessingCount == 0)
        // Metadata loading is NOT blocking - it happens in background in the UI layer
        if (!directEoseReceived) {
            Timber.d("checkIfAllMessagesProcessed: Still waiting for EOSE")
            return
        }
        
        val pendingCount = giftWrapProcessingCount.get()
        if (pendingCount > 0) {
            Timber.d("checkIfAllMessagesProcessed: %d messages still processing", pendingCount)
            return
        }
        
        if (giftWrapProcessingInProgress) {
            Timber.d("checkIfAllMessagesProcessed: Processing still in progress")
            return
        }
        
        // All conditions met: finalize loading - just like FeedViewModel
        if (_isLoading.value) {
            finalizeDirectMessageLoading("eose_and_all_messages_processed")
        }
    }

    private fun finalizeDirectMessageLoading(reason: String) {
        if (!_isLoading.value) return
        
        directLoadTimeoutJob?.cancel()
        directLoadTimeoutJob = null
        _isLoading.value = false
        Timber.d("Direct messages loading finalized via: %s (totalMessages=%d conversations=%d)", reason, _messages.value.size, getConversations().size)
        // Drain any plain NIP-17 messages that arrived while we were loading
        try {
            while (true) {
                val ev = pendingPlainMessages.poll() ?: break
                try {
                    val evJson = ev.toString()
                    val msg = MessageRepository.parseMessage(evJson)
                    if (msg != null && msg.recipientPubkeys.contains(userPubkey)) {
                        _messages.update { current ->
                            if (current.any { it.id == msg.id }) current else (current + msg).sortedBy { it.createdAt }
                        }
                        Timber.d("Drained buffered plain DM: %s", msg.id)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to process buffered plain DM")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error draining pending plain messages")
        }
    }

    fun stopDirectMessagesSubscription() {
        directLoadTimeoutJob?.cancel()
        directLoadTimeoutJob = null
        _isLoading.value = false
        directSubscriptionStarted = false
        directEoseReceived = false
        giftWrapProcessingCount.set(0)
        giftWrapProcessingInProgress = false
        directSubscriptionId?.let { id ->
            try {
                subscriptionManager.unsubscribe(id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unsubscribe direct subscription %s", id)
            } finally {
                directSubscriptionId = null
            }
        }
    }



    private companion object {
        const val KIND_GIFT_WRAP = 1059
        const val KIND_SEAL = 13
        const val KIND_DM = 14
        private const val DIRECT_DM_HISTORY_DAYS = 180L
        private const val DIRECT_DM_LIMIT_PER_FILTER = 1000
        private const val CONVERSATION_DM_HISTORY_DAYS = 180L
        private const val CONVERSATION_DM_LIMIT_PER_FILTER = 1000
        // Stable timestamp across VM instances in one process, helps filter dedupe in SubscriptionManager.
        val DIRECT_DM_SINCE: Long =
            (System.currentTimeMillis() / 1000) - (DIRECT_DM_HISTORY_DAYS * 24L * 60L * 60L)
    // Use centralized onboarding relays as a safe default; runtime val (not const)
    val relayUrl: String = Constants.ONBOARDING_RELAYS.firstOrNull() ?: ""
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun decryptNip17Message(wrapEvent: JSONObject, expectedOtherPubkey: String? = null): Message? {
        return try {
            val authContext = resolveAuthContext(refreshOnce = true)
            val senderHints = buildList {
                val expected = cleanPubkeyFormat(expectedOtherPubkey ?: "")
                if (expected.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) add(expected.lowercase())
                _messages.value.forEach { msg ->
                    val sender = cleanPubkeyFormat(msg.pubkey).lowercase()
                    if (sender.matches(Regex("[0-9a-f]{64}")) && !sender.equals(cleanedUserPubkey, true)) {
                        add(sender)
                    }
                    msg.recipientPubkeys
                        .map { cleanPubkeyFormat(it).lowercase() }
                        .filter { it.matches(Regex("[0-9a-f]{64}")) && !it.equals(cleanedUserPubkey, true) }
                        .forEach { add(it) }
                }
            }
            val innerEvent = authContext.localPrivateKeyBytes?.let { recipientPrivateKeyBytes ->
                timber.log.Timber.d("Using local nsec key for NIP-44 decrypt")
                messageRepository.decryptGiftWrappedMessage(
                    event = wrapEvent,
                    recipientPrivateKey = recipientPrivateKeyBytes,
                    senderPubkeyHints = senderHints
                )
            } ?: run {
                if (!com.hisa.data.nostr.ExternalSignerManager.isLauncherRegistered()) {
                    Timber.d("Skipping external decrypt: launcher not registered")
                    return null
                }
                val signerPkg = authContext.signerPackage ?: return null
                val signerPub = authContext.signerPubkey
                    ?: return null
                kotlin.runCatching {
                    com.hisa.data.nostr.ExternalSignerManager.ensureConfigured(
                        signerPub,
                        signerPkg,
                        null
                    )
                }
                timber.log.Timber.d("Using external signer for NIP-44 decrypt")
                // When using an external signer we should avoid prompting it repeatedly for
                // many candidate public keys. Prefer hinted candidates and limit attempts
                // so the launcher isn't spammed with multiple decrypt prompts.
                try {
                    messageRepository.decryptGiftWrappedMessage(
                        event = wrapEvent,
                        recipientPrivateKey = null,
                        externalDecryptor = { ciphertext, senderPubkey ->
                            com.hisa.data.nostr.ExternalSignerManager.nip44Decrypt(ciphertext, senderPubkey)
                        },
                        maxSenderCandidates = 1,
                        senderPubkeyHints = senderHints
                    )
                } catch (e: Exception) {
                    Timber.w(e, "External signer decrypt threw; treating as undecryptable for now")
                    null
                }
            } ?: return null

            val innerPTags = mutableListOf<String>()
            val innerTags = innerEvent.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until innerTags.length()) {
                val t = innerTags.optJSONArray(i) ?: continue
                if (t.optString(0) == "p") innerPTags.add(cleanPubkeyFormat(t.optString(1)))
            }
            Timber.d(
                "DM decrypt trace: outerId=%s outerPub=%s expectedOther=%s innerId=%s innerKind=%d innerPub=%s innerPTags=%s resolvedSender=%s",
                wrapEvent.optString("id", ""),
                cleanPubkeyFormat(wrapEvent.optString("pubkey", "")),
                cleanPubkeyFormat(expectedOtherPubkey ?: ""),
                innerEvent.optString("id", ""),
                innerEvent.optInt("kind", -1),
                cleanPubkeyFormat(innerEvent.optString("pubkey", "")),
                innerPTags.joinToString(","),
                cleanPubkeyFormat(innerEvent.optString("__resolved_sender_pubkey", ""))
            )

            // 2. Parse the decrypted message
            val parsed = messageRepository.parseMessage(innerEvent.toString()) ?: run {
                Timber.w(
                    "Decrypted inner event parse failed: outerId=%s innerId=%s innerKind=%d innerPub=%s",
                    wrapEvent.optString("id", ""),
                    innerEvent.optString("id", ""),
                    innerEvent.optInt("kind", -1),
                    cleanPubkeyFormat(innerEvent.optString("pubkey", ""))
                )
                return null
            }

            // 3. Normalize the message
            normalizeMessage(
                message = parsed,
                outerPubkey = wrapEvent.optString("pubkey"),
                resolvedSenderPubkey = innerEvent.optString("__resolved_sender_pubkey"),
                expectedOtherPubkey = expectedOtherPubkey
            ).also {
                Timber.d(
                    "DM normalize trace: msgId=%s finalSender=%s recipients=%s expectedOther=%s me=%s",
                    it.id,
                    cleanPubkeyFormat(it.pubkey),
                    it.recipientPubkeys.map { pk -> cleanPubkeyFormat(pk) },
                    cleanPubkeyFormat(expectedOtherPubkey ?: ""),
                    cleanedUserPubkey
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt NIP-17 message")
            null
        }
    }

    private fun normalizeMessage(
        message: Message,
        outerPubkey: String,
        resolvedSenderPubkey: String?,
        expectedOtherPubkey: String?
    ): Message {
        val cleanedResolved = cleanPubkeyFormat(resolvedSenderPubkey ?: "")
        val cleanedExpected = cleanPubkeyFormat(expectedOtherPubkey ?: "")
        val cleanedOuter = cleanPubkeyFormat(outerPubkey)

        fun normalizeSenderAndRecipients(pubkey: String, recipients: List<String>): Pair<String, List<String>> {
            val cleanedPub = cleanPubkeyFormat(pubkey)
            val cleanedRecipients = recipients.map { cleanPubkeyFormat(it) }.filter { it.isNotBlank() }
            val incomingForMe = cleanedRecipients.any { it.equals(cleanedUserPubkey, true) }
            val senderLooksWrapperLike =
                cleanedPub.isBlank() ||
                cleanedPub.equals(cleanedUserPubkey, true) ||
                cleanedPub.equals(cleanedOuter, true)

            val candidateExpected = if (cleanedExpected.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) cleanedExpected else ""
            val candidateResolved = if (cleanedResolved.matches(Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE))) cleanedResolved else ""

            // Replace the wrapper-like sender when we have a better candidate that is not ourselves.
            val shouldReplace = senderLooksWrapperLike && (
                (candidateExpected.isNotBlank() && !candidateExpected.equals(cleanedUserPubkey, true)) ||
                (candidateResolved.isNotBlank() && !candidateResolved.equals(cleanedUserPubkey, true)) ||
                incomingForMe
            )

            val finalPub = when {
                shouldReplace && candidateExpected.isNotBlank() && !candidateExpected.equals(cleanedUserPubkey, true) -> candidateExpected
                shouldReplace && candidateResolved.isNotBlank() && !candidateResolved.equals(cleanedUserPubkey, true) -> candidateResolved
                else -> cleanedPub
            }

            return Pair(finalPub, cleanedRecipients)
        }

        return when (message) {
            is Message.TextMessage -> {
                val (pub, recips) = normalizeSenderAndRecipients(message.pubkey, message.recipientPubkeys)
                message.copy(pubkey = pub, recipientPubkeys = recips)
            }
            is Message.FileMessage -> {
                val (pub, recips) = normalizeSenderAndRecipients(message.pubkey, message.recipientPubkeys)
                message.copy(pubkey = pub, recipientPubkeys = recips)
            }
            else -> message
        }
    }

    private suspend fun updateMessageState(message: Message, expectedOtherPubkey: String?) {
        val sender = cleanPubkeyFormat(message.pubkey)
        val recipients = message.recipientPubkeys.map { cleanPubkeyFormat(it) }
        val relatesToCurrentUser =
            sender.equals(cleanedUserPubkey, true) ||
                recipients.any { it.equals(cleanedUserPubkey, true) }
        if (!relatesToCurrentUser) {
            Timber.d(
                "Skipping message not addressed to current user: id=%s sender=%s recipients=%s me=%s",
                message.id,
                sender,
                recipients,
                cleanedUserPubkey
            )
            return
        }

        val isInConversation = expectedOtherPubkey?.let { other ->
            message.pubkey == cleanPubkeyFormat(other) || 
            message.recipientPubkeys.contains(cleanPubkeyFormat(other))
        } ?: true

        if (!isInConversation) {
            Timber.w("Message not in current conversation: ${message.id}")
            return
        }

        _messages.update { current ->
            if (current.any { it.id == message.id }) {
                current // Skip duplicates
            } else {
                (current + message).sortedBy { it.createdAt }.also {
                    Timber.i("Added message to state. Total: ${it.size}")
                }
            }
        }
    }

    /**
     * Add placeholder for undecryptable messages
     */
    private suspend fun addUndecryptablePlaceholder(
        giftWrap: JSONObject, 
        expectedOtherPubkey: String?
    ) {
        try {
            val outerPubkey = giftWrap.optString("pubkey")
            // Prefer the expected conversation partner when available. The outer event
            // pubkey may be an ephemeral signing key used for the gift-wrap; using that
            // for UI/grouping causes the wrong pubkey to show when decryption fails.
            val displayPubkey = if (!expectedOtherPubkey.isNullOrBlank()) {
                cleanPubkeyFormat(expectedOtherPubkey)
            } else {
                cleanPubkeyFormat(outerPubkey)
            }

            // Skip if the placeholder would point to ourselves
            if (displayPubkey == cleanedUserPubkey) return

            val placeholder = Message.TextMessage(
                id = giftWrap.optString("id"),
                pubkey = displayPubkey,
                recipientPubkeys = extractRecipients(giftWrap, expectedOtherPubkey),
                content = "Unable to decrypt message",
                createdAt = giftWrap.optLong("created_at"),
                subject = null,
                replyTo = null,
                relayUrls = null
            )
            Timber.w(
                "Placeholder trace: eventId=%s outerPub=%s expectedOther=%s displayPub=%s recipients=%s",
                giftWrap.optString("id", ""),
                cleanPubkeyFormat(outerPubkey),
                cleanPubkeyFormat(expectedOtherPubkey ?: ""),
                cleanPubkeyFormat(displayPubkey),
                placeholder.recipientPubkeys.map { cleanPubkeyFormat(it) }
            )
            
            _messages.update { current ->
                if (current.any { it.id == placeholder.id }) current 
                else (current + placeholder).sortedBy { it.createdAt }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create placeholder")
        }
    }

    /**
     * Extract recipients from event tags
     */
    private fun extractRecipients(event: JSONObject, fallbackPubkey: String?): List<String> {
        val recipients = mutableListOf<String>()
        val tags = event.optJSONArray("tags") ?: JSONArray()
        
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.optString(0) == "p") {
                recipients.add(cleanPubkeyFormat(tag.optString(1)))
            }
        }

        if (recipients.isEmpty() && fallbackPubkey != null) {
            recipients.add(cleanPubkeyFormat(fallbackPubkey))
        }

        return recipients
    }

    override fun onCleared() {
        super.onCleared()
        // Subscriptions created via SubscriptionManager should be unsubscribed explicitly
        stopDirectMessagesSubscription()
    }

    /**
     * Initialize the view model by reading stored nsec (if any) to populate
     * the user's signing pubkey, and ensure an X25519 private key exists.
     * If no X25519 key is found or it is invalid, a new one will be generated
     * and stored in secure storage.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun initialize() {
        try {
            // Populate signing pubkey/privateKey if we have an nsec stored
            val nsec = secureStorage.getNsec()
            if (!nsec.isNullOrBlank()) {
                try {
                    val privBytes = com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec)
                    if (privBytes.size == 32) {
                        val ecKey = org.bitcoinj.core.ECKey.fromPrivate(privBytes)
                        val uncompressed = ecKey.decompress().pubKeyPoint.getEncoded(false)
                        val xOnly = uncompressed.copyOfRange(1, 33)
                        userPubkey = xOnly.joinToString("") { "%02x".format(it) }
                        privateKey = privBytes.joinToString("") { "%02x".format(it) }
                        // Zero the raw bytes as soon as we consumed them
                        for (i in privBytes.indices) privBytes[i] = 0
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to derive signing pubkey from stored nsec")
                }
            }

            // External signer fallback: recover signing pubkey/package from persisted auth prefs.
            if (userPubkey.isBlank()) {
                val extPub = normalizeSigningPubkey(secureStorage.getExternalSignerPubkey())
                if (extPub.matches(Regex("[0-9a-fA-F]{64}"))) {
                    userPubkey = extPub.lowercase()
                }
            }
            try {
                val extPub = secureStorage.getExternalSignerPubkey()
                val extPkg = secureStorage.getExternalSignerPackage()
                if (!extPub.isNullOrBlank() && !extPkg.isNullOrBlank()) {
                    kotlinx.coroutines.runBlocking {
                        com.hisa.data.nostr.ExternalSignerManager.ensureConfigured(
                            normalizeSigningPubkey(extPub),
                            extPkg,
                            null
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to restore external signer configuration")
            }

            // Ensure an X25519 private key exists in secure storage
            var storedX25519 = secureStorage.getX25519PrivateKey()
            if (storedX25519.isNullOrBlank() || storedX25519.length != 64 || !storedX25519.matches(Regex("[0-9a-fA-F]{64}"))) {
                // Generate a new 32-byte X25519 key and persist it
                val rng = SecureRandom()
                val keyBytes = ByteArray(32)
                rng.nextBytes(keyBytes)
                val hex = keyBytes.joinToString("") { "%02x".format(it) }
                secureStorage.storeX25519PrivateKey(hex)
                // Zero sensitive bytes
                for (i in keyBytes.indices) keyBytes[i] = 0
                storedX25519 = hex
                Timber.d("Generated new X25519 private key and stored in secure storage")
            }
            x25519PrivateKey = storedX25519
            // Derive and cache our X25519 public key hex for later recipient matching
            try {
                val privBytesForPub = hexStringToBytes(storedX25519)
                try {
                    val xPrivForPub = X25519PrivateKeyParameters(privBytesForPub, 0)
                    val xPub = ByteArray(32)
                    xPrivForPub.generatePublicKey().encode(xPub, 0)
                    x25519PublicKey = xPub.joinToString("") { "%02x".format(it) }
                } finally {
                    // zero sensitive bytes
                    for (i in privBytesForPub.indices) privBytesForPub[i] = 0
                }
            } catch (e: Exception) {
                x25519PublicKey = null
                Timber.w(e, "Failed to derive X25519 public key from stored private key")
            }
            Timber.i("MessagesViewModel initialized: userPubkey=%s x25519Present=%s", userPubkey, !x25519PrivateKey.isNullOrBlank())
        } catch (e: Exception) {
            Timber.e(e, "Initialization failed for MessagesViewModel")
        }
    }

    /**
     * Allows setting/replacing the X25519 private key (hex) at runtime and persists it.
     */
    fun setX25519PrivateKey(hex: String) {
        if (!hex.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw IllegalArgumentException("X25519 private key must be 64 hex characters (32 bytes)")
        }
        secureStorage.storeX25519PrivateKey(hex.lowercase())
        x25519PrivateKey = hex.lowercase()
    }

   
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleGiftWrap(giftWrap: JSONObject, expectedOtherPubkey: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                giftWrapProcessingMutex.withLock {
                    try {
                        val eventId = giftWrap.optString("id", "unknown")
                        if (eventId.isNotBlank() && eventId != "unknown") {
                            val firstSeen = seenGiftWrapEventIds.add(eventId)
                            if (!firstSeen) {
                                Timber.d("Skipping duplicate gift wrap event: %s", eventId)
                                return@withLock
                            }
                        }
                        Timber.i("Processing gift wrap event: $eventId")

                        // 1. Verify message is for current user
                        if (!isMessageForCurrentUser(giftWrap)) {
                            Timber.d("Message not for current user, skipping")
                            return@withLock
                        }

                        val authContext = resolveAuthContext(refreshOnce = false)
                        val usingExternalSignerOnly = authContext.localPrivateKeyBytes == null && authContext.hasExternalSigner
                        val hasNoDecryptCapability = authContext.localPrivateKeyBytes == null && !authContext.hasExternalSigner

                        if (hasNoDecryptCapability) {
                            addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                            return@withLock
                        }

                        if (usingExternalSignerOnly && expectedOtherPubkey.isNullOrBlank()) {
                            if (!com.hisa.data.nostr.ExternalSignerManager.isLauncherRegistered()) {
                                Timber.d("Inbox: launcher not registered, queueing for retry: %s", eventId)
                                val eventId = giftWrap.optString("id", "")
                                if (eventId.isNotBlank()) {
                                    pendingDecryptRetryQueue[eventId] = PendingDecryptMessage(eventId, giftWrap, expectedOtherPubkey)
                                }
                                return@withLock
                            }
                            // Messages tab (inbox) path: trigger signer request at most once.
                            // This allows one-time approval prompt while avoiding a request storm.
                            val shouldRequestOnce = inboxExternalDecryptRequested.compareAndSet(false, true)
                            if (!shouldRequestOnce) {
                                addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                                return@withLock
                            }
                            val decryptedMessage = decryptNip17Message(giftWrap, expectedOtherPubkey)
                            if (decryptedMessage != null) {
                                updateMessageState(decryptedMessage, expectedOtherPubkey)
                            } else {
                                addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                            }
                            return@withLock
                        }

                        // When launcher is not attached, queue for retry instead of marking undecryptable
                        if (usingExternalSignerOnly && !com.hisa.data.nostr.ExternalSignerManager.isLauncherRegistered()) {
                            Timber.d("Conversation: launcher not registered, queueing for retry: %s", eventId)
                            val eventId = giftWrap.optString("id", "")
                            if (eventId.isNotBlank()) {
                                pendingDecryptRetryQueue[eventId] = PendingDecryptMessage(eventId, giftWrap, expectedOtherPubkey)
                            }
                            return@withLock
                        }

                        // 2. Decrypt the message
                        val decryptedMessage = decryptNip17Message(giftWrap, expectedOtherPubkey) ?: run {
                            Timber.e("Failed to decrypt or parse message %s", eventId)
                            addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                            return@withLock
                        }

                        // 3. Update state if message is valid
                        updateMessageState(decryptedMessage, expectedOtherPubkey)
                    } catch (e: Exception) {
                        Timber.e(e, "Error in handleGiftWrap")
                        addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                    }
                }
            } finally {
                // Decrement counter: this message has finished processing
                val count = giftWrapProcessingCount.decrementAndGet()
                Timber.d("Gift wrap processing complete. Remaining: %d", count)
                giftWrapProcessingInProgress = false
                // Check if all messages are now complete
                checkIfAllMessagesProcessed()
            }
        }
    }

    private fun isMessageForCurrentUser(event: JSONObject): Boolean {
        // Keep events authored by us so outbox/history appears in conversation lists.
        if (cleanPubkeyFormat(event.optString("pubkey")) == cleanedUserPubkey) {
            return true
        }

        val tags = event.optJSONArray("tags") ?: return false
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.optString(0) == "p") {
                val recipientPubkey = cleanPubkeyFormat(tag.optString(1))
                if (recipientPubkey == cleanedUserPubkey) {
                    return true
                }
            }
        }
        return false
    }

    fun getConversations(): Map<String, List<Message>> {
        val messages = _messages.value
        Timber.d("Total messages in state: %d", messages.size)
        // Sample up to 5 messages for tracing to avoid noisy logs
        if (messages.isNotEmpty()) {
            messages.take(5).forEach { msg ->
                Timber.d(
                    "Conversation source sample: msgId=%s sender=%s recipients=%s placeholder=%s",
                    msg.id,
                    cleanPubkeyFormat(msg.pubkey),
                    msg.recipientPubkeys.map { cleanPubkeyFormat(it) },
                    (msg is Message.TextMessage && msg.content == "Unable to decrypt message")
                )
            }
            if (messages.size > 5) {
                Timber.d("...and %d more messages not logged for brevity", messages.size - 5)
            }
        }

        fun buildChatroomKey(message: Message): ChatroomKey {
            val participants = buildSet {
                val sender = cleanPubkeyFormat(message.pubkey)
                if (sender.isNotBlank()) add(sender)
                message.recipientPubkeys
                    .map { cleanPubkeyFormat(it) }
                    .filter { it.isNotBlank() }
                    .forEach { add(it) }
            }
            val others = participants.filter { !it.equals(cleanedUserPubkey, true) }.toSet()
            return if (others.isNotEmpty()) {
                ChatroomKey(others)
            } else {
                // Self-only fallback should be rare; keep sender to avoid dropping message.
                ChatroomKey(setOf(cleanPubkeyFormat(message.pubkey)))
            }
        }

        val groupedByRoom = messages.groupBy(::buildChatroomKey)

        // Keep existing UI contract: Map<otherPubkey, messages> for 1:1 chats.
        // For group keys, we still expose a deterministic synthetic key.
        // Sort messages within each conversation chronologically (newest first) for better UX
        val grouped = groupedByRoom.mapKeys { (room, _) ->
            if (room.isOneToOne) room.users.first() else room.users.sorted().joinToString(",")
        }.filterKeys { it.isNotBlank() }
            .mapValues { (_, msgs) ->
                msgs.sortedByDescending { it.createdAt }
            }
        
        // Sort conversations by most recent message timestamp (newest first)
        val sortedGrouped = grouped.toList()
            .sortedByDescending { (_, msgs) -> msgs.maxOfOrNull { it.createdAt } ?: 0L }
            .toMap()
        
        Timber.i("Grouped %d messages into %d conversations (sorted by recency): %s", 
            messages.size,
            sortedGrouped.size,
            sortedGrouped.map { (key, msgs) -> "$key: ${msgs.size} msgs, latest=${msgs.firstOrNull()?.createdAt}" })
            
        return sortedGrouped
    }


    private fun createInnerMessageEvent(
        recipientPubkey: String,
        content: String,
        subject: String?,
        replyTo: String?
    ): JSONObject {
        // Build the tags list
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", recipientPubkey))
        subject?.let { tags.add(listOf("subject", it)) }
        replyTo?.let { tags.add(listOf("e", it)) }

        // Create the message event using MessageRepository
        return messageRepository.createMessageEvent(
            kind = 14,  // NIP-17 Direct Message
            content = content,
            tags = tags,
            createdAt = System.currentTimeMillis() / 1000
        )
    }

    private suspend fun sendGiftWrappedMessage(
        innerMessage: JSONObject,
        recipientPubkey: String,
        encryptionPrivateKeyBytes: ByteArray?,
        senderSigningPubkey: String
    ) {
        val innerKind = innerMessage.optInt("kind", 14)
        val innerContent = innerMessage.optString("content", "")
        val innerTags = innerMessage.optJSONArray("tags")?.let { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONArray(i) ?: continue
                    add(buildList {
                        for (j in 0 until t.length()) add(t.optString(j))
                    })
                }
            }
        } ?: listOf(listOf("p", recipientPubkey))

        // Use the MessageRepository to prepare the encrypted message
        val wrappedEvent = if (encryptionPrivateKeyBytes != null) {
            messageRepository.prepareGiftWrappedMessage(
                senderPrivateKey = encryptionPrivateKeyBytes,
                recipientPubkey = recipientPubkey,
                content = innerContent,
                kind = innerKind,
                tags = innerTags
            )
        } else {
            if (!com.hisa.data.nostr.ExternalSignerManager.isLauncherRegistered()) {
                throw IllegalStateException("External signer launcher not registered")
            }
            val authCtx = resolveAuthContext(refreshOnce = false)
            messageRepository.prepareGiftWrappedMessageExternal(
                senderSigningPubkey = senderSigningPubkey,
                recipientPubkey = recipientPubkey,
                content = innerContent,
                kind = innerKind,
                tags = innerTags,
                externalSignerPubkey = authCtx.signerPubkey,
                externalSignerPackage = authCtx.signerPackage,
                externalEncryptor = { plaintext, peerPubkey ->
                    com.hisa.data.nostr.ExternalSignerManager.nip44Encrypt(plaintext, peerPubkey)
                }
            )
        }
        // NIP-59: outer gift-wrap (kind 1059) must be signed by the random one-time key.
        // MessageRepository already returns a fully signed wrapper event; publish it as-is.
        val eventJson = wrappedEvent
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
    }

    private fun resolveSenderSigningPubkey(signingPrivateKeyBytes: ByteArray?): String {
        if (cleanedUserPubkey.matches(Regex("[0-9a-f]{64}"))) {
            return cleanedUserPubkey
        }
        val configuredExternalPubkey = com.hisa.data.nostr.ExternalSignerManager.getConfiguredPubkey()
        if (!configuredExternalPubkey.isNullOrBlank()) {
            val normalized = normalizeSigningPubkey(configuredExternalPubkey)
            if (normalized.matches(Regex("[0-9a-f]{64}"))) return normalized
        }
        val storedExternalPub = normalizeSigningPubkey(secureStorage.getExternalSignerPubkey())
        if (storedExternalPub.matches(Regex("[0-9a-f]{64}"))) {
            return storedExternalPub
        }
        if (signingPrivateKeyBytes != null && signingPrivateKeyBytes.size == 32) {
            val key = org.bitcoinj.core.ECKey.fromPrivate(signingPrivateKeyBytes)
            val uncompressed = key.decompress().pubKeyPoint.getEncoded(false)
            val xOnly = uncompressed.copyOfRange(1, 33)
            return xOnly.joinToString("") { "%02x".format(it) }
        }
        return cleanedUserPubkey
    }

    private fun hexStringToBytes(hex: String): ByteArray {
        // Clean the hex string and ensure it's properly formatted
        val cleanHex = hex.trim().lowercase().replace("0x", "")
        
        // Validate hex string length
        if (cleanHex.length != 64) {
            throw IllegalArgumentException("Invalid private key length. Expected 32 bytes (64 hex characters)")
        }
        
        // Validate hex string characters
        if (!cleanHex.matches(Regex("[0-9a-f]+"))) {
            throw IllegalArgumentException("Invalid hex characters in private key")
        }
        
        val bytes = ByteArray(32)
        for (i in 0 until 64 step 2) {
            bytes[i / 2] = cleanHex.substring(i, i + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun normalizeSigningPubkey(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val trimmed = input.trim()
        val asHex = if (trimmed.startsWith("npub", true)) {
            com.hisa.util.KeyGenerator.npubToPublicKey(trimmed) ?: trimmed
        } else {
            trimmed
        }
        return cleanPubkeyFormat(asHex).lowercase()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendMessage(recipientPubkey: String, content: String, subject: String? = null, replyTo: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val authContext = resolveAuthContext(refreshOnce = true)
                val signingPrivateKeyBytes = authContext.localPrivateKeyBytes
                
                // Nostr addressing uses the recipient signing pubkey (kind:1059 p-tag).
                val cleanRecipientPubkey = cleanPubkeyFormat(recipientPubkey)
                if (!cleanRecipientPubkey.matches(Regex("[0-9a-f]{64}"))) {
                    _sendError.value = "Invalid recipient pubkey"
                    return@launch
                }
                
                // If we have no local signing key and no external signer configured, error out
                if (signingPrivateKeyBytes == null && !authContext.hasExternalSigner) {
                    val err = "No signing key available. Configure an external signer or import an nsec to send messages."
                    Timber.e(err)
                    _sendError.value = err
                    return@launch
                }
                if (signingPrivateKeyBytes == null && !com.hisa.data.nostr.ExternalSignerManager.isLauncherRegistered()) {
                    _sendError.value = "External signer is not attached. Re-open DM screen and approve signer requests."
                    return@launch
                }

                val senderSigningPubkey = resolveSenderSigningPubkey(signingPrivateKeyBytes)
                if (!senderSigningPubkey.matches(Regex("[0-9a-f]{64}"))) {
                    _sendError.value = "Unable to resolve sender pubkey for DM event signing"
                    return@launch
                }

                // Create the inner kind:14 message event
                val innerMessage = createInnerMessageEvent(cleanRecipientPubkey, content, subject, replyTo)
                
                // Add to local state immediately for UI responsiveness (optimistic update)
                val tempId = "temp-${System.currentTimeMillis()}"
                val newMessage = Message.TextMessage(
                    id = tempId,
                    pubkey = senderSigningPubkey,
                    recipientPubkeys = listOf(cleanRecipientPubkey),
                    content = content,
                    createdAt = System.currentTimeMillis() / 1000,
                    subject = subject,
                    replyTo = replyTo,
                    relayUrls = null // Will be updated when we receive the relay's copy
                )
                Timber.i("Adding optimistic message id=%s to=%s", tempId, cleanRecipientPubkey)
                _messages.value = _messages.value + newMessage

                // Create and send a gift-wrapped version for the recipient (inner message kind should be 14)
                sendGiftWrappedMessage(
                    innerMessage = innerMessage,
                    recipientPubkey = cleanRecipientPubkey,
                    encryptionPrivateKeyBytes = signingPrivateKeyBytes,
                    senderSigningPubkey = senderSigningPubkey
                )

                // Save conversation mapping
                val myPub = senderSigningPubkey.ifBlank { userPubkey }
                ConversationRepository.getOrCreateConversation(
                    listOf(myPub, cleanRecipientPubkey)
                )

            } catch (e: Exception) {
                Timber.e(e, "Failed to send message")
                _sendError.value = e.message ?: "Unknown error while sending message"
                // Do not rethrow to avoid crashing background coroutine
            }
        }
    }

    /**
     * Subscribe to a two-way conversation with [otherPubkey]. Creates two subscriptions
     * (other->me and me->other) both filtering on kind=1059 and p-tags.
     */
    fun subscribeToConversation(otherPubkey: String) {
        try {
            val me = cleanedUserPubkey
            val other = cleanPubkeyFormat(otherPubkey)
            if (me.isBlank() || other.isBlank()) return
            if (activeConversationPartner == other && conversationSubscriptionIds.isNotEmpty()) {
                Timber.d("Conversation subscription already active for %s; skipping duplicate REQ", other)
                return
            }

            // Unsubscribe from previous conversation
            unsubscribeConversation()
            activeConversationPartner = other

            // Wait for external signer launcher before subscribing if using external signer
            viewModelScope.launch(Dispatchers.IO) {
                val authContext = resolveAuthContext(refreshOnce = false)
                if (authContext.hasExternalSigner && authContext.localPrivateKeyBytes == null) {
                    Timber.d("Conversation subscription: waiting for external signer launcher before subscribing")
                    com.hisa.data.nostr.ExternalSignerManager.waitForLauncherReady(timeoutMs = 10_000)
                }

                val since = (System.currentTimeMillis() / 1000) - (CONVERSATION_DM_HISTORY_DAYS * 24L * 60L * 60L)

                // Two-way DM filters in one subscription:
                // - incoming from other to me
                // - outgoing from me to other
                val filtersArray = org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("kinds", org.json.JSONArray().put(1059))
                        put("authors", org.json.JSONArray().put(other))
                        put("#p", org.json.JSONArray().put(me))
                        put("since", since)
                        put("limit", CONVERSATION_DM_LIMIT_PER_FILTER)
                    })
                    put(JSONObject().apply {
                        put("kinds", org.json.JSONArray().put(1059))
                        put("authors", org.json.JSONArray().put(me))
                        put("#p", org.json.JSONArray().put(other))
                        put("since", since)
                        put("limit", CONVERSATION_DM_LIMIT_PER_FILTER)
                    })
                }
                Timber.i(
                    "Creating conversation subscription filters (since=%d limitPerFilter=%d): %s",
                    since,
                    CONVERSATION_DM_LIMIT_PER_FILTER,
                    filtersArray.toString()
                )

                val subId = subscriptionManager.subscribe(filtersArray, { event ->
                    try {
                        val obj = JSONObject().apply {
                            put("id", event.id)
                            put("pubkey", event.pubkey)
                            put("created_at", event.createdAt)
                            put("kind", event.kind)
                            put("tags", org.json.JSONArray(event.tags.map { org.json.JSONArray(it) }))
                            put("content", event.content)
                            put("sig", event.sig)
                        }
                        // Sample conversation subscription logs to avoid spamming
                        if (subscriptionEventCounter.incrementAndGet() % 200L == 0L) {
                            Timber.d("Conversation subscription received event (sample) id=%s pubkey=%s tags=%s", event.id, event.pubkey, obj.optJSONArray("tags")?.toString())
                            Timber.d("Invoking handleGiftWrap for conversation event id=%s", event.id)
                        }
                        handleGiftWrap(obj, other)
                        if (subscriptionEventCounter.get() % 200L == 0L) {
                            Timber.d("handleGiftWrap completed for event id=%s", event.id)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error handling conversation event (multi-filter)")
                    }
                })
                conversationSubscriptionIds.add(subId)
                Timber.i("Subscribed to conversation with %s sub=%s", other, subId)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to subscribe to conversation %s", otherPubkey)
        }
    }

    fun unsubscribeConversation() {
        conversationSubscriptionIds.forEach { id ->
            try {
                subscriptionManager.unsubscribe(id)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unsubscribe conversation sub %s", id)
            }
        }
        conversationSubscriptionIds.clear()
        activeConversationPartner = null
    }
}
