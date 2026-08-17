package com.hisa.ui.screens.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ShippingZone
import com.hisa.domain.service.CreateMarketplaceService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class CreateServiceViewModel @Inject constructor(
    private val createMarketplaceService: CreateMarketplaceService
) : ViewModel() {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Use the centralized NostrEventSigner for canonical NIP-01 signing (Schnorr/BIP-340)

    fun createService(
        title: String,
        summary: String,
        description: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                createMarketplaceService.createService(
                    title = title,
                    summary = summary,
                    description = description,
                    tags = tags,
                    privateKeyHex = privateKeyHex,
                    pubKey = pubKey
                )

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createStall(
        stallId: String,
        title: String,
        summary: String,
        description: String,
        currency: String,
        shippingZones: List<ShippingZone>,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                createMarketplaceService.createStall(
                    stallId = stallId,
                    title = title,
                    summary = summary,
                    description = description,
                    currency = currency,
                    shippingZones = shippingZones,
                    tags = tags,
                    privateKeyHex = privateKeyHex,
                    pubKey = pubKey
                )

                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createProduct(
        stallId: String,
        name: String,
        description: String,
        price: String,
        currency: String,
        tags: List<List<String>>,
        privateKeyHex: String?,
        pubKey: String,
        onSuccess: () -> Unit,
        productId: String? = null,
        images: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                createMarketplaceService.createProduct(
                    stallId = stallId,
                    name = name,
                    description = description,
                    price = price,
                    currency = currency,
                    tags = tags,
                    privateKeyHex = privateKeyHex,
                    pubKey = pubKey,
                    productId = productId,
                    images = images
                )
                onSuccess()
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
