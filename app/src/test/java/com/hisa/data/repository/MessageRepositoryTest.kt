package com.hisa.data.repository

import org.bitcoinj.core.ECKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageRepositoryTest {

    @Test
    fun nip04EncryptAndDecryptRoundTripWorks() {
        val senderKey = ECKey()
        val recipientKey = ECKey()

        val plaintext = "hello from a refactor"
        val ciphertext = MessageRepository.nip04Encrypt(
            plaintext = plaintext,
            senderPrivateKey = senderKey.privKeyBytes,
            recipientPubkey = ecKeyToXOnlyHex(recipientKey)
        )

        val decrypted = MessageRepository.nip04Decrypt(
            encryptedContent = ciphertext,
            recipientPrivateKey = recipientKey.privKeyBytes,
            senderPubkey = ecKeyToXOnlyHex(senderKey)
        )

        assertEquals(plaintext, decrypted)
    }

    private fun ecKeyToXOnlyHex(key: ECKey): String {
        val uncompressed = key.decompress().pubKeyPoint.getEncoded(false)
        val xOnly = uncompressed.copyOfRange(1, 33)
        return xOnly.joinToString("") { "%02x".format(it) }
    }
}
