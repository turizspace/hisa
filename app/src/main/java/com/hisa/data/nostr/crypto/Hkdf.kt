package com.hisa.data.nostr.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter

class Hkdf {
    private val hashLen = 32

    fun extract(ikm: ByteArray, salt: ByteArray): ByteArray {
        val hmac = HMac(SHA256Digest())
        hmac.init(KeyParameter(salt))
        hmac.update(ikm, 0, ikm.size)
        return ByteArray(hashLen).also { hmac.doFinal(it, 0) }
    }

    fun expand(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(prk.size == hashLen) { "Invalid PRK length" }
        val n = (outputLength + hashLen - 1) / hashLen
        require(n <= 255) { "HKDF output too large" }

        val out = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            val hmac = HMac(SHA256Digest())
            hmac.init(KeyParameter(prk))
            if (t.isNotEmpty()) hmac.update(t, 0, t.size)
            hmac.update(info, 0, info.size)
            hmac.update(i.toByte())
            t = ByteArray(hashLen).also { hmac.doFinal(it, 0) }
            val take = minOf(hashLen, outputLength - offset)
            System.arraycopy(t, 0, out, offset, take)
            offset += take
        }
        return out
    }
}

