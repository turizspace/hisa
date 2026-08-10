package com.hisa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.hisa.ui.theme.AccentPrimary
import com.hisa.ui.theme.GlassAlphaLow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hisa.data.model.Metadata
import com.hisa.data.model.ServiceListing
import com.hisa.data.model.Stall
import com.hisa.ui.util.LocalProfileRepository
import org.json.JSONArray
import com.hisa.util.formatServicePrice

@Composable
fun ServicePreviewCard(
    service: ServiceListing,
    publisherMetadata: Metadata? = null,
    modifier: Modifier = Modifier,
    showTags: Boolean = false,
    onClick: () -> Unit = {},
    hero: Boolean = false,
    featured: Boolean = false
) {
    val profileRepository = LocalProfileRepository.current
    val profiles by profileRepository.profiles.collectAsState()
    val cachedPublisherMetadata = profiles[service.pubkey]
    val resolvedPublisherMetadata = publisherMetadata ?: cachedPublisherMetadata

    val authorHandle = remember(service.pubkey, resolvedPublisherMetadata?.displayName, resolvedPublisherMetadata?.name) {
        (resolvedPublisherMetadata?.displayName?.ifBlank { null }
            ?: resolvedPublisherMetadata?.name?.ifBlank { null }
            ?: service.pubkey.take(12)).removePrefix("@")
    }
    val imageUrl = remember(service.eventId, resolvedPublisherMetadata?.picture, service.rawTags, service.content) {
        service.rawTags
            .firstOrNull { it.isNotEmpty() && it[0] == "image" }
            ?.getOrNull(1) as? String
            ?: resolvedPublisherMetadata?.picture?.takeIf { it.isNotBlank() }
            ?: Regex("(https?:\\/\\/\\S+\\.(?:png|jpe?g|gif|webp))", RegexOption.IGNORE_CASE)
                .find(service.content ?: "")
                ?.value
    }
    val supportingText = remember(service.rawTags, service.tags, showTags) {
        when {
            showTags && service.tags.isNotEmpty() -> service.tags.take(2).joinToString(" · ")
            else -> (service.rawTags.firstOrNull { it.size > 1 && it[0] == "location" }?.getOrNull(1) as? String).orEmpty()
        }
    }

    MarketplacePreviewCard(
        title = service.title,
        summary = service.summary ?: "",
        imageUrl = normalizeImageUrl(imageUrl),
        attribution = authorHandle,
        attributionImageUrl = normalizeImageUrl(resolvedPublisherMetadata?.picture),
        primaryChip = formatServicePrice(service),
        secondaryText = supportingText,
        modifier = modifier,
        onClick = onClick,
        hero = hero,
        featured = featured
    )
}

@Composable
fun StallPreviewCard(
    stall: Stall,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val ownerHandle = remember(stall.ownerDisplayName, stall.ownerPubkey) {
        stall.ownerDisplayName.ifBlank { stall.ownerPubkey.take(12) }.removePrefix("@")
    }
    val supportingText = remember(stall.categories, stall.currency) {
        when {
            stall.categories.isNotEmpty() -> stall.categories.take(2).joinToString(" · ")
            stall.currency.isNotBlank() -> stall.currency
            else -> ""
        }
    }

    MarketplacePreviewCard(
        title = stall.name,
        summary = stall.description,
        imageUrl = normalizeImageUrl(stall.picture),
        attribution = ownerHandle,
        attributionImageUrl = normalizeImageUrl(stall.ownerProfilePicture),
        primaryChip = stall.currency.ifBlank { null },
        secondaryText = supportingText,
        modifier = modifier,
        onClick = onClick
    )
}

@Composable
private fun MarketplacePreviewCard(
    title: String,
    summary: String,
    imageUrl: String?,
    attribution: String,
    attributionImageUrl: String?,
    primaryChip: String?,
    secondaryText: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    hero: Boolean = false,
    featured: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .heightIn(min = 206.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Image area - supports hero (larger, edge-to-edge) and featured badge with glow
            val imageHeight = if (modifier == Modifier) 96.dp else  if (false) 96.dp else 96.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(
                        when {
                            hero -> 220.dp
                            !imageUrl.isNullOrBlank() -> 140.dp
                            else -> 96.dp
                        }
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.22f))
                            )
                        )
                )

                if (!primaryChip.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = AccentPrimary.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = primaryChip,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                // Featured badge with subtle glow
                if (featured) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .shadow(8.dp, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = AccentPrimary.copy(alpha = 0.95f)
                    ) {
                        Text(
                            text = "FEATURED",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (secondaryText.isNotBlank()) {
                    Text(
                        text = secondaryText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                AttributionRow(
                    handle = attribution,
                    imageUrl = attributionImageUrl
                )
            }
        }
    }
}

@Composable
private fun AttributionRow(
    handle: String,
    imageUrl: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Seller avatar",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = handle.firstOrNull()?.uppercase() ?: "@",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = "by @$handle",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}



private fun normalizeImageUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("[")) {
        val firstFromArray = runCatching {
            JSONArray(trimmed).let { arr ->
                (0 until arr.length())
                    .mapNotNull { index -> arr.optString(index).trim().takeIf { it.isNotBlank() } }
                    .firstOrNull()
            }
        }.getOrNull()
        if (!firstFromArray.isNullOrBlank()) return upgradeImageUrl(firstFromArray)
    }
    val candidate = trimmed
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
        ?: return null

    return upgradeImageUrl(candidate)
}

private fun upgradeImageUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) {
        "https://${url.substringAfter("://")}"
    } else {
        url
    }
