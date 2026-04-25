package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import com.hisa.data.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val feedRepository: FeedRepository
) : ViewModel() {
    val services: StateFlow<List<com.hisa.data.model.ServiceListing>> = feedRepository.services
    val categories: StateFlow<List<String>> = feedRepository.categories
    val isLoading: StateFlow<Boolean> = feedRepository.isLoading

    // Persist selected category so FeedTab can restore it after navigation
    private val _selectedCategory = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory

    fun setSelectedCategory(category: String?) {
        _selectedCategory.value = category
    }

    fun subscribeToFeed() {
        feedRepository.ensureStarted()
    }

    fun refreshFeed() {
        feedRepository.refresh()
    }
}
