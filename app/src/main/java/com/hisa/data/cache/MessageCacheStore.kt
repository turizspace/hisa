package com.hisa.data.cache

import android.content.Context
import com.hisa.data.model.Message
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "messages_cache",
        fallbackPrefsName = "messages_cache_fallback"
    )

    fun readMessages(): List<Message> {
        val json = sharedPreferences.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString<List<MessageSnapshot>>(json)
                .map { it.toDomainMessage() }
        }.getOrDefault(emptyList())
    }

    fun writeMessages(messages: List<Message>) {
        val snapshots = messages.map { it.toSnapshot() }
        val json = Json.encodeToString(snapshots)
        sharedPreferences.edit()
            .putString(KEY_MESSAGES, json)
            .apply()
    }

    fun clear() {
        sharedPreferences.edit().remove(KEY_MESSAGES).apply()
    }

    companion object {
        private const val KEY_MESSAGES = "messages"
    }
}

@Serializable
private data class MessageSnapshot(
    val id: String,
    val type: String,
    val pubkey: String,
    val recipientPubkeys: List<String>,
    val content: String? = null,
    val fileUrl: String? = null,
    val mimeType: String? = null,
    val encryptionAlgorithm: String? = null,
    val decryptionKey: String? = null,
    val decryptionNonce: String? = null,
    val fileHash: String? = null,
    val originalHash: String? = null,
    val fileSize: Long? = null,
    val dimensions: List<Int>? = null,
    val blurhash: String? = null,
    val thumbnailUrl: String? = null,
    val fallbackUrls: List<String>? = null,
    val targetEventId: String? = null,
    val targetEventPubkey: String? = null,
    val targetEventKind: Int? = null,
    val subject: String? = null,
    val replyTo: String? = null,
    val relayUrls: Map<String, String>? = null,
    val createdAt: Long
)

private fun Message.toSnapshot(): MessageSnapshot = when (this) {
    is Message.TextMessage -> MessageSnapshot(
        id = id,
        type = "text",
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        content = content,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls,
        createdAt = createdAt
    )
    is Message.FileMessage -> MessageSnapshot(
        id = id,
        type = "file",
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        fileUrl = fileUrl,
        mimeType = mimeType,
        encryptionAlgorithm = encryptionAlgorithm,
        decryptionKey = decryptionKey,
        decryptionNonce = decryptionNonce,
        fileHash = fileHash,
        originalHash = originalHash,
        fileSize = fileSize,
        dimensions = dimensions?.let { listOf(it.first, it.second) },
        blurhash = blurhash,
        thumbnailUrl = thumbnailUrl,
        fallbackUrls = fallbackUrls,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls,
        createdAt = createdAt
    )
    is Message.ReactionMessage -> MessageSnapshot(
        id = id,
        type = "reaction",
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        content = content,
        targetEventId = targetEventId,
        targetEventPubkey = targetEventPubkey,
        targetEventKind = targetEventKind,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls,
        createdAt = createdAt
    )
}

private fun MessageSnapshot.toDomainMessage(): Message = when (type) {
    "file" -> Message.FileMessage(
        id = id,
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        fileUrl = fileUrl.orEmpty(),
        createdAt = createdAt,
        mimeType = mimeType.orEmpty(),
        encryptionAlgorithm = encryptionAlgorithm.orEmpty(),
        decryptionKey = decryptionKey.orEmpty(),
        decryptionNonce = decryptionNonce.orEmpty(),
        fileHash = fileHash.orEmpty(),
        originalHash = originalHash,
        fileSize = fileSize,
        dimensions = dimensions?.let { if (it.size >= 2) it[0] to it[1] else null },
        blurhash = blurhash,
        thumbnailUrl = thumbnailUrl,
        fallbackUrls = fallbackUrls,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls
    )
    "reaction" -> Message.ReactionMessage(
        id = id,
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        content = content.orEmpty(),
        targetEventId = targetEventId.orEmpty(),
        targetEventPubkey = targetEventPubkey,
        targetEventKind = targetEventKind,
        createdAt = createdAt,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls
    )
    else -> Message.TextMessage(
        id = id,
        pubkey = pubkey,
        recipientPubkeys = recipientPubkeys,
        content = content.orEmpty(),
        createdAt = createdAt,
        subject = subject,
        replyTo = replyTo,
        relayUrls = relayUrls
    )
}
