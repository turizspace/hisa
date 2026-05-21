package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Product
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrMarketplaceParser
import com.hisa.data.nostr.SubscriptionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject

class ProductsViewModel(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val stallId: String
) : ViewModel() {
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _rawEvents = MutableStateFlow<Map<String, String>>(emptyMap())
    val rawEvents: StateFlow<Map<String, String>> = _rawEvents

    init {
        subscribeToProducts()
    }

    private fun subscribeToProducts() {
        viewModelScope.launch {
            try {
                subscriptionManager.subscribe(
                    filter = SubscriptionManager.filterNIP15Products(),
                    onEvent = { event ->
                        try {
                            val product = NostrMarketplaceParser.parseProduct(event) ?: return@subscribe
                            if (product.stallId != stallId) return@subscribe

                            // Store product
                            val nextProducts = _products.value.associateBy { it.id }.toMutableMap()
                            val existing = nextProducts[product.id]
                            if (existing == null || product.createdAt >= existing.createdAt) {
                                nextProducts[product.id] = product
                            }
                            _products.value = nextProducts.values.sortedByDescending { it.createdAt }

                            // Also store formatted raw event for display
                            val formattedEvent = try {
                                JSONObject(event.toString()).toString(2)
                            } catch (e: Exception) {
                                event.toString()
                            }
                            _rawEvents.value = _rawEvents.value.toMutableMap().apply {
                                this[product.id] = formattedEvent
                            }
                        } catch (_: Exception) {}
                    }
                )
            } catch (_: Exception) {}
        }
    }
}

class ProductsViewModelFactory(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val stallId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProductsViewModel(nostrClient, subscriptionManager, stallId) as T
    }
}
