package com.hisa.data.nostr

import com.hisa.util.normalizeCategory
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

fun NostrEvent.tagsLogString(): String = tags.joinToString(prefix = "[", postfix = "]") { tag ->
    tag.joinToString(prefix = "[", postfix = "]")
}

fun NostrEvent.tagValues(tagName: String): List<String> =
    tags.filter { it.firstOrNull() == tagName }
        .mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }

fun NostrEvent.categoryLogString(): String =
    tagValues("t").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

fun NostrEvent.normalizedCategories(): Set<String> =
    tagValues("t")
        .map(::normalizeCategory)
        .filter { it.isNotBlank() }
        .toSet()

fun NostrEvent.normalizedCategoryLogString(): String =
    normalizedCategories()
        .sorted()
        .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
