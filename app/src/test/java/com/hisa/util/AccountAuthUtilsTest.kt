package com.hisa.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountAuthUtilsTest {
    @Test
    fun `resolveAccountSession derives local auth from nsec`() {
        val (privateKeyHex, pubkeyHex) = KeyGenerator.generateKeyPair()
        val nsec = KeyGenerator.privateKeyToNsec(privateKeyHex)

        val session = resolveAccountSession(
            pubkeyHint = null,
            privateKeyHexHint = null,
            nsec = nsec,
            externalSignerPubkeyHint = null,
            externalSignerPackageHint = null
        )

        assertEquals(AuthSource.LOCAL_NSEC, session.source)
        assertEquals(privateKeyHex.lowercase(), session.privateKeyHex?.lowercase())
        assertEquals(pubkeyHex.lowercase(), session.pubkey?.lowercase())
    }

    @Test
    fun `resolveAccountSession preserves external signer details`() {
        val session = resolveAccountSession(
            pubkeyHint = null,
            privateKeyHexHint = null,
            nsec = null,
            externalSignerPubkeyHint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            externalSignerPackageHint = "com.example.signer"
        )

        assertEquals(AuthSource.EXTERNAL_SIGNER, session.source)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", session.signerPubkey)
        assertEquals("com.example.signer", session.signerPackage)
    }
}
