package com.hisa.data.cache

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiResumeStateStoreTest {
    @Test
    fun persistsResumeStateAcrossWrites() {
        val sharedPreferences = InMemorySharedPreferences()
        val store = UiResumeStateStore(sharedPreferences)

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

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = LinkedHashMap<String, Any>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? {
            return values[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return values[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return values[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return values[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return values[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val changes = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                putValue(key, value)
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                putValue(key, values?.toSet())
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                putValue(key, value)
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                putValue(key, value)
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                putValue(key, value)
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                putValue(key, value)
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals += key
                    changes.remove(key)
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
                removals.clear()
                changes.clear()
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                changes.forEach { (key, value) ->
                    if (value == null) {
                        values.remove(key)
                    } else {
                        values[key] = value
                    }
                }
            }

            private fun putValue(key: String?, value: Any?) {
                if (key != null) {
                    changes[key] = value
                    removals.remove(key)
                }
            }
        }
    }
}
