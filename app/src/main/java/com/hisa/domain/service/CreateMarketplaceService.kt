package com.hisa.domain.service

import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import com.hisa.data.model.ShippingZone
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CreateMarketplaceService(
    private val nostrClient: NostrClient,
    private val signingService: NostrSigningService
) {
    suspend fun createService(
        title: String,
        summary: String,
        description: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String
    ) {
        val mutableTags = tags.toMutableList()
        if (mutableTags.none { it.firstOrNull() == "title" }) {
            mutableTags.add(listOf("title", title))
        }
        if (mutableTags.none { it.firstOrNull() == "summary" }) {
            mutableTags.add(listOf("summary", summary))
        }

        signingService.signAndPublish(
            nostrClient = nostrClient,
            kind = 30402,
            content = description,
            tags = mutableTags.distinct(),
            pubkeyHint = pubKey,
            privateKeyHexHint = privateKeyHex
        )
    }

    suspend fun createStall(
        stallId: String,
        title: String,
        summary: String,
        description: String,
        currency: String,
        shippingZones: List<ShippingZone>,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String
    ) {
        val categories = tags.mapNotNull { tag ->
            if (tag.isNotEmpty() && tag[0] == "t" && tag.size > 1) tag[1] else null
        }

        val metadata = JSONObject().apply {
            put("id", stallId)
            put("name", title)
            put("description", summary.ifBlank { description })
            put("currency", currency.ifBlank { "SATS" })
            put("shipping", JSONArray().apply {
                shippingZones.forEach { zone ->
                    put(JSONObject().apply {
                        put("id", zone.id)
                        put("name", zone.name)
                        put("cost", zone.cost)
                        put("regions", JSONArray(zone.regions))
                    })
                }
            })
            if (summary.isNotBlank() || description.isNotBlank()) {
                put("about", summary.ifBlank { description })
            }
        }

        val stallTags = mutableListOf<List<String>>(listOf("d", stallId))
        categories.forEach { stallTags.add(listOf("t", it)) }

        signingService.signAndPublish(
            nostrClient = nostrClient,
            kind = 30017,
            content = metadata.toString(),
            tags = stallTags,
            pubkeyHint = pubKey,
            privateKeyHexHint = privateKeyHex
        )
    }

    suspend fun createProduct(
        stallId: String,
        name: String,
        description: String,
        price: String,
        currency: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        productId: String? = null,
        images: List<String> = emptyList()
    ) {
        val resolvedProductId = productId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val safePrice = price.ifBlank { "0" }
        val productMetadata = JSONObject().apply {
            put("id", resolvedProductId)
            put("stall_id", stallId)
            put("name", name)
            put("description", description.ifBlank { name })
            put("currency", currency.ifBlank { "SATS" })
            put("price", safePrice)
            put("quantity", null)
            put("shipping", JSONArray())
            if (images.isNotEmpty()) put("images", JSONArray(images))
        }

        val productTags = mutableListOf<List<String>>(listOf("d", resolvedProductId), listOf("stall_id", stallId))
        tags.forEach { tag ->
            if (tag.isNotEmpty() && tag[0] == "t" && tag.size > 1) {
                productTags.add(listOf("t", tag[1]))
            }
        }

        signingService.signAndPublish(
            nostrClient = nostrClient,
            kind = 30018,
            content = productMetadata.toString(),
            tags = productTags,
            pubkeyHint = pubKey,
            privateKeyHexHint = privateKeyHex
        )
    }
}
