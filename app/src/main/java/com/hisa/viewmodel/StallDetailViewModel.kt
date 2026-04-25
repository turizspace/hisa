package com.hisa.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Product
import com.hisa.data.model.Stall
import com.hisa.data.repository.MarketplaceRepository
import com.hisa.data.repository.ProductRepository
import com.hisa.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StallDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    marketplaceRepository: MarketplaceRepository,
    productRepository: ProductRepository,
    profileRepository: ProfileRepository
) : ViewModel() {
    private val stallId: String = requireNotNull(savedStateHandle.get<String>("stallId"))
    private val ownerPubkey: String = requireNotNull(savedStateHandle.get<String>("ownerPubkey"))
    private val eventId: String = requireNotNull(savedStateHandle.get<String>("eventId"))

    val stall: StateFlow<Stall?> = combine(
        marketplaceRepository.stalls,
        profileRepository.profiles
    ) { stalls, profiles ->
        val matchingStall = stalls.firstOrNull {
            it.ownerPubkey == ownerPubkey && (it.id == stallId || it.eventId == eventId)
        } ?: return@combine null

        val ownerMetadata = profiles[ownerPubkey]
        matchingStall.copy(
            ownerDisplayName = matchingStall.ownerDisplayName.ifBlank {
                ownerMetadata?.displayName?.ifBlank { null }
                    ?: ownerMetadata?.name?.ifBlank { null }
                    ?: ""
            },
            ownerProfilePicture = matchingStall.ownerProfilePicture.ifBlank {
                ownerMetadata?.picture?.ifBlank { null } ?: ""
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val products: StateFlow<List<Product>> = productRepository.productsByAuthor.map { byAuthor ->
        byAuthor[ownerPubkey]
            .orEmpty()
            .filter { it.stallId == stallId }
            .sortedByDescending { it.createdAt }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        marketplaceRepository.ensureStarted()
        productRepository.ensureAuthorSubscribed(ownerPubkey)
        profileRepository.ensureProfiles(setOf(ownerPubkey))
    }
}
