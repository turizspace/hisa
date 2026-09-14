package com.hisa.data.repository

import com.hisa.data.model.ServiceListing
import com.hisa.data.nostr.NostrEvent
import org.json.JSONArray
import org.json.JSONObject

object ServiceEventParser {
    fun parse(event: NostrEvent): ServiceListing? {
        if (event.kind != 30402) return null

        return buildServiceListing(
            eventId = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            content = event.content.trim(),
            rawTags = event.tags,
            rawEvent = event.toJson().toString()
        )
    }

    fun parse(eventJson: String): ServiceListing? {
        try {
            val obj = JSONObject(eventJson)
            if (obj.getInt("kind") != 30402) {
                return null
            }

            val tags = obj.optJSONArray("tags") ?: JSONArray()
            val rawTags = (0 until tags.length()).map { i ->
                val tag = tags.getJSONArray(i)
                (0 until tag.length()).map { j -> tag.optString(j, "") }
            }
            return buildServiceListing(
                eventId = obj.optString("id", ""),
                pubkey = obj.optString("pubkey", ""),
                createdAt = obj.optLong("created_at", System.currentTimeMillis() / 1000),
                content = obj.optString("content", "").trim(),
                rawTags = rawTags,
                rawEvent = eventJson
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun buildServiceListing(
        eventId: String,
        pubkey: String,
        createdAt: Long,
        content: String,
        rawTags: List<List<String>>,
        rawEvent: String
    ): ServiceListing {
        val tagsByName = rawTags.groupBy { it.firstOrNull().orEmpty() }
        val title = tagsByName["title"]?.firstOrNull()?.getOrNull(1)
        val summary = tagsByName["summary"]?.firstOrNull()?.getOrNull(1)
        val dTag = tagsByName["d"]?.firstOrNull()?.getOrNull(1)
        val priceTag = tagsByName["price"]?.firstOrNull()
        val priceAmount = priceTag?.getOrNull(1).orEmpty()
        val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"
        val price = when {
            priceAmount.isBlank() -> "N/A"
            priceAmount == "0" || priceAmount.lowercase() == "free" -> "Free"
            priceCurrency == "USD" -> "$$priceAmount USD"
            priceCurrency == "SATS" -> "$$priceAmount sats"
            else -> "$$priceAmount $priceCurrency"
        }
        val finalSummary = when {
            !summary.isNullOrBlank() -> summary
            content.startsWith("{") && content.endsWith("}") -> runCatching {
                val contentJson = JSONObject(content)
                contentJson.optString("description").ifBlank {
                    contentJson.optString("summary", content)
                }
            }.getOrDefault(content)
            content.isNotBlank() -> content
            else -> "No summary available"
        }
        return ServiceListing(
            eventId = eventId,
            dTag = dTag,
            title = title?.takeIf { it.isNotBlank() } ?: eventId,
            summary = finalSummary,
            content = content.takeIf { it.isNotBlank() },
            price = price,
            tags = rawTags.filter { it.firstOrNull() == "t" }
                .mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) },
            pubkey = pubkey,
            rawTags = rawTags,
            rawEvent = rawEvent,
            createdAt = createdAt
        )
    }
}
