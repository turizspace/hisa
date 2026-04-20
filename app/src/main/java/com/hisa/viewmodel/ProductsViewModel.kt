package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Product
import com.hisa.data.nostr.NostrClient
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
                            // Filter products for this stall only (stall_id tag matches)
                            val stallIdFromEvent = event.tags.find { it.isNotEmpty() && it[0] == "stall_id" }?.getOrNull(1) ?: ""
                            if (stallIdFromEvent != stallId) return@subscribe

                            // Parse product fields from tags
                            val productId = event.tags.find { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1) ?: event.id
                            val name = event.tags.find { it.isNotEmpty() && it[0] == "title" }?.getOrNull(1) ?: ""
                            val description = event.tags.find { it.isNotEmpty() && it[0] == "description" }?.getOrNull(1) ?: ""
                            val priceStr = event.tags.find { it.isNotEmpty() && it[0] == "price" }?.getOrNull(1) ?: "0"
                            val price = priceStr.toDoubleOrNull() ?: 0.0
                            val currency = event.tags.find { it.isNotEmpty() && it[0] == "currency" }?.getOrNull(1) ?: "USD"
                            val quantityStr = event.tags.find { it.isNotEmpty() && it[0] == "quantity" }?.getOrNull(1)
                            val quantity = quantityStr?.toIntOrNull()
                            val pictures = event.tags.filter { it.isNotEmpty() && it[0] == "image" }.mapNotNull { it.getOrNull(1) }
                            val categories = event.tags.filter { it.isNotEmpty() && it[0] == "t" }.mapNotNull { it.getOrNull(1) }

                            val product = Product(
                                id = productId,
                                stallId = stallId,
                                name = name,
                                description = description,
                                price = price.toString(),
                                currency = currency,
                                quantity = quantity,
                                pictures = pictures,
                                categories = categories
                            )

                            // Store product
                            _products.value = (_products.value + product).distinctBy { it.id }

                            // Also store formatted raw event for display
                            val formattedEvent = try {
                                JSONObject(event.toString()).toString(2)
                            } catch (e: Exception) {
                                event.toString()
                            }
                            _rawEvents.value = _rawEvents.value.toMutableMap().apply {
                                this[productId] = formattedEvent
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
