package com.hisa.data.nostr

import org.json.JSONObject

object NostrChannelUtils {
    private val hex64Regex = Regex("^[0-9a-fA-F]{64}$")

    // Build a canonical e-tag: ["e", <eventId>, <relayUrl>, <marker>]
    fun buildETag(eventId: String, relayUrl: String = "", marker: String = "root"): List<String> {
        return listOf("e", eventId, relayUrl, marker)
    }

    // Validate an e-tag follows expected shape and contains a 64-char hex id
    fun isValidETag(tag: List<String>): Boolean {
        if (tag.size < 4) return false
        if (tag[0] != "e") return false
        if (!hex64Regex.matches(tag[1])) return false
        if (tag[3] != "root" && tag[3] != "reply") return false
        return true
    }

    suspend fun createChannel(
        name: String,
        about: String,
        picture: String,
        relays: List<String>,
        categories: List<String>,
        privateKey: ByteArray,
        pubkey: String
    ): NostrEvent {
        val metadata = JSONObject().apply {
            put("name", name)
            put("about", about)
            put("picture", picture)
            put("relays", org.json.JSONArray(relays))
        }

        val tags = mutableListOf<List<String>>()
        categories.forEach { tags.add(listOf("t", it)) }
        relays.forEach { tags.add(listOf("relay", it)) }

        val eventJson = NostrEventSigner.signEvent(
            kind = 40,
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

    suspend fun updateChannelMetadata(
        channelId: String,
        name: String,
        about: String,
        picture: String,
        relays: List<String>,
        categories: List<String>,
    privateKey: ByteArray,
        pubkey: String
    ): NostrEvent {
        val metadata = JSONObject().apply {
            put("name", name)
            put("about", about)
            put("picture", picture)
            put("relays", relays)
        }

    val tags = mutableListOf<List<String>>()
    // Prefer the first relay if available to populate the e-tag relay field
    val relayForETag = relays.firstOrNull() ?: ""
    tags.add(buildETag(channelId, relayForETag, "root"))
        categories.forEach { tags.add(listOf("t", it)) }

        val eventJson = NostrEventSigner.signEvent(
            kind = 41,
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

    suspend fun createChannelMessage(
        channelId: String,
        content: String,
        replyTo: String? = null,
        mentionedPubkeys: List<String> = emptyList(),
    privateKey: ByteArray,
        pubkey: String
    ): NostrEvent {
    val tags = mutableListOf<List<String>>()
    // Channel messages should include an e-tag referencing the channel create (kind-40) event
    // We don't always have a relay URL here; leave empty if unknown.
    tags.add(buildETag(channelId, "", "root"))
        
        if (replyTo != null) {
            tags.add(buildETag(replyTo, "", "reply"))
        }
        
        mentionedPubkeys.forEach { pubkey ->
            tags.add(listOf("p", pubkey))
        }

        val eventJson = NostrEventSigner.signEvent(
            kind = 42,
            content = content,
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

    suspend fun hideMessage(
        messageId: String,
        reason: String? = null,
        privateKey: ByteArray,
        pubkey: String
    ): NostrEvent {
        val content = reason?.let {
            JSONObject().put("reason", it).toString()
        } ?: ""

        val eventJson = NostrEventSigner.signEvent(
            kind = 43,
            content = content,
            // hideMessage uses a minimal e-tag referencing the message id
            tags = listOf(listOf("e", messageId)),
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

    suspend fun muteUser(
        pubkey: String,
        reason: String? = null,
        privateKey: ByteArray,
        authorPubkey: String
    ): NostrEvent {
        val content = reason?.let {
            JSONObject().put("reason", it).toString()
        } ?: ""

        val eventJson = NostrEventSigner.signEvent(
            kind = 44,
            content = content,
            tags = listOf(listOf("p", pubkey)),
            pubkey = authorPubkey,
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
