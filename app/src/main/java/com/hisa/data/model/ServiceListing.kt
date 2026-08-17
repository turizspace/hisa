package com.hisa.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ServiceListing(
    val eventId: String,
    val dTag: String? = null,
    val title: String,
    val summary: String,
    val content: String?,
    val price: String,
    val tags: List<String>, // Topic tags (t tags)
    val pubkey: String,
    val rawTags: List<List<String>> = emptyList(), // All original tags from the event
    val rawEvent: String? = null, // Added rawEvent property for debugging
    val createdAt: Long // Timestamp of when the service was created
)
