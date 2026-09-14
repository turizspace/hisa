package com.hisa.data.repository

import com.hisa.data.model.Metadata
import com.hisa.util.Constants
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import org.bitcoinj.core.Bech32

data class DeveloperSupportProfile(
    val metadata: Metadata?,
    val lightningAddress: String?,
    val lnurlPayUrl: String?
)

@Singleton
class DeveloperSupportRepository @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val profileRepository: ProfileRepository
) {
    private val _profile = MutableStateFlow<DeveloperSupportProfile?>(null)
    val profile: StateFlow<DeveloperSupportProfile?> = _profile
    private val _sourceState = MutableStateFlow(
        DonationSourceState<DeveloperSupportProfile?>(null, status = DonationSourceStatus.EMPTY)
    )
    val sourceState: StateFlow<DonationSourceState<DeveloperSupportProfile?>> = _sourceState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Volatile
    private var started = false
    private val loadMutex = Mutex()
    private val refreshGeneration = AtomicLong(0L)

    suspend fun start() = loadMutex.withLock {
        if (started) return@withLock
        started = true
        refreshInternal()
    }

    suspend fun refresh() = loadMutex.withLock {
        started = true
        refreshGeneration.incrementAndGet()
        refreshInternal()
    }

    private suspend fun refreshInternal() = withContext(Dispatchers.IO) {
        val generation = refreshGeneration.incrementAndGet()
        _isLoading.value = true
        _sourceState.value = _sourceState.value.copy(status = DonationSourceStatus.LOADING, isLoading = true, error = null)
        try {
            val cached = profileRepository.getCachedProfile(Constants.HISA_DEV_PUBKEY)
            cached?.toLightningTarget()?.let { lightning ->
                val cachedProfile = DeveloperSupportProfile(
                    metadata = cached,
                    lightningAddress = lightning.displayValue,
                    lnurlPayUrl = lightning.lnurlPayUrl
                )
                _profile.value = cachedProfile
                _sourceState.value = DonationSourceState(
                    data = cachedProfile,
                    status = DonationSourceStatus.READY,
                    isStale = true
                )
            }
            val fetched = metadataRepository.getMetadataForPubkey(Constants.HISA_DEV_PUBKEY)
            val metadata = fetched ?: cached
            val lightning = metadata?.toLightningTarget()
            if (refreshGeneration.get() != generation) return@withContext
            _profile.value = DeveloperSupportProfile(
                metadata = metadata,
                lightningAddress = lightning?.displayValue,
                lnurlPayUrl = lightning?.lnurlPayUrl
            )
            _sourceState.value = DonationSourceState(
                data = _profile.value,
                status = if (_profile.value == null) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
                updatedAt = System.currentTimeMillis()
            )
        } catch (error: Exception) {
            _sourceState.value = _sourceState.value.copy(
                status = DonationSourceStatus.FAILED,
                isLoading = false,
                error = error.message ?: "Developer profile unavailable",
                errorCategory = DonationFailureCategory.PROFILE_METADATA_FAILURE
            )
        } finally {
            _isLoading.value = false
            _sourceState.value = _sourceState.value.copy(isLoading = false)
        }
    }

    private fun Metadata.toLightningTarget(): LightningTarget? {
        lud16?.toLnurlPayUrl()?.let { url ->
            return LightningTarget(lud16.trim(), url)
        }
        lud06?.decodeLnurl()?.let { url ->
            return LightningTarget("LNURL Pay", url)
        }
        return null
    }

    private fun String.toLnurlPayUrl(): String? {
        val parts = trim().split("@", limit = 2)
        if (parts.size != 2 || parts.any(String::isBlank)) return null
        return "https://${parts[1]}/.well-known/lnurlp/${parts[0]}"
    }

    private fun String.decodeLnurl(): String? = runCatching {
        val decoded = Bech32.decode(trim().lowercase())
        if (decoded.hrp != "lnurl") return null
        var accumulator = 0
        var bitCount = 0
        val bytes = ArrayList<Byte>()
        decoded.data.forEach { word ->
            accumulator = (accumulator shl 5) or (word.toInt() and 0x1f)
            bitCount += 5
            while (bitCount >= 8) {
                bitCount -= 8
                bytes += ((accumulator shr bitCount) and 0xff).toByte()
            }
        }
        if (bitCount >= 5 || ((accumulator shl (8 - bitCount)) and 0xff) != 0) return null
        bytes.toByteArray().decodeToString().trim().takeIf {
            it.startsWith("https://", ignoreCase = true)
        }
    }.getOrNull()

    private data class LightningTarget(
        val displayValue: String,
        val lnurlPayUrl: String
    )
}
