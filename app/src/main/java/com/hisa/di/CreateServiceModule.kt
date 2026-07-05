package com.hisa.di

import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.NostrSigningService
import com.hisa.ui.screens.create.CreateServiceViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
object CreateServiceModule {
    
    @Provides
    @ViewModelScoped
    fun provideCreateServiceViewModel(
        nostrClient: NostrClient,
        signingService: NostrSigningService
    ): CreateServiceViewModel {
        return CreateServiceViewModel(nostrClient, signingService)
    }
}
