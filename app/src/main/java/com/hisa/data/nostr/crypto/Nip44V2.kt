package com.hisa.data.nostr.crypto

import org.bitcoinj.core.ECKey
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.log2

class Nip44V2(
    private val random: SecureRandom = SecureRandom()
) : Nip44 {
    private val hkdf = Hkdf()

    companion object {
        private const val HASH_LEN = 32
        private const val CHACHA_NONCE_LEN = 12
        private const val MIN_PLAINTEXT_SIZE = 1
        private const val MAX_PLAINTEXT_SIZE = 65535
        private val SALT_PREFIX = "nip44-v2".toByteArray(Charsets.UTF_8)
        private val SECP256K1_N: BigInteger = BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",
            16
        )
    }

    override fun getConversationKey(privateKey: ByteArray, pubKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "Invalid private key length" }
        require(pubKey.size == 32) { "Invalid public key length" }
        val scalar = BigInteger(1, privateKey)
        require(scalar >= BigInteger.ONE && scalar < SECP256K1_N) { "Invalid secp256k1 private scalar" }

        val compressed = byteArrayOf(0x02) + pubKey
        val point: ECPoint = ECKey.CURVE.curve.decodePoint(compressed)
        require(!point.isInfinity) { "Invalid secp256k1 point" }
        val sharedX = point.multiply(scalar).normalize().xCoord.encoded.to32Bytes()
        return hkdf.extract(sharedX, SALT_PREFIX)
    }

    override fun encrypt(msg: String, privateKey: ByteArray, pubKey: ByteArray): EncryptedPayload {
        return encrypt(msg, getConversationKey(privateKey, pubKey))
    }

    override fun encrypt(plaintext: String, conversationKey: ByteArray): EncryptedPayload {
        val nonce = ByteArray(HASH_LEN).also { random.nextBytes(it) }
        return encryptWithNonce(plaintext, conversationKey, nonce)
    }

    override fun encryptWithNonce(plaintext: String, conversationKey: ByteArray, nonce: ByteArray): EncryptedPayload {
        val (chachaKey, chachaNonce, hmacKey) = getMessageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = chacha(chachaKey, chachaNonce, padded, encrypt = true)
        val mac = hmacAad(hmacKey, ciphertext, nonce)
        return EncryptedPayload(nonce = nonce, ciphertext = ciphertext, mac = mac)
    }

    override fun decrypt(payload: String, privateKey: ByteArray, pubKey: ByteArray): String? {
        return decrypt(payload, getConversationKey(privateKey, pubKey))
    }

    override fun decrypt(decoded: EncryptedPayload, privateKey: ByteArray, pubKey: ByteArray): String? {
        return decrypt(decoded, getConversationKey(privateKey, pubKey))
    }

    override fun decrypt(payload: String, conversationKey: ByteArray): String? {
        val decoded = EncryptedPayload.decode(payload) ?: return null
        return decrypt(decoded, conversationKey)
    }

    override fun decrypt(decoded: EncryptedPayload, conversationKey: ByteArray): String {
        val (chachaKey, chachaNonce, hmacKey) = getMessageKeys(conversationKey, decoded.nonce)
        val expectedMac = hmacAad(hmacKey, decoded.ciphertext, decoded.nonce)
        require(MessageDigest.isEqual(expectedMac, decoded.mac)) { "Invalid MAC" }
        val padded = chacha(chachaKey, chachaNonce, decoded.ciphertext, encrypt = false)
        return unpad(padded)
    }

    override fun calcPaddedLen(len: Int): Int {
        require(len > 0) { "expected positive integer" }
        if (len <= 32) return 32
        val nextPower = 1 shl (floor(log2((len - 1).toFloat())) + 1).toInt()
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (floor((len - 1).toFloat() / chunk).toInt() + 1)
    }

    private fun getMessageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        require(conversationKey.size == HASH_LEN) { "Invalid conversation key length" }
        require(nonce.size == HASH_LEN) { "Invalid nonce length" }
        val keys = hkdf.expand(conversationKey, nonce, 76)
        return Triple(
            keys.copyOfRange(0, 32),
            keys.copyOfRange(32, 32 + CHACHA_NONCE_LEN),
            keys.copyOfRange(44, 76)
        )
    }

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.toByteArray(Charsets.UTF_8)
        val len = unpadded.size
        require(len in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "Invalid plaintext length" }
        val paddedLen = calcPaddedLen(len)
        val out = ByteArray(2 + paddedLen)
        out[0] = ((len ushr 8) and 0xff).toByte()
        out[1] = (len and 0xff).toByte()
        System.arraycopy(unpadded, 0, out, 2, len)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        require(padded.size >= 2) { "Invalid padded size" }
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len in MIN_PLAINTEXT_SIZE..MAX_PLAINTEXT_SIZE) { "Invalid plaintext length in padding" }
        require(padded.size == 2 + calcPaddedLen(len)) { "Invalid padding shape" }
        val unpadded = padded.copyOfRange(2, 2 + len)
        require(unpadded.size == len) { "Invalid padding framing" }
        return unpadded.decodeToString()
    }

    private fun hmacAad(key: ByteArray, message: ByteArray, aad: ByteArray): ByteArray {
        require(aad.size == HASH_LEN) { "AAD must be 32 bytes" }
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(key))
        val data = aad + message
        hmac.update(data, 0, data.size)
        return ByteArray(HASH_LEN).also { hmac.doFinal(it, 0) }
    }

    private fun chacha(key: ByteArray, nonce: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val out = ByteArray(data.size)
        val engine = ChaCha7539Engine()
        engine.init(encrypt, ParametersWithIV(KeyParameter(key), nonce))
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    private fun ByteArray.to32Bytes(): ByteArray {
        if (size == 32) return this
        if (size > 32) return copyOfRange(size - 32, size)
        val out = ByteArray(32)
        System.arraycopy(this, 0, out, 32 - size, size)
        return out
    }
}
