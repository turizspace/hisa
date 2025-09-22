package com.hisa.util

fun cleanPubkeyFormat(pubkey: String): String {
    return when {
        pubkey.startsWith("02") || pubkey.startsWith("03") -> {
            if (pubkey.length > 2) pubkey.substring(2) else pubkey
        }
        pubkey.startsWith("04") -> {
            // Expected uncompressed pubkey format: prefix '04' + 64 hex chars = 66 length
            // Be defensive: if the string is shorter than expected, return what we can.
            when {
                pubkey.length >= 66 -> pubkey.substring(2, 66)
                pubkey.length > 2 -> pubkey.substring(2)
                else -> pubkey
            }
        }
        else -> pubkey
    }
}
