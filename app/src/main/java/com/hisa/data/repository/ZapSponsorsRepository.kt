package com.hisa.data.repository

import com.hisa.data.cache.DonationCacheStore
import com.hisa.data.nostr.EventVerifier
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.nostr.tagValues
import com.hisa.util.Constants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/** A public sponsor derived from validated NIP-57 zap receipts. */
@Serializable
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
 * request must be valid Nostr events, and the BOLT-11 amount must match the
 * requested amount when present.
 */
@Singleton
class ZapSponsorsRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository,
    private val donationCacheStore: DonationCacheStore,
    private val appScope: CoroutineScope
) {
    private data class ZapContribution(
        val senderPubkey: String,
        val amountMilliSats: Long,
        val createdAt: Long
    )

    private val contributionsByReceiptId = ConcurrentHashMap<String, ZapContribution>()
    private val _sponsors = MutableStateFlow<List<ZapSponsor>>(emptyList())
    val sponsors: StateFlow<List<ZapSponsor>> = _sponsors
    private val _sponsorState = MutableStateFlow(DonationSourceState(emptyList<ZapSponsor>()))
    val sponsorState: StateFlow<DonationSourceState<List<ZapSponsor>>> = _sponsorState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Volatile
    private var started = false

    @Volatile
    private var receiptSubscriptionId: String? = null
    private val refreshGeneration = AtomicLong(0L)

    init {
        val cachedSponsors = donationCacheStore.readSponsors()
        _sponsors.value = cachedSponsors.items
        val cacheFailure = donationCacheStore.consumeReadFailure("sponsors_v1")
        _sponsorState.value = DonationSourceState(
            data = _sponsors.value,
            status = if (_sponsors.value.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            error = cacheFailure?.message,
            errorCategory = cacheFailure?.let { DonationFailureCategory.CACHE_DECODE_FAILURE },
            isStale = _sponsors.value.isNotEmpty()
            ,updatedAt = cachedSponsors.updatedAt.takeIf { it > 0L }
        )
    }

    @Volatile
    private var initialSnapshotComplete = false

    @Volatile
    private var livePublishJob: Job? = null

    fun start() {
        if (started) return
        started = true
        val generation = refreshGeneration.incrementAndGet()
        initialSnapshotComplete = false
        contributionsByReceiptId.clear()
        _isLoading.value = true
        _sponsorState.value = _sponsorState.value.copy(status = DonationSourceStatus.LOADING, isLoading = true, error = null)
        nostrClient.refreshStoredRelays()
        nostrClient.connect()

        appScope.launch(Dispatchers.IO) {
            if (started && refreshGeneration.get() == generation) {
                subscribeToZapReceipts(generation)
            }
        }
    }

    fun stop() {
        started = false
        refreshGeneration.incrementAndGet()
        initialSnapshotComplete = false
        livePublishJob?.cancel()
        livePublishJob = null
        receiptSubscriptionId?.let(subscriptionManager::unsubscribe)
        receiptSubscriptionId = null
        _isLoading.value = false
        _sponsorState.value = _sponsorState.value.copy(isLoading = false)
    }

    fun refresh() {
        receiptSubscriptionId?.let(subscriptionManager::unsubscribe)
        receiptSubscriptionId = null
        contributionsByReceiptId.clear()
        started = false
        refreshGeneration.incrementAndGet()
        start()
    }

    private fun subscribeToZapReceipts(generation: Long) {
        if (!started) return

        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(ZAP_RECEIPT_KIND))
            put("#p", JSONArray().put(Constants.HISA_DEV_PUBKEY))
            put("limit", Constants.ZAP_RECEIPT_FETCH_LIMIT)
        }
        receiptSubscriptionId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { receipt ->
                if (!started || refreshGeneration.get() != generation) return@subscribe
                parseValidatedContribution(receipt)?.let { contribution ->
                    if (contributionsByReceiptId.putIfAbsent(receipt.id, contribution) == null) {
                        scheduleLivePublish()
                    }
                }
            },
            onEndOfStoredEvents = {
                // Do not expose partial ranking while historical receipts are still
                // arriving. The first visible list is a complete, aggregated snapshot.
                initialSnapshotComplete = true
                livePublishJob?.cancel()
                livePublishJob = null
                if (started && refreshGeneration.get() == generation) publishSponsors(generation)
                _isLoading.value = false
            }
        )
    }

    private fun scheduleLivePublish() {
        if (!initialSnapshotComplete || livePublishJob?.isActive == true) return
        livePublishJob = appScope.launch(Dispatchers.Default) {
            delay(LIVE_UPDATE_DEBOUNCE_MS)
            if (started && initialSnapshotComplete) publishSponsors(refreshGeneration.get())
        }
    }

    private fun parseValidatedContribution(
        receipt: NostrEvent
    ): ZapContribution? {
        if (receipt.kind != ZAP_RECEIPT_KIND ||
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

    private fun publishSponsors(generation: Long) {
        if (!started || refreshGeneration.get() != generation) return
        val minimumMilliSats = Constants.MIN_SPONSOR_ZAP_TOTAL_SATS * MILLISATS_PER_SAT
        val sponsors = contributionsByReceiptId.values.toList()
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
        _sponsorState.value = DonationSourceState(
            data = sponsors,
            status = if (sponsors.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            updatedAt = System.currentTimeMillis()
        )
        val sponsorPubkeys = sponsors.mapTo(mutableSetOf()) { it.pubkey }
        appScope.launch(Dispatchers.IO) {
            profileRepository.ensureFreshProfiles(sponsorPubkeys)
        }
        donationCacheStore.writeSponsors(sponsors)
    }

    private fun NostrEvent.isValidNostrEvent(): Boolean =
        EventVerifier.verifyEvent(toJson().toString()).let { it.idMatches && it.signatureValid }

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
        const val LIVE_UPDATE_DEBOUNCE_MS = 1_000L
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
