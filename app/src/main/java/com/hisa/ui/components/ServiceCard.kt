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
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.CurrencyBitcoin
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

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
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

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
            .padding(8.dp)
            .animateContentSize()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(14.dp),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
            .clickable(enabled = onClick != null) { onClick?.invoke(service.eventId) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column {
            // Snackbar host for copy confirmations
            Box(modifier = Modifier.fillMaxWidth()) {
                SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.TopEnd))
            }
            // Hero image area (optional)
            if (!imageUrl.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // subtle gradient to improve text contrast
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                                    startY = 60f
                                )
                            )
                    )

                    // Title + time overlay (show listing title here when image exists)
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = service.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatTimeAgo(service.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }

                    // Seller overlay (top-left) - show avatar + name like detail screen
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                if (metadata?.picture != null && metadata?.picture!!.isNotBlank()) {
                                    Image(
                                        painter = rememberAsyncImagePainter(metadata?.picture),
                                        contentDescription = "Seller picture",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant))
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = metadata?.name?.takeIf { it.isNotBlank() } ?: service.pubkey.take(8),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // small price chip on top-right
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
                        // Place price chip and overflow menu together at the top-end
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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

                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Copy pubkey") }, onClick = {
                                    clipboard.setText(AnnotatedString(service.pubkey))
                                    menuExpanded = false
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Pubkey copied") }
                                })
                                DropdownMenuItem(text = { Text("Copy npub") }, onClick = {
                                    try {
                                        val npub = com.hisa.util.KeyGenerator.publicKeyToNpub(service.pubkey)
                                        clipboard.setText(AnnotatedString(npub))
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Npub copied") }
                                    } catch (e: Exception) {
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Failed to convert pubkey") }
                                    }
                                    menuExpanded = false
                                })
                                DropdownMenuItem(text = { Text("Copy note id") }, onClick = {
                                    clipboard.setText(AnnotatedString(service.eventId))
                                    menuExpanded = false
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Note id copied") }
                                })
                            }
                        }
                    }
                }
            } else {
                // If no hero image, show a compact header with avatar + title
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            if (metadata?.picture != null && metadata?.picture!!.isNotBlank()) {
                                Image(
                                    painter = rememberAsyncImagePainter(metadata?.picture),
                                    contentDescription = "Profile Picture",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = metadata?.name?.takeIf { it.isNotBlank() }
                                    ?: service.pubkey.take(8),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatTimeAgo(service.createdAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Main content area: avoid duplicate titles. If hero image present we already show the title there.
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                val hasHero = !imageUrl.isNullOrBlank()

                // If no hero image, render the title card like in details. When hero exists, title is on the image.
                if (!hasHero) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = service.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Show either summary OR content to avoid duplication. Preference rules:
                // - If hero exists: show summary (if present) else show content preview.
                // - If no hero: show content preview (if present) else show summary.
                val summaryText = service.summary.takeIf { it.isNotBlank() }
                val contentPreview = service.content?.replace("\n", " ")?.trim()?.takeIf { it.isNotBlank() }

                val showSummaryFirst = hasHero
                if (showSummaryFirst) {
                    if (!summaryText.isNullOrBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodyMedium, // lighter than titleMedium
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                    } else if (!contentPreview.isNullOrBlank()) {
                        Text(
                            text = contentPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                } else {
                    if (!contentPreview.isNullOrBlank()) {
                        Text(
                            text = contentPreview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    } else if (!summaryText.isNullOrBlank()) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Tags row (modern chips)
                if (showTags) {
                    val topicTags = service.rawTags.filter { it.isNotEmpty() && it[0] == "t" }
                        .mapNotNull { it.getOrNull(1) as? String }
                        .distinct()
                    if (topicTags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            topicTags.take(3).forEach { tag ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(20.dp),
                                    tonalElevation = 0.dp
                                ) {
                                    Text(
                                        text = if (tag.length > 18) tag.take(15) + "..." else tag,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            if (topicTags.size > 3) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text(
                                        text = "+${topicTags.size - 3}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Location + price + actions row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Mirror ServiceDetailScreen: read rawTags for location entries
                        val locationTag = service.rawTags.find { it.isNotEmpty() && it[0] == "location" }
                        val location = locationTag?.getOrNull(1) as? String
                        if (!location.isNullOrBlank()) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "Location",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Price is shown on hero as a chip; avoid repeating it here before the message button.
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Message button (primary) + optional small secondary action could be added later
                Button(
                    onClick = { onMessageClick?.invoke(service.pubkey, metadata?.picture) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Message,
                        contentDescription = "Message Icon",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Message",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
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
