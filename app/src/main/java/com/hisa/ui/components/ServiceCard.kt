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
            // Hero image area - full width, prominent
            if (!imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    
                    // Overlay gradient for better text visibility
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.3f)
                                    ),
                                    startY = 80f
                                )
                            )
                    )
                }
            }

            // Compact content area below image
            Column(modifier = Modifier.padding(10.dp)) {
                // Title chip - compact
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = service.title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Tags row - smaller font
                if (showTags) {
                    val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                        .mapNotNull { it.getOrNull(1) as? String }
                        .distinct()
                    if (topicTags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            topicTags.take(2).forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 0.dp
                                ) {
                                    Text(
                                        text = if (tag.length > 10) tag.take(8) + ".." else tag,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (topicTags.size > 2) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "+${topicTags.size - 2}",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Price chip
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
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = priceChip,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Location chip - below price
                val location = service.rawTags
                    .filter { it.isNotEmpty() && it[0] == "location" }
                    .mapNotNull { it.getOrNull(1) as? String }
                    .firstOrNull()
                
                if (!location.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = location.take(20) + if (location.length > 20) ".." else "",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Posted time chip - below location
                val timeAgo = formatTimeAgo(service.createdAt)
                Surface(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = timeAgo,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Message button - full width
                Button(
                    onClick = { onMessageClick?.invoke(service.pubkey, metadata?.picture) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
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
                        modifier = Modifier.size(14.dp)
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
