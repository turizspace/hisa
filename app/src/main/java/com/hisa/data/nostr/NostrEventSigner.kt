    package com.hisa.data.nostr

    import org.json.JSONArray
    import org.json.JSONObject
    import java.security.MessageDigest
    import com.hisa.data.repository.MessageRepository
    import com.hisa.util.KeyGenerator
    import com.hisa.util.hexToByteArray
    import org.bitcoinj.core.ECKey

    object NostrEventSigner {
        /**
        * Centralized NIP-01 event signing for all event types.
        * @param kind Event kind (Int)
        * @param content Event content (String)
        * @param tags List of List<String> (tags)
        * @param pubkey x-only pubkey (64 hex chars, lowercase)
        * @param privKey 32-byte private key (ByteArray)
        * @param createdAt Unix timestamp (Long)
        * @return JSONObject with id, pubkey, created_at, kind, tags, content, sig
        */
    private fun normalizeToXOnlyHex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        try {
            if (s.startsWith("npub", true)) {
                val conv = KeyGenerator.npubToPublicKey(s)
                if (conv == null) return null
                s = conv
            }
        } catch (_: Exception) {
        }
        s = s.removePrefix("0x").lowercase()
        return when (s.length) {
            66 -> if (s.startsWith("02") || s.startsWith("03")) s.substring(2) else null
            130 -> if (s.startsWith("04")) s.substring(2, 66) else null
            64 -> s
            else -> null
        }
    }

    suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        pubkey: String,
        privKey: ByteArray?,
        externalSignerPubkey: String? = null,
        externalSignerPackage: String? = null,
        contentResolver: android.content.ContentResolver? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JSONObject {
        var normalizedPub = normalizeToXOnlyHex(pubkey)
            ?: throw IllegalArgumentException("Invalid pubkey format: $pubkey")

        if (privKey != null) {
            try {
                val ec = ECKey.fromPrivate(privKey)
                val uncompressed = ec.decompress().pubKeyPoint.getEncoded(false)
                val xOnly = uncompressed.copyOfRange(1, 33)
                normalizedPub = xOnly.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
            }
        }

        val tagsJsonArray = JSONArray().apply { tags.forEach { inner -> put(JSONArray(inner)) } }
        val eventIdCandidate = computeEventId(normalizedPub, createdAt, kind, tags, content)

        return if (privKey != null) {
            val eventIdBytes = hexToByteArray(eventIdCandidate, 32)
            val sig = MessageRepository.schnorrSignBIP340(
                eventIdBytes,
                privKey
            ).joinToString("") { "%02x".format(it) }

            JSONObject().apply {
                put("id", eventIdCandidate)
                put("pubkey", normalizedPub)
                put("created_at", createdAt)
                put("kind", kind)
                put("tags", tagsJsonArray)
                put("content", content)
                put("sig", sig)
            }
        } else {
            val cfgPub = externalSignerPubkey ?: ExternalSignerManager.getConfiguredPubkey()
            val cfgPkg = externalSignerPackage ?: ExternalSignerManager.getConfiguredPackage()

            if (cfgPub.isNullOrBlank() || cfgPkg.isNullOrBlank()) {
                throw IllegalStateException(
                    "External signing requested but the app is not wired to an external signer. " +
                        "Provide externalSignerPubkey and externalSignerPackage to enable external signing."
                )
            }

            val eventJsonObject = JSONObject().apply {
                put("id", eventIdCandidate)
                put("pubkey", normalizedPub)
                put("created_at", createdAt)
                put("kind", kind)
                put("tags", tagsJsonArray)
                put("content", content)
                put("sig", "")
            }

            val eventJsonString = eventJsonObject.toString()
            val intentResult = try {
                ExternalSignerManager.signEvent(eventJsonString, eventIdCandidate)
            } catch (e: Exception) {
                android.util.Log.e(
                    "NostrEventSigner",
                    "External signer failed for eventId=$eventIdCandidate pubkey=$normalizedPub kind=$kind createdAt=$createdAt tags=${tags.size}",
                    e
                )
                throw IllegalStateException("External signer failed: ${e.message}", e)
            }

            val signedEventJson = intentResult.event
                ?: throw IllegalStateException("External signer did not return a signed event")

            val parsed = try {
                JSONObject(signedEventJson)
            } catch (e: Exception) {
                android.util.Log.e(
                    "NostrEventSigner",
                    "External signer returned invalid JSON for eventId=$eventIdCandidate pubkey=$normalizedPub kind=$kind createdAt=$createdAt",
                    e
                )
                throw IllegalStateException("External signer returned invalid JSON", e)
            }

            val returnedSig = parsed.optString("sig").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("External signer returned empty signature")
            val returnedId = parsed.optString("id").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("External signer returned empty id")

            if (returnedSig.length != 128 || !returnedSig.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw IllegalStateException("Invalid signature format from signer: expected 128-char hex")
            }
            if (returnedId.length != 64 || !returnedId.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw IllegalStateException("Invalid id format from signer: expected 64-char hex")
            }

            // Detailed field comparison to detect signer modifications
            val sentEvent = JSONObject(eventJsonObject.toString())
            val sentPubkey = sentEvent.optString("pubkey", "")
            val sentCreatedAt = sentEvent.optLong("created_at", 0L)
            val sentKind = sentEvent.optInt("kind", -1)
            val sentTags = sentEvent.optJSONArray("tags") ?: JSONArray()
            val sentContent = sentEvent.optString("content", "")
            val returnedPubkey = parsed.optString("pubkey", "")
            val returnedCreatedAt = parsed.optLong("created_at", 0L)
            val returnedKind = parsed.optInt("kind", -1)
            val returnedTags = parsed.optJSONArray("tags") ?: JSONArray()
            val returnedContent = parsed.optString("content", "")
            
            val pubkeyMatch = sentPubkey == returnedPubkey
            val createdAtMatch = sentCreatedAt == returnedCreatedAt
            val kindMatch = sentKind == returnedKind
            val tagsMatch = sentTags.toString() == returnedTags.toString()
            val contentMatch = sentContent == returnedContent
            
            if (!pubkeyMatch || !createdAtMatch || !kindMatch || !tagsMatch || !contentMatch) {
                android.util.Log.w(
                    "NostrEventSigner",
                    "External signer returned event with field modifications: pubkey=$pubkeyMatch createdAt=$createdAtMatch kind=$kindMatch tags=$tagsMatch content=$contentMatch"
                )
                if (!contentMatch) {
                    android.util.Log.w(
                        "NostrEventSigner",
                        "Content mismatch: sent_len=${sentContent.length} returned_len=${returnedContent.length}"
                    )
                }
            }

            val parsedComputedId = try {
                computeEventId(
                    pubkey = parsed.optString("pubkey"),
                    createdAt = parsed.optLong("created_at"),
                    kind = parsed.optInt("kind"),
                    tags = (0 until parsed.optJSONArray("tags")!!.length()).map { i ->
                        val tagArr = parsed.optJSONArray("tags")!!.getJSONArray(i)
                        (0 until tagArr.length()).map { tagArr.getString(it) }
                    },
                    content = parsed.optString("content")
                )
            } catch (e: Exception) {
                null
            }

            if (returnedId != eventIdCandidate || returnedId != parsedComputedId) {
                android.util.Log.w(
                    "NostrEventSigner",
                    "External signer ID diagnostic: candidate=$eventIdCandidate returned=$returnedId parsedComputed=$parsedComputedId pubkey=$normalizedPub kind=$kind createdAt=$createdAt tags=${tags.size}"
                )
            }

            val verification = try {
                EventVerifier.verifyEvent(parsed.toString())
            } catch (e: Exception) {
                null
            }
            if (verification != null && (!verification.idMatches || !verification.signatureValid)) {
                android.util.Log.w(
                    "NostrEventSigner",
                    "Signed external event verification failed: idMatches=${verification.idMatches} sigValid=${verification.signatureValid} computed=${verification.computedId} returnedId=$returnedId pubkey=$normalizedPub kind=$kind"
                )
            }

            // If the signer returned fields that recompute to our candidate ID, but put a different ID in the response,
            // check if the signature is actually valid over the candidate ID (signer might have a response-encoding bug)
            if (parsedComputedId == eventIdCandidate && parsedComputedId != returnedId) {
                val eventWithCandidateId = JSONObject(parsed.toString()).apply {
                    put("id", eventIdCandidate)
                }
                val verificationWithCandidateId = try {
                    EventVerifier.verifyEvent(eventWithCandidateId.toString())
                } catch (e: Exception) {
                    null
                }
                if (verificationWithCandidateId?.signatureValid == true && verificationWithCandidateId.idMatches) {
                    android.util.Log.d(
                        "NostrEventSigner",
                        "Signature IS valid with candidate ID; signer had fields correct but wrong id in response"
                    )
                    // Use the candidate ID since signature validates over it
                    return buildSignedEventFromExternalSigner(eventWithCandidateId.toString(), eventIdCandidate, returnedSig)
                }
            }

            // If the signer returned a malformed event JSON, try using the returned signature with the original unsigned payload.
            val originalSignedEvent = buildSignedEventFromExternalSigner(eventJsonString, eventIdCandidate, returnedSig)
            val originalVerification = try {
                EventVerifier.verifyEvent(originalSignedEvent.toString())
            } catch (e: Exception) {
                null
            }
            if (originalVerification?.signatureValid == true && originalVerification.idMatches) {
                android.util.Log.d(
                    "NostrEventSigner",
                    "Fallback to original unsigned payload with signer-provided signature"
                )
                return originalSignedEvent
            }

            buildSignedEventFromExternalSigner(signedEventJson, eventIdCandidate, returnedSig)
        }
    }

    private fun computeEventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
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

        fun buildSignedEventFromExternalSigner(
            signedEventJson: String,
            fallbackId: String,
            fallbackSig: String
        ): JSONObject {
            val signed = JSONObject(signedEventJson)
            val returnedId = signed.optString("id", fallbackId).takeIf { it.isNotBlank() } ?: fallbackId
            val returnedSig = signed.optString("sig", fallbackSig).takeIf { it.isNotBlank() } ?: fallbackSig
            val computedId = try {
                EventVerifier.computeCanonicalId(signed)
            } catch (_: Exception) {
                null
            }

            val effectiveId = when {
                computedId != null && fallbackId.isNotBlank() && computedId.equals(fallbackId, ignoreCase = true) -> fallbackId
                computedId != null && returnedId.isNotBlank() && returnedId.equals(computedId, ignoreCase = true) -> returnedId
                fallbackId.isNotBlank() -> fallbackId
                else -> returnedId
            }

            return JSONObject().apply {
                put("id", effectiveId)
                put("pubkey", signed.optString("pubkey"))
                put("created_at", signed.optLong("created_at"))
                put("kind", signed.optInt("kind"))
                put("tags", signed.optJSONArray("tags") ?: JSONArray())
                put("content", signed.optString("content"))
                put("sig", returnedSig)
            }
        }
    }
