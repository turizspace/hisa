package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hisa.data.model.Metadata
import com.hisa.data.model.Stall
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.util.ProfileMetaUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import java.util.concurrent.ConcurrentHashMap

class StallsViewModel(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileMetaUtil: ProfileMetaUtil
) : ViewModel() {
    private val _stalls = MutableStateFlow<List<Stall>>(emptyList())
    val stalls: StateFlow<List<Stall>> = _stalls

    // Cache owner profiles as they're fetched (pubkey -> Metadata)
    private val ownerProfiles = ConcurrentHashMap<String, Metadata?>()

    init {
        subscribeToStalls()
    }

    private fun subscribeToStalls() {
        viewModelScope.launch {
            try {
                subscriptionManager.subscribe(
                    filter = SubscriptionManager.filterNIP15Stalls(),
                    onEvent = { event ->
                        try {
                            val dTag = event.tags.find { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1)
                            val title = event.tags.find { it.isNotEmpty() && it[0] == "title" }?.getOrNull(1) ?: ""
                            val about = event.tags.find { it.isNotEmpty() && it[0] == "summary" }?.getOrNull(1) ?: ""
                            val image = event.tags.find { it.isNotEmpty() && it[0] == "image" }?.getOrNull(1) ?: ""
                            val categories = event.tags.filter { it.isNotEmpty() && it[0] == "t" }.mapNotNull { it.getOrNull(1) }
                            val id = dTag ?: event.id

                            // Stall name from title tag (NIP-15)
                            val stallName = title

                            val stall = Stall(
                                id = id,
                                ownerPubkey = event.pubkey,
                                name = stallName,
                                description = about,
                                picture = image,
                                categories = categories,
                                ownerDisplayName = "",  // Will be updated when profile metadata arrives
                                ownerProfilePicture = ""  // Will be updated when profile metadata arrives
                            )
                            _stalls.value = (_stalls.value + stall).distinctBy { it.id }
                            
                            // Fetch owner's kind 0 metadata to populate owner display name and picture
                            fetchOwnerProfile(event.pubkey)
                        } catch (_: Exception) {}
                    }
                )
            } catch (_: Exception) {}
        }
    }

    /**
     * Fetch owner's kind 0 profile metadata asynchronously.
     * When fetched, updates the stall name and profile info.
     */
    private fun fetchOwnerProfile(pubkey: String) {
        // Only fetch if not already cached
        if (ownerProfiles.containsKey(pubkey)) return

        profileMetaUtil.fetchProfileMetadata(pubkey) { metadata ->
            if (metadata != null) {
                ownerProfiles[pubkey] = metadata
                // Update stalls that belong to this owner with better name and profile picture
                _stalls.value = _stalls.value.map { stall ->
                    if (stall.ownerPubkey == pubkey) {
                        val betterName = metadata.displayName ?: metadata.name ?: stall.name
                        val profilePic = metadata.picture ?: ""
                        stall.copy(
                            name = if (stall.name.startsWith("Shop by")) betterName else stall.name,
                            ownerDisplayName = betterName,
                            ownerProfilePicture = profilePic
                        )
                    } else {
                        stall
                    }
                }
            }
        }
    }
}

class StallsViewModelFactory(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val profileMetaUtil: ProfileMetaUtil
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StallsViewModel(nostrClient, subscriptionManager, profileMetaUtil) as T
    }
}
