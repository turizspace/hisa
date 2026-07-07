package com.hisa.data.repository

class OrderReadStateStore {
    private val readOrderIds = linkedSetOf<String>()

    fun markRead(orderId: String) {
        if (orderId.isBlank()) return
        readOrderIds.add(orderId)
    }

    fun markRead(orderIds: Collection<String>) {
        orderIds.filter { it.isNotBlank() }.forEach { readOrderIds.add(it) }
    }

    fun isRead(orderId: String): Boolean {
        return readOrderIds.contains(orderId)
    }

    fun snapshot(): Set<String> = readOrderIds.toSet()

    fun replaceWith(orderIds: Collection<String>) {
        readOrderIds.clear()
        markRead(orderIds)
    }

    fun clear() {
        readOrderIds.clear()
    }
}
