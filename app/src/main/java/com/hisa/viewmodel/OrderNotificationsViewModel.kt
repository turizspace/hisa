package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Order
import com.hisa.data.repository.OrderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class OrderNotificationsViewModel @Inject constructor(
    private val orderRepository: OrderRepository
) : ViewModel() {

    private val _currentUserPubkey = MutableStateFlow<String?>(null)
    val currentUserPubkey: StateFlow<String?> = _currentUserPubkey

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _showNotificationPanel = MutableStateFlow(false)
    val showNotificationPanel: StateFlow<Boolean> = _showNotificationPanel

    /**
     * All orders for the current seller
     */
    val orders: StateFlow<List<Order>> = orderRepository.orders

    /**
     * Unread order count for badge display
     */
    val unreadCount: StateFlow<Int> = orderRepository.unreadCount

    /**
     * Recent unread INCOMING orders only (where current user is merchant/seller).
     * These are orders received from buyers, not orders sent by the current user.
     * Maximum 5 orders are shown.
     */
    val recentUnreadOrders: StateFlow<List<Order>> = orders.combine(currentUserPubkey) { allOrders, userPubkey ->
        if (userPubkey.isNullOrBlank()) return@combine emptyList()
        allOrders
            .filter { !it.isRead && !it.isBuyer(userPubkey) } // Only show incoming orders (user is merchant)
            .sortedByDescending { it.createdAt }
            .take(5)
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * All incoming orders only (where current user is merchant/seller).
     * Filtered from the complete orders list to show only received orders.
     */
    val incomingOrders: StateFlow<List<Order>> = orders.combine(currentUserPubkey) { allOrders, userPubkey ->
        if (userPubkey.isNullOrBlank()) return@combine emptyList()
        allOrders.filter { !it.isBuyer(userPubkey) } // Only incoming orders
    }.stateIn(
        scope = viewModelScope,
        started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * Start listening for orders relevant to the current user.
     */
    fun startListeningForOrders(userPubkey: String) {
        if (_currentUserPubkey.value == userPubkey && _isListening.value) {
            return // Already listening for this pubkey
        }

        viewModelScope.launch {
            try {
                _currentUserPubkey.value = userPubkey
                orderRepository.ensureStarted(userPubkey)
                _isListening.value = true
                Timber.d("Started listening for orders for user: $userPubkey")
            } catch (e: Exception) {
                Timber.e(e, "Error starting order listener")
                _isListening.value = false
            }
        }
    }

    /**
     * Stop listening for orders
     */
    fun stopListeningForOrders() {
        orderRepository.stopListening()
        _isListening.value = false
        _currentUserPubkey.value = null
        orderRepository.clearAllOrders()
        Timber.d("Stopped listening for orders")
    }

    /**
     * Toggle notification panel visibility
     */
    fun toggleNotificationPanel() {
        _showNotificationPanel.value = !_showNotificationPanel.value
    }

    fun setNotificationPanelVisibility(visible: Boolean) {
        _showNotificationPanel.value = visible
    }

    /**
     * Mark a specific order as read
     */
    fun markOrderAsRead(orderId: String) {
        orderRepository.markAsRead(orderId)
    }

    /**
     * Mark all orders as read
     */
    fun markAllAsRead() {
        val allOrderIds = orders.value.map { it.orderId }
        orderRepository.markMultipleAsRead(allOrderIds)
    }

    /**
     * Get formatted order summary for display
     */
    fun getOrderSummary(order: Order): String {
        val amountLabel = when {
            order.currency.equals("USD", ignoreCase = true) -> "\$${order.amount}"
            order.currency.equals("SATS", ignoreCase = true) -> "${order.amount} sats"
            else -> "${order.amount} ${order.currency.uppercase()}"
        }
        return buildString {
            append("Order #${order.orderId.take(8)}: ")
            if (order.items.isNotEmpty()) {
                append("${order.items.size} item(s) - ")
            }
            append("$amountLabel from @${order.buyerDisplayName}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // OrderRepository is a singleton shared by the Messages tab, order drawer,
        // and order conversation screen. A route-scoped ViewModel being cleared
        // during navigation must not clear the shared order list.
    }
}
