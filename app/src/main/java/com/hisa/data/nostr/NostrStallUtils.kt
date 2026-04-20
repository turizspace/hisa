package com.hisa.data.nostr

import org.json.JSONObject

object NostrStallUtils {
    private val hex64Regex = Regex("^[0-9a-fA-F]{64}$")

    suspend fun createStall(
        name: String,
        about: String,
        picture: String,
        relays: List<String>,
        categories: List<String>,
        location: String?,
        geohash: String?,
        privateKey: ByteArray?,
        pubkey: String
    ): NostrEvent {
        val metadata = JSONObject().apply {
            put("name", name)
            put("about", about)
            put("picture", picture)
            put("relays", org.json.JSONArray(relays))
            location?.let { put("location", it) }
            geohash?.let { put("geohash", it) }
        }

        val tags = mutableListOf<List<String>>()
        categories.forEach { tags.add(listOf("t", it)) }
        relays.forEach { tags.add(listOf("relay", it)) }

        val eventJson = NostrEventSigner.signEvent(
            kind = 30017,
            content = metadata.toString(),
            tags = tags,
            pubkey = pubkey,
            privKey = privateKey
        )
        return NostrEvent(
            id = eventJson.getString("id"),
            pubkey = eventJson.getString("pubkey"),
            createdAt = eventJson.getLong("created_at"),
            kind = eventJson.getInt("kind"),
            tags = (0 until eventJson.getJSONArray("tags").length()).map { i ->
                val tagArr = eventJson.getJSONArray("tags").getJSONArray(i)
                (0 until tagArr.length()).map { tagArr.getString(it) }
            },
            content = eventJson.getString("content"),
            sig = eventJson.getString("sig")
        )
    }

    suspend fun createProduct(
        stallId: String,
        title: String,
        description: String,
        price: String?,
        currency: String? = null,
        images: List<String> = emptyList(),
        privateKey: ByteArray?,
        pubkey: String
    ): NostrEvent {
        val metadata = JSONObject().apply {
            put("title", title)
            put("description", description)
            price?.let { put("price", it) }
            currency?.let { put("currency", it) }
            if (images.isNotEmpty()) put("images", org.json.JSONArray(images))
        }

        val tags = mutableListOf<List<String>>()
        // reference the parent stall using an e-tag
        tags.add(listOf("e", stallId, "", "root"))

        val eventJson = NostrEventSigner.signEvent(
            kind = 30018,
            content = metadata.toString(),
            tags = tags,
            pubkey = pubkey,
            privKey = privateKey
        )

        return NostrEvent(
            id = eventJson.getString("id"),
            pubkey = eventJson.getString("pubkey"),
            createdAt = eventJson.getLong("created_at"),
            kind = eventJson.getInt("kind"),
            tags = (0 until eventJson.getJSONArray("tags").length()).map { i ->
                val tagArr = eventJson.getJSONArray("tags").getJSONArray(i)
                (0 until tagArr.length()).map { tagArr.getString(it) }
            },
            content = eventJson.getString("content"),
            sig = eventJson.getString("sig")
        )
    }
}
