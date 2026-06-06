package com.hisa.data.repository

import com.hisa.data.model.Order
import com.hisa.data.model.OrderItem
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.NostrEventSigner
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.nostr.toNostrEvent
import com.hisa.util.hexToByteArrayOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

@Singleton
class OrderRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val metadataRepository: MetadataRepository,
    private val profileRepository: ProfileRepository
) {
    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> = _orders

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount

    private var subscriptionListenerId: String? = null
    private val ordersByEventId = ConcurrentHashMap<String, Order>()

    @Volatile
    private var started = false
    private var currentUserPubkey: String? = null

    fun ensureStarted(userPubkey: String) {
        val normalizedPubkey = userPubkey.trim().lowercase()
        if (normalizedPubkey.isBlank()) return
        if (started && normalizedPubkey == currentUserPubkey) return

        stopListening()
        clearAllOrders()

        currentUserPubkey = normalizedPubkey
        started = true

        nostrClient.connect()
        subscriptionListenerId = subscriptionManager.subscribe(
            filtersArray = createOrderFilters(normalizedPubkey),
            onEvent = { event ->
                try {
                    val order = parseOrder(event) ?: return@subscribe
                    val partyPubkeys = setOf(order.buyerPubkey, order.sellerPubkey).filter { it.isNotBlank() }.toSet()
                    if (partyPubkeys.isNotEmpty()) {
                        profileRepository.ensureProfiles(partyPubkeys)
                    }
                    upsertOrder(order)
                } catch (e: Exception) {
                    android.util.Log.e("OrderRepository", "Error parsing order event", e)
                }
            },
            onEndOfStoredEvents = {
                updateUnreadCount()
            }
        )
    }

    fun stopListening() {
        subscriptionListenerId?.let { subscriptionManager.unsubscribe(it) }
        subscriptionListenerId = null
        started = false
    }

    private fun getProfileMetadata(pubkey: String): Pair<String, String>? {
        return try {
            val metadata = profileRepository.getCachedProfile(pubkey)
            val displayName = metadata?.displayName ?: metadata?.name ?: pubkey.take(8)
            val picture = metadata?.picture ?: ""
            Pair(displayName, picture)
        } catch (e: Exception) {
            android.util.Log.w("OrderRepository", "Failed to get profile metadata for pubkey=$pubkey", e)
            null
        }
    }

    fun markAsRead(orderId: String) {
        _orders.update { orders ->
            orders.map { order ->
                if (order.orderId == orderId) order.copy(isRead = true) else order
            }
        }
        updateUnreadCount()
    }

    fun markMultipleAsRead(orderIds: List<String>) {
        val ids = orderIds.toSet()
        _orders.update { orders ->
            orders.map { order ->
                if (order.orderId in ids) order.copy(isRead = true) else order
            }
        }
        updateUnreadCount()
    }

    fun clearAllOrders() {
        _orders.value = emptyList()
        ordersByEventId.clear()
        _unreadCount.value = 0
    }

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
        if (buyerPubkey.isBlank()) {
            throw IllegalArgumentException("Buyer pubkey is required to create an order")
        }

        val privateKeyBytes = hexToByteArrayOrNull(buyerPrivateKeyHex, 32)
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
        val eventJson = NostrEventSigner.signEvent(
            kind = 16,
            content = content,
            tags = tags,
            pubkey = buyerPubkey,
            privKey = privateKeyBytes
        )
        val event = eventJson.toNostrEvent()
        nostrClient.publishEvent(event)
        return event.id
    }

    private fun upsertOrder(order: Order) {
        val existing = ordersByEventId[order.eventId]
        if (existing == null || order.createdAt >= existing.createdAt) {
            ordersByEventId[order.eventId] = order
            _orders.update { current ->
                val map = current.associateBy { it.eventId }.toMutableMap()
                map[order.eventId] = order
                map.values.sortedByDescending { it.createdAt }
            }
        }
    }

    private fun updateUnreadCount() {
        _unreadCount.value = _orders.value.count { !it.isRead }
    }

    private fun parseOrder(event: NostrEvent): Order? {
        if (event.kind != 16) return null

        // Extract order ID (from `order` tag)
        val orderId = event.firstTagValue("order") ?: event.id.take(12)
        
        // The event author is the buyer; the p-tag holds the seller pubkey
        val buyerPubkey = event.pubkey
        val sellerPubkey = event.firstTagValue("p") ?: return null
        
        // Extract type to verify it's an order creation (type: 1)
        val type = event.firstTagValue("type") ?: return null
        if (type != "1") return null // Only handle order creation (type 1)

        // Extract order details
        val subject = event.firstTagValue("subject") ?: "Order"
        val amountStr = event.firstTagValue("amount") ?: "0"
        val amount = amountStr.toLongOrNull() ?: 0L

        // Extract items
        val currency = event.firstTagValue("currency")?.uppercase() ?: "SATS"
        val items = event.tagValues("item").mapNotNull { itemTag ->
            if (itemTag.size < 1) return@mapNotNull null
            val productRef = itemTag.getOrNull(0) ?: return@mapNotNull null
            val quantity = itemTag.getOrNull(1)?.toIntOrNull() ?: 1
            OrderItem(
                productReference = productRef,
                quantity = quantity,
                productPrice = itemTag.getOrNull(3)
            )
        }

        // Extract optional fields
        val shippingOption = event.firstTagValue("shipping")
        val shippingAddress = event.firstTagValue("address")
        val buyerEmail = event.firstTagValue("email")
        val buyerPhone = event.firstTagValue("phone")

        // Fetch metadata for both parties
        val buyerMetadata = getProfileMetadata(buyerPubkey)
        val sellerMetadata = getProfileMetadata(sellerPubkey)
        val buyerDisplayName = buyerMetadata?.first ?: buyerPubkey.take(8)
        val buyerPicture = buyerMetadata?.second ?: ""
        val sellerDisplayName = sellerMetadata?.first ?: sellerPubkey.take(8)
        val sellerPicture = sellerMetadata?.second ?: ""

        return Order(
            eventId = event.id,
            orderId = orderId,
            buyerPubkey = buyerPubkey,
            buyerDisplayName = buyerDisplayName,
            buyerPicture = buyerPicture,
            sellerPubkey = sellerPubkey,
            sellerDisplayName = sellerDisplayName,
            sellerPicture = sellerPicture,
            amount = amount,
            currency = currency,
            subject = subject,
            items = items,
            shippingOption = shippingOption,
            shippingAddress = shippingAddress,
            buyerEmail = buyerEmail,
            buyerPhone = buyerPhone,
            notes = event.content,
            createdAt = event.createdAt,
            isRead = false
        )
    }

    companion object {
        /**
         * Create a Nostr filters array for Kind 16 order messages relevant to the current user.
         * Includes events addressed to the user as a seller and events authored by the user as a buyer.
         */
        fun createOrderFilters(currentUserPubkey: String): JSONArray {
            return JSONArray().apply {
                put(JSONObject().apply {
                    put("kinds", JSONArray().put(16))
                    put("#p", JSONArray().put(currentUserPubkey))
                    put("limit", 100)
                })
                put(JSONObject().apply {
                    put("kinds", JSONArray().put(16))
                    put("authors", JSONArray().put(currentUserPubkey))
                    put("limit", 100)
                })
            }
        }
    }
}

/**
 * Extension functions for NostrEvent to extract tag values
 */
private fun NostrEvent.firstTagValue(tagName: String): String? {
    return tags.find { it.isNotEmpty() && it[0] == tagName }?.getOrNull(1)
}

private fun NostrEvent.tagValues(tagName: String): List<List<String>> {
    return tags.filter { it.isNotEmpty() && it[0] == tagName }
}
