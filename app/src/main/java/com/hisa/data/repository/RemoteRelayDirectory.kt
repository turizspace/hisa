package com.hisa.data.repository

import android.content.Context
import com.hisa.data.nostr.EventVerifier
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrEvent
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.util.Constants
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves NIP-65 read relays for remote identities that the app depends on.
 * The cached relays supplement, rather than replace, the signed-in user's relay
 * pool. This keeps remote profile/receipt discovery reliable without mutating
 * the user's relay preferences.
 */
@Singleton
class RemoteRelayDirectory @Inject constructor(
    @ApplicationContext context: Context,
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager
) {
    private val preferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "remote_relay_directory",
        fallbackPrefsName = "remote_relay_directory_fallback"
    )
    private val cachedRelays = ConcurrentHashMap<String, List<String>>()

    suspend fun ensureRelayCoverage(pubkey: String): List<String> = withContext(Dispatchers.IO) {
        val normalizedPubkey = pubkey.trim().lowercase()
        if (normalizedPubkey.isBlank()) return@withContext emptyList()

        // Always retain a known public bootstrap path while resolving a remote
        // identity's NIP-65 event. These are supplemental, never persisted as
        // the user's chosen relay list.
        nostrClient.includeDiscoveredRelays(Constants.ONBOARDING_RELAYS)

        cachedFor(normalizedPubkey).takeIf { it.isNotEmpty() }?.let { cached ->
            nostrClient.includeDiscoveredRelays(cached)
        }

        val discovered = fetchPreferredReadRelays(normalizedPubkey)
        if (discovered.isNotEmpty()) {
            cachedRelays[normalizedPubkey] = discovered
            preferences.edit().putString(cacheKey(normalizedPubkey), discovered.joinToString("\n")).apply()
            nostrClient.includeDiscoveredRelays(discovered)
        }
        return@withContext discovered.ifEmpty { cachedFor(normalizedPubkey) }
    }

    private suspend fun fetchPreferredReadRelays(pubkey: String): List<String> {
        val events = mutableListOf<NostrEvent>()
        val finished = CompletableDeferred<Unit>()
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(NIP65_KIND))
            put("authors", JSONArray().put(pubkey))
            put("limit", 1)
        }
        val listenerId = subscriptionManager.subscribe(
            filter = filter,
            onEvent = { event ->
                if (event.kind == NIP65_KIND && event.pubkey.equals(pubkey, ignoreCase = true) && event.isValid()) {
                    synchronized(events) { events += event }
                }
            },
            onEndOfStoredEvents = { if (!finished.isCompleted) finished.complete(Unit) },
            // This request is sent to two NIP-65 relays. Do not close it when
            // the first one reaches EOSE; its peer may still return the event.
            autoCloseOnEose = false
        )
        return try {
            withTimeoutOrNull(NIP65_TIMEOUT_MS) { finished.await() }
            delay(EOSE_SETTLE_MS)
            val newest = synchronized(events) { events.maxByOrNull { it.createdAt } }
            val readRelayUrls = newest?.tags.orEmpty()
                .filter { tag -> tag.firstOrNull() == "r" && tag.getOrNull(1)?.isNotBlank() == true }
                .filter { tag -> tag.getOrNull(2)?.lowercase() != "write" }
                .map { tag -> tag[1].trim() }
            com.hisa.util.RelayHealth.normalizeRelayUrls(readRelayUrls)
        } finally {
            subscriptionManager.unsubscribe(listenerId)
        }
    }

    private fun cachedFor(pubkey: String): List<String> = cachedRelays[pubkey] ?: run {
        val stored = preferences.getString(cacheKey(pubkey), null)
            ?.split("\n")
            .orEmpty()
        val normalized = com.hisa.util.RelayHealth.normalizeRelayUrls(stored)
        cachedRelays[pubkey] = normalized
        normalized
    }

    private fun NostrEvent.isValid(): Boolean =
        EventVerifier.verifyEvent(toJson().toString()).let { it.idMatches && it.signatureValid }

    private fun cacheKey(pubkey: String) = "nip65_$pubkey"

    private companion object {
        const val NIP65_KIND = 10002
        const val NIP65_TIMEOUT_MS = 4_000L
        const val EOSE_SETTLE_MS = 500L
    }
}
