package com.hisa.data.nostr

import org.json.JSONArray
import org.json.JSONObject

data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", JSONArray(tags.map { JSONArray(it) }))
            put("content", content)
            put("sig", sig)
        }
    }
}
