package com.hisa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.hisa.data.model.ServiceListing
import com.hisa.data.repository.ServiceRepository
@Composable
fun CategoryChipRow(
    categories: List<String>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onSelect(null) },
            label = { Text("All") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelect(category) },
                label = { Text(category) }
            )
        }
    }
}

@Composable
fun SectionedFeed(
    grouped: Map<String, List<ServiceListing>>,
    onItemClick: (ServiceListing) -> Unit,
    onSeeAll: (String) -> Unit,
    onMessageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    userPubkey: String? = null // Current user's pubkey to check if service is user's own
) {
    LazyColumn(modifier = modifier) {
        grouped.forEach { (category, itemsInCategory) ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(category, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSeeAll(category) }) { Text("See all") }
                }
            }

            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(itemsInCategory.take(6)) { service ->
                        Box(modifier = Modifier.width(300.dp)) {
                            CompactServiceCard(
                                service = service,
                                onClick = { onItemClick(service) },
                                onMessageClick = { pubkey -> onMessageClick(pubkey) },
                                userPubkey = userPubkey
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FeedSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp)) {
        repeat(2) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(vertical = 6.dp)
                    .clip(MaterialTheme.shapes.medium),
                color = Color(0xFFEEEEEE)
            ) {}
        }
    }
}

@Composable
fun CompactServiceCard(
    service: ServiceListing,
    onClick: () -> Unit = {},
    onMessageClick: (String) -> Unit = {},
    userPubkey: String? = null // Current user's pubkey to check if service is user's own
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(95.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier
            .fillMaxHeight()
            .padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            
            // Image section - fixed size, almost touches border
            val imageUrl = service.rawTags
                .filter { it.isNotEmpty() && it[0] == "image" }
                .mapNotNull { it.getOrNull(1) as? String }
                .firstOrNull()

            Box(modifier = Modifier
                .size(85.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (!imageUrl.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // Content section - flexible
            Column(modifier = Modifier
                .weight(1f)
                .fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                
                // Title and summary
                Column {
                    Text(
                        service.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        service.summary ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Price and action row - fixed bottom area
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    
                    // Price chip
                    val priceText = run {
                        val priceTag = service.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
                        val priceValue = priceTag?.getOrNull(1) as? String ?: service.price
                        val priceCurrency = (priceTag?.getOrNull(2) as? String)?.uppercase() ?: "SATS"
                        when {
                            priceValue.isNullOrBlank() || priceValue.equals("N/A", true) -> null
                            priceValue == "0" || priceValue.lowercase() == "free" || priceValue.lowercase() == "open" -> "Free"
                            priceValue.lowercase().contains("sat") -> priceValue
                            priceCurrency.lowercase() == "usd" -> "$${priceValue}"
                            priceCurrency.lowercase() == "sats" || priceCurrency.isBlank() -> {
                                if (priceValue.all { it.isDigit() }) {
                                    val amount = priceValue.toLongOrNull()
                                    when {
                                        amount == null -> priceValue
                                        amount < 1000 -> "${amount} sats"
                                        amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                                        else -> String.format("%.1fM sats", amount / 1000000.0)
                                    }
                                } else {
                                    priceValue
                                }
                            }
                            else -> "${priceValue} ${priceCurrency}"
                        }
                    }

                    if (!priceText.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = priceText,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Message button - hidden if this is the user's own listing
                    val isOwnListing = userPubkey?.let { it == service.pubkey } ?: false
                    if (!isOwnListing) {
                        IconButton(
                            onClick = { onMessageClick(service.pubkey) },
                            modifier = Modifier.size(28.dp),
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Message,
                                contentDescription = "Message",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactServiceCardVariantA(
    service: ServiceListing,
    onClick: () -> Unit = {},
    onMessageClick: (String) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(75.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxHeight().padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            val imageUrl = service.rawTags
                .filter { it.isNotEmpty() && it[0] == "image" }
                .mapNotNull { it.getOrNull(1) as? String }
                .firstOrNull()

            Box(modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(5.dp))) {
                if (!imageUrl.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)))
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(service.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                Spacer(modifier = Modifier.height(1.dp))
                Text(service.summary ?: "", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(2.dp))

                // tags + price + reserved action area
                val priceTextA = run {
                    val priceTag = service.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
                    val priceValue = priceTag?.getOrNull(1) as? String ?: service.price
                    val priceCurrency = (priceTag?.getOrNull(2) as? String)?.uppercase() ?: "SATS"
                    when {
                        priceValue.isNullOrBlank() || priceValue.equals("N/A", true) -> null
                        priceValue == "0" || priceValue.lowercase() == "free" || priceValue.lowercase() == "open" -> "Free"
                        priceValue.lowercase().contains("sat") -> priceValue
                        priceCurrency.lowercase() == "usd" -> "$${priceValue}"
                        priceCurrency.lowercase() == "sats" || priceCurrency.isBlank() -> {
                            if (priceValue.all { it.isDigit() }) {
                                val amount = priceValue.toLongOrNull()
                                when {
                                    amount == null -> priceValue
                                    amount < 1000 -> "${amount} sats"
                                    amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                                    else -> String.format("%.1fM sats", amount / 1000000.0)
                                }
                            } else {
                                priceValue
                            }
                        }
                        else -> "${priceValue} ${priceCurrency}"
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val priceTextA = run {
                        val priceTag = service.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
                        val priceValue = priceTag?.getOrNull(1) as? String ?: service.price
                        val priceCurrency = (priceTag?.getOrNull(2) as? String)?.uppercase() ?: "SATS"
                        when {
                            priceValue.isNullOrBlank() || priceValue.equals("N/A", true) -> null
                            priceValue == "0" || priceValue.lowercase() == "free" || priceValue.lowercase() == "open" -> "Free"
                            priceValue.lowercase().contains("sat") -> priceValue
                            priceCurrency.lowercase() == "usd" -> "$${priceValue}"
                            priceCurrency.lowercase() == "sats" || priceCurrency.isBlank() -> {
                                if (priceValue.all { it.isDigit() }) {
                                    val amount = priceValue.toLongOrNull()
                                    when {
                                        amount == null -> priceValue
                                        amount < 1000 -> "${amount} sats"
                                        amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                                        else -> String.format("%.1fM sats", amount / 1000000.0)
                                    }
                                } else {
                                    priceValue
                                }
                            }
                            else -> "${priceValue} ${priceCurrency}"
                        }
                    }

                    if (!priceTextA.isNullOrBlank()) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                            Text(text = priceTextA, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { onMessageClick(service.pubkey) }) {
                            Icon(imageVector = Icons.Filled.Message, contentDescription = "Message", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactServiceCardVariantB(
    service: ServiceListing,
    onClick: () -> Unit = {},
    onMessageClick: (String) -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.fillMaxHeight().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val imageUrl = service.rawTags
                .filter { it.isNotEmpty() && it[0] == "image" }
                .mapNotNull { it.getOrNull(1) as? String }
                .firstOrNull()

            Box(modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(6.dp))) {
                if (!imageUrl.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = "Service image",
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

            Column(modifier = Modifier.weight(1f)) {
                Text(service.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(modifier = Modifier.height(1.dp))
                Text(service.summary ?: "", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(3.dp))

                val priceTextB = run {
                    val priceTag = service.rawTags.firstOrNull { it.size > 1 && it[0] == "price" }
                    val priceValue = priceTag?.getOrNull(1) as? String ?: service.price
                    val priceCurrency = (priceTag?.getOrNull(2) as? String)?.uppercase() ?: "SATS"
                    when {
                        priceValue.isNullOrBlank() || priceValue.equals("N/A", true) -> null
                        priceValue == "0" || priceValue.lowercase() == "free" || priceValue.lowercase() == "open" -> "Free"
                        priceValue.lowercase().contains("sat") -> priceValue
                        priceCurrency.lowercase() == "usd" -> "$${priceValue}"
                        priceCurrency.lowercase() == "sats" || priceCurrency.isBlank() -> {
                            if (priceValue.all { it.isDigit() }) {
                                val amount = priceValue.toLongOrNull()
                                when {
                                    amount == null -> priceValue
                                    amount < 1000 -> "${amount} sats"
                                    amount < 1000000 -> String.format("%.1fK sats", amount / 1000.0)
                                    else -> String.format("%.1fM sats", amount / 1000000.0)
                                }
                            } else {
                                priceValue
                            }
                        }
                        else -> "${priceValue} ${priceCurrency}"
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!priceTextB.isNullOrBlank()) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
                            Text(text = priceTextB, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                        IconButton(onClick = { onMessageClick(service.pubkey) }) {
                            Icon(imageVector = Icons.Filled.Message, contentDescription = "Message", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CompactServiceCardPreviews() {
    val demo = ServiceRepository.getServiceByEventId("demo")
    demo?.let {
        Column(modifier = Modifier.padding(8.dp)) {
            CompactServiceCard(service = it, onClick = {}, onMessageClick = {})
            Spacer(modifier = Modifier.height(8.dp))
            CompactServiceCardVariantA(service = it, onClick = {}, onMessageClick = {})
            Spacer(modifier = Modifier.height(8.dp))
            CompactServiceCardVariantB(service = it, onClick = {}, onMessageClick = {})
        }
    }
}
