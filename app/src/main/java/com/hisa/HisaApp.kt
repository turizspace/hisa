package com.hisa

import android.app.Application
// import com.hisa.BuildConfig
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicLong
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

    // Counter to sample WS messages and avoid spamming logs
    private val wsMessageCounter = AtomicLong(0)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Hilt will automatically inject the dependencies
        // Use unconditional Timber debug planting during development to ensure logs are visible
        // Initialize Timber with DebugTree for local debugging.
        // NOTE: In production builds you may want to restore a release/no-op tree.
        Timber.plant(Timber.DebugTree())
        
        // Debug: Monitor connection state
        // Route all incoming WS messages through the SubscriptionManager so
        // its active subscriptions receive events (EVENT, EOSE, NOTICE, ...)
        nostrClient.registerMessageHandler { message ->
            try {
                val count = wsMessageCounter.incrementAndGet()
                // Log a sampled message every 1000 events to avoid log spam
                if (count % 1000L == 0L) {
                    Timber.d("Routing WS message sample (#%d): %s", count, message.take(200))
                }
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
