
package com.hisa.util

import android.util.Log
import org.bitcoinj.core.Bech32
import org.bitcoinj.core.ECKey
import java.security.SecureRandom
import org.bouncycastle.util.encoders.Hex

object KeyGenerator {
    fun generateKeyPair(): Pair<String, String> {
        try {
            // Generate private key
            val secureRandom = SecureRandom()
            val privateKeyBytes = ByteArray(32)
            secureRandom.nextBytes(privateKeyBytes)
            
            // Use bitcoinj's ECKey to handle the secp256k1 operations
            val ecKey = ECKey.fromPrivate(privateKeyBytes)
            val publicKeyBytes = ecKey.pubKey
            
            // Convert to hex strings, remove '04' prefix from public key
            val privateKeyHex = Hex.toHexString(privateKeyBytes)
            val publicKeyHex = Hex.toHexString(publicKeyBytes.copyOfRange(1, publicKeyBytes.size))
            
            return Pair(privateKeyHex, publicKeyHex)
        } catch (e: Exception) {
            Log.e("KeyGenerator", "Error generating key pair", e)
            throw e
        }
    }

    fun privateKeyToNsec(privateKey: String): String {
        try {
            val data = Hex.decode(privateKey)
            // Convert to 5-bit words for Bech32
            val converted = convertBits(data, 8, 5, true)
            return Bech32.encode("nsec", converted)
        } catch (e: Exception) {
            Log.e("KeyGenerator", "Error converting private key to nsec", e)
            throw e
        }
    }

    fun publicKeyToNpub(publicKey: String): String {
        try {
            val data = Hex.decode(publicKey)
            // Convert to 5-bit words for Bech32
            val converted = convertBits(data, 8, 5, true)
            return Bech32.encode("npub", converted)
        } catch (e: Exception) {
            Log.e("KeyGenerator", "Error converting public key to npub", e)
            throw e
        }
    }
    
    // Helper function to convert between bit lengths
    private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val maxv = (1 shl toBits) - 1
        val result = mutableListOf<Byte>()
        
        for (b in data) {
            val value = b.toInt() and 0xff
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc shr bits) and maxv).toByte())
            }
        }
        
        if (pad) {
            if (bits > 0) {
                result.add(((acc shl (toBits - bits)) and maxv).toByte())
            }
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            throw IllegalArgumentException("Invalid padding")
        }
        
        return result.toByteArray()
    }

    fun nsecToPrivateKey(nsec: String): ByteArray {
    // Decode bech32 nsec to raw 32-byte private key
    val bech = Bech32.decode(nsec)
    val hrp = bech.hrp
    val data = bech.data
    require(hrp == "nsec") { "Invalid nsec prefix: $hrp" }
    val decoded = convertBits(data, 5, 8, false)
    require(decoded.size == 32) { "Decoded nsec must be 32 bytes, got ${decoded.size}" }
    return decoded
    }
}
