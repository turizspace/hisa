    package com.hisa.data.nostr

    import org.json.JSONArray
    import org.json.JSONObject
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

            // Normalize returned pubkey to x-only lowercase hex before verifying canonical ids.
            val returnedPubkeyRaw = parsed.optString("pubkey", "")
            val normalizedReturnedPubkey = normalizeToXOnlyHex(returnedPubkeyRaw)
                ?: returnedPubkeyRaw.trim().lowercase()

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
            val returnedCreatedAt = parsed.optLong("created_at", 0L)
            val returnedKind = parsed.optInt("kind", -1)
            val returnedTags = parsed.optJSONArray("tags") ?: JSONArray()
            val returnedContent = parsed.optString("content", "")
            
            val returnedPubkey = normalizedReturnedPubkey
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
                    pubkey = normalizeToXOnlyHex(parsed.optString("pubkey"))
                        ?: parsed.optString("pubkey"),
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

            val parsedVerification = try {
                EventVerifier.verifyEvent(parsed.toString())
            } catch (e: Exception) {
                null
            }
            if (parsedVerification != null && (!parsedVerification.idMatches || !parsedVerification.signatureValid)) {
                android.util.Log.w(
                    "NostrEventSigner",
                    "Signed external event verification failed: idMatches=${parsedVerification.idMatches} sigValid=${parsedVerification.signatureValid} computed=${parsedVerification.computedId} returnedId=$returnedId pubkey=$normalizedPub kind=$kind"
                )
            }

            // Prefer the original unsigned payload when the signer only returns a signature.
            val originalSignedEvent = buildSignedEventFromExternalSigner(
                signedEventJson = eventJsonString,
                fallbackId = eventIdCandidate,
                fallbackSig = returnedSig,
                unsignedEventJson = eventJsonString
            )
            val originalVerification = try {
                EventVerifier.verifyEvent(originalSignedEvent.toString())
            } catch (e: Exception) {
                null
            }
            if (originalVerification?.signatureValid == true && originalVerification.idMatches) {
                android.util.Log.d(
                    "NostrEventSigner",
                    "Using original unsigned payload with signer-provided signature"
                )
                return originalSignedEvent
            }

            // If the signer returned a full event payload, validate it before using it.
            val signedEvent = buildSignedEventFromExternalSigner(
                signedEventJson = signedEventJson,
                fallbackId = eventIdCandidate,
                fallbackSig = returnedSig,
                unsignedEventJson = eventJsonString
            )
            val signedVerification = try {
                EventVerifier.verifyEvent(signedEvent.toString())
            } catch (e: Exception) {
                null
            }
            if (signedVerification?.signatureValid == true && signedVerification.idMatches) {
                return signedEvent
            }

            throw IllegalStateException(
                "External signer returned an event that does not match the canonical payload: " +
                    "returnedId=$returnedId computedId=$parsedComputedId expectedId=$eventIdCandidate"
            )
        }
    }

    private fun computeEventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String {
        return NostrCanonicalJson.computeEventId(pubkey, createdAt, kind, tags, content)
    }

        fun buildSignedEventFromExternalSigner(
            signedEventJson: String,
            fallbackId: String,
            fallbackSig: String,
            unsignedEventJson: String? = null
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

            val normalizedSignedPubkey = normalizeToXOnlyHex(signed.optString("pubkey"))
                ?: signed.optString("pubkey").trim().lowercase()
            val finalEvent = JSONObject().apply {
                put("id", effectiveId)
                put("pubkey", normalizedSignedPubkey)
                put("created_at", signed.optLong("created_at"))
                put("kind", signed.optInt("kind"))
                put("tags", signed.optJSONArray("tags") ?: JSONArray())
                put("content", signed.optString("content"))
                put("sig", returnedSig)
            }

            if (!unsignedEventJson.isNullOrBlank()) {
                val expected = try {
                    JSONObject(unsignedEventJson)
                } catch (_: Exception) {
                    null
                }
                if (expected != null) {
                    val expectedId = try {
                        EventVerifier.computeCanonicalId(expected)
                    } catch (_: Exception) {
                        null
                    }
                    val canonicalId = try {
                        EventVerifier.computeCanonicalId(finalEvent)
                    } catch (_: Exception) {
                        null
                    }
                    if (expectedId != null && canonicalId != null && !canonicalId.equals(expectedId, ignoreCase = true)) {
                        throw IllegalStateException(
                            "External signer returned an event whose canonical id does not match the expected payload: expected=$expectedId actual=$canonicalId"
                        )
                    }
                    val mismatches = mutableListOf<String>()
                    val expectedPubkey = normalizeToXOnlyHex(expected.optString("pubkey"))
                        ?: expected.optString("pubkey").trim().lowercase()
                    val finalPubkey = normalizeToXOnlyHex(finalEvent.optString("pubkey"))
                        ?: finalEvent.optString("pubkey").trim().lowercase()
                    if (expectedPubkey != finalPubkey) mismatches.add("pubkey")
                    if (expected.optLong("created_at") != finalEvent.optLong("created_at")) mismatches.add("created_at")
                    if (expected.optInt("kind") != finalEvent.optInt("kind")) mismatches.add("kind")
                    if (expected.optJSONArray("tags")?.toString() != finalEvent.optJSONArray("tags")?.toString()) mismatches.add("tags")
                    if (expected.optString("content") != finalEvent.optString("content")) mismatches.add("content")
                    if (mismatches.isNotEmpty()) {
                        throw IllegalStateException(
                            "External signer returned event with field mismatches: ${mismatches.joinToString(", ")}"
                        )
                    }
                }
            }

            return finalEvent
        }
    }
