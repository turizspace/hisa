package com.hisa.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Blossom Upload Descriptor (BUD) event builder
 * Generates NIP-98 HTTP File Server API compatible events
 * 
 * Kind: 24242 (File server events)
 * Tags:
 *   - ["t", verb]           : Action type (e.g., "upload", "delete")
 *   - ["expiration", unix]  : Event expires at timestamp (prevents replay)
 *   - ["x", sha256]         : File SHA-256 hash for integrity
 * 
 * Reference: https://github.com/nostr-protocol/nips/blob/master/98.md
 */
object BudEventBuilder {
    
    const val BUD_EVENT_KIND = 24242
    const val DEFAULT_EXPIRATION_SECONDS = 300L  // 5 minutes
    
    /**
     * Generate unsigned BUD event for file upload
     * 
     * The event will be sent to external signer (Amber) which will:
     * - Compute the canonical ID
     * - Sign with user's private key
     * - Return signed event with "id" and "sig" fields
     * 
     * @param pubkey User's Nostr public key (hex, x-only)
     * @param fileSha256 SHA-256 hash of file to upload (hex)
     * @param verb Action type: "upload", "delete", etc. (default: "upload")
     * @param expirationSeconds How long until event expires (default: 300 = 5 min)
     * @param filename Original filename (included in content)
     * @return Unsigned event JSON string (no "id" or "sig" yet)
     * 
     * Best Practice: Keep expiration short (5 min) to prevent replay attacks
     */
    fun buildUnsignedEvent(
        pubkey: String,
        fileSha256: String,
        verb: String = "upload",
        expirationSeconds: Long = DEFAULT_EXPIRATION_SECONDS,
        filename: String = "file"
    ): String {
        if (pubkey.length != 64 || !pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw IllegalArgumentException("Invalid pubkey: must be 64-char hex")
        }
        if (fileSha256.length != 64 || !fileSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw IllegalArgumentException("Invalid file hash: must be 64-char hex")
        }
        
        val now = System.currentTimeMillis() / 1000L
        val expirationTime = now + expirationSeconds
        
        // Build tags array with required fields
        val tags = JSONArray().apply {
            // Action type tag
            put(JSONArray().apply {
                put("t")
                put(verb)
            })
            // Expiration tag (Unix timestamp)
            put(JSONArray().apply {
                put("expiration")
                put(expirationTime.toString())
            })
            // File hash tag (for integrity verification)
            put(JSONArray().apply {
                put("x")
                put(fileSha256)
            })
        }
        
        // Build unsigned event
        val event = JSONObject().apply {
            put("kind", BUD_EVENT_KIND)
            put("created_at", now)
            put("tags", tags)
            put("content", "Upload $filename")
            put("pubkey", pubkey)
            // Note: "id" and "sig" will be added by external signer
        }
        
        return event.toString()
    }
    
    /**
     * Validate unsigned event structure before sending to signer
     * 
     * Ensures:
     * - Correct kind (24242)
     * - Required fields present (pubkey, created_at, tags, content)
     * - NOT already signed (no "id" or "sig")
     * - Contains required tags (t, expiration, x)
     */
    fun validateUnsignedEvent(eventJson: String): Boolean {
        return try {
            val event = JSONObject(eventJson)
            
            // Check kind
            if (event.getInt("kind") != BUD_EVENT_KIND) {
                android.util.Log.w("BudEventBuilder", "Wrong kind: ${event.getInt("kind")} != $BUD_EVENT_KIND")
                return false
            }
            
            // Check required fields
            if (!event.has("created_at")) {
                android.util.Log.w("BudEventBuilder", "Missing created_at")
                return false
            }
            if (!event.has("tags")) {
                android.util.Log.w("BudEventBuilder", "Missing tags")
                return false
            }
            if (!event.has("content")) {
                android.util.Log.w("BudEventBuilder", "Missing content")
                return false
            }
            if (!event.has("pubkey")) {
                android.util.Log.w("BudEventBuilder", "Missing pubkey")
                return false
            }
            
            // Must NOT have sig or id yet (should be unsigned)
            if (event.has("sig")) {
                android.util.Log.w("BudEventBuilder", "Event already has signature")
                return false
            }
            if (event.has("id")) {
                android.util.Log.w("BudEventBuilder", "Event already has id")
                return false
            }
            
            // Check tags contain required fields
            val tags = event.getJSONArray("tags")
            var hasVerb = false
            var hasExpiration = false
            var hasFileHash = false
            
            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                when (tag.getString(0)) {
                    "t" -> hasVerb = true
                    "expiration" -> hasExpiration = true
                    "x" -> hasFileHash = true
                }
            }
            
            if (!hasVerb) {
                android.util.Log.w("BudEventBuilder", "Missing 't' tag (verb)")
                return false
            }
            if (!hasExpiration) {
                android.util.Log.w("BudEventBuilder", "Missing 'expiration' tag")
                return false
            }
            if (!hasFileHash) {
                android.util.Log.w("BudEventBuilder", "Missing 'x' tag (file hash)")
                return false
            }
            
            true
        } catch (e: Exception) {
            android.util.Log.e("BudEventBuilder", "Validation error: ${e.message}")
            false
        }
    }
    
    /**
     * Validate signed event before sending to Blossom server
     * 
     * Ensures:
     * - Has valid id (64-char hex)
     * - Has valid signature (128-char hex, Schnorr format)
     * - Pubkey is present and valid
     * - All unsigned fields still present
     */
    fun validateSignedEvent(eventJson: String): Boolean {
        return try {
            val event = JSONObject(eventJson)
            
            // Check required fields from unsigned event still present
            if (!validateUnsignedEventFields(event)) return false
            
            // Check for signed fields
            val id = event.optString("id", "")
            val sig = event.optString("sig", "")
            
            if (id.isBlank()) {
                android.util.Log.w("BudEventBuilder", "Missing or empty id")
                return false
            }
            if (sig.isBlank()) {
                android.util.Log.w("BudEventBuilder", "Missing or empty signature")
                return false
            }
            
            // Validate id is 64-char hex
            if (id.length != 64 || !id.all { it in '0'..'9' || it in 'a'..'f' }) {
                android.util.Log.w("BudEventBuilder", "Invalid id format")
                return false
            }
            
            // Validate sig is 128-char hex (Schnorr format)
            if (sig.length != 128 || !sig.all { it in '0'..'9' || it in 'a'..'f' }) {
                android.util.Log.w("BudEventBuilder", "Invalid signature format")
                return false
            }
            
            true
        } catch (e: Exception) {
            android.util.Log.e("BudEventBuilder", "Signed event validation error: ${e.message}")
            false
        }
    }
    
    /**
     * Check that basic unsigned fields are still present in event
     */
    private fun validateUnsignedEventFields(event: JSONObject): Boolean {
        return event.has("kind") &&
               event.has("created_at") &&
               event.has("tags") &&
               event.has("content") &&
               event.has("pubkey") &&
               event.getInt("kind") == BUD_EVENT_KIND
    }
}
