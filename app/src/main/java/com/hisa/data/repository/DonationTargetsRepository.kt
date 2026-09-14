package com.hisa.data.repository

import com.hisa.data.cache.DonationCacheStore
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
    private val remoteRelayDirectory: RemoteRelayDirectory,
    private val donationCacheStore: DonationCacheStore,
    private val appScope: CoroutineScope
) {
    private val _paymentTargets = MutableStateFlow<List<PaymentTarget>>(emptyList())
    val paymentTargets: StateFlow<List<PaymentTarget>> = _paymentTargets

    private val _badgeAwards = MutableStateFlow<List<BadgeAward>>(emptyList())
    val badgeAwards: StateFlow<List<BadgeAward>> = _badgeAwards

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    @Volatile
    private var started = false
    @Volatile
    private var subscriptionId: String? = null
    @Volatile
    private var publishJob: Job? = null

    init {
        donationCacheStore.read()?.let { cached ->
            _paymentTargets.value = cached.paymentTargets
            _badgeAwards.value = cached.badgeAwards.filter(BadgeAwardPolicy::isDisplayable)
        }
    }

    fun start() {
        if (started) return
        started = true
        collectedEvents.clear()
        publishJob?.cancel()
        publishJob = null
        _isLoading.value = true
        nostrClient.refreshStoredRelays()
        nostrClient.connect()
        appScope.launch(Dispatchers.IO) {
            remoteRelayDirectory.ensureRelayCoverage(Constants.HISA_DEV_PUBKEY)
            subscribeToAuthorEvents()
        }
    }

    fun stop() {
        started = false
        publishJob?.cancel()
        publishJob = null
        subscriptionId?.let(subscriptionManager::unsubscribe)
        subscriptionId = null
        _isLoading.value = false
    }

    fun refresh() {
        subscriptionId?.let(subscriptionManager::unsubscribe)
        subscriptionId = null
        collectedEvents.clear()
        started = false
        start()
    }

    private fun subscribeToAuthorEvents() {
        if (!started) return

        val filter = JSONObject().apply {
            put("kinds", JSONArray().apply {
                put(PAYMENT_TARGET_KIND)
                put(BADGE_DEFINITION_KIND)
                put(BADGE_AWARD_KIND)
                put(PROFILE_BADGES_KIND)
            })
            put("authors", JSONArray().put(Constants.HISA_DEV_PUBKEY))
            put("limit", Constants.DONATION_EVENT_FETCH_LIMIT)
        }
        subscriptionId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { event ->
                if (!BadgeAwardPolicy.isHiddenEvent(event.id)) {
                    collectedEvents[event.id] = event
                }
            },
            onEndOfStoredEvents = {
                // This subscription fans out to multiple relays. The first EOSE
                // is not proof that every relay has supplied its events.
                schedulePublishAfterRelaySettle()
            }
        )
    }

    private fun schedulePublishAfterRelaySettle() {
        if (!started || publishJob?.isActive == true) return
        publishJob = appScope.launch(Dispatchers.Default) {
            delay(RELAY_EOSE_SETTLE_MS)
            if (started) publish(collectedEvents.values.toList())
        }
    }

    private val collectedEvents = ConcurrentHashMap<String, NostrEvent>()

    private fun publish(events: List<NostrEvent>) {
        val author = Constants.HISA_DEV_PUBKEY
        val paymentEvent = events
            .filter { it.kind == PAYMENT_TARGET_KIND && it.pubkey.equals(author, ignoreCase = true) }
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

        val definitions = events
            .filter { it.kind == BADGE_DEFINITION_KIND && it.pubkey.equals(author, ignoreCase = true) }
            .mapNotNull { event ->
                val dTag = event.tagValues("d").singleOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val address = "30009:${author.lowercase()}:$dTag"
                address to BadgeDefinition(
                    address = address,
                    name = event.tagValues("name").singleOrNull().orEmpty().ifBlank { dTag },
                    description = event.tagValues("description").singleOrNull().orEmpty(),
                    imageUrl = event.tagValues("image").singleOrNull()?.takeIf(String::isNotBlank)
                )
            }
            .toMap()

        val awards = events
            .filter {
                it.kind == BADGE_AWARD_KIND &&
                    it.pubkey.equals(author, ignoreCase = true)
            }
            .flatMap { award ->
                val definitionAddress = award.tagValues("a").firstOrNull { it.startsWith("30009:") }
                    ?.let { address ->
                        val parts = address.split(":", limit = 3)
                        if (parts.size == 3) "${parts[0]}:${parts[1].lowercase()}:${parts[2]}" else address
                    }
                    ?: return@flatMap emptyList()
                val definition = definitions[definitionAddress] ?: return@flatMap emptyList()
                award.tagValues("p")
                    .map { it.trim().lowercase() }
                    .filter(String::isNotBlank)
                    .map { recipient ->
                    BadgeAward(
                        awardEventId = award.id,
                        recipientPubkey = recipient.lowercase(),
                        definitionAddress = definitionAddress,
                        name = definition.name,
                        description = definition.description,
                        imageUrl = definition.imageUrl
                    )
                }
            }
            .distinctBy { "${it.recipientPubkey}:${it.definitionAddress}" }
            .sortedBy { it.name.lowercase() }

        _paymentTargets.value = targets
        _badgeAwards.value = awards
            .filter(BadgeAwardPolicy::isDisplayable)
        donationCacheStore.read()?.let { cached ->
            donationCacheStore.write(targets, awards, cached.sponsors)
        } ?: donationCacheStore.write(targets, awards, emptyList())
        profileRepository.ensureProfiles(awards.mapTo(mutableSetOf()) { it.recipientPubkey })
        _isLoading.value = false
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
        const val PROFILE_BADGES_KIND = 10008
        const val RELAY_EOSE_SETTLE_MS = 750L
    }
}
