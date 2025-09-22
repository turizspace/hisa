package com.hisa.data.repository

import com.hisa.data.model.Message
import android.os.Build
import androidx.annotation.RequiresApi
import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine as ChaCha20Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest


import com.hisa.crypto.Schnorr
import java.math.BigInteger


/**
 * Repository for handling NIP-17 Direct Messages
 * Implements:
 * - Kind 14 for chat messages
 * - NIP-44 encryption
 * - NIP-59 seals and gift wraps
 */
object MessageRepository {
    private val secureRandom = SecureRandom()
    
    private fun generateRandomTimestamp(maxAgeSeconds: Int): Long {
        val now = System.currentTimeMillis() / 1000
        return now - secureRandom.nextInt(maxAgeSeconds)
    }
    
    private fun generateRandomBytes(size: Int): ByteArray {
        return ByteArray(size).apply { secureRandom.nextBytes(this) }
    }
    


    // NIP-44 v2 Encryption Constants
    private const val VERSION_BYTE: Byte = 0x02
    private const val NONCE_SIZE = 32  // Random nonce size for NIP-44 v2
    private const val AUTH_SIZE = 32
    private const val MAX_PLAINTEXT_SIZE = 65535
    private const val MIN_PADDED_SIZE = 32

    /**
     * Implements NIP-44 message encryption
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun nip44Encrypt(
        plaintext: String,
        senderPrivateKey: ByteArray,
        recipientPubkey: String
    ): String {
        // 1. Convert recipient's hex pubkey to byte array and validate (strict)
        val recipientPubkeyBytes = hexToBytes(recipientPubkey)

        // 2. Calculate X25519 shared secret
        val sharedSecret = calculateSharedSecret(senderPrivateKey, recipientPubkeyBytes)

        // 3. Derive a conversation key using HKDF-SHA256 (nip44-v2)
        val conversationKey = deriveConversationKey(sharedSecret) // 32 bytes

        // 4. Generate random 12-byte IV for AES-GCM
        val iv = generateRandomBytes(12)

        // 5. Create AES-GCM cipher and initialize with derived key
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(conversationKey, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

    // 6. Add AAD to bind version to ciphertext (keep minimal and deterministic)
    val aad = byteArrayOf(VERSION_BYTE)
    cipher.updateAAD(aad)

        // 7. Encrypt the plaintext
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintextBytes)

        // 8. Combine version byte, IV, and ciphertext
        val result = ByteArray(1 + iv.size + ciphertext.size)
        result[0] = VERSION_BYTE
        System.arraycopy(iv, 0, result, 1, iv.size)
        System.arraycopy(ciphertext, 0, result, 1 + iv.size, ciphertext.size)

        // 9. Base64 encode
        return java.util.Base64.getEncoder().encodeToString(result)
    }

    /**
     * Implements NIP-44 message decryption
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun nip44Decrypt(
        encryptedContent: String,
        recipientPrivateKey: ByteArray,
        senderPubkey: String
    ): String {
        // 1. Decode base64
        val encryptedBytes = Base64.getDecoder().decode(encryptedContent)

        // 2. Verify version byte
        if (encryptedBytes[0] != VERSION_BYTE) {
            throw IllegalArgumentException("Unsupported NIP-44 version byte: ${encryptedBytes[0]}")
        }

        // 3. Extract 12-byte IV and ciphertext
        val iv = encryptedBytes.copyOfRange(1, 13)
        val ciphertext = encryptedBytes.copyOfRange(13, encryptedBytes.size)

        // 4. Convert sender's hex pubkey to byte array
    val senderPubkeyBytes = hexToBytes(senderPubkey)

    // 5. Calculate shared secret using X25519
    val sharedSecret = calculateSharedSecret(recipientPrivateKey, senderPubkeyBytes)

    // 6. Derive conversation key via HKDF
    val conversationKey = deriveConversationKey(sharedSecret)

    // 7. Create AES-GCM cipher for decryption
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey = javax.crypto.spec.SecretKeySpec(conversationKey, "AES")
    val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)

    // 8. Reconstruct AAD (must match encrypt)
    val aad = byteArrayOf(VERSION_BYTE)
    cipher.updateAAD(aad)

    // 9. Decrypt the ciphertext
    val plaintext = cipher.doFinal(ciphertext)

    return String(plaintext)
    }



    // Helper function to convert hex string to byte array
    private fun hexToBytes(hex: String): ByteArray {
        // Strict: expect exactly 64 hex chars (32 bytes) representing X25519 key material
        val cleanHex = hex.replace("0x", "").trim().lowercase()
        if (!cleanHex.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException("Invalid hex string format for X25519 key (expected 64 hex chars)")
        }

        return ByteArray(32).also { bytes ->
            for (i in bytes.indices) {
                val j = i * 2
                bytes[i] = cleanHex.substring(j, j + 2).toInt(16).toByte()
            }
        }
    }

    // Helper function to convert byte array to hex string
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Calculate shared secret using ECDH
    private fun calculateSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey))
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(publicKey), sharedSecret, 0)
        return sharedSecret
    }

    // Derive conversation key using HKDF
    private fun deriveConversationKey(sharedSecret: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val salt = "nip44-v2".toByteArray(Charsets.UTF_8)
        hkdf.init(HKDFParameters(sharedSecret, salt, null))
        val conversationKey = ByteArray(32)
        hkdf.generateBytes(conversationKey, 0, conversationKey.size)
        return conversationKey
    }

    // Derive message keys using HKDF-expand
    /**
     * Decrypt a NIP-59 gift-wrapped message.
     * Handles the multi-layer decryption:
     * 1. Outer gift wrap (kind:1059)
     * 2. Inner seal (kind:13)
     * 3. Final message (kind:14)
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun decryptGiftWrappedMessage(event: JSONObject, recipientPrivateKey: ByteArray): JSONObject? {
        try {
            // Extract the content and sender pubkey from the outer gift wrap
            val encryptedContent = event.getString("content")
            // Prefer an explicit X25519 sender pubkey tag ("x") if present; fall back to the event.pubkey
            // Use the optString overload with a default to avoid nullable String?.
            var senderPubkey: String = event.optString("pubkey", "")
            val tags = event.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tags.length()) {
                try {
                    val tag = tags.getJSONArray(i)
                    // Use optString to avoid nullable String returns from getString
                    if (tag.length() > 0 && tag.optString(0, "") == "x") {
                        // Use optString with default to ensure non-null String
                        senderPubkey = tag.optString(1, "")
                        break
                    }
                } catch (ignore: Exception) {
                }
            }
            // senderPubkey is now a non-null String (may be empty, nip44Decrypt will validate)
            
            // First layer: decrypt the gift wrap to get either a sealed message (kind 13)
            // or directly the inner message (kind 14/15) depending on sender implementation.
            timber.log.Timber.d("Attempting NIP-44 decrypt: encryptedContentLen=%d senderPubkey=%s", encryptedContent.length, senderPubkey)
            val sealedJson = nip44Decrypt(encryptedContent, recipientPrivateKey, senderPubkey)
            val sealedMessage = JSONObject(sealedJson)

            val sealedKind = sealedMessage.optInt("kind")
            timber.log.Timber.d("Decrypted outer layer kind=%d id=%s", sealedKind, sealedMessage.optString("id", ""))
            when (sealedKind) {
                13 -> {
                    // Two-layer gift-wrap: decrypt the seal to extract the inner message
                    val sealContent = sealedMessage.getString("content")
                    val sealSenderPubkey = sealedMessage.getString("pubkey")
                    val innerJson = nip44Decrypt(sealContent, recipientPrivateKey, sealSenderPubkey)
                    return JSONObject(innerJson)
                }
                14, 15 -> {
                    // Single-layer gift wrap: the outer decrypted payload already contains the inner message
                    return sealedMessage
                }
                    else -> throw IllegalArgumentException("Unexpected sealed message kind: $sealedKind")
            }
        } catch (e: Exception) {
                // Log truncated encrypted content for diagnosis (avoid logging full keys)
                try { timber.log.Timber.e(e, "Failed to decrypt gift-wrapped message: outerContentSnippet=%s", event.optString("content").take(64)) } catch (_: Exception) {}
            return null
        }
    }

    /**
     * Create a message event of the specified kind.
     */
    fun createMessageEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long
    ): JSONObject {
        return JSONObject().apply {
            put("kind", kind)
            put("content", content)
            put("created_at", createdAt)
            put("tags", JSONArray().apply {
                tags.forEach { tag ->
                    put(JSONArray(tag))
                }
            })
        }
    }

    /**
     * Build canonical NIP-01 JSON array string for the given event fields and return its SHA-256 id.
     * This ensures decrypted inner events have a stable, predictable id so UI can correlate them.
     */
    private fun computeEventIdCanonical(pubkey: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val tagsJsonArray = JSONArray().apply { tags.forEach { inner -> put(JSONArray(inner)) } }
        val arrElement = JSONArray().apply {
            put(0)
            put(pubkey)
            put(createdAt)
            put(kind)
            put(tagsJsonArray)
            put(content)
        }
        val serialized = arrElement.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Prepare a gift-wrapped message using NIP-59.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun prepareGiftWrappedMessage(
        senderPrivateKey: ByteArray,
        recipientPubkey: String,
        content: String,
        kind: Int,
        tags: List<List<String>>
    ): JSONObject {
        // Choose a semi-random created_at within the last 2 days for inner event determinism
        val twoDaysInSeconds = 2 * 24 * 60 * 60
        val createdAt = generateRandomTimestamp(twoDaysInSeconds)

        // Build inner message and populate pubkey/id deterministically when possible
        val messageEvent = createMessageEvent(kind, content, tags, createdAt)
        var senderX25519PubHex: String? = null
        try {
            val xPrivParam = X25519PrivateKeyParameters(senderPrivateKey, 0)
            val xPubParam = xPrivParam.generatePublicKey()
            val xPub = ByteArray(32)
            xPubParam.encode(xPub, 0)
            senderX25519PubHex = bytesToHex(xPub)
            messageEvent.put("pubkey", senderX25519PubHex)
            try {
                val computedId = computeEventIdCanonical(senderX25519PubHex, createdAt, kind, tags, content)
                messageEvent.put("id", computedId)
            } catch (_: Exception) { /* best-effort */ }
        } catch (e: Exception) {
            if (!messageEvent.has("id")) messageEvent.put("id", "")
        }

        // Encrypt inner message using NIP-44 v2
        val encryptedContent = nip44Encrypt(messageEvent.toString(), senderPrivateKey, recipientPubkey)

        // Construct outer gift-wrap event but sign it with a secp256k1 ephemeral signing key.
        // Keep the X25519 ephemeral public key only in the "x" tag.
        // Generate an ephemeral signing keypair and an ephemeral X25519 key if not already created.
        val signingKeypair = ECKey()
        // Ensure we reuse the x25519 pub we derived earlier (senderX25519PubHex) in the x tag.
        val ephemeralSigningPub = try {
            derivePublicKey(signingKeypair.privKeyBytes)
        } catch (e: Exception) { "" }

        // Build tags for canonicalization: keep provided tags and ensure p/x tags exist
        val tagsList = mutableListOf<List<String>>()
        tags.forEach { t -> tagsList.add(t) }
    // Ensure p tag for recipient is present (no relayUrl available here)
    tagsList.add(listOf("p", recipientPubkey, ""))
        // Add x tag with the X25519 pub so recipient can decrypt
        senderX25519PubHex?.let { tagsList.add(listOf("x", it)) }

        // Compute contentEncrypted using the previously produced encryptedContent
        val contentEncryptedFinal = encryptedContent

        // Compute canonical id using ephemeral signing key's secp pub
        val outerCreatedAt = createdAt
        val outerId = computeEventIdCanonical(ephemeralSigningPub, outerCreatedAt, 1059, tagsList, contentEncryptedFinal)

        val giftWrap = JSONObject().apply {
            put("id", outerId)
            put("pubkey", ephemeralSigningPub)
            put("created_at", outerCreatedAt)
            put("kind", 1059)
            put("tags", JSONArray().apply {
                // reserialize tagsList into JSONArray
                tagsList.forEach { tag -> put(JSONArray(tag)) }
            })
            put("content", contentEncryptedFinal)
        }

        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(
                JSONArray().apply {
                    put(0)
                    put(ephemeralSigningPub)
                    put(outerCreatedAt)
                    put(1059)
                    // Build JSONArray of tags for serialization
                    val tagsArray = JSONArray().apply {
                        tagsList.forEach { t -> put(JSONArray(t)) }
                    }
                    put(tagsArray)
                    put(contentEncryptedFinal)
                }.toString().toByteArray(Charsets.UTF_8)
            )
            val sigBytes = schnorrSignBIP340(hash, signingKeypair.privKeyBytes)
            giftWrap.put("sig", bytesToHex(sigBytes))
        } catch (_: Exception) { }

        return giftWrap
    }

    private fun deriveMessageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(conversationKey, null, nonce))
        val keys = ByteArray(76)
        hkdf.generateBytes(keys, 0, keys.size)
        return Triple(
            keys.copyOfRange(0, 32),    // ChaCha key
            keys.copyOfRange(32, 44),   // ChaCha nonce
            keys.copyOfRange(44, 76)    // HMAC key
        )
    }



    /**
    * BIP-340 Schnorr signature for Nostr using a real implementation (see Schnorr.kt).
    */
    fun schnorrSignBIP340(messageHash: ByteArray, privateKey: ByteArray): ByteArray {
        require(messageHash.size == 32) { "Message hash must be 32 bytes" }
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        return Schnorr.sign(messageHash, privateKey)
    }

    private fun deterministicNonce(
        messageHash: ByteArray,
        privateKey: ByteArray,
        n: BigInteger
    ): BigInteger {
        // Simplified RFC6979-like nonce generation
        val k = hashSHA256(privateKey + messageHash)
        return BigInteger(1, k).mod(n)
    }

    private fun hashSHA256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }


    /**
     * Deprecated: Use NostrEventSigner.signEvent for all event signing.
     * This method is no longer used and will be removed in future versions.
     */
    @Deprecated("Use NostrEventSigner.signEvent instead.")
    private fun signEvent(eventJson: String, privKey: ByteArray): String {
        throw UnsupportedOperationException("signEvent is deprecated. Use NostrEventSigner.signEvent instead.")
    }

    // Create a NIP-17 Kind 14 (chat message) event
    fun createMessageEvent(
        senderPubkey: String,
        recipientPubkeys: List<String>,
        content: String,
        subject: String? = null,
        replyTo: String? = null,
        relayUrls: Map<String, String>? = null
    ): JSONObject {
        // Validate all pubkeys
        if (!validatePubkey(senderPubkey)) {
            throw IllegalArgumentException("Invalid sender pubkey format: $senderPubkey")
        }
        recipientPubkeys.forEach { pubkey ->
            if (!validatePubkey(pubkey)) {
                throw IllegalArgumentException("Invalid recipient pubkey format: $pubkey")
            }
        }

        val tags = mutableListOf<JSONArray>()
        
        // Add recipient p tags with optional relay URLs
        recipientPubkeys.forEach { pubkey ->
            val tagArray = if (relayUrls?.containsKey(pubkey) == true) {
                JSONArray(arrayOf("p", pubkey, relayUrls[pubkey]))
            } else {
                JSONArray(arrayOf("p", pubkey, ""))
            }
            tags.add(tagArray)
        }
        
        // Add optional subject tag
        subject?.let { tags.add(JSONArray(arrayOf("subject", it))) }
        
        // Add optional reply tag
        replyTo?.let { tags.add(JSONArray(arrayOf("e", it, "", "reply"))) }
        
        return JSONObject().apply {
            put("id", "") // Will be filled by the client
            put("pubkey", senderPubkey)
            put("created_at", System.currentTimeMillis() / 1000)
            put("kind", 14)
            put("tags", JSONArray(tags))
            put("content", content)
            // Note: No sig field as NIP-17 messages MUST NOT be signed
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNIP59Seal(
        encryptedContent: String,
        senderPrivateKey: ByteArray,
        senderPubkey: String? = null
    ): JSONObject {
        // Generate random created_at within last 2 days
        val twoDaysInSeconds = 2 * 24 * 60 * 60
        val randomCreatedAt = generateRandomTimestamp(twoDaysInSeconds)

        // Build tags list (empty for seal)
        val tagsList: List<List<String>> = emptyList()

        // Compute canonical event id using NIP-01 canonicalization
        val id = computeEventIdCanonical(senderPubkey ?: "", randomCreatedAt, 13, tagsList, encryptedContent)

        val sealEvent = JSONObject().apply {
            put("id", id)
            put("pubkey", senderPubkey ?: "")
            put("created_at", randomCreatedAt)
            put("kind", 13)
            put("tags", JSONArray())
            put("content", encryptedContent)
        }

        // Sign canonical hash
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(
                JSONArray().apply {
                    put(0)
                    put(senderPubkey ?: "")
                    put(randomCreatedAt)
                    put(13)
                    put(JSONArray())
                    put(encryptedContent)
                }.toString().toByteArray(Charsets.UTF_8)
            )
            val sigBytes = schnorrSignBIP340(hash, senderPrivateKey)
            sealEvent.put("sig", bytesToHex(sigBytes))
        } catch (_: Exception) { }

        return sealEvent
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNIP59GiftWrap(
        sealEvent: JSONObject,
        recipientPubkey: String,
        relayUrl: String? = null
    ): JSONObject {
        // Generate a random one-time-use signing keypair (secp256k1) for signing the gift wrap
        val signingKeypair = ECKey()
        // Generate a random one-time-use X25519 keypair for encrypting the seal
        val x25519Priv = generateRandomBytes(32)
        val x25519PrivParam = X25519PrivateKeyParameters(x25519Priv)
        val x25519PubParam = x25519PrivParam.generatePublicKey()
        val x25519Pub = ByteArray(32)
        x25519PubParam.encode(x25519Pub, 0)

        val twoDaysInSeconds = 2 * 24 * 60 * 60
        val now = System.currentTimeMillis() / 1000
        val randomCreatedAt = now - SecureRandom().nextInt(twoDaysInSeconds)

        // Derive the secp256k1 x-only pubkey for the signing keypair so the signature
        // on the gift-wrap can be verified correctly by relays/clients.
        val signingPubHex = try {
            derivePublicKey(signingKeypair.privKeyBytes)
        } catch (e: Exception) {
            // Fallback: if derivation fails, leave pubkey empty (signature may still be added best-effort)
            ""
        }

        // Build tags as List<List<String>> for canonical id computation
        val tagsList = mutableListOf<List<String>>()
        tagsList.add(listOf("p", recipientPubkey, relayUrl ?: ""))
        tagsList.add(listOf("x", bytesToHex(x25519Pub)))

        val contentEncrypted = nip44Encrypt(sealEvent.toString(), x25519Priv, recipientPubkey)

        // Compute canonical id using signing key's secp256k1 x-only pubkey
        val id = computeEventIdCanonical(signingPubHex, randomCreatedAt, 1059, tagsList, contentEncrypted)

        val giftWrap = JSONObject().apply {
            put("id", id)
            put("pubkey", signingPubHex)
            put("created_at", randomCreatedAt)
            put("kind", 1059)
            put("tags", JSONArray().apply {
                put(JSONArray().apply { put("p"); put(recipientPubkey); put(relayUrl ?: "") })
                put(JSONArray().apply { put("x"); put(bytesToHex(x25519Pub)) })
            })
            put("content", contentEncrypted)
        }

        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(
                JSONArray().apply {
                    put(0)
                    put(signingPubHex)
                    put(randomCreatedAt)
                    put(1059)
                    // Build JSONArray of tags for serialization
                    val tagsArray = JSONArray().apply {
                        put(JSONArray().apply { put("p"); put(recipientPubkey); put(relayUrl ?: "") })
                        put(JSONArray().apply { put("x"); put(bytesToHex(x25519Pub)) })
                    }
                    put(tagsArray)
                    put(contentEncrypted)
                }.toString().toByteArray(Charsets.UTF_8)
            )
            val sigBytes = schnorrSignBIP340(hash, signingKeypair.privKeyBytes)
            giftWrap.put("sig", bytesToHex(sigBytes))
        } catch (_: Exception) { }

        return giftWrap
    }

    // Validate a secp256k1 public key
    private fun validatePubkey(pubkey: String): Boolean {
        return when {
            pubkey.length == 66 && (pubkey.startsWith("02") || pubkey.startsWith("03")) -> true // compressed
            pubkey.length == 130 && pubkey.startsWith("04") -> true // uncompressed
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun prepareEncryptedMessage(
        senderPrivateKey: ByteArray,
        recipientPubkey: String,
        content: String,
        subject: String? = null,
        replyTo: String? = null,
        relayUrl: String? = null
    ): List<JSONObject> {
        // Derive sender's public key
        val senderPubkey = derivePublicKey(senderPrivateKey)
        
        // Format the recipient pubkey with "02" prefix for secp256k1 format if needed
        val formattedRecipientPubkey = formatPubkey(recipientPubkey)
        
        // Create the basic message event (unsigned)
        val messageEvent = createMessageEvent(
            senderPubkey = senderPubkey,
            recipientPubkeys = listOf(formattedRecipientPubkey),
            content = content,
            subject = subject,
            replyTo = replyTo,
            relayUrls = relayUrl?.let { mapOf(formattedRecipientPubkey to it) }
        )

        // Encrypt the message using NIP-44 v2
        val encryptedContent = nip44Encrypt(
            messageEvent.toString(),
            senderPrivateKey,
            recipientPubkey
        )

        // Create the signed seal
        val sealEvent = createNIP59Seal(
            encryptedContent,
            senderPrivateKey,
            senderPubkey
        )

        // Create gift wraps for both sender and recipient
        val giftWraps = mutableListOf<JSONObject>()
        
        // Gift wrap for recipient
        giftWraps.add(createNIP59GiftWrap(
            sealEvent,
            formattedRecipientPubkey,
            relayUrl
        ))
        
        // Gift wrap for sender (so they can decrypt their own messages)
        giftWraps.add(createNIP59GiftWrap(
            sealEvent,
            senderPubkey,
            relayUrl
        ))

        return giftWraps
    }


    @RequiresApi(Build.VERSION_CODES.O)
    fun createFileMessageEvent(
        senderPubkey: String,
        recipientPubkeys: List<String>,
        fileUrl: String,
        mimeType: String,
        fileSize: Long? = null,
        dimensions: Pair<Int, Int>? = null,
        blurhash: String? = null,
        thumbnailUrl: String? = null,
        subject: String? = null,
        replyTo: String? = null,
        relayUrls: Map<String, String>? = null
    ): JSONObject {
        // Validate pubkeys
        if (!validatePubkey(senderPubkey)) {
            throw IllegalArgumentException("Invalid sender pubkey format: $senderPubkey")
        }
        recipientPubkeys.forEach { pubkey ->
            if (!validatePubkey(pubkey)) {
                throw IllegalArgumentException("Invalid recipient pubkey format: $pubkey")
            }
        }

        val tags = mutableListOf<JSONArray>()

        // Add recipient p tags with optional relay URLs
        recipientPubkeys.forEach { pubkey ->
            val tagArray = if (relayUrls?.containsKey(pubkey) == true) {
                JSONArray(arrayOf("p", pubkey, relayUrls[pubkey]))
            } else {
                JSONArray(arrayOf("p", pubkey, ""))
            }
            tags.add(tagArray)
        }

        // Add required file tags
        tags.add(JSONArray(arrayOf("file-type", mimeType)))
        tags.add(JSONArray(arrayOf("encryption-algorithm", "aes-gcm")))

        // Generate encryption key and nonce
        val key = generateRandomBytes(32)
        val nonce = generateRandomBytes(12)

        tags.add(JSONArray(arrayOf("decryption-key", bytesToHex(key))))
        tags.add(JSONArray(arrayOf("decryption-nonce", bytesToHex(nonce))))

        // Add optional file metadata tags
        fileSize?.let { tags.add(JSONArray(arrayOf("size", it.toString()))) }
        dimensions?.let { (width, height) ->
            tags.add(JSONArray(arrayOf("dim", "${width}x$height")))
        }
        blurhash?.let { tags.add(JSONArray(arrayOf("blurhash", it))) }
        thumbnailUrl?.let { tags.add(JSONArray(arrayOf("thumb", it))) }
        subject?.let { tags.add(JSONArray(arrayOf("subject", it))) }
        replyTo?.let { tags.add(JSONArray(arrayOf("e", it, "", "reply"))) }

        return JSONObject().apply {
            put("id", "") // Will be filled by the client
            put("pubkey", senderPubkey)
            put("created_at", System.currentTimeMillis() / 1000)
            put("kind", 15)
            put("tags", JSONArray(tags))
            put("content", fileUrl)
            // Note: No sig field as NIP-17 messages MUST NOT be signed
        }
    }

    // Helper function to derive public key from private key
    private fun derivePublicKey(privateKey: ByteArray): String {
    val key = ECKey.fromPrivate(privateKey)
    // Get the full uncompressed public key (65 bytes, starts with 0x04)
    val uncompressed = key.decompress().pubKeyPoint.getEncoded(false)
    // x-only pubkey is the 32 bytes after the prefix (skip 1st byte, then take next 32)
    val xOnly = uncompressed.copyOfRange(1, 33)
    return xOnly.joinToString("") { "%02x".format(it) }
    }

    // Helper function to format public key correctly for secp256k1
    private fun formatPubkey(pubkey: String): String {
        // First strip any prefix if present
        val strippedKey = when {
            pubkey.startsWith("02") || pubkey.startsWith("03") -> pubkey.substring(2)
            pubkey.startsWith("04") -> pubkey.substring(2, 66)
            pubkey.length == 64 -> pubkey
            else -> throw IllegalArgumentException("Invalid public key length")
        }
        
        // Then add the compressed format prefix
        return "02$strippedKey"
    }

    /**
     * Encrypt a file using AES-GCM
     * Returns the encrypted file bytes and encryption parameters
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun encryptFile(fileBytes: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        // Generate random key and nonce
        val key = generateRandomBytes(32)
        val nonce = generateRandomBytes(12)

        // Create cipher
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))

        // Encrypt
        val encryptedBytes = cipher.doFinal(fileBytes)

        return Triple(encryptedBytes, key, nonce)
    }

    /**
     * Decrypt a file using AES-GCM
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun decryptFile(
        encryptedBytes: ByteArray,
        key: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(nonce))
        return cipher.doFinal(encryptedBytes)
    }

    /**
     * Parse a NIP-17 event (Kind 14 or 15) from JSON
     */
    fun parseMessage(eventJson: String): Message? {
        return try {
            val obj = JSONObject(eventJson)
            
            when (obj.optInt("kind")) {
                14 -> parseTextMessage(obj)
                15 -> parseFileMessage(obj)
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "Failed to parse message event", e)
            null
        }
    }

    private fun parseTextMessage(obj: JSONObject): Message.TextMessage? {
        // Extract common fields
        val (recipientPubkeys, relayUrls, subject, replyTo) = extractCommonFields(obj)
        
        return Message.TextMessage(
            id = obj.getString("id"),
            pubkey = obj.getString("pubkey"),
            recipientPubkeys = recipientPubkeys,
            content = obj.getString("content"),
            createdAt = obj.getLong("created_at"),
            subject = subject,
            replyTo = replyTo,
            relayUrls = if (relayUrls.isNotEmpty()) relayUrls else null
        )
    }

    private fun parseFileMessage(obj: JSONObject): Message.FileMessage? {
        // Extract common fields
        val (recipientPubkeys, relayUrls, subject, replyTo) = extractCommonFields(obj)

        // Extract file-specific tags
        var mimeType: String? = null
        var encryptionAlgorithm: String? = null
        var decryptionKey: String? = null
        var decryptionNonce: String? = null
        var fileHash: String? = null
        var originalHash: String? = null
        var fileSize: Long? = null
        var dimensions: Pair<Int, Int>? = null
        var blurhash: String? = null
        var thumbnailUrl: String? = null
        val fallbackUrls = mutableListOf<String>()

        val tags = obj.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tags.length()) {
            val tag = tags.getJSONArray(i)
            when (tag.getString(0)) {
                "file-type" -> mimeType = tag.getString(1)
                "encryption-algorithm" -> encryptionAlgorithm = tag.getString(1)
                "decryption-key" -> decryptionKey = tag.getString(1)
                "decryption-nonce" -> decryptionNonce = tag.getString(1)
                "x" -> fileHash = tag.getString(1)
                "ox" -> originalHash = tag.getString(1)
                "size" -> fileSize = tag.getString(1).toLongOrNull()
                "dim" -> {
                    val dims = tag.getString(1).split("x")
                    if (dims.size == 2) {
                        dimensions = Pair(
                            dims[0].toIntOrNull() ?: 0,
                            dims[1].toIntOrNull() ?: 0
                        )
                    }
                }
                "blurhash" -> blurhash = tag.getString(1)
                "thumb" -> thumbnailUrl = tag.getString(1)
                "fallback" -> fallbackUrls.add(tag.getString(1))
            }
        }

        // Validate required fields
        if (mimeType == null || encryptionAlgorithm == null || 
            decryptionKey == null || decryptionNonce == null || fileHash == null) {
            return null
        }

        return Message.FileMessage(
            id = obj.getString("id"),
            pubkey = obj.getString("pubkey"),
            recipientPubkeys = recipientPubkeys,
            fileUrl = obj.getString("content"),
            createdAt = obj.getLong("created_at"),
            mimeType = mimeType,
            encryptionAlgorithm = encryptionAlgorithm,
            decryptionKey = decryptionKey,
            decryptionNonce = decryptionNonce,
            fileHash = fileHash,
            originalHash = originalHash,
            fileSize = fileSize,
            dimensions = dimensions,
            blurhash = blurhash,
            thumbnailUrl = thumbnailUrl,
            fallbackUrls = if (fallbackUrls.isNotEmpty()) fallbackUrls else null,
            subject = subject,
            replyTo = replyTo,
            relayUrls = if (relayUrls.isNotEmpty()) relayUrls else null
        )
    }

    private fun extractCommonFields(obj: JSONObject): CommonFields {
        val recipientPubkeys = mutableListOf<String>()
        val relayUrls = mutableMapOf<String, String>()
        var subject: String? = null
        var replyTo: String? = null

        val tags = obj.optJSONArray("tags") ?: JSONArray()
        for (i in 0 until tags.length()) {
            val tag = tags.getJSONArray(i)
            when (tag.getString(0)) {
                "p" -> {
                    val pubkey = tag.getString(1)
                    recipientPubkeys.add(pubkey)
                    if (tag.length() > 2) {
                        val relayUrl = tag.getString(2)
                        if (relayUrl.isNotEmpty()) {
                            relayUrls[pubkey] = relayUrl
                        }
                    }
                }
                "subject" -> subject = tag.getString(1)
                "e" -> {
                    if (tag.length() > 3 && tag.getString(3) == "reply") {
                        replyTo = tag.getString(1)
                    }
                }
            }
        }

        return CommonFields(recipientPubkeys, relayUrls, subject, replyTo)
    }

    private data class CommonFields(
        val recipientPubkeys: List<String>,
        val relayUrls: Map<String, String>,
        val subject: String?,
        val replyTo: String?
    )

    /**
     * NIP-01 compliant event serialization: [0, pubkey, created_at, kind, tags, content]
     */
    private fun serializeEventNIP01(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: JSONArray,
        content: String
    ): String {
        val arr = JSONArray()
        arr.put(0)
        arr.put(pubkey)
        arr.put(createdAt)
        arr.put(kind)
        arr.put(tags)
        arr.put(content)
        return arr.toString()
    }

    /**
     * NIP-01 compliant event signing: serializes as array, hashes, signs, sets id/sig fields.
     */

    /**
     * Deprecated: Use NostrEventSigner.signEvent for all event signing.
     * This method is no longer used and will be removed in future versions.
     */
    @Deprecated("Use NostrEventSigner.signEvent instead.")
    fun signEventNIP01(event: JSONObject, privKey: ByteArray): JSONObject {
        throw UnsupportedOperationException("signEventNIP01 is deprecated. Use NostrEventSigner.signEvent instead.")
    }

    // Helper: ByteArray to hex (lowercase, no prefix)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

}
