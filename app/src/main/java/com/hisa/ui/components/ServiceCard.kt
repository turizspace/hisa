package com.hisa.ui.components


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hisa.data.model.ServiceListing

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.rememberAsyncImagePainter
import com.hisa.data.model.Metadata
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.tooling.preview.Preview
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.ui.util.formatTimeAgo
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CornerSize

@Composable
fun ServiceCard(
    service: ServiceListing,
    showTags: Boolean = true,
    onClick: ((String) -> Unit)? = null, // Pass eventId
    onMessageClick: ((String, String?) -> Unit)? = null // Pass pubkey and profile picture
) {
    // Keep a stable key per service event
    val cardKey = "${service.eventId}.${service.pubkey}"
    val profileMetaUtil = LocalProfileMetaUtil.current
    var metadata by remember(cardKey) { mutableStateOf<Metadata?>(null) }

    LaunchedEffect(cardKey) {
        profileMetaUtil.fetchProfileMetadata(service.pubkey, eventId = service.eventId) { result ->
            metadata = result
        }
    }

    // Prefer images explicitly listed in the service (tag name 'image'), then fall back to metadata.picture or URLs inside content
    val imageUrl by remember(service.eventId, metadata?.picture, service.rawTags, service.content) {
        mutableStateOf(
            (service.rawTags
                .filter { it.isNotEmpty() && it[0] == "image" }
                .mapNotNull { it.getOrNull(1) as? String }
                .firstOrNull())
                ?: metadata?.picture?.takeIf { it.isNotBlank() }
                ?: Regex("(https?:\\/\\/\\S+\\.(?:png|jpe?g|gif|webp))", RegexOption.IGNORE_CASE)
                    .find(service.content ?: "")
                    ?.value
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(12.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
            .clickable(enabled = onClick != null) { onClick?.invoke(service.eventId) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Hero image area (optional) - clean and simple
            if (!imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Main content area - simplified with fixed heights for consistency
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                // Title - fixed height container for consistent card sizing
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    Text(
                        text = service.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Price chip - in content area
                val priceChip = run {
                    val priceTag =
                        (service.tags as? List<*>)?.mapNotNull {
                            val tag = it as? List<*>
                            if (tag != null && tag.size >= 3 && tag[0] is String && tag[0] == "price" && tag[1] is String && tag[2] is String)
                                tag.map { it as String }
                            else null
                        }?.firstOrNull()
                    if (priceTag != null) {
                        val amount = priceTag[1]
                        val currency = priceTag[2]
                        when {
                            amount.isNullOrBlank() -> "N/A"
                            amount == "0" || amount.equals("free", true) -> "Free"
                            currency.equals("USD", true) -> "$$amount"
                            currency.equals("SATS", true) -> "$amount sats"
                            else -> "$amount $currency"
                        }
                    } else {
                        when {
                            service.price.isBlank() -> null
                            service.price == "0" || service.price.equals("free", true) -> "Free"
                            service.price.all { it.isDigit() } -> {
                                val amt = service.price.toLongOrNull()
                                if (amt == null) service.price else if (amt < 1000) "$amt sats" else String.format(
                                    "%,d sats",
                                    amt
                                )
                            }
                            else -> service.price
                        }
                    }
                }

                if (!priceChip.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = priceChip,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Summary/content preview - fixed height for consistency
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    val summaryText = service.summary.takeIf { it.isNotBlank() }
                    val contentPreview = service.content?.replace("\n", " ")?.trim()?.takeIf { it.isNotBlank() }

                    if (!summaryText.isNullOrBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (!contentPreview.isNullOrBlank()) {
                        Text(
                            text = contentPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Tags (compact) - fixed height container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (showTags) {
                        val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                            .mapNotNull { it.getOrNull(1) as? String }
                            .distinct()
                        if (topicTags.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                topicTags.take(2).forEach { tag ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(16.dp),
                                        tonalElevation = 0.dp
                                    ) {
                                        Text(
                                            text = if (tag.length > 12) tag.take(10) + ".." else tag,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                if (topicTags.size > 2) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(
                                            text = "+${topicTags.size - 2}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Message button
                Button(
                    onClick = { onMessageClick?.invoke(service.pubkey, metadata?.picture) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Message,
                        contentDescription = "Message Icon",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Message",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }
        } // Column end
    } // Card end
} // <-- closes ServiceCard

@Preview(showBackground = true)
@Composable
fun ServiceCardPreview() {
    ServiceCard(
        service = com.hisa.data.repository.ServiceRepository.getServiceByEventId("demo")!!,
        onClick = {},
        onMessageClick = { _, _ -> }
    )
}
