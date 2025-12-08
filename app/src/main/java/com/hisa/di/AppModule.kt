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
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                "secure_prefs",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Clearer logging and fallback to regular SharedPreferences when EncryptedSharedPreferences isn't available
            android.util.Log.w("AppModule", "EncryptedSharedPreferences unavailable, falling back to regular SharedPreferences: ${e.localizedMessage}")
            context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
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
        metadataRepository: MetadataRepository
    ): ProfileMetaUtil {
    return ProfileMetaUtil(metadataRepository, provideApplicationCoroutineScope())
    }

}
