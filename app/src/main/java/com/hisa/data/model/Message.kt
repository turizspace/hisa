package com.hisa.data.model

/**
 * Represents a NIP-17 Direct Message (Kind 14 or 15)
 * 
 * According to NIP-17:
 * - Messages MUST NOT be signed to prevent leaking to relays
 * - Uses NIP-44 encryption and NIP-59 seals/gift wraps
 * - Content is plain text for Kind 14, file URL for Kind 15
 */
sealed class Message {
    abstract val id: String
    abstract val pubkey: String // sender's pubkey
    abstract val recipientPubkeys: List<String> // from p tags
    abstract val createdAt: Long
    abstract val subject: String? // from subject tag
    abstract val replyTo: String? // from e tag with "reply" marker
    abstract val relayUrls: Map<String, String>? // pubkey to relay URL mapping from p tags

    /**
     * Kind 14: Text Message
     */
    data class TextMessage(
        override val id: String,
        override val pubkey: String,
        override val recipientPubkeys: List<String>,
        val content: String,
        override val createdAt: Long,
        override val subject: String? = null,
        override val replyTo: String? = null,
        override val relayUrls: Map<String, String>? = null
    ) : Message()

    /**
     * Kind 15: File Message
     */
    data class FileMessage(
        override val id: String,
        override val pubkey: String,
        override val recipientPubkeys: List<String>,
        val fileUrl: String,
        override val createdAt: Long,
        val mimeType: String,
        val encryptionAlgorithm: String,
        val decryptionKey: String,
        val decryptionNonce: String,
        val fileHash: String, // SHA-256 of encrypted file
        val originalHash: String? = null, // SHA-256 of original file
        val fileSize: Long? = null,
        val dimensions: Pair<Int, Int>? = null,
        val blurhash: String? = null,
        val thumbnailUrl: String? = null,
        val fallbackUrls: List<String>? = null,
        override val subject: String? = null,
        override val replyTo: String? = null,
        override val relayUrls: Map<String, String>? = null
    ) : Message()
}