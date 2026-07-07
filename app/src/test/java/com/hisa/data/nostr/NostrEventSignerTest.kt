package com.hisa.data.nostr

import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventSignerTest {

    private fun Throwable.isNativeSecp256k1Unavailable(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message.orEmpty()
            if (current is UnsatisfiedLinkError ||
                message.contains("secp256k1", ignoreCase = true) ||
                message.contains("no implementation", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    @Test
    fun `buildSignedEventFromExternalSigner preserves signer returned id`() {
        val unsignedEventJson = JSONObject().apply {
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234)
            put("kind", 4)
            put("tags", JSONArray())
            put("content", "hi")
        }
        val signerReturnedEvent = JSONObject(unsignedEventJson.toString()).apply {
            put("id", EventVerifier.computeCanonicalId(this))
            put("sig", "bb".repeat(64))
        }

        val result = NostrEventSigner.buildSignedEventFromExternalSigner(
            signedEventJson = signerReturnedEvent.toString(),
            fallbackId = EventVerifier.computeCanonicalId(unsignedEventJson),
            fallbackSig = "cc".repeat(64),
            unsignedEventJson = unsignedEventJson.toString()
        )

        assertEquals(signerReturnedEvent.getString("id"), result.getString("id"))
        assertEquals("bb".repeat(64), result.getString("sig"))
    }

    @Test
    fun `buildSignedEventFromExternalSigner uses canonical id when signer returns a mismatched id`() {
        val unsignedEventJson = JSONObject().apply {
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234)
            put("kind", 4)
            put("tags", JSONArray())
            put("content", "hi")
        }
        val signerReturnedEvent = JSONObject(unsignedEventJson.toString()).apply {
            put("id", "22".repeat(64))
            put("sig", "bb".repeat(64))
        }

        val fallbackId = EventVerifier.computeCanonicalId(unsignedEventJson)
        val result = NostrEventSigner.buildSignedEventFromExternalSigner(
            signedEventJson = signerReturnedEvent.toString(),
            fallbackId = fallbackId,
            fallbackSig = "cc".repeat(64),
            unsignedEventJson = unsignedEventJson.toString()
        )

        assertEquals(fallbackId, result.getString("id"))
        assertEquals("bb".repeat(64), result.getString("sig"))
    }

    @Test
    fun `buildSignedEventFromExternalSigner rejects events whose fields do not match the unsigned payload`() {
        val unsignedEventJson = JSONObject().apply {
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234)
            put("kind", 4)
            put("tags", JSONArray())
            put("content", "hello")
        }
        val signerReturnedEvent = JSONObject(unsignedEventJson.toString()).apply {
            put("id", "22".repeat(64))
            put("content", "hi")
            put("sig", "bb".repeat(64))
        }

        val fallbackId = EventVerifier.computeCanonicalId(unsignedEventJson)
        assertThrows(IllegalStateException::class.java) {
            NostrEventSigner.buildSignedEventFromExternalSigner(
                signedEventJson = signerReturnedEvent.toString(),
                fallbackId = fallbackId,
                fallbackSig = "cc".repeat(64),
                unsignedEventJson = unsignedEventJson.toString()
            )
        }
    }

    @Test
    fun `signs a local event with a verifiable schnorr signature`() {
        val key = ECKey()
        val privKey = key.privKeyBytes
        val pubkey = key.pubKeyPoint.getEncoded(false).copyOfRange(1, 33).joinToString("") { "%02x".format(it) }

        val signedEvent = try {
            kotlinx.coroutines.runBlocking {
                NostrEventSigner.signEvent(
                    kind = 1,
                    content = "hello",
                    tags = emptyList(),
                    pubkey = pubkey,
                    privKey = privKey,
                    createdAt = 1234L
                )
            }
        } catch (e: Throwable) {
            assertTrue(e.stackTraceToString(), e.isNativeSecp256k1Unavailable())
            return
        }

        val verification = EventVerifier.verifyEvent(signedEvent.toString())
        assertTrue(verification.reason ?: "id mismatch", verification.idMatches)
        if (verification.reason?.startsWith("Native verification unavailable") == true) {
            return
        }
        assertTrue(verification.reason ?: "signature invalid", verification.signatureValid)
        assertEquals("hello", signedEvent.optString("content"))
    }
}
