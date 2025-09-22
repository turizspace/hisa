package com.hisa.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager

class ChannelsViewModelFactory(
    private val nostrClient: NostrClient,
    private val subscriptionManager: SubscriptionManager,
    private val privateKey: ByteArray?,
    private val pubkey: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChannelsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChannelsViewModel(nostrClient, subscriptionManager, privateKey, pubkey) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
