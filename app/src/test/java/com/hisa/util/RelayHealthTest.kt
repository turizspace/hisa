package com.hisa.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayHealthTest {

    @Test
    fun `normalizes relay urls and removes duplicates`() {
        val input = listOf(
            " wss://relay.example.com ",
            "https://relay.example.com",
            "relay2.example.com",
            "ws://relay2.example.com"
        )

        val normalized = RelayHealth.normalizeRelayUrls(input)

        assertEquals(
            listOf(
                "wss://relay.example.com",
                "wss://relay2.example.com"
            ),
            normalized
        )
    }
}
