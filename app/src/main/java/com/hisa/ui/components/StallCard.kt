package com.hisa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hisa.data.model.Stall
import org.json.JSONArray

@Composable
fun StallCard(stall: Stall, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val ownerHandle = stall.ownerDisplayName
        .ifBlank { stall.ownerPubkey.take(12) }
        .removePrefix("@")
    val ownerPicture = remember(stall.ownerProfilePicture) { normalizeStallImageUrl(stall.ownerProfilePicture) }
    val stallPicture = remember(stall.picture) { normalizeStallImageUrl(stall.picture) }

    ElevatedCard(
        modifier = modifier.padding(8.dp),
        onClick = { onClick?.invoke() },
        enabled = onClick != null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Owner info row with profile picture
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Owner profile section
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Owner profile picture or avatar
                    if (!ownerPicture.isNullOrBlank()) {
                        AsyncImage(
                            model = ownerPicture,
                            contentDescription = "Owner profile picture",
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.Store,
                                contentDescription = "Store icon",
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }

                    // Owner name or pubkey display
                    Column {
                        Text(
                            text = "by @$ownerHandle",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Seller",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Stall picture
            if (!stallPicture.isNullOrBlank()) {
                AsyncImage(
                    model = stallPicture,
                    contentDescription = "Stall picture",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(MaterialTheme.shapes.medium),
                    contentScale = ContentScale.Crop
                )
            }

            // Stall name (title)
            Text(
                text = stall.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Stall description
            Text(
                text = stall.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Currency and categories footer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stall.currency, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = stall.categories.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun normalizeStallImageUrl(raw: String?): String? {
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
        if (!firstFromArray.isNullOrBlank()) return upgradeStallImageUrl(firstFromArray)
    }
    val candidate = trimmed
        .lineSequence()
        .map(String::trim)
        .firstOrNull { it.isNotBlank() }
        ?: return null

    return upgradeStallImageUrl(candidate)
}

private fun upgradeStallImageUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) {
        "https://${url.substringAfter("://")}"
    } else {
        url
    }
