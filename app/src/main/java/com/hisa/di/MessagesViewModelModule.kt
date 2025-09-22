package com.hisa.di

import android.os.Build
import androidx.annotation.RequiresApi
import com.hisa.viewmodel.MessagesViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped
import com.hisa.data.nostr.NostrClient
import com.hisa.data.repository.MetadataRepository
import com.hisa.data.repository.MessageRepository
import com.hisa.data.storage.SecureStorage

@Module
@InstallIn(ViewModelComponent::class)
object MessagesViewModelModule {
    @RequiresApi(Build.VERSION_CODES.O)
    @Provides
    @ViewModelScoped
    fun provideMessagesViewModel(
        nostrClient: NostrClient,
        messageRepository: MessageRepository,
        metadataRepository: MetadataRepository,
        secureStorage: SecureStorage,
        subscriptionManager: com.hisa.data.nostr.SubscriptionManager
    ): MessagesViewModel {
        return MessagesViewModel(nostrClient, messageRepository, metadataRepository, secureStorage, subscriptionManager)
    }
}
