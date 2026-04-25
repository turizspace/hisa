package com.hisa.data.model

/**
 * NIP-15 Product (kind 30018)
 * Represents a product listed under a stall (kind 30017).
 *
 * NIP-15 stores most fields in JSON content. The d/tag t-tags are still used
 * for addressability and search.
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
