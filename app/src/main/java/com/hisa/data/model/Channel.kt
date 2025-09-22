package com.hisa.data.model

data class Channel(
    val id: String, // event id of kind 40 (channel creation)
    val name: String,
    val about: String,
    val picture: String,
    val relays: List<String>,
    val creatorPubkey: String,
    val categories: List<String> = emptyList(), // from "t" tags
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val metadata: Map<String, Any> = emptyMap() // for additional metadata fields
)

data class ChannelMessage(
    val id: String,
    val pubkey: String,
    val channelId: String,
    val content: String,
    val authorPubkey: String,
    val recipientPubkeys: List<String>,
    val createdAt: Long,
    val replyTo: String? = null, // for threaded messages
    val mentions: List<String> = emptyList() // "p" tags for mentions
)

// Helper sealed class for managing channel states and updates
sealed class ChannelEvent {
    data class ChannelCreate(val channel: Channel) : ChannelEvent()
    data class MetadataUpdate(
        val channelId: String,
        val name: String?,
        val about: String?,
        val picture: String?,
        val relays: List<String>?
    ) : ChannelEvent()
    data class NewMessage(val message: ChannelMessage) : ChannelEvent()
    data class HideMessage(val messageId: String, val reason: String?) : ChannelEvent()
    data class MuteUser(val pubkey: String, val reason: String?) : ChannelEvent()
}
