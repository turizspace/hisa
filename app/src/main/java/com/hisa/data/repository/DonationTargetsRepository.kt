package com.hisa.data.repository

import com.hisa.data.cache.DonationCacheStore
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

@Serializable
data class PaymentTarget(
    val type: String,
    val address: String
) {
    val uri: String
        get() = when (type) {
            "bitcoin" -> "bitcoin:$address"
            "lightning" -> "lightning:$address"
            else -> "payto://$type/$address"
        }
}

@Serializable
data class BadgeAward(
    val awardEventId: String = "",
    val recipientPubkey: String,
    val definitionAddress: String,
    val name: String,
    val description: String,
    val imageUrl: String?
)

enum class DonationSourceStatus {
    LOADING,
    READY,
    EMPTY,
    FAILED
}

enum class DonationFailureCategory {
    RELAY_FAILURE,
    RELAY_TIMEOUT,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    CACHE_DECODE_FAILURE,
    PROFILE_METADATA_FAILURE,
    UNKNOWN
}

data class DonationSourceState<T>(
    val data: T,
    val status: DonationSourceStatus = DonationSourceStatus.EMPTY,
    val isLoading: Boolean = false,
    val error: String? = null,
    val errorCategory: DonationFailureCategory? = null,
    val isStale: Boolean = false,
    val updatedAt: Long? = null
)

object BadgeAwardPolicy {
    private val hiddenEventIds = setOf(
        "bbe4a45cea327b000e6e2b3b3e3e72d3fe2df26a53f6bc6ec4caec54c7428159"
    )

    fun isDisplayable(award: BadgeAward): Boolean =
        award.awardEventId.isNotBlank() &&
            !hiddenEventIds.contains(award.awardEventId.trim().lowercase())

    fun isHiddenEvent(eventId: String): Boolean =
        hiddenEventIds.contains(eventId.trim().lowercase())

    fun hiddenEventIds(): Set<String> = hiddenEventIds
}

@Singleton
class DonationTargetsRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileRepository: ProfileRepository,
    private val developerSupportRepository: DeveloperSupportRepository,
    private val donationCacheStore: DonationCacheStore,
    private val appScope: CoroutineScope
) {
    private val _paymentTargets = MutableStateFlow<List<PaymentTarget>>(emptyList())
    val paymentTargets: StateFlow<List<PaymentTarget>> = _paymentTargets
    private val _paymentTargetState = MutableStateFlow(DonationSourceState(emptyList<PaymentTarget>()))
    val paymentTargetState: StateFlow<DonationSourceState<List<PaymentTarget>>> = _paymentTargetState

    private val _badgeAwards = MutableStateFlow<List<BadgeAward>>(emptyList())
    val badgeAwards: StateFlow<List<BadgeAward>> = _badgeAwards
    private val _badgeState = MutableStateFlow(DonationSourceState(emptyList<BadgeAward>()))
    val badgeState: StateFlow<DonationSourceState<List<BadgeAward>>> = _badgeState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Volatile
    private var started = false
    @Volatile
    private var subscriptionId: String? = null
    @Volatile
    private var publishJob: Job? = null
    private val refreshGeneration = AtomicLong(0L)

    init {
        val cachedPaymentTargets = donationCacheStore.readPaymentTargets()
        val cachedBadgeAwards = donationCacheStore.readBadgeAwards()
        val paymentCacheFailure = donationCacheStore.consumeReadFailure("payment_targets_v1")
        val badgeCacheFailure = donationCacheStore.consumeReadFailure("badge_awards_v1")
        _paymentTargets.value = cachedPaymentTargets.items
        _badgeAwards.value = cachedBadgeAwards.items.filter(BadgeAwardPolicy::isDisplayable)
        _paymentTargetState.value = DonationSourceState(
            data = cachedPaymentTargets.items,
            status = if (cachedPaymentTargets.items.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            error = paymentCacheFailure?.message,
            errorCategory = paymentCacheFailure?.let { DonationFailureCategory.CACHE_DECODE_FAILURE },
            isStale = cachedPaymentTargets.items.isNotEmpty(), updatedAt = cachedPaymentTargets.updatedAt.takeIf { it > 0L }
        )
        _badgeState.value = DonationSourceState(
            data = _badgeAwards.value,
            status = if (_badgeAwards.value.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            error = badgeCacheFailure?.message,
            errorCategory = badgeCacheFailure?.let { DonationFailureCategory.CACHE_DECODE_FAILURE },
            isStale = _badgeAwards.value.isNotEmpty(), updatedAt = cachedBadgeAwards.updatedAt.takeIf { it > 0L }
        )
        if (_badgeAwards.value.isNotEmpty()) {
            ensureAwardProfiles(_badgeAwards.value)
        }
    }

    fun start() {
        if (started) return
        started = true
        val generation = refreshGeneration.incrementAndGet()
        paymentTargetEvents.clear()
        badgeDefinitionEvents.clear()
        badgeAwardEvents.clear()
        publishJob?.cancel()
        publishJob = null
        _isLoading.value = true
        _paymentTargetState.value = _paymentTargetState.value.copy(status = DonationSourceStatus.LOADING, isLoading = true, error = null)
        _badgeState.value = _badgeState.value.copy(status = DonationSourceStatus.LOADING, isLoading = true, error = null)
        nostrClient.refreshStoredRelays()
        nostrClient.connect()
        appScope.launch(Dispatchers.IO) {
            try {
                developerSupportRepository.start()
                if (started && refreshGeneration.get() == generation) {
                    subscribeToAuthorEvents(generation)
                }
            } catch (error: Exception) {
                if (started && refreshGeneration.get() == generation) {
                    val message = error.message ?: "Donation data unavailable"
                    _paymentTargetState.value = _paymentTargetState.value.copy(
                        status = DonationSourceStatus.FAILED,
                        isLoading = false,
                        error = message,
                        errorCategory = DonationFailureCategory.RELAY_FAILURE
                    )
                    _badgeState.value = _badgeState.value.copy(
                        status = DonationSourceStatus.FAILED,
                        isLoading = false,
                        error = message,
                        errorCategory = DonationFailureCategory.RELAY_FAILURE
                    )
                    _isLoading.value = false
                }
            }
        }
    }

    fun stop() {
        started = false
        refreshGeneration.incrementAndGet()
        publishJob?.cancel()
        publishJob = null
        subscriptionId?.let(subscriptionManager::unsubscribe)
        subscriptionId = null
        _isLoading.value = false
        _paymentTargetState.value = _paymentTargetState.value.copy(isLoading = false)
        _badgeState.value = _badgeState.value.copy(isLoading = false)
    }

    fun refresh() {
        subscriptionId?.let(subscriptionManager::unsubscribe)
        subscriptionId = null
        paymentTargetEvents.clear()
        badgeDefinitionEvents.clear()
        badgeAwardEvents.clear()
        started = false
        refreshGeneration.incrementAndGet()
        start()
    }

    private fun subscribeToAuthorEvents(generation: Long) {
        if (!started) return

        val filter = JSONObject().apply {
            put("kinds", JSONArray().apply {
                put(PAYMENT_TARGET_KIND)
                put(BADGE_DEFINITION_KIND)
                put(BADGE_AWARD_KIND)
            })
            put("authors", JSONArray().put(Constants.HISA_DEV_PUBKEY))
            put("limit", Constants.DONATION_EVENT_FETCH_LIMIT)
        }
        subscriptionId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { event ->
                if (started && refreshGeneration.get() == generation) {
                    collectDonationEvent(event)
                }
            },
            onEndOfStoredEvents = {
                // This subscription fans out to multiple relays. The first EOSE
                // is not proof that every relay has supplied its events.
                schedulePublishAfterRelaySettle(generation)
            }
        )
    }

    private fun schedulePublishAfterRelaySettle(generation: Long) {
        if (!started || refreshGeneration.get() != generation || publishJob?.isActive == true) return
        publishJob = appScope.launch(Dispatchers.Default) {
            delay(RELAY_EOSE_SETTLE_MS)
            if (started && refreshGeneration.get() == generation) {
                publish(generation)
            }
        }
    }

    private val paymentTargetEvents = ConcurrentHashMap<String, NostrEvent>()
    private val badgeDefinitionEvents = ConcurrentHashMap<String, NostrEvent>()
    private val badgeAwardEvents = ConcurrentHashMap<String, NostrEvent>()

    private fun collectDonationEvent(event: NostrEvent) {
        when (event.kind) {
            PAYMENT_TARGET_KIND -> collectPaymentTargetEvent(event)
            BADGE_DEFINITION_KIND -> collectBadgeDefinitionEvent(event)
            BADGE_AWARD_KIND -> collectBadgeAwardEvent(event)
        }
    }

    private fun collectPaymentTargetEvent(event: NostrEvent) {
        paymentTargetEvents[event.id] = event
    }

    private fun collectBadgeDefinitionEvent(event: NostrEvent) {
        badgeDefinitionEvents[event.id] = event
    }

    private fun collectBadgeAwardEvent(event: NostrEvent) {
        if (!BadgeAwardPolicy.isHiddenEvent(event.id)) {
            badgeAwardEvents[event.id] = event
        }
    }

    private fun publish(generation: Long) {
        if (!started || refreshGeneration.get() != generation) return
        publishPaymentTargets(generation)
        publishBadges(generation)
        _isLoading.value = _paymentTargetState.value.isLoading || _badgeState.value.isLoading
    }

    private fun publishPaymentTargets(generation: Long) {
        if (!started || refreshGeneration.get() != generation) return
        try {
        val author = Constants.HISA_DEV_PUBKEY
        val paymentEvent = paymentTargetEvents.values
            .filter { it.pubkey.equals(author, ignoreCase = true) }
            .maxWithOrNull(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
        val targets = paymentEvent?.tags
            ?.filter { it.size >= 3 && it[0] == "payto" }
            ?.mapNotNull { tag ->
                val type = tag[1].trim().lowercase()
                val address = tag[2].trim()
                if (type.isBlank() || address.isBlank()) null else PaymentTarget(type, address)
            }
            ?.distinctBy { "${it.type}:$it.address" }
            .orEmpty()

        val updatedAt = System.currentTimeMillis()
        _paymentTargetState.value = DonationSourceState(
            data = targets,
            status = if (targets.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            updatedAt = updatedAt
        )
        donationCacheStore.writePaymentTargets(targets)
        _paymentTargets.value = targets
        } catch (error: Exception) {
            _paymentTargetState.value = _paymentTargetState.value.copy(
                status = DonationSourceStatus.FAILED,
                isStale = _paymentTargetState.value.data.isNotEmpty(),
                error = error.message ?: "Payment targets unavailable",
                errorCategory = DonationFailureCategory.INVALID_RESPONSE
            )
        } finally {
            _paymentTargetState.value = _paymentTargetState.value.copy(isLoading = false)
        }
    }

    private fun publishBadges(generation: Long) {
        if (!started || refreshGeneration.get() != generation) return
        try {
        val author = Constants.HISA_DEV_PUBKEY
        val definitions = badgeDefinitionEvents.values
            .filter { it.pubkey.equals(author, ignoreCase = true) }
            .mapNotNull { event ->
                val dTag = event.tagValues("d").singleOrNull()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val address = "30009:${author.lowercase()}:$dTag"
                address to BadgeDefinition(
                    address = address,
                    name = event.tagValues("name").singleOrNull().orEmpty().ifBlank { dTag },
                    description = event.tagValues("description").singleOrNull().orEmpty(),
                    imageUrl = event.tagValues("image").singleOrNull()?.takeIf(String::isNotBlank)
                )
            }
            .toMap()
        val awards = badgeAwardEvents.values
            .filter { it.pubkey.equals(author, ignoreCase = true) }
            .flatMap { award ->
                val definitionAddress = award.tagValues("a").firstOrNull { it.startsWith("30009:") }
                    ?.let(::normalizeDefinitionAddress)
                    ?: return@flatMap emptyList()
                val definition = definitions[definitionAddress] ?: return@flatMap emptyList()
                award.tagValues("p")
                    .map { it.trim().lowercase() }
                    .filter(String::isNotBlank)
                    .map { recipient ->
                        BadgeAward(
                            awardEventId = award.id,
                            recipientPubkey = recipient,
                            definitionAddress = definitionAddress,
                            name = definition.name,
                            description = definition.description,
                            imageUrl = definition.imageUrl
                        )
                    }
            }
            .distinctBy { "${it.recipientPubkey}:${it.definitionAddress}" }
            .sortedBy { it.name.lowercase() }
            .filter(BadgeAwardPolicy::isDisplayable)
        _badgeAwards.value = awards
        _badgeState.value = DonationSourceState(
            data = awards,
            status = if (awards.isEmpty()) DonationSourceStatus.EMPTY else DonationSourceStatus.READY,
            updatedAt = System.currentTimeMillis()
        )
        donationCacheStore.writeBadgeAwards(awards)
        ensureAwardProfiles(awards)
        } catch (error: Exception) {
            _badgeState.value = _badgeState.value.copy(
                status = DonationSourceStatus.FAILED,
                isStale = _badgeState.value.data.isNotEmpty(),
                error = error.message ?: "Mission badges unavailable",
                errorCategory = DonationFailureCategory.INVALID_RESPONSE
            )
        } finally {
            _badgeState.value = _badgeState.value.copy(isLoading = false)
        }
    }

    private fun normalizeDefinitionAddress(address: String): String {
        val parts = address.split(":", limit = 3)
        return if (parts.size == 3) "${parts[0]}:${parts[1].lowercase()}:${parts[2]}" else address
    }

    private fun ensureAwardProfiles(awards: List<BadgeAward>) {
        val recipientPubkeys = awards
            .mapTo(mutableSetOf()) { it.recipientPubkey.trim().lowercase() }
            .filterTo(mutableSetOf()) { it.isNotBlank() && it != "unknown" }
        if (recipientPubkeys.isEmpty()) return

        // Badge recipients are remote identities just like zap sponsors. Their
        // kind-0 metadata often lives on their own NIP-65 relays, so querying
        // only the app's current relay pool produces the fallback pubkey text.
        appScope.launch(Dispatchers.IO) {
            val result = profileRepository.ensureFreshProfiles(recipientPubkeys)
            if (result.failed.isNotEmpty()) {
                _badgeState.value = _badgeState.value.copy(
                    error = "Some badge recipient profiles could not be loaded.",
                    errorCategory = DonationFailureCategory.PROFILE_METADATA_FAILURE
                )
            }
        }
    }

    private data class BadgeDefinition(
        val address: String,
        val name: String,
        val description: String,
        val imageUrl: String?
    )

    private companion object {
        const val PAYMENT_TARGET_KIND = 10133
        const val BADGE_DEFINITION_KIND = 30009
        const val BADGE_AWARD_KIND = 8
        const val RELAY_EOSE_SETTLE_MS = 750L
    }
}
