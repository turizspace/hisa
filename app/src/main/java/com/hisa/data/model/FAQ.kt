package com.hisa.data.model

data class FAQ(
    val question: String,
    val answer: String,
    val category: String = "General"
)

object FAQRepository {
    val faqs = listOf(
        FAQ(
            question = "What is Hisa?",
            answer = "Hisa is a decentralized freelance marketplace built on the Nostr protocol. It allows freelancers to offer services and connect with clients directly, using secure messaging and Bitcoin payments.",
            category = "General"
        ),
        FAQ(
            question = "How do I get started?",
            answer = "Getting started with Hisa is simple:\n" +
                    "1. Create your profile with a name and description\n" +
                    "2. Browse available services or create your own listing\n" +
                    "3. Connect with other users through secure messaging\n\n" +
                    "We recommend backing up your keys from the Settings menu after creating your account.",
            category = "Getting Started"
        ),
        FAQ(
            question = "How secure is the messaging system?",
            answer = "Hisa uses state-of-the-art encryption following NIP-44 and NIP-17 standards. All messages are end-to-end encrypted using ChaCha20 encryption, ECDH key exchange, and HMAC-SHA256 for message authentication. Your private keys never leave your device.",
            category = "Security"
        ),
        FAQ(
            question = "How do payments work?",
            answer = "Hisa currently supports Bitcoin payments through the Lightning Network. Prices can be listed in sats (Bitcoin's smallest unit) or fiat currencies. The actual payment process is peer-to-peer between users.",
            category = "Payments"
        ),
        FAQ(
            question = "What are Service Listings?",
            answer = "Service Listings are Nostr events (kind 30402) that describe services offered by freelancers. They include:\n" +
                    "• Title and description\n" +
                    "• Price information\n" +
                    "• Location (if applicable)\n" +
                    "• Tags for categorization\n" +
                    "• Optional images",
            category = "Services"
        ),
        FAQ(
            question = "How do I manage my profile?",
            answer = "Your profile is managed through Nostr kind 0 events. You can update:\n" +
                    "• Display name\n" +
                    "• Profile picture\n" +
                    "• About information\n" +
                    "• Contact details\n" +
                    "Changes are broadcast to relays and visible to other users.",
            category = "Profile"
        ),
        FAQ(
            question = "What are my Nostr keys and how do I manage them?",
            answer = "Your Nostr keys are automatically generated when you create your account. They consist of:\n" +
                    "• A public key (npub) - Your identity on the network\n" +
                    "• A private key (nsec) - Your secret access key\n\n" +
                    "Hisa securely stores your keys on your device. For extra security:\n" +
                    "1. Go to Settings to backup your private key\n" +
                    "2. Store the backup safely offline\n" +
                    "3. Never share your private key with anyone\n\n" +
                    "Remember: Your private key is the only way to access your account. If lost, your account cannot be recovered.",
            category = "Security"
        ),
        FAQ(
            question = "How do I contact support?",
            answer = "Hisa is a decentralized application, but you can get help through:\n" +
                    "1. Community channels on Nostr\n" +
                    "2. GitHub issues for technical problems\n" +
                    "3. Direct messages to maintainers",
            category = "Support"
        ),
        FAQ(
            question = "Can I use multiple devices?",
            answer = "Yes! Since Hisa uses Nostr protocol, you can:\n" +
                    "1. Import your private key on multiple devices\n" +
                    "2. Access your messages and listings from any device\n" +
                    "3. Receive notifications across all logged-in devices",
            category = "General"
        ),
        FAQ(
            question = "What relays does Hisa use?",
            answer = "Hisa connects to popular Nostr relays including:\n" +
                    "• relay.damus.io\n" +
                    "• nos.lol\n" +
                    "• relay.nostr.band\n" +
                    "You can also configure custom relays in the settings.",
            category = "Technical"
        ),
    )
}
