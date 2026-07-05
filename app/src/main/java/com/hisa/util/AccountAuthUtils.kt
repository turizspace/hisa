package com.hisa.util

import org.bitcoinj.core.ECKey

enum class AuthSource {
    LOCAL_NSEC,
    LOCAL_KEY_HEX,
    EXTERNAL_SIGNER,
    NONE
}

data class AccountAuthSession(
    val source: AuthSource,
    val pubkey: String?,
    val privateKeyHex: String?,
    val privateKeyBytes: ByteArray?,
    val signerPubkey: String?,
    val signerPackage: String?
) {
    val hasLocalKey: Boolean get() = privateKeyBytes != null
    val hasExternalSigner: Boolean get() = source == AuthSource.EXTERNAL_SIGNER
    val canSign: Boolean get() = hasLocalKey || hasExternalSigner
}

fun resolveAccountSession(
    pubkeyHint: String? = null,
    privateKeyHexHint: String? = null,
    nsec: String? = null,
    externalSignerPubkeyHint: String? = null,
    externalSignerPackageHint: String? = null
): AccountAuthSession {
    val normalizedPubkey = normalizeNostrPubkey(pubkeyHint)
    val normalizedSignerPubkey = normalizeNostrPubkey(externalSignerPubkeyHint ?: pubkeyHint)

    val localKeyBytes = privateKeyHexHint
        ?.let { hexToByteArrayOrNull(it, 32) }
        ?: nsec?.takeIf { it.isNotBlank() }
            ?.let { runCatching { KeyGenerator.nsecToPrivateKey(it) }.getOrNull() }
            ?.takeIf { it.size == 32 }

    if (localKeyBytes != null) {
        val derivedPubkey = deriveNostrPubkey(localKeyBytes) ?: normalizedPubkey
        val localHex = localKeyBytes.toHexString()
        val source = if (nsec != null) AuthSource.LOCAL_NSEC else AuthSource.LOCAL_KEY_HEX
        return AccountAuthSession(
            source = source,
            pubkey = derivedPubkey,
            privateKeyHex = localHex,
            privateKeyBytes = localKeyBytes,
            signerPubkey = null,
            signerPackage = null
        )
    }

    val resolvedSignerPubkey = normalizedSignerPubkey
    val resolvedSignerPackage = externalSignerPackageHint?.takeIf { it.isNotBlank() }
    if (!resolvedSignerPubkey.isNullOrBlank() && !resolvedSignerPackage.isNullOrBlank()) {
        return AccountAuthSession(
            source = AuthSource.EXTERNAL_SIGNER,
            pubkey = resolvedSignerPubkey,
            privateKeyHex = null,
            privateKeyBytes = null,
            signerPubkey = resolvedSignerPubkey,
            signerPackage = resolvedSignerPackage
        )
    }

    return AccountAuthSession(
        source = AuthSource.NONE,
        pubkey = normalizedPubkey,
        privateKeyHex = null,
        privateKeyBytes = null,
        signerPubkey = null,
        signerPackage = null
    )
}

fun deriveNostrPubkey(privateKeyBytes: ByteArray): String? {
    return runCatching {
        val ecKey = ECKey.fromPrivate(privateKeyBytes)
        val uncompressed = ecKey.decompress().pubKeyPoint.getEncoded(false)
        val xOnly = uncompressed.copyOfRange(1, 33)
        xOnly.toHexString()
    }.getOrNull()
}
