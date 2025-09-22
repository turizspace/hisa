package com.hisa

import android.app.Application
import com.google.android.datatransport.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
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
        // Hilt will automatically inject the dependencies
        // Initialize Timber. Debug builds get a DebugTree, release builds get a no-op tree.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // No-op: prevents logging sensitive data in production
            Timber.plant(object : Timber.Tree() {
                override fun isLoggable(tag: String?, priority: Int): Boolean {
                    return false
                }
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    // Intentionally no-op in release
                }
            })
        }
        
        // Debug: Monitor connection state
        // Route all incoming WS messages through the SubscriptionManager so
        // its active subscriptions receive events (EVENT, EOSE, NOTICE, ...)
        nostrClient.registerMessageHandler { message ->
            try {
                Timber.d("Routing WS message to SubscriptionManager: %s", message.take(200))
                subscriptionManager.handleMessage(message)
            } catch (e: Exception) {
                Timber.e(e, "Error routing WS message to SubscriptionManager")
            }
        }
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
