package com.hisa.data.cache

import android.content.Context
import android.content.SharedPreferences
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiResumeStateStore internal constructor(
    private val sharedPreferences: SharedPreferences
) {
    @Inject constructor(
        @ApplicationContext context: Context
    ) : this(
        createSharedPreferences(context, DEFAULT_PREFS_NAME)
    )

    constructor(context: Context, prefsName: String) : this(createSharedPreferences(context, prefsName)) {
        sharedPreferences.edit().clear().apply()
    }

    var selectedTab: Int
        get() = sharedPreferences.getInt(KEY_SELECTED_TAB, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_SELECTED_TAB, value).apply()

    var searchQuery: String
        get() = sharedPreferences.getString(KEY_SEARCH_QUERY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_SEARCH_QUERY, value).apply()

    var feedSearchQuery: String
        get() = sharedPreferences.getString(KEY_FEED_SEARCH_QUERY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_FEED_SEARCH_QUERY, value).apply()

    var feedSelectedCategory: String?
        get() = sharedPreferences.getString(KEY_FEED_SELECTED_CATEGORY, null)
        set(value) {
            if (value.isNullOrBlank()) {
                sharedPreferences.edit().remove(KEY_FEED_SELECTED_CATEGORY).apply()
            } else {
                sharedPreferences.edit().putString(KEY_FEED_SELECTED_CATEGORY, value).apply()
            }
        }

    var feedShowAllServices: Boolean
        get() = sharedPreferences.getBoolean(KEY_FEED_SHOW_ALL_SERVICES, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_FEED_SHOW_ALL_SERVICES, value).apply()

    var stallsSearchQuery: String
        get() = sharedPreferences.getString(KEY_STALLS_SEARCH_QUERY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_STALLS_SEARCH_QUERY, value).apply()

    var feedListFirstVisibleItemIndex: Int
        get() = sharedPreferences.getInt(KEY_FEED_LIST_INDEX, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_FEED_LIST_INDEX, value).apply()

    var feedListFirstVisibleItemOffset: Int
        get() = sharedPreferences.getInt(KEY_FEED_LIST_OFFSET, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_FEED_LIST_OFFSET, value).apply()

    var stallsListFirstVisibleItemIndex: Int
        get() = sharedPreferences.getInt(KEY_STALLS_LIST_INDEX, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_STALLS_LIST_INDEX, value).apply()

    var stallsListFirstVisibleItemOffset: Int
        get() = sharedPreferences.getInt(KEY_STALLS_LIST_OFFSET, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_STALLS_LIST_OFFSET, value).apply()

    fun saveSelectedTab(tab: Int) {
        selectedTab = tab
    }

    fun saveSearchQuery(query: String) {
        searchQuery = query
    }

    fun saveFeedSearchQuery(query: String) {
        feedSearchQuery = query
    }

    fun saveFeedCategory(category: String?) {
        feedSelectedCategory = category
    }

    fun saveFeedShowAllServices(showAll: Boolean) {
        feedShowAllServices = showAll
    }

    fun saveStallsSearchQuery(query: String) {
        stallsSearchQuery = query
    }

    fun saveFeedScrollPosition(index: Int, offset: Int) {
        feedListFirstVisibleItemIndex = index
        feedListFirstVisibleItemOffset = offset
    }

    fun saveStallsScrollPosition(index: Int, offset: Int) {
        stallsListFirstVisibleItemIndex = index
        stallsListFirstVisibleItemOffset = offset
    }

    companion object {
        private const val DEFAULT_PREFS_NAME = "ui_resume_state"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val KEY_SEARCH_QUERY = "search_query"
        private const val KEY_FEED_SEARCH_QUERY = "feed_search_query"
        private const val KEY_FEED_SELECTED_CATEGORY = "feed_selected_category"
        private const val KEY_FEED_SHOW_ALL_SERVICES = "feed_show_all_services"
        private const val KEY_STALLS_SEARCH_QUERY = "stalls_search_query"
        private const val KEY_FEED_LIST_INDEX = "feed_list_index"
        private const val KEY_FEED_LIST_OFFSET = "feed_list_offset"
        private const val KEY_STALLS_LIST_INDEX = "stalls_list_index"
        private const val KEY_STALLS_LIST_OFFSET = "stalls_list_offset"

        private fun createSharedPreferences(context: Context, prefsName: String): SharedPreferences {
            return SecurePreferencesHelper.create(
                context = context,
                prefsName = prefsName,
                fallbackPrefsName = "${prefsName}_fallback"
            )
        }
    }
}
