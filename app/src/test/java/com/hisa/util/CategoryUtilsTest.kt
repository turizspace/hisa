package com.hisa.util

import com.hisa.ui.components.categoryUiFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CategoryUtilsTest {
    @Test
    fun `normalizeCategory maps common aliases to canonical keys`() {
        assertEquals("plumbing", normalizeCategory("plumber"))
        assertEquals("plumbing", normalizeCategory("pipes"))
        assertEquals("electrician", normalizeCategory("electrical"))
        assertEquals("food", normalizeCategory("Restaurant"))
        assertEquals("delivery", normalizeCategory("courier"))
        assertEquals("tech", normalizeCategory("software"))
    }

    @Test
    fun `humanizeCategoryLabel returns title case labels`() {
        assertEquals("Electrician", humanizeCategoryLabel("electrician"))
        assertEquals("Local Services", humanizeCategoryLabel("local_services"))
        assertEquals("Home Repair", humanizeCategoryLabel("home-repair"))
    }

    @Test
    fun `categoryUiFor returns a CategoryUi for known and unknown keys`() {
        val plumbing = categoryUiFor("plumber")
        assertEquals("Plumbing", plumbing.label)
        assertNotNull(plumbing.icon)

        val unknown = categoryUiFor("random-tag")
        assertEquals("Random Tag", unknown.label)
        assertNotNull(unknown.icon)
    }

    @Test
    fun `normalizeCategory uses first word for multiword tags`() {
        assertEquals("plumbing", normalizeCategory("plumbing service"))
        assertEquals("electrician", normalizeCategory("electrical services"))
        assertEquals("food", normalizeCategory("food delivery"))
        assertEquals("transport", normalizeCategory("transportation network"))
    }
}
