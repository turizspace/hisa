package com.hisa.di

import android.content.Context
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.repository.MetadataRepository
import com.hisa.ui.util.ProfileMetaUtil
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.hisa.util.Constants
import com.hisa.util.SecurePreferencesHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.coroutines.CoroutineContext

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNostrClient(
        @ApplicationContext context: Context,
        relayUrls: List<String>
    ): NostrClient {
        return NostrClient(relayUrls, context)
    }

    @Provides
    fun provideRelayUrls(@ApplicationContext context: Context): List<String> {
        // Use EncryptedSharedPreferences to load relays from user settings
        val prefs = try {
            SecurePreferencesHelper.create(
                context = context,
                prefsName = SecurePreferencesHelper.AUTH_PREFS_NAME,
                fallbackPrefsName = SecurePreferencesHelper.AUTH_PREFS_FALLBACK
            )
        } catch (e: Exception) {
            android.util.Log.w("AppModule", "EncryptedSharedPreferences unavailable, falling back to regular SharedPreferences: ${e.localizedMessage}")
            context.getSharedPreferences(SecurePreferencesHelper.AUTH_PREFS_FALLBACK, Context.MODE_PRIVATE)
        }

        val relaysString = try {
            prefs.getString("relays", null)
        } catch (e: Exception) {
            android.util.Log.w("AppModule", "Failed to read relay list from prefs: ${e.localizedMessage}")
            null
        }

        // Provide a safer default set of relays (use secure wss when possible)
        return relaysString?.split("\n")?.filter { it.isNotBlank() }
            ?: Constants.ONBOARDING_RELAYS
    }

    @Provides
    @Singleton
    fun provideApplicationCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideSubscriptionManager(
        nostrClient: NostrClient,
        appScope: CoroutineScope
    ): SubscriptionManager {
        return SubscriptionManager(nostrClient, appScope)
    }

    @Provides
    @Singleton
    fun provideMetadataRepository(
        nostrClient: NostrClient,
        subscriptionManager: com.hisa.data.nostr.SubscriptionManager
    ): MetadataRepository {
        return MetadataRepository(nostrClient, subscriptionManager)
    }

    // MessagesViewModel factory is provided by MessagesViewModelModule

    @Provides
    @Singleton
    fun provideMessageRepository(): com.hisa.data.repository.MessageRepository {
    // MessageRepository is declared as a Kotlin `object` (singleton).
    // Return its singleton instance rather than a class reference.
    return com.hisa.data.repository.MessageRepository
    }

    @Provides
    @Singleton
    fun provideProfileMetaUtil(
        metadataRepository: MetadataRepository,
        appScope: CoroutineScope
    ): ProfileMetaUtil {
    return ProfileMetaUtil(metadataRepository, appScope)
    }

}
