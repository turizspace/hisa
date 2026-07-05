package com.hisa.viewmodel

import com.hisa.data.model.ServiceListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedViewModelTest {
    @Test
    fun filterServices_matchesCategoryAndTextQuery() {
        val services = listOf(
            ServiceListing(
                eventId = "1",
                title = "Plumbing help",
                summary = "Fast repairs",
                content = null,
                price = "10",
                tags = listOf("repair", "home"),
                pubkey = "pk1",
                createdAt = 200L
            ),
            ServiceListing(
                eventId = "2",
                title = "Garden design",
                summary = "Creative layout",
                content = null,
                price = "20",
                tags = listOf("design"),
                pubkey = "pk2",
                createdAt = 100L
            )
        )

        val filtered = FeedViewModel.filterServices(
            services = services,
            selectedCategory = "repair",
            query = "plumb"
        )

        assertEquals(1, filtered.size)
        assertEquals("1", filtered.first().eventId)
    }

    @Test
    fun deriveDisplayCategories_normalizesAndDeduplicatesTags() {
        val categories = listOf("Repair", "design")
        val services = listOf(
            ServiceListing(
                eventId = "1",
                title = "Test",
                summary = "",
                content = null,
                price = "1",
                tags = listOf("home", "Repair"),
                pubkey = "pk1",
                createdAt = 200L
            )
        )

        val derived = FeedViewModel.deriveDisplayCategories(categories, services)

        assertTrue(derived.contains("design"))
        assertTrue(derived.contains("repair"))
        assertEquals(2, derived.size)
    }
}
