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

fun JSONObject.toNostrEvent(): NostrEvent {
    val tagsArray = optJSONArray("tags") ?: JSONArray()
    return NostrEvent(
        id = getString("id"),
        pubkey = getString("pubkey"),
        createdAt = getLong("created_at"),
        kind = getInt("kind"),
        tags = (0 until tagsArray.length()).map { i ->
            val tagArr = tagsArray.getJSONArray(i)
            (0 until tagArr.length()).map { tagArr.getString(it) }
        },
        content = getString("content"),
        sig = getString("sig")
    )
}
