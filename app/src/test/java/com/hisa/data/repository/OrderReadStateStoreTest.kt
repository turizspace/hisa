package com.hisa.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderReadStateStoreTest {
    @Test
    fun `markRead persists ids across snapshots`() {
        val store = OrderReadStateStore()

        store.markRead("order-1")
        assertTrue(store.isRead("order-1"))
        assertFalse(store.isRead("order-2"))

        store.markRead(listOf("order-2", "order-3"))

        assertTrue(store.isRead("order-2"))
        assertTrue(store.isRead("order-3"))
        assertTrue(store.snapshot().containsAll(setOf("order-1", "order-2", "order-3")))
    }
}
