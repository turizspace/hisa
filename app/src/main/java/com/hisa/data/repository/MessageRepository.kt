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
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine as ChaCha20Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import org.bouncycastle.math.ec.ECPoint


import com.hisa.crypto.Schnorr
import java.math.BigInteger
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.EventVerifier
import com.hisa.data.nostr.crypto.getNip44


/**
 * Repository for handling NIP-17 Direct Messages
 * Implements:
 * - Kind 14 for chat messages
 * - NIP-44 encryption
 * - NIP-59 seals and gift wraps
 */
object MessageRepository {
    private val secureRandom = SecureRandom()
    private val nip44 = getNip44()
    
    private fun generateRandomTimestamp(maxAgeSeconds: Int): Long {
        val now = System.currentTimeMillis() / 1000
        return now - secureRandom.nextInt(maxAgeSeconds)
    }
    
    private fun generateRandomBytes(size: Int): ByteArray {
        return ByteArray(size).apply { secureRandom.nextBytes(this) }
    }
    


    // NIP-44 v2 Encryption Constants
    private const val VERSION_BYTE: Byte = 0x02
    private const val NONCE_SIZE = 32  // NIP-44 v2 nonce length
    private const val AUTH_SIZE = 32
    private const val CHACHA_NONCE_SIZE = 12
    private const val MAX_PLAINTEXT_SIZE = 65535
    private const val MIN_PLAINTEXT_SIZE = 1
    private const val MIN_PADDED_SIZE = 32
    private const val MIN_BASE64_LEN = 132
    private const val MAX_BASE64_LEN = 87472
    private const val MIN_DECODED_LEN = 99
    private const val MAX_DECODED_LEN = 65603
    private val SECP256K1_N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
        16
    )

    /**
     * Implements NIP-44 message encryption
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun nip44Encrypt(
        plaintext: String,
        senderPrivateKey: ByteArray,
        recipientPubkey: String
    ): String {
        val pubBytes = hexToBytes(recipientPubkey)
        return nip44.encrypt(plaintext, senderPrivateKey, pubBytes).encode()
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
        val normalized = normalizeToXOnlyHex(senderPubkey)
            ?: throw IllegalArgumentException("Invalid sender pubkey format: $senderPubkey")
        val pubBytes = hexToBytes(normalized)
        return try {
            nip44.decrypt(encryptedContent, recipientPrivateKey, pubBytes)
                ?: throw IllegalArgumentException("NIP-44 decrypt failed")
        } catch (e: Exception) {
            // If the payload is JSON, try to extract ciphertext field as fallback
            try {
                val trimmed = encryptedContent.trim()
                if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    val ct = obj.optString("ciphertext")
                    if (!ct.isNullOrBlank()) {
                        return nip44.decrypt(ct, recipientPrivateKey, pubBytes)
                            ?: throw IllegalArgumentException("NIP-44 decrypt failed (fallback)")
                    }
                }
            } catch (_: Exception) {}
            throw e
        }
    }

    private fun padNip44Plaintext(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "NIP-44 plaintext must not be empty" }
        val targetSize = calculatePaddedSize(plaintext.size)
        val out = ByteArray(2 + targetSize)
        val len = plaintext.size
        out[0] = ((len ushr 8) and 0xff).toByte()
        out[1] = (len and 0xff).toByte()
        System.arraycopy(plaintext, 0, out, 2, len)
        // NIP-44 v2 requires zero-byte suffix padding.
        // ByteArray is already zero-initialized, so no extra write is needed.
        return out
    }

    private fun unpadNip44Plaintext(padded: ByteArray): ByteArray {
        require(padded.size >= 2) { "NIP-44 padded payload too short" }
        val length = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(length in 1..MAX_PLAINTEXT_SIZE) { "Invalid NIP-44 plaintext length: $length" }
        require(2 + length <= padded.size) { "Invalid NIP-44 padded length framing" }
        require(padded.size == 2 + calculatePaddedSize(length)) { "Invalid NIP-44 padding shape" }
        return padded.copyOfRange(2, 2 + length)
    }

    private fun calculatePaddedSize(length: Int): Int {
        if (length <= MIN_PADDED_SIZE) return MIN_PADDED_SIZE
        if (length > MAX_PLAINTEXT_SIZE) throw IllegalArgumentException("Plaintext too large")
        val nextPower = Integer.highestOneBit(length - 1) shl 1
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return ((length + chunk - 1) / chunk) * chunk
    }



    // Helper function to convert hex string to byte array
    private fun hexToBytes(hex: String): ByteArray {
        // Strict: expect exactly 64 hex chars (32 bytes)
        val cleanHex = hex.replace("0x", "").trim().lowercase()
        if (!cleanHex.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException("Invalid 32-byte hex string (expected 64 hex chars)")
        }

        return ByteArray(32).also { bytes ->
            for (i in bytes.indices) {
                val j = i * 2
                bytes[i] = cleanHex.substring(j, j + 2).toInt(16).toByte()
            }
        }
    }

    /**
     * Normalize a variety of pubkey formats to x-only 64-hex string.
     * Accepts:
     * - npub (bech32) -> hex (via KeyGenerator.npubToPublicKey)
     * - compressed (02/03 + 64 hex) -> drop prefix -> 64 hex
     * - uncompressed (04 + 128 hex) -> take x-coordinate (bytes 1..32) -> 64 hex
     * - already x-only (64 hex) -> return lowercase
     */
    private fun normalizeToXOnlyHex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        try {
            if (s.startsWith("npub", true)) {
                val conv = com.hisa.util.KeyGenerator.npubToPublicKey(s)
                if (conv == null) return null
                s = conv
            }
        } catch (_: Exception) {}
        s = s.removePrefix("0x").lowercase()
        return when (s.length) {
            66 -> if (s.startsWith("02") || s.startsWith("03")) s.substring(2) else null
            130 -> if (s.startsWith("04")) s.substring(2, 66) else null
            64 -> s
            else -> null
        }
    }

    // Helper function to convert byte array to hex string
    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Calculate NIP-44 shared secret using secp256k1 ECDH: x-coordinate(priv * pub).
    private fun calculateSharedSecret(privateKey: ByteArray, otherPubkeyHex: String): ByteArray {
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val privScalar = BigInteger(1, privateKey)
        require(privScalar >= BigInteger.ONE && privScalar < SECP256K1_N) { "Invalid secp256k1 private key scalar" }
        val cleanPub = normalizeToXOnlyHex(otherPubkeyHex)
            ?: throw IllegalArgumentException("Invalid or unsupported pubkey format: $otherPubkeyHex")
        val compressed = hexToBytes("02$cleanPub")
        val otherPoint: ECPoint = ECKey.CURVE.curve.decodePoint(compressed)
        require(!otherPoint.isInfinity) { "Invalid secp256k1 public key point" }
        val sharedPoint = otherPoint.multiply(privScalar).normalize()
        return to32Bytes(sharedPoint.xCoord.encoded)
    }

    // Derive conversation key using HKDF-extract only (NIP-44 v2).
    private fun deriveConversationKey(sharedSecret: ByteArray): ByteArray {
        val salt = "nip44-v2".toByteArray(Charsets.UTF_8)
        return hmacSha256(salt, sharedSecret)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        hmac.update(data, 0, data.size)
        val out = ByteArray(32)
        hmac.doFinal(out, 0)
        return out
    }

    private fun to32Bytes(input: ByteArray): ByteArray {
        if (input.size == 32) return input
        if (input.size > 32) return input.copyOfRange(input.size - 32, input.size)
        val out = ByteArray(32)
        System.arraycopy(input, 0, out, 32 - input.size, input.size)
        return out
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
    suspend fun decryptGiftWrappedMessage(
        event: JSONObject,
        recipientPrivateKey: ByteArray? = null,
        externalDecryptor: (suspend (ciphertext: String, senderPubkey: String) -> String)? = null,
        maxSenderCandidates: Int? = null,
        senderPubkeyHints: List<String> = emptyList()
    ): JSONObject? {
        try {
            val outerVerification = EventVerifier.verifyEvent(event.toString())
            val outerVerified = outerVerification.idMatches && outerVerification.signatureValid
            if (!outerVerified) {
                timber.log.Timber.w(
                    "Outer gift-wrap failed NIP-01 verification (compat mode): eventId=%s idMatches=%s sigValid=%s",
                    event.optString("id", ""),
                    outerVerification.idMatches,
                    outerVerification.signatureValid
                )
            }

            val decryptFn: suspend (String, String) -> String = when {
                recipientPrivateKey != null -> { ciphertext, sender ->
                    nip44Decrypt(ciphertext, recipientPrivateKey, sender)
                }
                externalDecryptor != null -> externalDecryptor
                else -> throw IllegalStateException("No decryption method available")
            }

            // Extract encrypted payload.
            val encryptedContent = event.getString("content")
            // Prefer outer pubkey first for NIP-59 wraps (the wrapper key is the correct sender
            // for decrypting the outer ciphertext). Keep other candidates as fallback.
            val senderCandidates = mutableListOf<String>()
            val outerPubkey = event.optString("pubkey", "").trim().lowercase()
            val xTagCandidates = mutableListOf<String>()
            val pTagCandidates = mutableListOf<String>()
            val tags = event.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tags.length()) {
                val tag = tags.optJSONArray(i) ?: continue
                if (tag.optString(0) == "x") {
                    val candidate = tag.optString(1, "").trim().lowercase()
                    if (candidate.matches(Regex("[0-9a-f]{64}"))) {
                        xTagCandidates.add(candidate)
                    }
                }
                if (tag.optString(0) == "p") {
                    val candidate = tag.optString(1, "").trim().lowercase()
                    if (candidate.matches(Regex("[0-9a-f]{64}"))) {
                        pTagCandidates.add(candidate)
                    }
                }
            }
            if (outerPubkey.matches(Regex("[0-9a-f]{64}"))) {
                senderCandidates.add(outerPubkey)
            }
            senderPubkeyHints
                .map { it.trim().lowercase() }
                .filter { it.matches(Regex("[0-9a-f]{64}")) }
                .forEach { senderCandidates.add(it) }
            if (!outerVerified) {
                senderCandidates.addAll(xTagCandidates)
            }
            if (outerVerified) {
                senderCandidates.addAll(xTagCandidates)
            }
            val dedupedCandidates = senderCandidates.distinct()
            // Normalize candidates to x-only 64-hex and dedupe again
            val normalizedCandidatesAll = dedupedCandidates.mapNotNull { normalizeToXOnlyHex(it) }.distinct()
            val candidates = if (maxSenderCandidates != null) {
                normalizedCandidatesAll.take(maxSenderCandidates)
            } else {
                normalizedCandidatesAll
            }
            val candidateLog = if (normalizedCandidatesAll.size <= 10) normalizedCandidatesAll.joinToString(",") else normalizedCandidatesAll.joinToString(",") { it.take(12) }
            timber.log.Timber.d(
                "Gift-wrap decrypt candidates: eventId=%s outerVerified=%s candidates=%s",
                event.optString("id", ""),
                outerVerified,
                candidateLog
            )

            // Try candidates until one decrypts.
            var lastError: Exception? = null
            val candidateErrors = mutableListOf<String>()
            for (senderPubkey in candidates) {
                try {
                    timber.log.Timber.d(
                        "Attempting NIP-44 decrypt: encryptedContentLen=%d senderPubkey=%s",
                        encryptedContent.length,
                        senderPubkey
                    )
                    val sealedJson = decryptFn(encryptedContent, senderPubkey)
                    
                    timber.log.Timber.d(
                        "decryptFn returned (candidate=%s): resultLen=%d resultStart=%s",
                        senderPubkey.take(12),
                        sealedJson.length,
                        sealedJson.take(50)
                    )
                    
                    val sealedMessage = parseJsonObjectOrThrow(
                        sealedJson,
                        "Outer decrypt returned non-JSON payload"
                    )

                    val sealedKind = sealedMessage.optInt("kind")
                    timber.log.Timber.d(
                        "Outer layer decrypted with candidate=%s kind=%d id=%s pubkey=%s",
                        senderPubkey.take(12),
                        sealedKind,
                        sealedMessage.optString("id", "").take(16),
                        sealedMessage.optString("pubkey", "").take(12)
                    )
                    
                    // Validate that kind makes sense for a gift-wrapped message.
                    // Outer layer should decrypt to:
                    // - kind 13 (seal), or
                    // - inner content directly (kind 14/15 DM, or kind 7 reaction to a DM).
                    if (sealedKind !in listOf(13, 14, 15, 7)) {
                        timber.log.Timber.w(
                            "Rejecting candidate: kind=%d is invalid for gift-wrap. candidate=%s",
                            sealedKind,
                            senderPubkey.take(12)
                        )
                        throw IllegalArgumentException("Invalid gift-wrap inner kind: $sealedKind")
                    }
                    
                    when (sealedKind) {
                        13 -> {
                            val sealVerification = EventVerifier.verifyEvent(sealedMessage.toString())
                            val sealVerified = sealVerification.idMatches && sealVerification.signatureValid
                            if (!sealVerified) {
                                timber.log.Timber.w(
                                    "Inner seal failed NIP-01 verification (compat mode): sealId=%s idMatches=%s sigValid=%s",
                                    sealedMessage.optString("id", ""),
                                    sealVerification.idMatches,
                                    sealVerification.signatureValid
                                )
                            }
                            // Two-layer gift-wrap: decrypt the seal to extract the inner message
                            val sealContent = sealedMessage.getString("content")
                            val sealSenderPubkey = sealedMessage.getString("pubkey")
                            timber.log.Timber.d(
                                "Two-layer gift-wrap trace: outerEventId=%s outerCandidate=%s sealPubkey=%s sealContentLen=%d",
                                event.optString("id", ""),
                                senderPubkey.take(12),
                                sealSenderPubkey.take(12),
                                sealContent.length
                            )
                            val innerSenderCandidates = buildList {
                                val cleanSealSender = normalizeToXOnlyHex(sealSenderPubkey)
                                if (!cleanSealSender.isNullOrBlank()) add(cleanSealSender)
                                val outerCandidateNorm = normalizeToXOnlyHex(senderPubkey)
                                if (!outerCandidateNorm.isNullOrBlank()) add(outerCandidateNorm)
                                // include p-tag candidates as a fallback for inner attempts
                                pTagCandidates.forEach { normalizeToXOnlyHex(it)?.let { add(it) } }
                                senderPubkeyHints
                                    .mapNotNull { normalizeToXOnlyHex(it) }
                                    .forEach { add(it) }
                            }.distinct()

                            var innerLastError: Exception? = null
                            val innerLog = if (innerSenderCandidates.size <= 10) innerSenderCandidates.joinToString(",") else innerSenderCandidates.joinToString(",") { it.take(12) }
                            timber.log.Timber.d(
                                "Inner seal decrypt candidates (%d total): %s",
                                innerSenderCandidates.size,
                                innerLog
                            )
                            for (innerSender in innerSenderCandidates) {
                                try {
                                    timber.log.Timber.d(
                                        "Attempting inner seal decrypt: eventId=%s sealContentLen=%d candidate=%s",
                                        event.optString("id", ""),
                                        sealContent.length,
                                        innerSender.take(12)
                                    )
                                    val innerJson = decryptFn(sealContent, innerSender)
                                    val innerObj = parseJsonObjectOrThrow(
                                        innerJson,
                                        "Inner decrypt returned non-JSON payload"
                                    )
                                    timber.log.Timber.d(
                                        "Successfully decrypted inner seal with candidate=%s innerKind=%d innerPubkey=%s",
                                        innerSender.take(12),
                                        innerObj.optInt("kind", -1),
                                        innerObj.optString("pubkey", "").take(12)
                                    )
                                    innerObj.put("__resolved_sender_pubkey", innerSender.lowercase())
                                    return innerObj
                                } catch (ie: Exception) {
                                    innerLastError = ie
                                    timber.log.Timber.d(
                                        "Inner seal decrypt failed for candidate=%s error=%s",
                                        innerSender.take(12),
                                        ie.message?.take(80) ?: ie::class.java.simpleName
                                    )
                                }
                            }
                            timber.log.Timber.e(
                                "AUDIT: All %d inner seal decrypt candidates failed. outerCandidate=%s sealSender=%s (might be true sender). Are we using wrong decryption direction?",
                                innerSenderCandidates.size,
                                senderPubkey.take(12),
                                sealSenderPubkey.take(12)
                            )
                            throw innerLastError ?: IllegalArgumentException("All inner seal decrypt candidates failed")
                        }
                        14, 15, 7 -> {
                            // Single-layer gift wrap: the outer decrypted payload already contains the inner message
                            sealedMessage.put("__resolved_sender_pubkey", senderPubkey.lowercase())
                            return sealedMessage
                        }
                        else -> throw IllegalArgumentException("Unexpected sealed message kind: $sealedKind")
                    }
                } catch (e: Exception) {
                    lastError = e
                    candidateErrors.add("${senderPubkey.take(12)}:${e.message ?: e::class.java.simpleName}")
                    val isInvalidKind = e.message?.contains("Invalid gift-wrap inner kind") == true
                    if (isInvalidKind) {
                        timber.log.Timber.w(
                            "AUDIT: Candidate decrypted successfully but produced invalid kind. This is a false positive. candidate=%s",
                            senderPubkey.take(12)
                        )
                    } else {
                        timber.log.Timber.d(
                            "Decrypt attempt failed: candidate=%s error=%s class=%s",
                            senderPubkey.take(12),
                            e.message ?: "unknown",
                            e::class.simpleName
                        )
                    }
                }
            }
            if (candidateErrors.isNotEmpty()) {
                timber.log.Timber.d(
                    "Gift-wrap decrypt failures: eventId=%s details=%s",
                    event.optString("id", ""),
                    candidateErrors.joinToString(" | ")
                )
            }
            throw lastError ?: IllegalStateException("No valid sender key candidate for decrypt")
        } catch (e: Exception) {
            // External signer may legitimately return plain text like "Could not decrypt the message".
            // Treat this as an expected decrypt miss instead of hard error noise.
            val msg = e.message.orEmpty()
            val expectedMiss = msg.contains("Could not decrypt", ignoreCase = true) ||
                msg.contains("non-JSON payload", ignoreCase = true)
            try {
                if (expectedMiss) {
                    timber.log.Timber.d(
                        "Gift-wrap decrypt miss: %s outerContentSnippet=%s",
                        msg,
                        event.optString("content").take(64)
                    )
                } else {
                    timber.log.Timber.e(
                        e,
                        "Failed to decrypt gift-wrapped message: outerContentSnippet=%s",
                        event.optString("content").take(64)
                    )
                }
            } catch (_: Exception) {}
            return null
        }
    }

    private fun parseJsonObjectOrThrow(value: String, context: String): JSONObject {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{")) {
            throw IllegalArgumentException("$context: ${trimmed.take(80)}")
        }
        return JSONObject(trimmed)
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
        require(recipientPubkey.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid recipient pubkey" }
        require(senderPrivateKey.size == 32) { "Sender private key must be 32 bytes" }

        val senderPubkey = derivePublicKey(senderPrivateKey)
        val twoDaysInSeconds = 2 * 24 * 60 * 60
        val rumorCreatedAt = generateRandomTimestamp(twoDaysInSeconds)

        // Rumor (unsigned kind 14/15 event content)
        val rumor = createMessageEvent(kind, content, tags, rumorCreatedAt).apply {
            put("pubkey", senderPubkey)
            put("id", computeEventIdCanonical(senderPubkey, rumorCreatedAt, kind, tags, content))
        }

        // Seal (kind:13) signed by sender key; content is NIP-44 encrypted rumor.
        val sealContent = nip44Encrypt(rumor.toString(), senderPrivateKey, recipientPubkey.lowercase())
        val sealCreatedAt = generateRandomTimestamp(twoDaysInSeconds)
        // NIP-59: seal (kind 13) MUST have empty tags.
        val sealTags = emptyList<List<String>>()
        val sealId = computeEventIdCanonical(senderPubkey, sealCreatedAt, 13, sealTags, sealContent)
        val sealHash = MessageDigest.getInstance("SHA-256").digest(
            JSONArray().apply {
                put(0)
                put(senderPubkey)
                put(sealCreatedAt)
                put(13)
                put(JSONArray().apply { sealTags.forEach { put(JSONArray(it)) } })
                put(sealContent)
            }.toString().toByteArray(Charsets.UTF_8)
        )
        val sealSig = bytesToHex(schnorrSignBIP340(sealHash, senderPrivateKey))
        val sealEvent = JSONObject().apply {
            put("id", sealId)
            put("pubkey", senderPubkey)
            put("created_at", sealCreatedAt)
            put("kind", 13)
            put("tags", JSONArray())
            put("content", sealContent)
            put("sig", sealSig)
        }

        // Gift wrap (kind:1059) signed by one-time key; content is encrypted seal.
        val ephemeralKey = ECKey()
        val ephemeralPriv = ephemeralKey.privKeyBytes
        val ephemeralPub = derivePublicKey(ephemeralPriv)
        val wrapCreatedAt = generateRandomTimestamp(twoDaysInSeconds)
        val wrapContent = nip44Encrypt(sealEvent.toString(), ephemeralPriv, recipientPubkey.lowercase())
        val wrapTags = listOf(listOf("p", recipientPubkey.lowercase()))
        val wrapId = computeEventIdCanonical(ephemeralPub, wrapCreatedAt, 1059, wrapTags, wrapContent)
        val wrapHash = MessageDigest.getInstance("SHA-256").digest(
            JSONArray().apply {
                put(0)
                put(ephemeralPub)
                put(wrapCreatedAt)
                put(1059)
                put(JSONArray().apply { wrapTags.forEach { put(JSONArray(it)) } })
                put(wrapContent)
            }.toString().toByteArray(Charsets.UTF_8)
        )
        val wrapSig = bytesToHex(schnorrSignBIP340(wrapHash, ephemeralPriv))

        return JSONObject().apply {
            put("id", wrapId)
            put("pubkey", ephemeralPub)
            put("created_at", wrapCreatedAt)
            put("kind", 1059)
            put("tags", JSONArray().apply { wrapTags.forEach { put(JSONArray(it)) } })
            put("content", wrapContent)
            put("sig", wrapSig)
        }
    }

    /**
     * Prepare a NIP-59 gift wrap when sender key material lives in an external signer.
     * Seal encryption (rumor -> seal content) is delegated to the external signer.
     * Gift-wrap encryption (seal -> outer content) uses a local ephemeral key.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun prepareGiftWrappedMessageExternal(
        senderSigningPubkey: String,
        recipientPubkey: String,
        content: String,
        kind: Int,
        tags: List<List<String>>,
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null,
        externalEncryptor: suspend (plaintext: String, recipientPubkey: String) -> String
    ): JSONObject {
        require(recipientPubkey.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid recipient pubkey" }
        require(senderSigningPubkey.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid sender pubkey" }

        val twoDaysInSeconds = 2 * 24 * 60 * 60
        val rumorCreatedAt = generateRandomTimestamp(twoDaysInSeconds)
        val senderPubkey = senderSigningPubkey.lowercase()
        val recipient = recipientPubkey.lowercase()

        val rumor = createMessageEvent(kind, content, tags, rumorCreatedAt).apply {
            put("pubkey", senderPubkey)
            put("id", computeEventIdCanonical(senderPubkey, rumorCreatedAt, kind, tags, content))
        }

        val sealContent = externalEncryptor(rumor.toString(), recipient)
        val sealCreatedAt = generateRandomTimestamp(twoDaysInSeconds)
        // NIP-59: seal (kind 13) MUST have empty tags.
        val sealTags = emptyList<List<String>>()
        val sealEvent = NostrEventSigner.signEvent(
            kind = 13,
            content = sealContent,
            tags = sealTags,
            pubkey = senderPubkey,
            privKey = null,
            externalSignerPubkey = externalSignerPubkey,
            externalSignerPackage = externalSignerPackage,
            createdAt = sealCreatedAt
        )

        val ephemeralKey = ECKey()
        val ephemeralPriv = ephemeralKey.privKeyBytes
        val ephemeralPub = derivePublicKey(ephemeralPriv)
        val wrapCreatedAt = generateRandomTimestamp(twoDaysInSeconds)
        val wrapContent = nip44Encrypt(sealEvent.toString(), ephemeralPriv, recipient)
        val wrapTags = listOf(listOf("p", recipient))
        val wrapId = computeEventIdCanonical(ephemeralPub, wrapCreatedAt, 1059, wrapTags, wrapContent)
        val wrapHash = MessageDigest.getInstance("SHA-256").digest(
            JSONArray().apply {
                put(0)
                put(ephemeralPub)
                put(wrapCreatedAt)
                put(1059)
                put(JSONArray().apply { wrapTags.forEach { put(JSONArray(it)) } })
                put(wrapContent)
            }.toString().toByteArray(Charsets.UTF_8)
        )
        val wrapSig = bytesToHex(schnorrSignBIP340(wrapHash, ephemeralPriv))

        return JSONObject().apply {
            put("id", wrapId)
            put("pubkey", ephemeralPub)
            put("created_at", wrapCreatedAt)
            put("kind", 1059)
            put("tags", JSONArray().apply { wrapTags.forEach { put(JSONArray(it)) } })
            put("content", wrapContent)
            put("sig", wrapSig)
        }
    }

    private fun deriveMessageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == 32) { "Invalid conversation key length" }
        require(nonce.size == NONCE_SIZE) { "Invalid nonce length" }
        // NIP-44 v2 uses HKDF-expand with info=nonce over the 32-byte conversation key.
        val keys = hkdfExpandSha256(conversationKey, nonce, 76)
        return Triple(
            keys.copyOfRange(0, 32),    // ChaCha key
            keys.copyOfRange(32, 32 + CHACHA_NONCE_SIZE),   // ChaCha nonce
            keys.copyOfRange(44, 76)    // HMAC key
        )
    }

    private fun hkdfExpandSha256(prk: ByteArray, info: ByteArray, outputLen: Int): ByteArray {
        val hLen = 32
        val n = (outputLen + hLen - 1) / hLen
        require(n <= 255) { "HKDF output too large" }

        val out = ByteArray(outputLen)
        var previous = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            val hmac = HMac(SHA256Digest())
            hmac.init(KeyParameter(prk))
            if (previous.isNotEmpty()) hmac.update(previous, 0, previous.size)
            hmac.update(info, 0, info.size)
            hmac.update(i.toByte())
            val block = ByteArray(hLen)
            hmac.doFinal(block, 0)
            val toCopy = minOf(hLen, outputLen - offset)
            System.arraycopy(block, 0, out, offset, toCopy)
            offset += toCopy
            previous = block
        }
        return out
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

    // Validate a secp256k1 public key
    private fun validatePubkey(pubkey: String): Boolean {
        return when {
            pubkey.length == 66 && (pubkey.startsWith("02") || pubkey.startsWith("03")) -> true // compressed
            pubkey.length == 130 && pubkey.startsWith("04") -> true // uncompressed
            else -> false
        }
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
     * Parse decrypted NIP-17/NIP-25 message-like events (Kind 14/15/7) from JSON.
     */
    fun parseMessage(eventJson: String): Message? {
        return try {
            val obj = JSONObject(eventJson)
            
            when (obj.optInt("kind")) {
                14 -> parseTextMessage(obj)
                15 -> {
                    parseFileMessage(obj) ?: run {
                        android.util.Log.w(
                            "MessageRepository",
                            "Kind 15 missing required file tags; falling back to text parse id=${obj.optString("id")}"
                        )
                        parseTextMessage(obj)
                    }
                }
                7 -> parseReactionMessage(obj)
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
        val stableId = obj.optString("id").ifBlank {
            val source = "${obj.optString("pubkey")}:${obj.optLong("created_at")}:${obj.optString("content")}"
            MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        
        return Message.TextMessage(
            id = stableId,
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
        val stableId = obj.optString("id").ifBlank {
            val source = "${obj.optString("pubkey")}:${obj.optLong("created_at")}:${obj.optString("content")}"
            MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

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
            android.util.Log.w(
                "MessageRepository",
                "Kind 15 parse missing tags id=${obj.optString("id")} mime=$mimeType alg=$encryptionAlgorithm key=${decryptionKey != null} nonce=${decryptionNonce != null} x=${fileHash != null}"
            )
            return null
        }

        return Message.FileMessage(
            id = stableId,
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

    private fun parseReactionMessage(obj: JSONObject): Message.ReactionMessage? {
        val (recipientPubkeys, relayUrls, _, _) = extractCommonFields(obj)
        val stableId = obj.optString("id").ifBlank {
            val source = "${obj.optString("pubkey")}:${obj.optLong("created_at")}:${obj.optString("content")}:${obj.optJSONArray("tags")}"
            MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        val tags = obj.optJSONArray("tags") ?: JSONArray()
        var targetEventId: String? = null
        var targetEventPubkey: String? = null
        var targetEventKind: Int? = null
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            when (tag.optString(0)) {
                "e" -> {
                    val id = tag.optString(1, "").trim()
                    if (id.isNotBlank()) targetEventId = id
                }
                "p" -> {
                    val pk = tag.optString(1, "").trim()
                    if (pk.isNotBlank()) targetEventPubkey = pk
                }
                "k" -> {
                    val kindStr = tag.optString(1, "").trim()
                    targetEventKind = kindStr.toIntOrNull() ?: targetEventKind
                }
            }
        }

        if (targetEventId.isNullOrBlank()) {
            android.util.Log.w(
                "MessageRepository",
                "Kind 7 reaction missing required e-tag target id. id=${obj.optString("id")}"
            )
            return null
        }
        val targetId = targetEventId ?: return null

        val normalizedRecipients = recipientPubkeys.toMutableList()
        val targetPub = targetEventPubkey
        if (!targetPub.isNullOrBlank() && normalizedRecipients.none { it.equals(targetPub, true) }) {
            normalizedRecipients.add(targetPub)
        }

        return Message.ReactionMessage(
            id = stableId,
            pubkey = obj.optString("pubkey", ""),
            recipientPubkeys = normalizedRecipients,
            content = obj.optString("content", ""),
            targetEventId = targetId,
            targetEventPubkey = targetPub,
            targetEventKind = targetEventKind,
            createdAt = obj.optLong("created_at"),
            subject = null,
            replyTo = null,
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
