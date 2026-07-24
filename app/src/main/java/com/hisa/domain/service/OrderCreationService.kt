package com.hisa.domain.service

import com.hisa.data.model.OrderItem
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import com.hisa.data.nostr.toNostrEvent
import com.hisa.util.normalizeNostrPubkey
import java.util.UUID

class OrderCreationService(
    private val nostrClient: NostrClient,
    private val signingService: NostrSigningService
) {
    suspend fun createOrder(
        buyerPubkey: String,
        buyerPrivateKeyHex: String?,
        sellerPubkey: String,
        subject: String,
        items: List<OrderItem>,
        amount: Long,
        currency: String = "SATS",
        notes: String,
        shippingOption: String?,
        shippingAddress: String?,
        buyerEmail: String?,
        buyerPhone: String?
    ): String {
        val signingContext = signingService.resolveSigningContext(
            pubkeyHint = buyerPubkey,
            privateKeyHexHint = buyerPrivateKeyHex
        )
        val signingPubkey = signingContext.requirePubkey()
        val requestedBuyerPubkey = normalizeNostrPubkey(buyerPubkey)
        if (!requestedBuyerPubkey.isNullOrBlank() && requestedBuyerPubkey != signingPubkey) {
            throw IllegalArgumentException("Active signer does not match the buyer account")
        }
        if (signingPubkey.isBlank()) {
            throw IllegalArgumentException("Buyer pubkey is required to create an order")
        }

        nostrClient.connect()

        val orderId = UUID.randomUUID().toString()
        val tags = mutableListOf<List<String>>().apply {
            add(listOf("p", sellerPubkey))
            add(listOf("type", "1"))
            add(listOf("order", orderId))
            add(listOf("subject", subject))
            add(listOf("amount", amount.toString()))
            add(listOf("currency", currency.trim().uppercase().ifBlank { "SATS" }))
            items.forEach { item ->
                if (item.productPrice.isNullOrBlank()) {
                    add(listOf("item", item.productReference, item.quantity.toString()))
                } else {
                    add(listOf("item", item.productReference, item.quantity.toString(), item.productPrice))
                }
            }
            shippingOption?.takeIf { it.isNotBlank() }?.let { add(listOf("shipping", it)) }
            shippingAddress?.takeIf { it.isNotBlank() }?.let { add(listOf("address", it)) }
            buyerEmail?.takeIf { it.isNotBlank() }?.let { add(listOf("email", it)) }
            buyerPhone?.takeIf { it.isNotBlank() }?.let { add(listOf("phone", it)) }
        }

        val content = notes.ifBlank { "Order for $subject" }
        val eventJson = signingService.signEvent(
            signingContext = signingContext,
            kind = 16,
            content = content,
            tags = tags
        )
        val event = eventJson.toNostrEvent()
        nostrClient.publishEvent(event)
        return event.id
    }
}
