package com.hisa.data.nostr

import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

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

    private data class SchnorrVerification(
        val isValid: Boolean,
        val error: String? = null
    )

    private fun verifySchnorr(sig: ByteArray, msg: ByteArray, pub: ByteArray): SchnorrVerification {
        return try {
            val secpClass = Class.forName("fr.acinq.secp256k1.Secp256k1")
            val secp = secpClass.getMethod("get").invoke(null)
            val verifySchnorr = secpClass.getMethod(
                "verifySchnorr",
                ByteArray::class.java,
                ByteArray::class.java,
                ByteArray::class.java
            )
            SchnorrVerification(verifySchnorr.invoke(secp, sig, msg, pub) as? Boolean ?: false)
        } catch (e: Throwable) {
            val cause = if (e is InvocationTargetException) e.targetException else e
            val detail = cause.message ?: cause::class.java.simpleName
            SchnorrVerification(false, "Native verification unavailable: $detail")
        }
    }

    /**
     * Recompute canonical NIP-01 id for the given event JSONObject.
     */
    fun computeCanonicalId(event: JSONObject): String {
        return NostrCanonicalJson.computeEventId(event)
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

            if (sig.size != 64) {
                return VerificationResult(idMatches, false, computedId, "Invalid signature length: ${sig.size}")
            }
            if (msg.size != 32) {
                return VerificationResult(idMatches, false, computedId, "Invalid event id length: ${msg.size}")
            }
            if (pub.size != 32) {
                return VerificationResult(idMatches, false, computedId, "Invalid pubkey length: ${pub.size}")
            }

            // Verify via the ACINQ singleton. Reflection keeps JVM unit tests from
            // hard-failing when the native library is unavailable on the host.
            val schnorrVerification = verifySchnorr(sig, msg, pub)

            return VerificationResult(
                idMatches = idMatches,
                signatureValid = schnorrVerification.isValid,
                computedId = computedId,
                reason = schnorrVerification.error
            )
        } catch (e: Exception) {
            return VerificationResult(false, false, "", "Exception: ${e.message}")
        }
    }
}
