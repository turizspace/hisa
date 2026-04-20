package com.hisa.data.model

/**
 * NIP-15 Product (kind 30018)
 * Represents a product listed under a stall (kind 30017).
 *
 * Tag structure:
 * - d: product id (unique within stall)
 * - stall_id: reference to parent stall (d tag of kind 30017)
 * - name: product name
 * - description: product description
 * - images: array of image URLs
 * - currency: pricing currency (e.g., "USD", "SATS")
 * - price: product price
 * - quantity: available quantity (null = unlimited)
 * - t: categories (repeating)
 */
data class Product(
    val id: String,                          // From d tag
    val stallId: String,                     // From stall_id tag
    val name: String,
    val description: String = "",
    val pictures: List<String> = emptyList(),
    val currency: String = "USD",
    val price: String = "0",
    val quantity: Int? = null,
    val categories: List<String> = emptyList(),
    val createdAt: Long = 0L
)
