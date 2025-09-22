package com.hisa.crypto

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

object Schnorr {
    // BIP-340 Schnorr signature using ACINQ secp256k1-kmp
    fun sign(message: ByteArray, privateKey: ByteArray): ByteArray {
        require(message.size == 32) { "Message must be 32 bytes (SHA-256 hash)" }
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        val auxrand = ByteArray(32)
        SecureRandom().nextBytes(auxrand)
        return Secp256k1.signSchnorr(message, privateKey, auxrand)
    }
}
