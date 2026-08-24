package com.hisa.data.repository

import com.hisa.data.nostr.EventVerifier
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.nostr.tagValues
import com.hisa.util.Constants
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bitcoinj.core.Bech32
import org.json.JSONArray
import org.json.JSONObject

/** A public sponsor derived from validated NIP-57 zap receipts. */
data class ZapSponsor(
    val pubkey: String,
    val totalMilliSats: Long,
    val mostRecentZapAt: Long
)

/**
 * Maintains the live sponsor list for Hisa's developer pubkey.
 *
 * NIP-57 receipt events are not payment proofs, so this repository follows the
 * NIP validation rules before displaying a sender: the receipt and embedded zap
 * request must be valid Nostr events, the receipt must be signed by the LNURL
 * provider, and the BOLT-11 amount must match the requested amount when present.
 */
@Singleton
class ZapSponsorsRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val metadataRepository: MetadataRepository,
    private val profileRepository: ProfileRepository,
    private val appScope: CoroutineScope
) {
    private data class ZapProvider(
        val pubkey: String,
        val lnurlPayUrl: String
    )

    private data class ZapContribution(
        val senderPubkey: String,
        val amountMilliSats: Long,
        val createdAt: Long
    )

    private val httpClient = OkHttpClient()
    private val contributionsByReceiptId = ConcurrentHashMap<String, ZapContribution>()
    private val _sponsors = MutableStateFlow<List<ZapSponsor>>(emptyList())
    val sponsors: StateFlow<List<ZapSponsor>> = _sponsors

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Volatile
    private var started = false

    @Volatile
    private var receiptSubscriptionId: String? = null

    fun start() {
        if (started) return
        started = true
        _isLoading.value = true
        nostrClient.refreshStoredRelays()
        nostrClient.connect()

        appScope.launch(Dispatchers.IO) {
            val provider = findZapProvider()
            if (!started || provider == null) {
                _isLoading.value = false
                return@launch
            }
            subscribeToZapReceipts(provider)
        }
    }

    fun stop() {
        started = false
        receiptSubscriptionId?.let(subscriptionManager::unsubscribe)
        receiptSubscriptionId = null
        _isLoading.value = false
    }

    private suspend fun findZapProvider(): ZapProvider? {
        profileRepository.ensureProfiles(setOf(Constants.HISA_DEV_PUBKEY))
        val metadata = metadataRepository.getMetadataForPubkey(Constants.HISA_DEV_PUBKEY)
            ?: profileRepository.getCachedProfile(Constants.HISA_DEV_PUBKEY)
            ?: return null
        val lnurlPayUrl = metadata.lud16?.toLnurlPayUrl()
            ?: metadata.lud06?.decodeLnurl()?.normalizedLnurlUrl()
            ?: return null
        return fetchZapProvider(lnurlPayUrl)
    }

    private fun fetchZapProvider(lnurlPayUrl: String): ZapProvider? = runCatching {
        val request = Request.Builder().url(lnurlPayUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body?.string().orEmpty()
            val responseJson = JSONObject(body)
            val providerPubkey = responseJson.optString("nostrPubkey")
                .trim()
                .lowercase()
            if (!responseJson.optBoolean("allowsNostr") || !providerPubkey.isHexPubkey()) {
                return null
            }

            ZapProvider(
                pubkey = providerPubkey,
                lnurlPayUrl = lnurlPayUrl.normalizedLnurlUrl()
            )
        }
    }.getOrNull()

    private fun subscribeToZapReceipts(provider: ZapProvider) {
        if (!started) return

        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(ZAP_RECEIPT_KIND))
            put("#p", JSONArray().put(Constants.HISA_DEV_PUBKEY))
            put("limit", Constants.ZAP_RECEIPT_FETCH_LIMIT)
        }
        receiptSubscriptionId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { receipt ->
                parseValidatedContribution(receipt, provider)?.let { contribution ->
                    if (contributionsByReceiptId.putIfAbsent(receipt.id, contribution) == null) {
                        publishSponsors()
                    }
                }
            },
            onEndOfStoredEvents = {
                _isLoading.value = false
            }
        )
    }

    private fun parseValidatedContribution(
        receipt: NostrEvent,
        provider: ZapProvider
    ): ZapContribution? {
        if (receipt.kind != ZAP_RECEIPT_KIND ||
            receipt.pubkey.lowercase() != provider.pubkey ||
            receipt.tagValues("p").singleOrNull()?.lowercase() != Constants.HISA_DEV_PUBKEY
        ) {
            return null
        }
        if (!receipt.isValidNostrEvent()) return null

        val request = receipt.tagValues("description")
            .singleOrNull()
            ?.toNostrEventOrNull()
            ?: return null
        if (request.kind != ZAP_REQUEST_KIND ||
            !request.isValidNostrEvent() ||
            !request.pubkey.isHexPubkey() ||
            request.tagValues("p").singleOrNull()?.lowercase() != Constants.HISA_DEV_PUBKEY
        ) {
            return null
        }

        val requestedLnurl = request.tagValues("lnurl").singleOrNull()
        if (requestedLnurl != null && requestedLnurl.decodeLnurl()?.normalizedLnurlUrl() != provider.lnurlPayUrl) {
            return null
        }

        val invoiceAmount = receipt.tagValues("bolt11")
            .singleOrNull()
            ?.let(Bolt11AmountParser::amountMilliSats)
            ?: return null
        val requestedAmount = request.tagValues("amount").singleOrNull()
        if (requestedAmount != null && requestedAmount.toLongOrNull() != invoiceAmount) return null

        return ZapContribution(
            senderPubkey = request.pubkey.lowercase(),
            amountMilliSats = invoiceAmount,
            createdAt = receipt.createdAt
        )
    }

    private fun publishSponsors() {
        val minimumMilliSats = Constants.MIN_SPONSOR_ZAP_TOTAL_SATS * MILLISATS_PER_SAT
        val sponsors = contributionsByReceiptId.values
            .groupBy { it.senderPubkey }
            .map { (pubkey, contributions) ->
                ZapSponsor(
                    pubkey = pubkey,
                    totalMilliSats = contributions.sumOf { it.amountMilliSats },
                    mostRecentZapAt = contributions.maxOf { it.createdAt }
                )
            }
            .filter { it.totalMilliSats >= minimumMilliSats }
            .sortedWith(
                compareByDescending<ZapSponsor> { it.totalMilliSats }
                    .thenByDescending { it.mostRecentZapAt }
            )
            .take(Constants.MAX_DISPLAYED_ZAP_SPONSORS)

        _sponsors.value = sponsors
        profileRepository.ensureProfiles(sponsors.mapTo(mutableSetOf()) { it.pubkey })
    }

    private fun NostrEvent.isValidNostrEvent(): Boolean =
        EventVerifier.verifyEvent(toJson().toString()).let { it.idMatches && it.signatureValid }

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
        bytes.toByteArray().decodeToString()
    }.getOrNull()

    private fun String.normalizedLnurlUrl(): String = trim().removeSuffix("/")

    private fun String.isHexPubkey(): Boolean =
        length == 64 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun String.toNostrEventOrNull(): NostrEvent? = runCatching {
        val eventJson = JSONObject(this)
        val tagsJson = eventJson.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (index in 0 until tagsJson.length()) {
                val tag = tagsJson.optJSONArray(index) ?: continue
                add(buildList {
                    for (tagIndex in 0 until tag.length()) {
                        add(tag.optString(tagIndex))
                    }
                })
            }
        }
        NostrEvent(
            id = eventJson.optString("id"),
            pubkey = eventJson.optString("pubkey"),
            createdAt = eventJson.optLong("created_at"),
            kind = eventJson.optInt("kind"),
            tags = tags,
            content = eventJson.optString("content"),
            sig = eventJson.optString("sig")
        )
    }.getOrNull()

    private companion object {
        const val ZAP_REQUEST_KIND = 9734
        const val ZAP_RECEIPT_KIND = 9735
        const val MILLISATS_PER_SAT = 1_000L
    }
}

/** Extracts the encoded BOLT-11 invoice amount without trusting relay-supplied tags. */
object Bolt11AmountParser {
    fun amountMilliSats(invoice: String): Long? {
        val humanReadablePart = invoice.trim().lowercase().substringBeforeLast('1', missingDelimiterValue = "")
        val networkPrefix = listOf("lnbcrt", "lnbc", "lntb", "lnsb")
            .firstOrNull { humanReadablePart.startsWith(it) }
            ?: return null
        val amountPart = humanReadablePart.removePrefix(networkPrefix)
        if (amountPart.isBlank()) return null

        val multiplier = amountPart.last().takeIf { it in "munp" }
        val numberPart = if (multiplier == null) amountPart else amountPart.dropLast(1)
        val amount = numberPart.toLongOrNull() ?: return null
        return when (multiplier) {
            null -> amount.safeMultiply(100_000_000_000L)
            'm' -> amount.safeMultiply(100_000_000L)
            'u' -> amount.safeMultiply(100_000L)
            'n' -> amount.safeMultiply(100L)
            'p' -> if (amount % 10L == 0L) amount / 10L else null
            else -> null
        }
    }

    private fun Long.safeMultiply(other: Long): Long? =
        runCatching { Math.multiplyExact(this, other) }.getOrNull()
}
