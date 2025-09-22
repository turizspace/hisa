package com.hisa.di

import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.viewmodel.FeedViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {
    
    @Provides
    @ViewModelScoped
    fun provideFeedViewModel(
        nostrClient: NostrClient,
        subscriptionManager: SubscriptionManager
    ): FeedViewModel {
        return FeedViewModel(nostrClient, subscriptionManager)
    }
}
