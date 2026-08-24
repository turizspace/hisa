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
    const val HISA_DEV_PUBKEY = "06830f6cb5925bd82cca59bda848f0056666dff046c5382963a997a234da40c5"

    // NIP-57 sponsor display policy. A sponsor's valid zaps are aggregated before
    // applying the threshold so regular supporters can appear in the ticker.
    const val MIN_SPONSOR_ZAP_TOTAL_SATS = 10000L
    const val MAX_DISPLAYED_ZAP_SPONSORS = 20
    const val ZAP_RECEIPT_FETCH_LIMIT = 500
}
