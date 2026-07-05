package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.ServiceListing
import com.hisa.data.repository.FeedRepository
import com.hisa.util.normalizeCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepository
) : ViewModel() {
    val services: StateFlow<List<ServiceListing>> = feedRepository.services
    val categories: StateFlow<List<String>> = feedRepository.categories
    val isLoading: StateFlow<Boolean> = feedRepository.isLoading

    // Persist selected category so FeedTab can restore it after navigation
    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _showAllServices = MutableStateFlow(false)
    val showAllServices: StateFlow<Boolean> = _showAllServices

    val feedUiState: StateFlow<FeedUiState> = combine(
        services,
        categories,
        selectedCategory,
        searchQuery,
        showAllServices
    ) { serviceListings, availableCategories, selectedCategory, query, showAllServices ->
        val normalizedQuery = query.trim()
        val filteredServices = filterServices(serviceListings, selectedCategory, normalizedQuery)
        val derivedCategories = deriveDisplayCategories(availableCategories, serviceListings)
        FeedUiState(
            services = filteredServices,
            categories = derivedCategories,
            selectedCategory = selectedCategory,
            searchQuery = normalizedQuery,
            showAllServices = showAllServices,
            isLoading = false
        )
    }
        .combine(isLoading) { state, loading -> state.copy(isLoading = loading) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowAllServices(showAll: Boolean) {
        _showAllServices.value = showAll
    }

    fun subscribeToFeed() {
        feedRepository.ensureStarted()
    }

    fun refreshFeed() {
        feedRepository.refresh()
    }

    companion object {
        fun filterServices(
            services: List<ServiceListing>,
            selectedCategory: String?,
            query: String
        ): List<ServiceListing> {
            val normalizedQuery = query.trim()
            val activeCategory = selectedCategory?.takeIf { it.isNotBlank() }

            return services
                .sortedByDescending { it.createdAt }
                .filter { service ->
                    activeCategory?.let { category ->
                        service.tags.map(::normalizeCategory).any { it == category }
                    } ?: true
                }
                .filter { service ->
                    if (normalizedQuery.isEmpty()) return@filter true
                    service.title.contains(normalizedQuery, ignoreCase = true) ||
                        (service.summary ?: "").contains(normalizedQuery, ignoreCase = true) ||
                        service.tags.any { it.contains(normalizedQuery, ignoreCase = true) }
                }
        }

        fun deriveDisplayCategories(
            categories: List<String>,
            services: List<ServiceListing>
        ): List<String> {
            val serviceCategories = services
                .flatMap { it.tags }
                .map(::normalizeCategory)
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            return (categories + serviceCategories)
                .distinct()
                .sorted()
        }
    }
}

data class FeedUiState(
    val services: List<ServiceListing> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = "",
    val showAllServices: Boolean = false,
    val isLoading: Boolean = false
)
