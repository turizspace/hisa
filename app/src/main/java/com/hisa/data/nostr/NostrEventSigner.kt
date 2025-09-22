    package com.hisa.data.nostr

    import org.json.JSONArray
    import org.json.JSONObject
    import java.security.MessageDigest
    import com.hisa.data.repository.MessageRepository
    import kotlinx.serialization.json.Json
    import kotlinx.serialization.json.JsonArray
    import kotlinx.serialization.json.JsonPrimitive

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
            // Build canonical JSON array using org.json to avoid Kotlin serialization generics
            val tagsJsonArray = JSONArray().apply { tags.forEach { inner -> put(JSONArray(inner)) } }
            val arrElement = JSONArray().apply {
                put(0)
                put(pubkey)
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
                        put("pubkey", pubkey)
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

                    // Parse returned signed event and extract sig field
                    val sigFromExternal = try {
                        val returned = JSONObject(signedEventJson)
                        val s = returned.optString("sig")
                        if (s.isNullOrBlank()) throw IllegalStateException("External signer returned empty signature")
                        s
                    } catch (e: Exception) {
                        throw IllegalStateException("Failed to parse signed event from external signer: ${e.message}")
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
                put("pubkey", pubkey)
                put("created_at", createdAt)
                put("kind", kind)
                put("tags", tagsJson)
                put("content", content)
                put("sig", sig)
            }
        }
    }
