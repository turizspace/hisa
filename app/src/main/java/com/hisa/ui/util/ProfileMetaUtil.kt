package com.hisa.ui.util

import com.hisa.data.model.Metadata
import com.hisa.data.repository.MetadataRepository
import com.hisa.data.repository.ServiceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred

@Singleton
class ProfileMetaUtil @Inject constructor(
    private val metadataRepository: MetadataRepository,
    // appScope should be provided by Hilt (see AppModule)
    private val appScope: CoroutineScope
) {
    // Thread-safe cache keyed by pubkey or pubkey_timestamp
    private val cache: ConcurrentMap<String, Metadata?> = ConcurrentHashMap()

    // Tracks in-progress fetches so multiple callers for the same key share the work
    // inProgress keyed by pubkey so we coalesce multiple per-event requests into a single subscription
    private val inProgress: ConcurrentMap<String, CompletableDeferred<List<Pair<Long, Metadata?>>>> = ConcurrentHashMap()

    // Cache key includes optional 'until' timestamp or eventId so historical metadata can be cached separately.
    fun fetchProfileMetadata(pubkey: String, eventId: String? = null, beforeTimestamp: Long? = null, onResult: (Metadata?) -> Unit) {
        // If an eventId is provided, try to resolve its timestamp from the ServiceRepository cache.
        val resolvedTimestamp = eventId?.let { ServiceRepository.getCachedService(it)?.createdAt } ?: beforeTimestamp
        val cacheKey = when {
            eventId != null -> "${pubkey}_event_${eventId}"
            resolvedTimestamp != null -> "${pubkey}_$resolvedTimestamp"
            else -> pubkey
        }

        // Return cached result fast when available
        cache[cacheKey]?.let {
            onResult(it)
            return
        }

        // If a fetch for this pubkey is already in progress, wait for it. Otherwise start one that fetches all metadata events for the pubkey.
        val pubkeyDeferred = inProgress.computeIfAbsent(pubkey) {
            CompletableDeferred<List<Pair<Long, Metadata?>>>().also { d ->
                appScope.launch(Dispatchers.IO) {
                    try {
                        val events = try {
                            metadataRepository.getAllMetadataEventsForPubkey(pubkey)
                                .map { Pair(it.first, it.second as Metadata?) }
                        } catch (e: Exception) {
                            emptyList<Pair<Long, Metadata?>>()
                        }
                        d.complete(events)
                    } catch (e: Exception) {
                        d.completeExceptionally(e)
                    } finally {
                        // Remove the inProgress entry so future requests will refresh
                        inProgress.remove(pubkey)
                    }
                }
            }
        }

        // Deliver the requested snapshot (if available) on the main thread when ready
        appScope.launch(Dispatchers.Main) {
            try {
                val allEvents = pubkeyDeferred.await()
                // Choose the metadata event that is the latest at or before resolvedTimestamp (if given)
                val chosen = allEvents
                    .filter { (createdAt, _) -> resolvedTimestamp?.let { createdAt <= it } ?: true }
                    .maxByOrNull { it.first }

                val meta = chosen?.second
                // Cache by cacheKey for quick subsequent lookup
                cache[cacheKey] = meta
                onResult(meta)
            } catch (e: Exception) {
                onResult(null)
            }
        }
    }
}

