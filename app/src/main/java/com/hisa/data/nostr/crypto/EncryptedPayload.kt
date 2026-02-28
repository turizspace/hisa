package com.hisa.data.nostr.crypto

import java.util.Base64

data class EncryptedPayload(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val mac: ByteArray
) {
    fun encode(): String {
        return Base64.getEncoder().encodeToString(byteArrayOf(VERSION.toByte()) + nonce + ciphertext + mac)
    }

    companion object {
        const val VERSION = 2

        fun decode(payload: String): EncryptedPayload? {
            val p = payload.trim()
            if (p.isEmpty() || p.startsWith("#")) return null
            return try {
                val raw = Base64.getDecoder().decode(p)
                if (raw[0].toInt() != VERSION) return null
                EncryptedPayload(
                    nonce = raw.copyOfRange(1, 33),
                    ciphertext = raw.copyOfRange(33, raw.size - 32),
                    mac = raw.copyOfRange(raw.size - 32, raw.size)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
