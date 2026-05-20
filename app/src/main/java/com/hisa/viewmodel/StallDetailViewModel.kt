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
        profileRepository.profiles,
        productRepository.productsByAuthor
    ) { stalls, profiles, productsByAuthor ->
        val matchingStall = stalls.firstOrNull {
            it.ownerPubkey == ownerPubkey && (it.id == stallId || it.eventId == eventId)
        } ?: return@combine null

        val ownerMetadata = profiles[ownerPubkey]
        val previewImage = productsByAuthor[ownerPubkey]
            .orEmpty()
            .filter { it.stallId == matchingStall.id || it.stallId == stallId }
            .sortedByDescending { it.createdAt }
            .asSequence()
            .flatMap { it.pictures.asSequence() }
            .firstOrNull { it.isNotBlank() }
        matchingStall.copy(
            ownerDisplayName = matchingStall.ownerDisplayName.ifBlank {
                ownerMetadata?.displayName?.ifBlank { null }
                    ?: ownerMetadata?.name?.ifBlank { null }
                    ?: ""
            },
            ownerProfilePicture = matchingStall.ownerProfilePicture.ifBlank {
                ownerMetadata?.picture?.ifBlank { null } ?: ""
            },
            picture = matchingStall.picture.ifBlank {
                previewImage.orEmpty()
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val products: StateFlow<List<Product>> = combine(
        productRepository.productsByAuthor,
        stall
    ) { byAuthor, resolvedStall ->
        val resolvedStallId = resolvedStall?.id
        byAuthor[ownerPubkey]
            .orEmpty()
            .filter { product ->
                product.stallId == stallId || (resolvedStallId != null && product.stallId == resolvedStallId)
            }
            .sortedByDescending { it.createdAt }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        marketplaceRepository.ensureStarted()
        productRepository.ensureStarted()
        productRepository.ensureAuthorSubscribed(ownerPubkey)
        profileRepository.ensureProfiles(setOf(ownerPubkey))
    }
}
