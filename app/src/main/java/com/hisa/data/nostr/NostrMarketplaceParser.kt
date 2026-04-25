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

        val picture = content?.optMeaningfulString("picture")
            ?: content?.optMeaningfulString("image")
            ?: event.firstTagValue("image")
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
            name = name,
            description = description,
            pictures = content?.optStringArray("images").orEmpty().ifEmpty {
                event.tagValues("image")
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
}
