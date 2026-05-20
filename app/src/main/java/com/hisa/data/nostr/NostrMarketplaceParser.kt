package com.hisa.data.nostr

import com.hisa.data.model.Product
import com.hisa.data.model.ShippingZone
import com.hisa.data.model.Stall
import org.json.JSONArray
import org.json.JSONObject

object NostrMarketplaceParser {
    fun stallKey(stallId: String, ownerPubkey: String): String =
        "${ownerPubkey.lowercase()}:$stallId"

    fun parseStall(event: NostrEvent): Stall? {
        if (event.kind != 30017) return null

        val content = event.content.toJsonObjectOrNull()
        val stallId = event.firstTagValue("d")
            ?: content?.optMeaningfulString("id")
            ?: event.id

        val name = content?.optMeaningfulString("name")
            ?: content?.optMeaningfulString("title")
            ?: event.firstTagValue("title")
            ?: "Untitled stall"

        val description = content?.optMeaningfulString("description")
            ?: content?.optMeaningfulString("about")
            ?: event.firstTagValue("summary")
            ?: event.firstTagValue("description")
            ?: ""

        val picture = content?.optFirstImageUrl("picture", "image", "images", "pictures")
            ?: event.firstTagValue("image")?.let(::upgradeImageUrl)
            ?: event.tagValues("image").firstOrNull()?.let(::upgradeImageUrl)
            ?: event.firstTagValue("picture")?.let(::upgradeImageUrl)
            ?: event.tagValues("picture").firstOrNull()?.let(::upgradeImageUrl)
            ?: ""

        val currency = content?.optMeaningfulString("currency") ?: "USD"
        val categories = event.tagValues("t").distinct()

        return Stall(
            id = stallId,
            eventId = event.id,
            ownerPubkey = event.pubkey,
            name = name,
            description = description,
            picture = picture,
            currency = currency,
            shippingZones = parseShippingZones(content),
            categories = categories,
            createdAt = event.createdAt
        )
    }

    fun parseProduct(event: NostrEvent): Product? {
        if (event.kind != 30018) return null

        val content = event.content.toJsonObjectOrNull()
        val productId = event.firstTagValue("d")
            ?: content?.optMeaningfulString("id")
            ?: event.id

        val stallId = content?.optMeaningfulString("stall_id")
            ?: event.firstTagValue("stall_id")
            ?: event.rootTagValue("e")
            ?: event.firstTagValue("e")
            ?: return null

        val name = content?.optMeaningfulString("name")
            ?: content?.optMeaningfulString("title")
            ?: event.firstTagValue("title")
            ?: event.firstTagValue("name")
            ?: "Untitled product"

        val description = content?.optMeaningfulString("description")
            ?: event.firstTagValue("description")
            ?: event.firstTagValue("summary")
            ?: ""

        val rawPrice = content?.optRawValue("price") ?: event.firstTagValue("price")
        val currency = content?.optMeaningfulString("currency")
            ?: event.firstTagValue("currency")
            ?: "USD"

        return Product(
            id = productId,
            stallId = stallId,
            authorPubkey = event.pubkey,
            name = name,
            description = description,
            pictures = content?.optImageUrls("images", "image", "picture", "pictures").orEmpty()
                .ifEmpty {
                    (event.tagValues("image") + event.tagValues("picture"))
                        .map(::upgradeImageUrl)
                        .distinct()
                },
            currency = currency,
            price = rawPrice?.toString() ?: "0",
            quantity = content?.optNullableInt("quantity")
                ?: event.firstTagValue("quantity")?.toIntOrNull(),
            categories = event.tagValues("t").distinct(),
            createdAt = event.createdAt
        )
    }

    private fun parseShippingZones(content: JSONObject?): List<ShippingZone> {
        val shipping = content?.optJSONArray("shipping") ?: return emptyList()
        return buildList {
            for (index in 0 until shipping.length()) {
                val zone = shipping.optJSONObject(index) ?: continue
                val zoneId = zone.optMeaningfulString("id") ?: continue
                add(
                    ShippingZone(
                        id = zoneId,
                        name = zone.optMeaningfulString("name") ?: zoneId,
                        cost = zone.optDoubleOrZero("cost"),
                        regions = zone.optStringArray("regions")
                    )
                )
            }
        }
    }

    private fun NostrEvent.firstTagValue(tagName: String): String? =
        tags.firstOrNull { it.firstOrNull() == tagName }?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun NostrEvent.rootTagValue(tagName: String): String? =
        tags.firstOrNull {
            it.firstOrNull() == tagName && it.getOrNull(3) == "root"
        }?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun NostrEvent.tagValues(tagName: String): List<String> =
        tags.filter { it.firstOrNull() == tagName }
            .mapNotNull { it.getOrNull(1)?.takeIf(String::isNotBlank) }

    private fun String.toJsonObjectOrNull(): JSONObject? = try {
        if (isBlank()) null else JSONObject(this)
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.optMeaningfulString(key: String): String? =
        optString(key, "").trim().takeIf(String::isNotBlank)

    private fun JSONObject.optRawValue(key: String): Any? =
        opt(key)?.takeUnless { it == JSONObject.NULL }

    private fun JSONObject.optStringArray(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return array.toStringList()
    }

    private fun JSONObject.optFirstImageUrl(vararg keys: String): String? =
        optImageUrls(*keys)
            .orEmpty()
            .firstOrNull()

    private fun JSONObject.optImageUrls(vararg keys: String): List<String>? =
        keys.asSequence()
            .mapNotNull { key ->
                when (val value = opt(key)) {
                    is String -> normalizeImageValues(value)
                    is JSONArray -> value.toStringList().map(::upgradeImageUrl).ifEmpty { null }
                    else -> null
                }
            }
            .firstOrNull()

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optDoubleOrZero(key: String): Double {
        val value = opt(key)
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }

    private fun normalizeImageValue(raw: String): String? =
        normalizeImageValues(raw).firstOrNull()

    private fun normalizeImageValues(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (trimmed.startsWith("[")) {
            val parsed = runCatching { JSONArray(trimmed).toStringList().map(::upgradeImageUrl) }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return trimmed
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() }
            .map(::upgradeImageUrl)
            .toList()
    }

    private fun upgradeImageUrl(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }
}
