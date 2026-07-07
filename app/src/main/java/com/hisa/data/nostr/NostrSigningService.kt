package com.hisa.data.nostr

import android.content.Context
import com.hisa.data.storage.SecureStorage
import com.hisa.util.KeyGenerator
import com.hisa.util.hexToByteArrayOrNull
import com.hisa.util.normalizeNostrPubkey
import com.hisa.util.resolveAccountSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.bitcoinj.core.ECKey
import org.json.JSONObject

@Singleton
class NostrSigningService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    data class SigningContext(
        val pubkey: String?,
        val localPrivateKeyBytes: ByteArray?,
        val localPrivateKeyHex: String?,
        val signerPubkey: String?,
        val signerPackage: String?,
        val hasExternalSigner: Boolean
    ) {
        val hasLocalKey: Boolean get() = localPrivateKeyBytes != null
        val canSign: Boolean get() = hasLocalKey || hasExternalSigner

        fun requirePubkey(): String {
            return pubkey
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: signerPubkey?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: throw IllegalStateException("No signer pubkey available")
        }
    }

    suspend fun resolveSigningContext(
        pubkeyHint: String? = null,
        privateKeyHexHint: String? = null,
        localPrivateKeyBytesHint: ByteArray? = null,
        externalSignerPubkeyHint: String? = null,
        externalSignerPackageHint: String? = null
    ): SigningContext {
        val hintedPrivateKeyHex = privateKeyHexHint
            ?.takeIf { it.isNotBlank() }
            ?: localPrivateKeyBytesHint
                ?.takeIf { it.size == 32 }
                ?.toHex()

        val session = resolveAccountSession(
            pubkeyHint = pubkeyHint,
            privateKeyHexHint = hintedPrivateKeyHex,
            nsec = secureStorage.getNsec(),
            externalSignerPubkeyHint = externalSignerPubkeyHint,
            externalSignerPackageHint = externalSignerPackageHint
        )

        if (session.hasLocalKey) {
            return SigningContext(
                pubkey = session.pubkey ?: normalizePubkey(pubkeyHint),
                localPrivateKeyBytes = session.privateKeyBytes,
                localPrivateKeyHex = session.privateKeyHex,
                signerPubkey = null,
                signerPackage = null,
                hasExternalSigner = false
            )
        }

        val configuredPubkey = normalizePubkey(ExternalSignerManager.getConfiguredPubkey())
        val configuredPackage = ExternalSignerManager.getConfiguredPackage()
        val storedPubkey = normalizePubkey(secureStorage.getExternalSignerPubkey())
        val storedPackage = secureStorage.getExternalSignerPackage()

        val externalPubkey = normalizePubkey(externalSignerPubkeyHint)
            ?: configuredPubkey
            ?: storedPubkey
            ?: normalizePubkey(pubkeyHint)
        val externalPackage = externalSignerPackageHint
            ?: configuredPackage
            ?: storedPackage

        val hasExternalSigner = !externalPubkey.isNullOrBlank() &&
            !externalPackage.isNullOrBlank() &&
            externalPubkey.matches(Regex("[0-9a-f]{64}"))

        if (hasExternalSigner) {
            ExternalSignerManager.ensureConfigured(
                externalPubkey,
                externalPackage,
                context.contentResolver
            )
        }

        return SigningContext(
            pubkey = externalPubkey ?: normalizePubkey(pubkeyHint),
            localPrivateKeyBytes = null,
            localPrivateKeyHex = null,
            signerPubkey = externalPubkey,
            signerPackage = externalPackage,
            hasExternalSigner = hasExternalSigner
        )
    }

    suspend fun ensureExternalSignerConfigured(): Boolean {
        val session = resolveSigningContext()
        return session.hasExternalSigner
    }

    fun hasExternalSignerTransport(): Boolean {
        return ExternalSignerManager.hasBackgroundResolver() || ExternalSignerManager.isLauncherRegistered()
    }

    suspend fun signEvent(
        kind: Int,
        content: String,
        tags: List<List<String>>,
        pubkeyHint: String? = null,
        privateKeyHexHint: String? = null,
        localPrivateKeyBytesHint: ByteArray? = null,
        externalSignerPubkeyHint: String? = null,
        externalSignerPackageHint: String? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JSONObject {
        val signingContext = resolveSigningContext(
            pubkeyHint = pubkeyHint,
            privateKeyHexHint = privateKeyHexHint,
            localPrivateKeyBytesHint = localPrivateKeyBytesHint,
            externalSignerPubkeyHint = externalSignerPubkeyHint,
            externalSignerPackageHint = externalSignerPackageHint
        )
        return signEvent(signingContext, kind, content, tags, createdAt)
    }

    suspend fun signEvent(
        signingContext: SigningContext,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JSONObject {
        if (!signingContext.canSign) {
            throw IllegalStateException("No signing key available. Import an nsec or connect an external signer.")
        }

        return NostrEventSigner.signEvent(
            kind = kind,
            content = content,
            tags = tags,
            pubkey = signingContext.requirePubkey(),
            privKey = signingContext.localPrivateKeyBytes,
            externalSignerPubkey = signingContext.signerPubkey,
            externalSignerPackage = signingContext.signerPackage,
            contentResolver = context.contentResolver,
            createdAt = createdAt
        )
    }

    suspend fun signAndPublish(
        nostrClient: NostrClient,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        pubkeyHint: String? = null,
        privateKeyHexHint: String? = null,
        localPrivateKeyBytesHint: ByteArray? = null,
        externalSignerPubkeyHint: String? = null,
        externalSignerPackageHint: String? = null,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val eventJson = signEvent(
            kind = kind,
            content = content,
            tags = tags,
            pubkeyHint = pubkeyHint,
            privateKeyHexHint = privateKeyHexHint,
            localPrivateKeyBytesHint = localPrivateKeyBytesHint,
            externalSignerPubkeyHint = externalSignerPubkeyHint,
            externalSignerPackageHint = externalSignerPackageHint,
            createdAt = createdAt
        )
        val event = eventJson.toNostrEvent()
        nostrClient.connect()
        nostrClient.publishEvent(event)
        return event
    }

    suspend fun signAndPublish(
        nostrClient: NostrClient,
        signingContext: SigningContext,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): NostrEvent {
        val eventJson = signEvent(
            signingContext = signingContext,
            kind = kind,
            content = content,
            tags = tags,
            createdAt = createdAt
        )
        val event = eventJson.toNostrEvent()
        nostrClient.connect()
        nostrClient.publishEvent(event)
        return event
    }

    private fun readStoredNsecPrivateKey(): ByteArray? {
        val nsec = secureStorage.getNsec()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { KeyGenerator.nsecToPrivateKey(nsec) }
            .getOrNull()
            ?.takeIf { it.size == 32 }
    }

    private fun normalizePubkey(pubkey: String?): String? {
        return normalizeNostrPubkey(pubkey)
    }

    private fun derivePubkey(privateKey: ByteArray): String? {
        return runCatching {
            val ecKey = ECKey.fromPrivate(privateKey)
            val uncompressed = ecKey.decompress().pubKeyPoint.getEncoded(false)
            val xOnly = uncompressed.copyOfRange(1, 33)
            xOnly.toHex()
        }.getOrNull()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
