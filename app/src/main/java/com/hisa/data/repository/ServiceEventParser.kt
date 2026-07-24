package com.hisa.data.repository

import com.hisa.data.model.ServiceListing
import org.json.JSONArray
import org.json.JSONObject

object ServiceEventParser {
    fun parse(eventJson: String): ServiceListing? {
        try {
            val obj = JSONObject(eventJson)
            if (obj.getInt("kind") != 30402) {
                return null
            }

            val tags = obj.optJSONArray("tags") ?: JSONArray()
            val tagMap = mutableMapOf<String, MutableList<List<String>>>()
            val tagList = mutableListOf<String>()

            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                val tagType = tag.getString(0)
                val tagValues = (0 until tag.length()).map { tag.optString(it, "") }
                tagMap.getOrPut(tagType) { mutableListOf() }.add(tagValues)

                if (tagType == "t") {
                    tag.optString(1, "").takeIf { it.isNotBlank() }?.let { tagList.add(it) }
                }
            }

            val title = tagMap["title"]?.firstOrNull()?.getOrNull(1)
            val summary = tagMap["summary"]?.firstOrNull()?.getOrNull(1)

            val priceTag = tagMap["price"]?.firstOrNull()
            val priceAmount = priceTag?.getOrNull(1) ?: ""
            val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"

            val price = when {
                priceAmount.isBlank() -> "N/A"
                priceAmount == "0" || priceAmount.lowercase() == "free" -> "Free"
                priceCurrency == "USD" -> "$priceAmount USD"
                priceCurrency == "SATS" -> "$priceAmount sats"
                else -> "$priceAmount $priceCurrency"
            }

            val eventId = obj.optString("id", "")
            val pubkey = obj.optString("pubkey", "")
            val content = obj.optString("content", "").trim()

            val finalSummary = when {
                !summary.isNullOrBlank() -> summary
                content.startsWith("{") && content.endsWith("}") -> try {
                    val contentJson = JSONObject(content)
                    val description = contentJson.optString("description", "")
                    if (description.isNotBlank()) description else contentJson.optString("summary", content)
                } catch (_: Exception) {
                    content
                }
                content.isNotBlank() -> content
                else -> "No summary available"
            }

            val finalTitle = title?.takeIf { it.isNotBlank() } ?: eventId
            val rawTags = (0 until tags.length()).map { i ->
                val tag = tags.getJSONArray(i)
                (0 until tag.length()).map { j -> tag.optString(j, "") }
            }

            val createdAt = obj.optLong("created_at", System.currentTimeMillis() / 1000)

            return ServiceListing(
                eventId = eventId,
                title = finalTitle,
                summary = finalSummary,
                content = content.takeIf { it.isNotBlank() },
                price = price,
                tags = tagList,
                pubkey = pubkey,
                rawTags = rawTags,
                rawEvent = eventJson,
                createdAt = createdAt
            )
        } catch (_: Exception) {
            return null
        }
    }
}
