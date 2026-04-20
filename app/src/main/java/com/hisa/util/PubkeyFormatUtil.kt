package com.hisa.util

fun cleanPubkeyFormat(pubkey: String): String {
    val trimmed = pubkey.trim()
    return when {
        trimmed.startsWith("02") || trimmed.startsWith("03") -> {
            if (trimmed.length > 2) trimmed.substring(2) else trimmed
        }
        trimmed.startsWith("04") -> {
            // Expected uncompressed pubkey format: prefix '04' + 64 hex chars = 66 length
            // Be defensive: if the string is shorter than expected, return what we can.
            when {
                trimmed.length >= 66 -> trimmed.substring(2, 66)
                trimmed.length > 2 -> trimmed.substring(2)
                else -> trimmed
            }
        }
        else -> trimmed
    }
}

fun normalizeNostrPubkey(pubkey: String?): String? {
    if (pubkey.isNullOrBlank()) return null
    val raw = pubkey.trim()
    val decoded = if (raw.startsWith("npub", ignoreCase = true)) {
        KeyGenerator.npubToPublicKey(raw) ?: return null
    } else {
        raw
    }
    val normalized = cleanPubkeyFormat(decoded).removePrefix("0x")
    return normalized.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }?.lowercase()
}

fun hexToByteArrayOrNull(hex: String?, expectedBytes: Int? = null): ByteArray? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.trim().removePrefix("0x").lowercase()
    if (clean.isEmpty() || clean.length % 2 != 0 || !clean.matches(Regex("^[0-9a-f]+$"))) {
        return null
    }
    val bytes = ByteArray(clean.length / 2)
    for (index in bytes.indices) {
        val start = index * 2
        bytes[index] = clean.substring(start, start + 2).toInt(16).toByte()
    }
    if (expectedBytes != null && bytes.size != expectedBytes) return null
    return bytes
}

fun hexToByteArray(hex: String, expectedBytes: Int? = null): ByteArray {
    return hexToByteArrayOrNull(hex, expectedBytes)
        ?: throw IllegalArgumentException("Invalid hex value")
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
