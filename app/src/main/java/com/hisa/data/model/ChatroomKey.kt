package com.hisa.data.model

/**
 * Uniquely identifies a conversation by participant public keys (excluding current user)
 * 
 * Examples:
 * - 1-to-1 with Alice: ChatroomKey({alice_pubkey})
 * - Group with Alice & Bob: ChatroomKey({alice_pubkey, bob_pubkey})
 * 
 * Properties:
 * - Immutable set ensures consistency across the app
 * - Comparable for sorted lists (e.g., UI conversation ordering)
 * - Hashable for use as collection keys
 * - Order-independent: {Alice, Bob} == {Bob, Alice}
 */
data class ChatroomKey(
    val users: Set<String>
) : Comparable<ChatroomKey> {
    
    init {
        require(users.isNotEmpty()) { "ChatroomKey must have at least one participant" }
    }

    /**
     * Compare by sorted user list for consistent ordering
     */
    override fun compareTo(other: ChatroomKey): Int {
        val thisSorted = users.sorted()
        val otherSorted = other.users.sorted()
        return thisSorted.joinToString(",").compareTo(otherSorted.joinToString(","))
    }

    /**
     * Create a new ChatroomKey excluding a specific user (e.g., removing current user from recipients)
     * Used when normalizing message recipients to find the "other" participant(s) in a conversation
     */
    fun excluding(pubkey: String): ChatroomKey {
        val filtered = users.filter { it != pubkey }.toSet()
        return if (filtered.isEmpty()) {
            // If all users are filtered out, this shouldn't happen in practice
            // Return a key with just this pubkey as fallback
            ChatroomKey(setOf(pubkey))
        } else {
            ChatroomKey(filtered)
        }
    }

    /**
     * Check if this is a 1-to-1 conversation
     */
    val isOneToOne: Boolean
        get() = users.size == 1

    /**
     * Check if this is a group conversation
     */
    val isGroup: Boolean
        get() = users.size > 1

    override fun hashCode(): Int = users.sorted().hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatroomKey) return false
        // Order-independent comparison: {A,B} == {B,A}
        return users == other.users
    }

    override fun toString(): String {
        val sorted = users.sorted()
        return if (sorted.size == 1) {
            sorted[0].take(16)
        } else {
            sorted.joinToString(", ") { it.take(8) }
        }
    }
}
