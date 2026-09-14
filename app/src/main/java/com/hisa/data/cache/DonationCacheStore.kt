package com.hisa.data.cache

import android.content.Context
import com.hisa.data.repository.BadgeAward
import com.hisa.data.repository.PaymentTarget
import com.hisa.data.repository.ZapSponsor
import com.hisa.util.SecurePreferencesHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class DonationCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "donation_cache",
        fallbackPrefsName = "donation_cache_fallback"
    )

    fun read(): Snapshot? {
        val json = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching {
            Json.decodeFromString<Snapshot>(json)
                .takeIf { it.cacheVersion == CACHE_VERSION }
        }.getOrNull()
    }

    fun write(
        paymentTargets: List<PaymentTarget>,
        badgeAwards: List<BadgeAward>,
        sponsors: List<ZapSponsor>
    ) {
        runCatching {
            preferences.edit()
                .putString(
                    KEY_SNAPSHOT,
                    Json.encodeToString(Snapshot(CACHE_VERSION, paymentTargets, badgeAwards, sponsors))
                )
                .apply()
        }
    }

    fun clear() {
        preferences.edit().remove(KEY_SNAPSHOT).apply()
    }

    @Serializable
    data class Snapshot(
        val cacheVersion: Int = CACHE_VERSION,
        val paymentTargets: List<PaymentTarget>,
        val badgeAwards: List<BadgeAward>,
        val sponsors: List<ZapSponsor>
    )

    private companion object {
        const val KEY_SNAPSHOT = "snapshot_v1"
        const val CACHE_VERSION = 4
    }
}
