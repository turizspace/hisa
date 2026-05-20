package com.hisa.data.repository

import com.hisa.data.model.Product
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrMarketplaceParser
import com.hisa.data.nostr.SubscriptionManager
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

@Singleton
class ProductRepository @Inject constructor(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val appScope: CoroutineScope
) {
    companion object {
        private const val AUTHOR_SUBSCRIPTION_TTL_MS = 10 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L
    }

    private data class AuthorSubscription(
        val listenerId: String,
        @Volatile var lastAccessAt: Long
    )

    private val _stallPreviewImages = MutableStateFlow<Map<String, String>>(emptyMap())
    val stallPreviewImages: StateFlow<Map<String, String>> = _stallPreviewImages

    private val authorSubscriptions = ConcurrentHashMap<String, AuthorSubscription>()
    private val _productsByAuthor = MutableStateFlow<Map<String, List<Product>>>(emptyMap())
    val productsByAuthor: StateFlow<Map<String, List<Product>>> = _productsByAuthor
    private var globalListenerId: String? = null

    @Volatile
    private var started = false

    init {
        appScope.launch(Dispatchers.IO) {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                cleanupExpiredSubscriptions()
            }
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true

        nostrClient.connect()
        globalListenerId = subscriptionManager.subscribe(
            filter = SubscriptionManager.filterNIP15Products(limit = 400),
            onEvent = { event ->
                val product = NostrMarketplaceParser.parseProduct(event) ?: return@subscribe
                upsertProduct(product)
            }
        )
    }

    fun ensureAuthorSubscribed(authorPubkey: String) {
        if (authorPubkey.isBlank()) return
        ensureStarted()

        val now = System.currentTimeMillis()
        authorSubscriptions[authorPubkey]?.let { existing ->
            existing.lastAccessAt = now
            return
        }

        nostrClient.connect()
        val listenerId = subscriptionManager.subscribe(
            filter = SubscriptionManager.filterNIP15Products(
                authorPubkey = authorPubkey,
                limit = 200
            ),
            onEvent = { event ->
                val product = NostrMarketplaceParser.parseProduct(event) ?: return@subscribe
                upsertProduct(product)
            }
        )

        authorSubscriptions[authorPubkey] = AuthorSubscription(
            listenerId = listenerId,
            lastAccessAt = now
        )
    }

    private fun cleanupExpiredSubscriptions() {
        val now = System.currentTimeMillis()
        authorSubscriptions.entries
            .filter { now - it.value.lastAccessAt > AUTHOR_SUBSCRIPTION_TTL_MS }
            .forEach { (authorPubkey, subscription) ->
                subscriptionManager.unsubscribe(subscription.listenerId)
                authorSubscriptions.remove(authorPubkey)
            }
    }

    private fun upsertProduct(product: Product) {
        val authorPubkey = product.authorPubkey
        if (authorPubkey.isBlank()) return

        val updatedProductsForAuthor = _productsByAuthor.updateAndGet { current ->
            val next = current.toMutableMap()
            val productsForAuthor = (next[authorPubkey] ?: emptyList()).associateBy { it.id }.toMutableMap()
            val existing = productsForAuthor[product.id]
            if (existing == null || product.createdAt >= existing.createdAt) {
                productsForAuthor[product.id] = product
            }
            next[authorPubkey] = productsForAuthor.values.sortedByDescending { it.createdAt }
            next
        }[authorPubkey].orEmpty()

        _stallPreviewImages.update { current ->
            val next = current.toMutableMap()
            val authorPrefix = "${authorPubkey.lowercase()}:"
            next.keys.filter { it.startsWith(authorPrefix) }.forEach(next::remove)

            updatedProductsForAuthor
                .groupBy { it.stallId }
                .forEach { (stallId, products) ->
                    val preview = products
                        .sortedByDescending { it.createdAt }
                        .asSequence()
                        .flatMap { it.pictures.asSequence() }
                        .firstOrNull { it.isNotBlank() }
                    if (!preview.isNullOrBlank()) {
                        next[NostrMarketplaceParser.stallKey(stallId, authorPubkey)] = preview
                    }
                }

            next
        }
    }
}
