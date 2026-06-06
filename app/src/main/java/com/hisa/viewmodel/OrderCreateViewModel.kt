package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.OrderItem
import com.hisa.data.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OrderCreationState {
    object Idle : OrderCreationState
    object Sending : OrderCreationState
    data class Success(val orderId: String) : OrderCreationState
    data class Error(val message: String) : OrderCreationState
}

@HiltViewModel
class OrderCreateViewModel @Inject constructor(
    private val orderRepository: OrderRepository
) : ViewModel() {
    private val _state = MutableStateFlow<OrderCreationState>(OrderCreationState.Idle)
    val state: StateFlow<OrderCreationState> = _state.asStateFlow()

    fun submitOrder(
        buyerPubkey: String,
        buyerPrivateKeyHex: String?,
        sellerPubkey: String,
        subject: String,
        items: List<OrderItem>,
        amount: Long,
        currency: String = "SATS",
        notes: String,
        shippingOption: String?,
        shippingAddress: String?,
        buyerEmail: String?,
        buyerPhone: String?
    ) {
        viewModelScope.launch {
            _state.value = OrderCreationState.Sending
            try {
                val eventId = orderRepository.createOrder(
                    buyerPubkey = buyerPubkey,
                    buyerPrivateKeyHex = buyerPrivateKeyHex,
                    sellerPubkey = sellerPubkey,
                    subject = subject,
                    items = items,
                    amount = amount,
                    currency = currency,
                    notes = notes,
                    shippingOption = shippingOption,
                    shippingAddress = shippingAddress,
                    buyerEmail = buyerEmail,
                    buyerPhone = buyerPhone
                )
                _state.value = OrderCreationState.Success(eventId)
            } catch (t: Throwable) {
                _state.value = OrderCreationState.Error(t.message ?: "Failed to create order")
            }
        }
    }

    fun resetState() {
        _state.value = OrderCreationState.Idle
    }
}
