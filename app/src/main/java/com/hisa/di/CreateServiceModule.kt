package com.hisa.di

import com.hisa.data.nostr.NostrClient
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
        nostrClient: NostrClient
    ): CreateServiceViewModel {
        return CreateServiceViewModel(nostrClient)
    }
}
