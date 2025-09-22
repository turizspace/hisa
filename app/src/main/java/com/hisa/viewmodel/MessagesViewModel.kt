package com.hisa.viewmodel

import com.hisa.util.cleanPubkeyFormat

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Message
import com.hisa.data.nostr.NostrClient
import com.hisa.data.repository.ConversationRepository
import com.hisa.data.repository.MetadataRepository
import com.hisa.data.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import timber.log.Timber
import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import com.hisa.util.Constants

@HiltViewModel
@RequiresApi(Build.VERSION_CODES.O)
class MessagesViewModel @Inject constructor(
    private val nostrClient: NostrClient,
    private val messageRepository: MessageRepository,
    private val metadataRepository: MetadataRepository,
    private val secureStorage: com.hisa.data.storage.SecureStorage,
    private val subscriptionManager: com.hisa.data.nostr.SubscriptionManager
) : ViewModel() {
    /**
     * Clears all messages from memory. Call this on logout or when switching accounts.
     */
    fun clearMessages() {
        _messages.value = emptyList()
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
    // Per-conversation subscription ids (two subscriptions per conversation to cover both directions)
    private val conversationSubscriptionIds: MutableList<String> = mutableListOf()
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages
    // Errors from send operations (observable by UI)
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError
    

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
                        Timber.d("Received DM event: %s", eventJson)
                        when (kind) {
                            12 -> handleGiftWrap(eventObj) // NIP-59 Gift Wrap
                            14 -> { // NIP-17 Direct Message (legacy, if any)
                                val msg = MessageRepository.parseMessage(eventJson)
                                Timber.d("Parsed direct message: %s", msg)
                                if (msg != null && msg.recipientPubkeys.contains(userPubkey)) {
                                    _messages.value = _messages.value + msg
                                    Timber.d("Added direct message to state: %s", msg)
                                } else {
                                    Timber.d("Message not for this user or failed to parse: %s", msg)
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

    init {
        // Kick off initialization: derive user pubkey from stored nsec and ensure
        // an X25519 private key exists for NIP-44 encryption.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                initialize()
                // Incoming messages are handled centrally by SubscriptionManager via NostrClient.incomingMessages
                // Subscribe to direct messages (kind 1059) addressed to this user
                if (userPubkey.isNotBlank()) {
                    try {
                        var assignedSubId = ""
                        // Subscribe with a larger time window to fetch historical messages
                        val thirtyDaysAgo = System.currentTimeMillis() / 1000 - (30 * 24 * 60 * 60)
                        
                        // Create filter for messages sent to us or by us
                        val subId = subscriptionManager.subscribeToDirectMessages(
                            userPubkey = userPubkey,
                            sinceTime = thirtyDaysAgo
                        ) { nostrEvent ->
                            try {
                                // Convert NostrEvent back to JSONObject and hand to handler
                                val obj = JSONObject().apply {
                                    put("id", nostrEvent.id)
                                    put("pubkey", nostrEvent.pubkey)
                                    put("created_at", nostrEvent.createdAt)
                                    put("kind", nostrEvent.kind)
                                    put("tags", JSONArray(nostrEvent.tags.map { tagParts -> JSONArray(tagParts) }))
                                    put("content", nostrEvent.content)
                                    put("sig", nostrEvent.sig)
                                }
                                Timber.d("Direct DM subscription callback for subId=%s eventId=%s", assignedSubId, nostrEvent.id)
                                handleGiftWrap(obj, null)
                            } catch (e: Exception) {
                                Timber.e(e, "Error handling subscribed direct message event")
                            }
                        }
                        directSubscriptionId = subId
                        assignedSubId = subId
                        Timber.i("Subscribed to direct messages for userPubkey=%s subId=%s", userPubkey, subId)
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to subscribe to direct messages for userPubkey=%s", userPubkey)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during MessagesViewModel initialization")
            }
        }
    }



    private companion object {
        const val KIND_GIFT_WRAP = 1059
        const val KIND_SEAL = 13
        const val KIND_DM = 14
    // Use centralized onboarding relays as a safe default; runtime val (not const)
    val relayUrl: String = Constants.ONBOARDING_RELAYS.firstOrNull() ?: ""
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun decryptNip17Message(wrapEvent: JSONObject): Message? {
        return try {
            val x25519KeyRaw = x25519PrivateKey ?: secureStorage.getX25519PrivateKey()
                ?: throw IllegalStateException("No X25519 key available")

            // Quick heuristic: if someone accidentally stored a bech32 nsec or other non-hex
            // value under the X25519 key slot, detect and emit a clear log.
            if (x25519KeyRaw.startsWith("nsec", true) || x25519KeyRaw.startsWith("npub", true)) {
                timber.log.Timber.e("Stored X25519 key looks like a bech32 nsec/npub value. Ensure X25519 is stored separately and is 64 hex chars.")
                throw IllegalStateException("Stored X25519 key appears to be bech32 (nsec). Use a proper X25519 64-hex private key")
            }

            if (!x25519KeyRaw.matches(Regex("[0-9a-fA-F]{64}"))) {
                timber.log.Timber.e("Stored X25519 key invalid format (expected 64 hex chars): %s", x25519KeyRaw.take(16))
                throw IllegalStateException("Invalid X25519 private key format")
            }

            timber.log.Timber.d("Using X25519 key to decrypt (hexSnippet)=%s", x25519KeyRaw.take(8))

            // Convert hex -> bytes and perform a small sanity check by deriving its public key
            val recipientPrivateKeyBytes = try {
                hexStringToBytes(x25519KeyRaw)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to parse stored X25519 key hex")
                throw IllegalStateException("Invalid X25519 private key format")
            }

            // Derive our X25519 public key to compare against incoming tags when available
            try {
                val xPrivParam = org.bouncycastle.crypto.params.X25519PrivateKeyParameters(recipientPrivateKeyBytes, 0)
                val xPub = ByteArray(32)
                xPrivParam.generatePublicKey().encode(xPub, 0)
                val derivedPubHex = xPub.joinToString("") { "%02x".format(it) }

                // Inspect event tags for diagnostics: collect p-tags (recipients) and the x-tag (ephemeral sender pub)
                val incomingPub = wrapEvent.optString("pubkey", "")
                var tagX: String? = null
                val pTags = mutableListOf<String>()
                val tags = wrapEvent.optJSONArray("tags") ?: org.json.JSONArray()
                for (i in 0 until tags.length()) {
                    val tag = tags.optJSONArray(i) ?: continue
                    when (tag.optString(0)) {
                        "x" -> tagX = tag.optString(1)
                        "p" -> pTags.add(tag.optString(1))
                    }
                }

                // Check whether our derived X25519 public key appears in the p-tags (recipient list)
                val matchesPTag = pTags.any { it.equals(derivedPubHex, true) }
                val matchesSigningPub = pTags.any { it.equals(cleanedUserPubkey, true) }

                timber.log.Timber.d(
                    "X25519 derivedPub=%s pTags=%s xTag=%s outerPub=%s matchesPTag=%s matchesSigningPub=%s",
                    derivedPubHex.take(12), pTags.joinToString(","), tagX?.take(12), incomingPub.take(12), matchesPTag, matchesSigningPub
                )

                if (!matchesPTag && !matchesSigningPub) {
                    timber.log.Timber.w(
                        "Derived X25519 public key not present in event p-tags. This may mean the sender used a different recipient pubkey format or the stored X25519 key is incorrect. derived=%s",
                        derivedPubHex.take(12)
                    )
                }
            } catch (e: Exception) {
                timber.log.Timber.w(e, "Failed to derive X25519 public key for sanity check")
            }

            // 1. Decrypt the outer layer
            val innerJson = messageRepository.decryptGiftWrappedMessage(
                event = wrapEvent,
                recipientPrivateKey = recipientPrivateKeyBytes
            )?.toString() ?: return null

            // 2. Parse the decrypted message
            val parsed = messageRepository.parseMessage(innerJson) ?: return null

            // 3. Normalize the message
            normalizeMessage(parsed, wrapEvent.optString("pubkey"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to decrypt NIP-17 message")
            null
        }
    }

    private fun normalizeMessage(message: Message, outerPubkey: String): Message {
        return when (message) {
            is Message.TextMessage -> message.copy(
                pubkey = cleanPubkeyFormat(message.pubkey),
                recipientPubkeys = message.recipientPubkeys.map { cleanPubkeyFormat(it) }
            )
            is Message.FileMessage -> message.copy(
                pubkey = cleanPubkeyFormat(message.pubkey),
                recipientPubkeys = message.recipientPubkeys.map { cleanPubkeyFormat(it) }
            )
            else -> message
        }
    }

    private suspend fun updateMessageState(message: Message, expectedOtherPubkey: String?) {
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
        directSubscriptionId?.let {
            try {
                subscriptionManager.unsubscribe(it)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unsubscribe direct subscription %s", it)
            }
        }
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
                val eventId = giftWrap.optString("id", "unknown")
                Timber.i("Processing gift wrap event: $eventId")

                // 1. Verify message is for current user
                if (!isMessageForCurrentUser(giftWrap)) {
                    Timber.d("Message not for current user, skipping")
                    return@launch
                }

                // 2. Decrypt the message
                val decryptedMessage = decryptNip17Message(giftWrap) ?: run {
                    Timber.e("Failed to decrypt message $eventId")
                    addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
                    return@launch
                }

                // 3. Update state if message is valid
                updateMessageState(decryptedMessage, expectedOtherPubkey)
            } catch (e: Exception) {
                Timber.e(e, "Error in handleGiftWrap")
                addUndecryptablePlaceholder(giftWrap, expectedOtherPubkey)
            }
        }
    }

    private fun isMessageForCurrentUser(event: JSONObject): Boolean {
        val tags = event.optJSONArray("tags") ?: return false
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.optString(0) == "p") {
                val recipientPubkey = cleanPubkeyFormat(tag.optString(1))
                if (recipientPubkey == cleanedUserPubkey || recipientPubkey == x25519PublicKey) {
                    return true
                }
            }
        }
        return false
    }

    fun getConversations(): Map<String, List<Message>> {
        val messages = _messages.value
        Timber.d("Total messages in state: %d", messages.size)
        
        // Group messages by conversation partner (the other person in the conversation)
        val grouped = messages.groupBy { msg ->
            // Normalize sender and recipients to canonical trimmed hex values
            val sender = cleanPubkeyFormat(msg.pubkey)
            val recipients = msg.recipientPubkeys.map { cleanPubkeyFormat(it) }

            when {
                // If we sent it (we're the pubkey), use the first recipient as conversation partner
                sender.equals(cleanedUserPubkey, true) -> {
                    val partner = recipients.firstOrNull()
                    Timber.d("Sent message id=%s grouped by recipient=%s", msg.id, partner)
                    partner ?: "NO_RECIPIENT"
                }
                // If we received it (we're in recipientPubkeys), use sender's pubkey as conversation partner
                recipients.any { it.equals(cleanedUserPubkey, true) } -> {
                    Timber.d("Received message id=%s grouped by sender=%s", msg.id, sender)
                    sender
                }
                // Fallback: choose sender (normalized)
                else -> {
                    Timber.d("Fallback grouping for message id=%s pubkey=%s recipients=%s", 
                        msg.id, sender, recipients)
                    sender
                }
            }
        }
        
        Timber.i("Grouped %d messages into %d conversations: %s", 
            messages.size,
            grouped.size,
            grouped.map { (key, msgs) -> "$key: ${msgs.size} msgs" })
            
        return grouped
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
        signingPrivateKeyBytes: ByteArray?,
        encryptionPrivateKeyBytes: ByteArray
    ) {
        // Use the MessageRepository to prepare the encrypted message
        val wrappedEvent = messageRepository.prepareGiftWrappedMessage(
            senderPrivateKey = encryptionPrivateKeyBytes,
            recipientPubkey = recipientPubkey,
            content = innerMessage.toString(),
            kind = 14,  // inner message kind should be 14 (NIP-17 Direct Message)
            tags = listOf(listOf("p", recipientPubkey))
        )
        // If the repository returned a fully-formed, signed gift-wrap (ephemeral signing key),
        // publish it as-is to avoid overwriting the outer pubkey/sig (which would break relay verification).
        val existingId = wrappedEvent.optString("id", "")
        val existingPub = wrappedEvent.optString("pubkey", "")
        val existingSig = wrappedEvent.optString("sig", "")
        if (existingId.isNotBlank() && existingPub.isNotBlank() && existingSig.isNotBlank()) {
            try {
                val nostrEvent = com.hisa.data.nostr.NostrEvent(
                    id = wrappedEvent.getString("id"),
                    pubkey = wrappedEvent.getString("pubkey"),
                    createdAt = wrappedEvent.getLong("created_at"),
                    kind = wrappedEvent.getInt("kind"),
                    tags = (0 until wrappedEvent.getJSONArray("tags").length()).map { i ->
                        val tagArr = wrappedEvent.getJSONArray("tags").getJSONArray(i)
                        (0 until tagArr.length()).map { tagArr.getString(it) }
                    },
                    content = wrappedEvent.getString("content"),
                    sig = wrappedEvent.getString("sig")
                )
                nostrClient.publishEvent(nostrEvent)
                return
            } catch (e: Exception) {
                Timber.w(e, "Failed to publish pre-signed gift-wrap; falling back to signing path")
                // Continue to signing path below if something unexpected occurred
            }
        }

        // Build tags for NostrEventSigner (wrappedEvent didn't contain a complete sig/pubkey)
        val tags = wrappedEvent.getJSONArray("tags").let { array ->
            List(array.length()) { idx ->
                val tagArray = array.getJSONArray(idx)
                List(tagArray.length()) { tagArray.getString(it) }
            }
        }

        // Use canonical NostrEventSigner for event creation/signing
        val eventJson = com.hisa.data.nostr.NostrEventSigner.signEvent(
            kind = 1059,
            content = wrappedEvent.getString("content"),
            tags = tags,
            pubkey = cleanPubkeyFormat(userPubkey),
            privKey = signingPrivateKeyBytes,
            createdAt = wrappedEvent.optLong("created_at", System.currentTimeMillis() / 1000)
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

    // Retrieve the current user's signing private key as a 64-char hex string.
    // This reads the stored bech32 nsec from SecureStorage and converts it to raw bytes,
    // then returns the hex representation. Throws if missing or invalid.
    private fun getPrivateKey(): String {
        // Try secure storage first
        val nsec = secureStorage.getNsec()
            ?: throw IllegalStateException("No nsec stored for current user")
        try {
            val privBytes = com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec)
            if (privBytes.size != 32) throw IllegalStateException("Invalid private key length")
            val hex = privBytes.joinToString("") { "%02x".format(it) }
            // Zero the sensitive byte array immediately
            for (i in privBytes.indices) privBytes[i] = 0
            return hex
        } catch (e: Exception) {
            throw IllegalStateException("Failed to decode stored nsec: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun sendMessage(recipientPubkey: String, content: String, subject: String? = null, replyTo: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Obtain local signing private key if present. For external-signer-only users
                // this will be null and signing will be delegated to the external signer.
                val signingPrivateKeyBytes: ByteArray? = try {
                    val nsec = secureStorage.getNsec()
                    if (!nsec.isNullOrBlank()) {
                        val priv = com.hisa.util.KeyGenerator.nsecToPrivateKey(nsec)
                        if (priv.size == 32) priv else null
                    } else null
                } catch (e: Exception) {
                    null
                }
                // Ensure X25519 private key is provided for encryption
                val x25519Hex = x25519PrivateKey
                if (x25519Hex == null) {
                    val err = "X25519 private key not available. A key should be auto-generated on initialize; please restart the app or set it manually via setX25519PrivateKey()."
                    Timber.e(err)
                    _sendError.value = err
                    return@launch
                }
                val encryptionPrivateKeyBytes = try {
                    hexStringToBytes(x25519Hex)
                } catch (hexEx: Exception) {
                    val err = "Invalid X25519 private key format"
                    Timber.e(hexEx, err)
                    _sendError.value = err
                    return@launch
                }
                
                // The recipientPubkey parameter must be the recipient's X25519 public key (32-byte hex)
                // Try to interpret the provided recipientPubkey. It may already be an X25519 pub (32-byte hex),
                // or it may be a signing pubkey (secp256k1 x-only). If it's not an X25519 pub, attempt to
                // resolve the recipient's metadata (kind=0) for a published X25519 public key field.
                var cleanRecipientPubkey = recipientPubkey.trim().lowercase()
                if (!cleanRecipientPubkey.matches(Regex("[0-9a-f]{64}"))) {
                    // Not an X25519 hex string; try to fetch metadata and look for an X25519 pub.
                    try {
                        val metaRaw = metadataRepository.getLatestMetadataRaw(cleanRecipientPubkey)
                        var resolved: String? = null
                        if (!metaRaw.isNullOrBlank()) {
                            try {
                                val json = org.json.JSONObject(metaRaw)
                                // Common places authors put X25519 pubkey
                                val candidates = listOf("x25519", "x25519_pub", "nip44_pub", "encryption", "encryption_pub")
                                for (k in candidates) {
                                    if (json.has(k)) {
                                        val v = json.optString(k, "").trim().lowercase()
                                        if (v.matches(Regex("[0-9a-f]{64}"))) { resolved = v; break }
                                    }
                                }
                                // Also some profiles embed a nested "keys" object
                                if (resolved == null && json.has("keys")) {
                                    val keysObj = json.optJSONObject("keys")
                                    if (keysObj != null) {
                                        val keysIter = keysObj.keys()
                                        while (keysIter.hasNext()) {
                                            val k = keysIter.next()
                                            val v = keysObj.optString(k, "").trim().lowercase()
                                            if (v.matches(Regex("[0-9a-f]{64}"))) { resolved = v; break }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Failed to parse recipient metadata for X25519 pub")
                            }
                        }
                        if (resolved == null) {
                            val err = "Recipient does not appear to have an X25519 public key published in their profile. Cannot encrypt message."
                            Timber.e(err)
                            _sendError.value = err
                            return@launch
                        }
                        cleanRecipientPubkey = resolved
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to resolve recipient X25519 public key from metadata")
                        _sendError.value = "Failed to resolve recipient encryption key"
                        return@launch
                    }
                }
                
                // If we have no local signing key and no external signer configured, error out
                if (signingPrivateKeyBytes == null && com.hisa.data.nostr.ExternalSignerManager.getConfiguredPackage().isNullOrBlank()) {
                    val err = "No signing key available. Configure an external signer or import an nsec to send messages."
                    Timber.e(err)
                    _sendError.value = err
                    return@launch
                }

                // Create the inner kind:14 message event
                val innerMessage = createInnerMessageEvent(cleanRecipientPubkey, content, subject, replyTo)
                
                // Add to local state immediately for UI responsiveness (optimistic update)
                val tempId = "temp-${System.currentTimeMillis()}"
                val newMessage = Message.TextMessage(
                    id = tempId,
                    pubkey = cleanedUserPubkey, // Use cleaned pubkey for consistent matching
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
                sendGiftWrappedMessage(innerMessage, cleanRecipientPubkey, signingPrivateKeyBytes, encryptionPrivateKeyBytes)

                // Save conversation mapping
                ConversationRepository.getOrCreateConversation(
                    listOf(userPubkey, cleanRecipientPubkey)
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

            // Only use #p tags for filtering to catch all messages involving us or the other party
            val filtersArray = org.json.JSONArray().apply {
                // Messages where we're mentioned in p-tag
                put(JSONObject().apply {
                    put("kinds", org.json.JSONArray().put(1059))
                    put("#p", org.json.JSONArray().put(me))
                })
                // Messages where other party is mentioned in p-tag
                put(JSONObject().apply {
                    put("kinds", org.json.JSONArray().put(1059))
                    put("#p", org.json.JSONArray().put(other))
                })
            }
            Timber.i("Creating conversation subscription filters: %s", filtersArray.toString())

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
                    Timber.d("Conversation subscription received event id=%s pubkey=%s tags=%s", event.id, event.pubkey, obj.optJSONArray("tags")?.toString())
                    // Pass the other party's pubkey so we can show a conversation placeholder even if decryption fails
                    Timber.d("Invoking handleGiftWrap for conversation event id=%s", event.id)
                    handleGiftWrap(obj, other)
                    Timber.d("handleGiftWrap completed for event id=%s", event.id)
                } catch (e: Exception) {
                    Timber.e(e, "Error handling conversation event (multi-filter)")
                }
            })
            conversationSubscriptionIds.add(subId)
            Timber.i("Subscribed to conversation with %s sub=%s", other, subId)
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
    }
}
