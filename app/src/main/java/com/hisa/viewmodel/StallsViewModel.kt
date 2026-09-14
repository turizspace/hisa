package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.nostr.NostrMarketplaceParser
import com.hisa.data.model.Stall
import com.hisa.data.repository.MarketplaceRepository
import com.hisa.data.repository.ProductRepository
import com.hisa.data.repository.ProfileRepository
import com.hisa.util.normalizeCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StallsViewModel @Inject constructor(
    private val marketplaceRepository: MarketplaceRepository,
    productRepository: ProductRepository,
    profileRepository: ProfileRepository
) : ViewModel() {
    val isLoading: StateFlow<Boolean> = marketplaceRepository.isLoading
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")

    val stalls: StateFlow<List<Stall>> = combine(
        marketplaceRepository.stalls,
        profileRepository.profiles,
        productRepository.stallPreviewImages
    ) { stalls, profiles, stallPreviewImages ->
        stalls.map { stall ->
            val ownerMetadata = profiles[stall.ownerPubkey]
            val previewKey = NostrMarketplaceParser.stallKey(stall.id, stall.ownerPubkey)
            stall.copy(
                ownerDisplayName = stall.ownerDisplayName.ifBlank {
                    ownerMetadata?.displayName?.ifBlank { null }
                        ?: ownerMetadata?.name?.ifBlank { null }
                        ?: ""
                },
                ownerProfilePicture = stall.ownerProfilePicture.ifBlank {
                    ownerMetadata?.picture?.ifBlank { null } ?: ""
                },
                picture = stall.picture.ifBlank {
                    stallPreviewImages[previewKey].orEmpty()
                }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val filteredStalls: StateFlow<List<Stall>> = combine(
        stalls,
        selectedCategory,
        searchQuery
    ) { listings, category, query ->
        val normalizedQuery = query.trim()
        listings
            .sortedByDescending { it.createdAt }
            .filter { stall ->
                category?.takeIf { it.isNotBlank() }?.let { activeCategory ->
                    stall.categories.map(::normalizeCategory).any { it == activeCategory }
                } ?: true
            }
            .filter { stall ->
                normalizedQuery.isEmpty() ||
                    stall.name.contains(normalizedQuery, ignoreCase = true) ||
                    stall.description.contains(normalizedQuery, ignoreCase = true) ||
                    stall.ownerDisplayName.contains(normalizedQuery, ignoreCase = true) ||
                    stall.categories.any { it.contains(normalizedQuery, ignoreCase = true) }
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilters(category: String?, query: String) {
        selectedCategory.value = category?.let(::normalizeCategory)?.takeIf { it.isNotBlank() }
        searchQuery.value = query
    }

    init {
        setFilters(null, "")
        marketplaceRepository.ensureStarted()
        productRepository.ensureStarted()
    }
}
