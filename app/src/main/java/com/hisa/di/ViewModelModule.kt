package com.hisa.di

import com.hisa.data.repository.FeedRepository
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
        feedRepository: FeedRepository
    ): FeedViewModel {
        return FeedViewModel(feedRepository)
    }
}
