package com.hisa.domain.service

import com.hisa.data.model.Message
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import com.hisa.data.repository.MessageRepository
import com.hisa.util.cleanPubkeyFormat
import org.json.JSONObject

class MessageSendingService(
    private val nostrClient: NostrClient,
    private val messageRepository: MessageRepository,
    private val signingService: NostrSigningService,
    private val resolveAuthContext: suspend (Boolean) -> NostrSigningService.SigningContext,
    private val onLocalMessage: (Message) -> Unit,
    private val onError: (String) -> Unit
) {
    suspend fun sendMessage(
        recipientPubkey: String,
        content: String,
        subject: String? = null,
        replyTo: String? = null,
        senderSigningPubkey: String,
        signingPrivateKeyBytes: ByteArray?
    ): Message? {
        val cleanRecipientPubkey = cleanPubkeyFormat(recipientPubkey)
        if (!cleanRecipientPubkey.matches(Regex("[0-9a-f]{64}"))) {
            onError("Invalid recipient pubkey")
            return null
        }

        val innerMessage = createInnerMessageEvent(cleanRecipientPubkey, content, subject, replyTo)
        val authContext = resolveAuthContext(false)
        val wrappedEvent = messageRepository.prepareGiftWrappedMessageForSigningContext(
            signingService = signingService,
            signingContext = authContext,
            recipientPubkey = cleanRecipientPubkey,
            content = innerMessage.optString("content", ""),
            kind = innerMessage.optInt("kind", 14),
            tags = innerMessage.optJSONArray("tags")?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val t = arr.optJSONArray(i) ?: continue
                        add(buildList {
                            for (j in 0 until t.length()) add(t.optString(j))
                        })
                    }
                }
            } ?: listOf(listOf("p", cleanRecipientPubkey)),
            externalEncryptor = { plaintext, peerPubkey ->
                com.hisa.data.nostr.ExternalSignerManager.nip44Encrypt(plaintext, peerPubkey)
            }
        )

        nostrClient.publishEvent(
            com.hisa.data.nostr.NostrEvent(
                id = wrappedEvent.getString("id"),
                pubkey = wrappedEvent.getString("pubkey"),
                createdAt = wrappedEvent.getLong("created_at"),
                kind = wrappedEvent.getInt("kind"),
                tags = (0 until wrappedEvent.getJSONArray("tags").length()).map { i ->
                    val tagArr = wrappedEvent.getJSONArray("tags").getJSONArray(i)
                    (0 until tagArr.length()).map { tagArr.getString(it) }
                },
                content = wrappedEvent.getString("content"),
                sig = wrappedEvent.getString("sig")
            )
        )

        val localRumor = MessageRepository.localRumorEventFromGiftWrap(wrappedEvent) ?: return null
        return MessageRepository.parseMessage(localRumor.toString())?.let { parsed ->
            parsed
        }
    }

    private fun createInnerMessageEvent(
        recipientPubkey: String,
        content: String,
        subject: String?,
        replyTo: String?
    ): JSONObject {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("p", recipientPubkey))
        subject?.let { tags.add(listOf("subject", it)) }
        replyTo?.let { tags.add(listOf("e", it)) }

        return messageRepository.createMessageEvent(
            kind = 14,
            content = content,
            tags = tags,
            createdAt = System.currentTimeMillis() / 1000
        )
    }
}
