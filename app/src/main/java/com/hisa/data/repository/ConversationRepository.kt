package com.hisa.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object ConversationRepository {
    // Clear all conversations (for logout or account switch)
    suspend fun clearAllConversations() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        sharedPrefs?.edit()?.clear()?.commit()
    }
    private var sharedPrefs: SharedPreferences? = null
    
    fun initStorage(context: Context) {
        if (sharedPrefs == null) {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            sharedPrefs = EncryptedSharedPreferences.create(
                context,
                "conversations_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    // Store conversation metadata
    suspend fun saveConversation(conversationId: String, participants: List<String>) = 
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val json = JSONObject().apply {
                put("participants", JSONArray(participants))
                put("updated_at", System.currentTimeMillis())
            }
            sharedPrefs?.edit()?.putString(conversationId, json.toString())?.commit()
        }

    // Get all pubkeys for a conversation
    suspend fun getConversationPubkeys(conversationId: String): List<String> = 
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                sharedPrefs?.getString(conversationId, null)?.let { json ->
                    JSONObject(json).getJSONArray("participants").let { participants ->
                        List(participants.length()) { participants.getString(it) }
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                android.util.Log.e("ConversationRepo", "Failed to parse conversation: $conversationId", e)
                emptyList()
            }
        }

    // Get conversation ID for a participant pair
    suspend fun getConversationId(pubkey1: String, pubkey2: String): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val result = sharedPrefs?.all?.entries?.firstOrNull { (conversationId, _) ->
                val pubkeys = getConversationPubkeys(conversationId)
                pubkeys.size == 2 && pubkeys.containsAll(listOf(pubkey1, pubkey2))
            }
            result?.key
        }
    }

    // Create or get conversation ID for participants
    suspend fun getOrCreateConversation(participants: List<String>): String {
        // For 1:1 chats, check existing conversations
        if (participants.size == 2) {
            getConversationId(participants[0], participants[1])?.let { return it }
        }

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Create new conversation ID (hash of sorted pubkeys)
            val sortedPubkeys = participants.sorted().joinToString("")
            val conversationId = java.security.MessageDigest.getInstance("SHA-256")
                .digest(sortedPubkeys.toByteArray())
                .joinToString("") { "%02x".format(it) }

            // Save in background
            saveConversation(conversationId, participants)
            conversationId
        }
    }

    // Get participant pubkey from conversation ID
    suspend fun getParticipantPubkey(conversationId: String, participantIndex: Int = 0): String? {
        val pubkeys = getConversationPubkeys(conversationId)
        return pubkeys.getOrNull(participantIndex)
    }
}
