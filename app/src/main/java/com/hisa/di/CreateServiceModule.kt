package com.hisa.di

import com.hisa.domain.service.CreateMarketplaceService
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
        createMarketplaceService: CreateMarketplaceService
    ): CreateServiceViewModel {
        return CreateServiceViewModel(createMarketplaceService)
    }
}
