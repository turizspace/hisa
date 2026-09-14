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
    const val SUPPORT_EMAIL = "hello@proofofink.art"
    const val SUPPORT_SUBJECT = "Hisa Support Request"

    const val DEFAULT_DONATION_AMOUNT_SATS = 5000L    // Default 5000 sats
    const val HISA_DEV_PUBKEY = "06830f6cb5925bd82cca59bda848f0056666dff046c5382963a997a234da40c5"
    const val GITHUB_URL = "https://github.com/turizspace/hisa"

    // NIP-57 sponsor display policy. A sponsor's valid zaps are aggregated before
    // applying the threshold so regular supporters can appear in the ticker.
    const val MIN_SPONSOR_ZAP_TOTAL_SATS = 10000L
    const val MAX_DISPLAYED_ZAP_SPONSORS = 200
    const val ZAP_RECEIPT_FETCH_LIMIT = 5000
    const val DONATION_EVENT_FETCH_LIMIT = 500
}
