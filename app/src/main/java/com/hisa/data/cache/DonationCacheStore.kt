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
import java.util.concurrent.ConcurrentHashMap

data class DonationCacheReadFailure(val message: String)

data class DonationCachedSection<T>(
    val items: List<T>,
    val updatedAt: Long
)

@Singleton
class DonationCacheStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = SecurePreferencesHelper.create(
        context = context,
        prefsName = "donation_cache",
        fallbackPrefsName = "donation_cache_fallback"
    )
    private val readFailures = ConcurrentHashMap<String, DonationCacheReadFailure>()

    fun readPaymentTargets(): DonationCachedSection<PaymentTarget> =
        readSection(KEY_PAYMENT_TARGETS, Section.serializer(PaymentTarget.serializer()))
            ?: DonationCachedSection(readLegacy()?.paymentTargets.orEmpty(), 0L)

    fun readBadgeAwards(): DonationCachedSection<BadgeAward> =
        readSection(KEY_BADGE_AWARDS, Section.serializer(BadgeAward.serializer()))
            ?: DonationCachedSection(readLegacy()?.badgeAwards.orEmpty(), 0L)

    fun readSponsors(): DonationCachedSection<ZapSponsor> =
        readSection(KEY_SPONSORS, Section.serializer(ZapSponsor.serializer()))
            ?: DonationCachedSection(readLegacy()?.sponsors.orEmpty(), 0L)

    fun writePaymentTargets(items: List<PaymentTarget>) {
        writeSection(KEY_PAYMENT_TARGETS, Section(CACHE_VERSION, System.currentTimeMillis(), items), Section.serializer(PaymentTarget.serializer()))
    }

    fun writeBadgeAwards(items: List<BadgeAward>) {
        writeSection(KEY_BADGE_AWARDS, Section(CACHE_VERSION, System.currentTimeMillis(), items), Section.serializer(BadgeAward.serializer()))
    }

    fun writeSponsors(items: List<ZapSponsor>) {
        writeSection(KEY_SPONSORS, Section(CACHE_VERSION, System.currentTimeMillis(), items), Section.serializer(ZapSponsor.serializer()))
    }
    fun consumeReadFailure(section: String): DonationCacheReadFailure? = readFailures.remove(section)

    fun clear() {
        preferences.edit()
            .remove(KEY_PAYMENT_TARGETS)
            .remove(KEY_BADGE_AWARDS)
            .remove(KEY_SPONSORS)
            .remove(KEY_LEGACY_SNAPSHOT)
            .apply()
    }

    @Serializable
    private data class Section<T>(
        val cacheVersion: Int,
        val updatedAt: Long,
        val items: List<T>
    )

    @Serializable
    private data class LegacySnapshot(
        val cacheVersion: Int = LEGACY_CACHE_VERSION,
        val paymentTargets: List<PaymentTarget>,
        val badgeAwards: List<BadgeAward>,
        val sponsors: List<ZapSponsor>
    )

    private fun <T> readSection(
        key: String,
        serializer: kotlinx.serialization.KSerializer<Section<T>>
    ): DonationCachedSection<T>? = preferences.getString(key, null)?.let { json ->
        runCatching {
            Json.decodeFromString(serializer, json)
                .takeIf { it.cacheVersion == CACHE_VERSION }
                ?.let { DonationCachedSection(it.items, it.updatedAt) }
        }.onFailure {
            readFailures[key] = DonationCacheReadFailure("$key cache could not be decoded")
        }.getOrNull()
    }

    private fun <T> writeSection(
        key: String,
        value: T,
        serializer: kotlinx.serialization.KSerializer<T>
    ) {
        runCatching {
            preferences.edit().putString(key, Json.encodeToString(serializer, value)).apply()
        }
    }

    private fun readLegacy(): LegacySnapshot? = preferences.getString(KEY_LEGACY_SNAPSHOT, null)?.let { json ->
        runCatching {
            Json.decodeFromString<LegacySnapshot>(json)
                .takeIf { it.cacheVersion == LEGACY_CACHE_VERSION }
        }.getOrNull()
    }

    private companion object {
        const val KEY_PAYMENT_TARGETS = "payment_targets_v1"
        const val KEY_BADGE_AWARDS = "badge_awards_v2"
        const val KEY_SPONSORS = "sponsors_v1"
        const val KEY_LEGACY_SNAPSHOT = "snapshot_v1"
        const val CACHE_VERSION = 1
        const val LEGACY_CACHE_VERSION = 4
    }
}
