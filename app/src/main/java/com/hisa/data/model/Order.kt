package com.hisa.data.model

/**
 * Marketplace Order (Kind 16, type: 1)
 * Represents a buyer's order sent to a seller.
 * Follows the Marketplace Protocol specification.
 */
data class Order(
    val eventId: String,
    val orderId: String,
    val buyerPubkey: String,
    val buyerDisplayName: String = "",
    val buyerPicture: String = "",
    val sellerPubkey: String = "",
    val sellerDisplayName: String = "",
    val sellerPicture: String = "",
    val amount: Long,
    val currency: String = "SATS",
    val subject: String = "",
    val items: List<OrderItem> = emptyList(),
    val shippingOption: String? = null,
    val shippingAddress: String? = null,
    val buyerEmail: String? = null,
    val buyerPhone: String? = null,
    val notes: String = "",
    val createdAt: Long = 0L,
    val isRead: Boolean = false
) {
    fun isBuyer(currentUserPubkey: String?): Boolean {
        return !currentUserPubkey.isNullOrBlank() && currentUserPubkey.equals(buyerPubkey, ignoreCase = true)
    }

    fun counterpartyPubkey(currentUserPubkey: String?): String {
        return if (isBuyer(currentUserPubkey)) sellerPubkey else buyerPubkey
    }

    fun counterpartyDisplayName(currentUserPubkey: String?): String {
        return if (isBuyer(currentUserPubkey)) {
            sellerDisplayName.ifBlank { sellerPubkey.take(12) + "..." }
        } else {
            buyerDisplayName.ifBlank { buyerPubkey.take(12) + "..." }
        }
    }

    fun counterpartyPicture(currentUserPubkey: String?): String {
        return if (isBuyer(currentUserPubkey)) sellerPicture else buyerPicture
    }
}

/**
 * Represents an item in an order
 */
data class OrderItem(
    val productReference: String, // Format: "30402:<pubkey>:<d-tag>"
    val productName: String = "",
    val quantity: Int = 1,
    val productImage: String? = null,
    val productPrice: String? = null
)

/**
 * Summary of order for notification badge
 */
data class OrderNotificationSummary(
    val totalUnreadOrders: Int = 0,
    val recentOrders: List<Order> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)
