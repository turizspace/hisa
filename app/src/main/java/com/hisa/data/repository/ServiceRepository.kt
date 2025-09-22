package com.hisa.data.repository

import com.hisa.data.model.ServiceListing
import org.json.JSONObject

object ServiceRepository {
    // Global cache for ServiceListing objects
    private val serviceCache = mutableMapOf<String, ServiceListing>()

    fun cacheService(service: ServiceListing) {

        serviceCache[service.eventId] = service
    }

    fun getCachedService(eventId: String): ServiceListing? {
        return serviceCache[eventId]
    }
        fun getAllCachedServices(): List<ServiceListing> {
            return serviceCache.values.toList()
        }
    // TODO: Replace with actual event fetch logic (network, cache, etc)
    fun getServiceByEventId(eventId: String): ServiceListing? {
        // First, try cache
        val cached = getCachedService(eventId)
        if (cached != null) return cached
        // For now, return a dummy service for preview/testing
        if (eventId == "demo") {
            return ServiceListing(
                eventId = "demo",
                title = "Demo Service",
                summary = "This is a demo service for preview.",
                content = "This is a longer demo description for the service. It shows how content will appear in the details.",
                price = "1000",
                tags = listOf("tag1", "tag2"),
                pubkey = "demo_pubkey",
                createdAt = System.currentTimeMillis() / 1000 // Add current timestamp for demo
            )
        }
        return null
    }
    /**
     * Parses a NIP-30402 service event
     * NIP-30402 standard format:
     * - kind: 30402
     * - content: Markdown content
     * - tags:
     *   - ["d", "identifier"]
     *   - ["title", "Service Title"]
     *   - ["summary", "Short description"]
     *   - ["price", "amount", "currency"]
     *   - ["t", "tag1"], ["t", "tag2"], etc.
     *   - ["image", "url", "dimensions"]
     *   - ["location", "place"]
     */
    fun parseServiceEvent(eventJson: String): ServiceListing? {
        try {
            val obj = JSONObject(eventJson)
            if (obj.getInt("kind") != 30402) {
                return null
            }

            val tags = obj.optJSONArray("tags") ?: org.json.JSONArray()
            val tagMap = mutableMapOf<String, MutableList<List<String>>>()
            val tagList = mutableListOf<String>()

            // First pass: collect all tags by type
            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                val tagType = tag.getString(0)
                val tagValues = (0 until tag.length()).map { tag.optString(it, "") }
                tagMap.getOrPut(tagType) { mutableListOf() }.add(tagValues)
                
                // Log tag processing
                
                // Collect topic tags
                if (tagType == "t") {
                    tag.optString(1, "").takeIf { it.isNotBlank() }?.let { tagList.add(it) }
                }
            }

            // Extract main fields from tags according to NIP-30402
            val title = tagMap["title"]?.firstOrNull()?.getOrNull(1)
            val summary = tagMap["summary"]?.firstOrNull()?.getOrNull(1)
            
            // Handle price according to standard: ["price", "amount", "currency"]
            val priceTag = tagMap["price"]?.firstOrNull()
            val priceAmount = priceTag?.getOrNull(1) ?: ""
            val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"
            
            // Format price according to currency
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
            
            // Try to parse content as JSON if it looks like JSON
            val finalSummary = when {
                !summary.isNullOrBlank() -> summary
                content.startsWith("{") && content.endsWith("}") -> try {
                    val contentJson = JSONObject(content)
                    val description = contentJson.optString("description", "")
                    if (description.isNotBlank()) description else contentJson.optString("summary", content)
                } catch (e: Exception) {
                    content // Fallback to raw content if JSON parsing fails
                }
                content.isNotBlank() -> content
                else -> "No summary available"
            }

            // Use appropriate fallbacks
            val finalTitle = title?.takeIf { it.isNotBlank() } ?: eventId
            // Convert all tags to List<List<String>> format
            val rawTags = (0 until tags.length()).map { i ->
                val tag = tags.getJSONArray(i)
                (0 until tag.length()).map { j -> tag.optString(j, "") }
            }

            val createdAt = obj.optLong("created_at", System.currentTimeMillis() / 1000) // Extract created_at from event

            val serviceListing = ServiceListing(
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

            return serviceListing
        } catch (e: Exception) {

            return null
        }
    }
}