package com.hisa.data.nostr

// Use reflection to call Secp256k1.verifySchnorr to avoid loading native library during unit tests
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object EventVerifier {
    data class VerificationResult(
        val idMatches: Boolean,
        val signatureValid: Boolean,
        val computedId: String,
        val reason: String? = null
    )

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().removePrefix("0x").lowercase()
        require(clean.length % 2 == 0) { "Invalid hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Recompute canonical NIP-01 id for the given event JSONObject.
     */
    fun computeCanonicalId(event: JSONObject): String {
        val pubkey = event.optString("pubkey", "")
        val createdAt = event.optLong("created_at", 0L)
        val kind = event.optInt("kind", 0)
        val tags = event.optJSONArray("tags") ?: JSONArray()
        val content = event.optString("content", "")

        val tagsJsonArray = JSONArray()
        for (i in 0 until tags.length()) {
            val inner = tags.getJSONArray(i)
            tagsJsonArray.put(inner)
        }

        val arr = JSONArray().apply {
            put(0)
            put(pubkey)
            put(createdAt)
            put(kind)
            put(tagsJsonArray)
            put(content)
        }
        val serialized = arr.toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verify event id and Schnorr signature using ACINQ Secp256k1 library.
     * Returns debug details so you can see whether id mismatch or signature mismatch.
     */
    fun verifyEvent(eventJson: String): VerificationResult {
        try {
            val obj = JSONObject(eventJson)
            val givenId = obj.optString("id", "")
            val sigHex = obj.optString("sig", "")
            val pubkeyHex = obj.optString("pubkey", "")

            if (givenId.isBlank() || sigHex.isBlank() || pubkeyHex.isBlank()) {
                return VerificationResult(false, false, "", "Missing id/sig/pubkey")
            }

            val computedId = computeCanonicalId(obj)
            val idMatches = computedId.equals(givenId, ignoreCase = true)

            // Prepare inputs for Schnorr verify: signature(64), message(32), pubkey(32)
            val sig = hexToBytes(sigHex)
            val msg = hexToBytes(computedId)
            val pub = hexToBytes(pubkeyHex)

            // Try to verify with ACINQ Secp256k1 via reflection. If native library is not available
            // (e.g., during unit tests), skip signature verification and report accordingly.
            val signatureValid = try {
                val cls = Class.forName("fr.acinq.secp256k1.Secp256k1")
                val method = cls.getMethod("verifySchnorr", ByteArray::class.java, ByteArray::class.java, ByteArray::class.java)
                method.invoke(null, sig, msg, pub) as? Boolean ?: false
            } catch (e: Throwable) {
                // Native library unavailable or method not found
                false
            }

            return VerificationResult(idMatches, signatureValid, computedId, null)
        } catch (e: Exception) {
            return VerificationResult(false, false, "", "Exception: ${e.message}")
        }
    }
}
