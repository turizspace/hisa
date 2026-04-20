package com.hisa.data.model

data class Stall(
    val id: String,
    val ownerPubkey: String,
    val name: String,
    val description: String,
    val picture: String = "",
    val currency: String = "USD",
    val shippingZones: List<ShippingZone> = emptyList(),
    val categories: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis() / 1000,
    // Owner profile metadata (fetched from kind 0)
    val ownerDisplayName: String = "",
    val ownerProfilePicture: String = ""
)

data class ShippingZone(
    val id: String,
    val name: String,
    val cost: Double,
    val regions: List<String>
)
