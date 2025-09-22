package com.hisa.util

object Constants {
    // Preset relays for onboarding (signup profile publish)
    val ONBOARDING_RELAYS = listOf(
        // A small curated set of public relays used to bootstrap subscriptions (NIP-65)
        "wss://relay.nostr.band",
        "wss://relay.nostr.info",
        "wss://nostr-pub.wellorder.net",
        "wss://nos.lol",
        "wss://relay.snort.social",
        "wss://nostr.oxtr.dev"
    )

    // Support contact information
    const val SUPPORT_EMAIL = "me@turiz.space"
    const val SUPPORT_SUBJECT = "Hisa Support Request"

    // Donation addresses
    const val LIGHTNING_ADDRESS = "turiz@walletofsatoshi.com"  // Replace with actual lightning address
    const val BITCOIN_ADDRESS = "bc1qf4ypmkjrrupezel5hedtq0jw0nhxpdmv2zsc3k"       // Replace with actual Bitcoin address
    const val DEFAULT_DONATION_AMOUNT_SATS = 5000L    // Default 5000 sats
}
