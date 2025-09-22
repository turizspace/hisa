package com.hisa.data.nostr

data class NostrFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val tags: Map<String, List<String>>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null
) {
    fun toJsonObject(): org.json.JSONObject {
        val json = org.json.JSONObject()
        kinds?.let { json.put("kinds", org.json.JSONArray(it)) }
        authors?.let { json.put("authors", org.json.JSONArray(it)) }
        tags?.forEach { (key, values) ->
            json.put("#$key", org.json.JSONObject().put("values", org.json.JSONArray(values)))
        }
        since?.let { json.put("since", it) }
        until?.let { json.put("until", it) }
        limit?.let { json.put("limit", it) }
        return json
    }
}
