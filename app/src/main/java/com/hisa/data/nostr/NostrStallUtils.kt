package com.hisa.data.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object NostrStallUtils {
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
        val stallId = UUID.randomUUID().toString()
        val metadata = JSONObject().apply {
            put("id", stallId)
            put("name", name)
            put("description", about)
            put("currency", "SATS")
            put("shipping", JSONArray())
            if (picture.isNotBlank()) put("picture", picture)
            if (about.isNotBlank()) put("about", about)
            if (relays.isNotEmpty()) put("relays", JSONArray(relays))
            location?.let { put("location", it) }
            geohash?.let { put("geohash", it) }
        }

        val tags = mutableListOf<List<String>>(listOf("d", stallId))
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
        val productId = UUID.randomUUID().toString()
        val metadata = JSONObject().apply {
            put("id", productId)
            put("stall_id", stallId)
            put("name", title)
            put("description", description)
            put("price", price?.toDoubleOrNull() ?: 0.0)
            put("currency", currency ?: "SATS")
            put("quantity", JSONObject.NULL)
            if (images.isNotEmpty()) put("images", JSONArray(images))
            if (title.isNotBlank()) put("title", title)
        }

        val tags = mutableListOf<List<String>>()
        tags.add(listOf("d", productId))

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
