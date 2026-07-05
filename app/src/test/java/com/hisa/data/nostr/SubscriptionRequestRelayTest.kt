package com.hisa.data.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRequestRelayTest {

    @Test
    fun `does not replay the same subscription to a relay that already has it`() {
        val assignedRelays = setOf("wss://relay-a")

        assertFalse(
            NostrClient.shouldSendSubscriptionToRelay(
                subscriptionId = "sub-1",
                relayUrl = "wss://relay-a",
                assignedRelays = assignedRelays
            )
        )
        assertTrue(
            NostrClient.shouldSendSubscriptionToRelay(
                subscriptionId = "sub-1",
                relayUrl = "wss://relay-b",
                assignedRelays = assignedRelays
            )
        )
    }
}
