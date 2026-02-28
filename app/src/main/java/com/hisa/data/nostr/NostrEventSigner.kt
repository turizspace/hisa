    package com.hisa.data.nostr

    import org.json.JSONArray
    import org.json.JSONObject
    import java.security.MessageDigest
    import com.hisa.data.repository.MessageRepository
    import kotlinx.serialization.json.Json
    import kotlinx.serialization.json.JsonArray
    import kotlinx.serialization.json.JsonPrimitive
    import com.hisa.util.KeyGenerator
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
    suspend fun signEvent(
            kind: Int,
            content: String,
            tags: List<List<String>>,
            pubkey: String,
            privKey: ByteArray?,
            // Optional: external signer wiring info. When provided and privKey is null,
            // the function will attempt to use the external signer.
            externalSignerPubkey: String? = null,
            externalSignerPackage: String? = null,
            contentResolver: android.content.ContentResolver? = null,
            createdAt: Long = System.currentTimeMillis() / 1000
        ): JSONObject {
            // Normalize pubkey to x-only hex (64 lowercase hex chars). Accept npub, compressed, uncompressed.
            fun normalizeToXOnlyHex(raw: String?): String? {
                if (raw.isNullOrBlank()) return null
                var s = raw.trim()
                try {
                    if (s.startsWith("npub", true)) {
                        val conv = KeyGenerator.npubToPublicKey(s)
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

            var normalizedPub = normalizeToXOnlyHex(pubkey) ?: throw IllegalArgumentException("Invalid pubkey format: $pubkey")

            // If we're signing locally with a private key, derive the x-only pubkey from that
            // private key and use it for canonicalization/signature to avoid mismatches when
            // callers pass an inconsistent `pubkey` value.
            if (privKey != null) {
                try {
                    val ec = ECKey.fromPrivate(privKey)
                    val uncompressed = ec.decompress().pubKeyPoint.getEncoded(false)
                    val xOnly = uncompressed.copyOfRange(1, 33)
                    normalizedPub = xOnly.joinToString("") { "%02x".format(it) }
                } catch (_: Exception) {
                    // Fall back to provided/normalized pubkey if derivation fails
                }
            }

            // Build canonical JSON array using org.json to avoid Kotlin serialization generics
            val tagsJsonArray = JSONArray().apply { tags.forEach { inner -> put(JSONArray(inner)) } }
            val arrElement = JSONArray().apply {
                put(0)
                put(normalizedPub)
                put(createdAt)
                put(kind)
                put(tagsJsonArray)
                put(content)
            }

            // Serialize to canonical string (UTF-8) for hashing
            val serialized = arrElement.toString()
            val hash = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
            val id = hash.joinToString("") { "%02x".format(it) }

            // If a private key was provided, sign locally. Otherwise delegate to external signer (Amber) if configured.
            val sig = if (privKey != null) {
                MessageRepository.schnorrSignBIP340(hash, privKey).joinToString("") { "%02x".format(it) }
            } else {
                // Attempt to use external signer via Intent bridge
                // If caller didn't provide explicit external signer wiring, use the globally configured one
                val cfgPub = externalSignerPubkey ?: com.hisa.data.nostr.ExternalSignerManager.getConfiguredPubkey()
                val cfgPkg = externalSignerPackage ?: com.hisa.data.nostr.ExternalSignerManager.getConfiguredPackage()

                if (!cfgPub.isNullOrBlank() && !cfgPkg.isNullOrBlank()) {
                    // Build the event JSONObject (sig is empty) to send to external signer
                    val tagsJson = JSONArray().apply { tags.forEach { put(JSONArray(it)) } }
                    val eventJsonObject = JSONObject().apply {
                        put("id", id)
                        put("pubkey", normalizedPub)
                        put("created_at", createdAt)
                        put("kind", kind)
                        put("tags", tagsJson)
                        put("content", content)
                        put("sig", "")
                    }

                    val eventJsonString = eventJsonObject.toString()

                    // Call ExternalSignerManager to launch signer and await result synchronously
                    val intentResult = try {
                        ExternalSignerManager.signEvent(eventJsonString, id)
                    } catch (e: Exception) {
                        throw IllegalStateException("External signer failed: ${e.message}")
                    }

                    // The external signer should return the signed event JSON in intentResult.event
                    val signedEventJson = intentResult.event ?: throw IllegalStateException("External signer did not return a signed event")

                    // Parse returned signed event and extract sig field, then verify it matches our computed id
                    val sigFromExternal = try {
                        val returned = JSONObject(signedEventJson)
                        val s = returned.optString("sig")
                        if (s.isNullOrBlank()) throw IllegalStateException("External signer returned empty signature")
                        // Build a verification object using our computed id and normalized pubkey
                        val verifyObj = JSONObject().apply {
                            put("id", id)
                            put("pubkey", normalizedPub)
                            put("created_at", createdAt)
                            put("kind", kind)
                            put("tags", tagsJson)
                            put("content", content)
                            put("sig", s)
                        }
                        val verification = try {
                            EventVerifier.verifyEvent(verifyObj.toString())
                        } catch (e: Exception) {
                            throw IllegalStateException("Failed to verify external signature: ${e.message}")
                        }
                        if (!verification.idMatches) throw IllegalStateException("External signer returned signature for different id (computed=${verification.computedId} expected=$id)")
                        if (!verification.signatureValid) throw IllegalStateException("External signer returned signature that does not verify for computed id")
                        s
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to parse/verify signed event from external signer: ${e.message}")
                    }

                    sigFromExternal
                } else {
                    throw IllegalStateException(
                        "External signing requested but the app is not wired to an external signer. " +
                        "Provide externalSignerPubkey and externalSignerPackage to enable external signing."
                    )
                }
            }
            
            // Build result JSONObject (legacy API) using org.json for compatibility
            val tagsJson = JSONArray().apply { tags.forEach { put(JSONArray(it)) } }
            return JSONObject().apply {
                put("id", id)
                put("pubkey", normalizedPub)
                put("created_at", createdAt)
                put("kind", kind)
                put("tags", tagsJson)
                put("content", content)
                put("sig", sig)
            }
        }
    }
