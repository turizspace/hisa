package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Stall
import com.hisa.data.repository.MarketplaceRepository
import com.hisa.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StallsViewModel @Inject constructor(
    marketplaceRepository: MarketplaceRepository,
    profileRepository: ProfileRepository
) : ViewModel() {
    val stalls: StateFlow<List<Stall>> = combine(
        marketplaceRepository.stalls,
        profileRepository.profiles
    ) { stalls, profiles ->
        stalls.map { stall ->
            val ownerMetadata = profiles[stall.ownerPubkey]
            stall.copy(
                ownerDisplayName = stall.ownerDisplayName.ifBlank {
                    ownerMetadata?.displayName?.ifBlank { null }
                        ?: ownerMetadata?.name?.ifBlank { null }
                        ?: ""
                },
                ownerProfilePicture = stall.ownerProfilePicture.ifBlank {
                    ownerMetadata?.picture?.ifBlank { null } ?: ""
                }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        marketplaceRepository.ensureStarted()
    }
}
