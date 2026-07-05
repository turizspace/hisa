package com.hisa.data.nostr

import org.bitcoinj.core.ECKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrEventSignerTest {

    @Test
    fun `buildSignedEventFromExternalSigner preserves signer returned id`() {
        val signerReturnedEvent = JSONObject().apply {
            put("id", "22".repeat(64))
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234)
            put("kind", 4)
            put("tags", JSONArray())
            put("content", "hi")
            put("sig", "bb".repeat(64))
        }

        val result = NostrEventSigner.buildSignedEventFromExternalSigner(
            signedEventJson = signerReturnedEvent.toString(),
            fallbackId = "11".repeat(64),
            fallbackSig = "cc".repeat(64)
        )

        assertEquals("22".repeat(64), result.getString("id"))
        assertEquals("bb".repeat(64), result.getString("sig"))
    }

    @Test
    fun `buildSignedEventFromExternalSigner uses canonical id when signer returns a mismatched id`() {
        val signerReturnedEvent = JSONObject().apply {
            put("id", "22".repeat(64))
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234)
            put("kind", 4)
            put("tags", JSONArray())
            put("content", "hi")
            put("sig", "bb".repeat(64))
        }

        val fallbackId = EventVerifier.computeCanonicalId(signerReturnedEvent)
        val result = NostrEventSigner.buildSignedEventFromExternalSigner(
            signedEventJson = signerReturnedEvent.toString(),
            fallbackId = fallbackId,
            fallbackSig = "cc".repeat(64)
        )

        assertEquals(fallbackId, result.getString("id"))
        assertEquals("bb".repeat(64), result.getString("sig"))
    }

    @Test
    fun `signs a local event with a verifiable schnorr signature`() {
        val key = ECKey()
        val privKey = key.privKeyBytes
        val pubkey = key.pubKeyPoint.getEncoded(false).copyOfRange(1, 33).joinToString("") { "%02x".format(it) }

        val signedEvent = kotlinx.coroutines.runBlocking {
            NostrEventSigner.signEvent(
                kind = 1,
                content = "hello",
                tags = emptyList(),
                pubkey = pubkey,
                privKey = privKey,
                createdAt = 1234L
            )
        }

        val verification = EventVerifier.verifyEvent(signedEvent.toString())
        assertTrue(verification.idMatches)
        assertTrue(verification.signatureValid)
    }
}
