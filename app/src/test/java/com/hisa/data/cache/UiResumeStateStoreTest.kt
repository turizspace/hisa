package com.hisa.data.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiResumeStateStoreTest {
    @Test
    fun persistsResumeStateAcrossWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = UiResumeStateStore(context, prefsName = "resume_state_store_test_${System.nanoTime()}")

        store.saveSelectedTab(3)
        store.saveFeedSearchQuery("plumbing")
        store.saveFeedCategory("repairs")
        store.saveFeedShowAllServices(true)
        store.saveStallsSearchQuery("wood")
        store.saveFeedScrollPosition(5, 123)
        store.saveStallsScrollPosition(7, 456)

        assertEquals(3, store.selectedTab)
        assertEquals("plumbing", store.feedSearchQuery)
        assertEquals("repairs", store.feedSelectedCategory)
        assertTrue(store.feedShowAllServices)
        assertEquals("wood", store.stallsSearchQuery)
        assertEquals(5, store.feedListFirstVisibleItemIndex)
        assertEquals(123, store.feedListFirstVisibleItemOffset)
        assertEquals(7, store.stallsListFirstVisibleItemIndex)
        assertEquals(456, store.stallsListFirstVisibleItemOffset)
    }
}
