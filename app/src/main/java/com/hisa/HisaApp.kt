package com.hisa

import android.app.Application
import android.content.pm.ApplicationInfo
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.data.repository.ConversationRepository
import com.hisa.ui.util.ProfileMetaUtil
import com.hisa.util.Constants
import timber.log.Timber

@HiltAndroidApp
class HisaApp : Application() {
    @Inject
    lateinit var nostrClient: NostrClient
    
    @Inject
    lateinit var subscriptionManager: SubscriptionManager
    
    @Inject
    lateinit var profileMetaUtil: ProfileMetaUtil

    override fun onCreate() {
        super.onCreate()
        instance = this
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
        }
        ConversationRepository.initStorage(this)
    }

    companion object {
        // You might want to make this configurable
        // Read the default/seed relays from Constants; fall back to empty string if none.
        // Note: this is a runtime val (not const) so callers expecting compile-time const will need updating.
        val RELAY_URL: String = Constants.ONBOARDING_RELAYS.firstOrNull() ?: ""

        // Public application instance set on startup.
        @JvmStatic
        var instance: HisaApp? = null
            private set
    }
}
