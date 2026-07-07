package com.hisa.data.nostr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

class NostrCanonicalJsonTest {
    @Test
    fun `serializeEventForId uses relay-compatible slash escaping`() {
        val serialized = NostrCanonicalJson.serializeEventForId(
            pubkey = "aa".repeat(32),
            createdAt = 1234L,
            kind = 1,
            tags = listOf(listOf("p", "bb/cc")),
            content = "abc/def</script>"
        )

        assertEquals(
            "[0,\"${"aa".repeat(32)}\",1234,1,[[\"p\",\"bb/cc\"]],\"abc/def</script>\"]",
            serialized
        )
        assertFalse(serialized.contains("\\/"))
    }

    @Test
    fun `computeEventId hashes canonical serialized bytes`() {
        val serialized = "[0,\"${"aa".repeat(32)}\",1234,1,[[\"p\",\"bb/cc\"]],\"abc/def</script>\"]"
        val expectedId = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            expectedId,
            NostrCanonicalJson.computeEventId(
                pubkey = "aa".repeat(32),
                createdAt = 1234L,
                kind = 1,
                tags = listOf(listOf("p", "bb/cc")),
                content = "abc/def</script>"
            )
        )
    }

    @Test
    fun `EventVerifier canonical id uses the shared serializer`() {
        val event = JSONObject().apply {
            put("pubkey", "aa".repeat(32))
            put("created_at", 1234L)
            put("kind", 1)
            put("tags", JSONArray().put(JSONArray().put("p").put("bb/cc")))
            put("content", "abc/def</script>")
        }

        assertEquals(
            NostrCanonicalJson.computeEventId(
                pubkey = "aa".repeat(32),
                createdAt = 1234L,
                kind = 1,
                tags = listOf(listOf("p", "bb/cc")),
                content = "abc/def</script>"
            ),
            EventVerifier.computeCanonicalId(event)
        )
    }
}
