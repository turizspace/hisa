package com.hisa.ui.screens.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hisa.ui.util.formatTimeAgo
import com.hisa.viewmodel.ServiceDetailViewModel
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.data.model.Metadata
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.hisa.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class, ExperimentalFoundationApi::class
)
@Composable
fun ServiceDetailScreen(
    eventId: String,
    pubkey: String,
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ServiceDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val service by viewModel.service.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var showRawEvent by remember { mutableStateOf(false) }
    val rawEvent by viewModel.rawEvent.collectAsState()
    val padding = PaddingValues(16.dp)

    LaunchedEffect(eventId) {
        viewModel.loadService(eventId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Service Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            // Raw event dialog
            if (showRawEvent) {
                AlertDialog(
                    onDismissRequest = { showRawEvent = false },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Raw Event Data")
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Raw Event", rawEvent)
                                clipboard.setPrimaryClip(clip)
                                // TODO: Show copied toast
                            }) {
                                Icon(Icons.Default.ContentCopy, "Copy to clipboard")
                            }
                        }
                    },
                    text = {
                        Box(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .heightIn(max = 400.dp)
                        ) {
                            Text(
                                text = rawEvent ?: "No raw event data available",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showRawEvent = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            // Main content
            when {
                isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            service != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                    // Header Section with Image carousel and publisher info
                    val profileMetaUtil = LocalProfileMetaUtil.current
                    var publisherMeta by remember(eventId) { mutableStateOf<Metadata?>(null) }
                    // Fetch metadata as of the service creation time so the displayed name/picture
                    // matches the event's context (historical metadata)
                    LaunchedEffect(eventId, pubkey) {
                        profileMetaUtil.fetchProfileMetadata(pubkey, eventId = eventId) { result ->
                            publisherMeta = result
                        }
                    }

                    // Extract image tags (tag name 'image' expected) - allow multiple
                    val imageUrls = service!!.rawTags
                        .filter { it.isNotEmpty() && it[0] == "image" }
                        .mapNotNull { it.getOrNull(1) as? String }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        if (imageUrls.isNotEmpty()) {
                            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                            val listState = rememberLazyListState()
                            val flingBehavior: FlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

                            LazyRow(
                                state = listState,
                                flingBehavior = flingBehavior,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            ) {
                                itemsIndexed(imageUrls) { index, imageUrl ->
                                    Image(
                                        painter = rememberAsyncImagePainter(imageUrl),
                                        contentDescription = "Service Image $index",
                                        modifier = Modifier
                                            .width(screenWidth)
                                            .height(260.dp)
                                            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            // Overlay publisher info on top-left of the image header
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .align(Alignment.TopStart),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.surface)
                                        ) {
                                            if (publisherMeta?.picture != null && publisherMeta?.picture!!.isNotBlank()) {
                                                Image(
                                                    painter = rememberAsyncImagePainter(publisherMeta?.picture),
                                                    contentDescription = "Publisher",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Icon(Icons.Default.Person, contentDescription = "Publisher default", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize().padding(8.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = publisherMeta?.name ?: pubkey.take(8) + "...",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = formatTimeAgo(service!!.createdAt),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Fallback: no image, show a small header with publisher info
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                ) {
                                    if (publisherMeta?.picture != null && publisherMeta?.picture!!.isNotBlank()) {
                                        Image(
                                            painter = rememberAsyncImagePainter(publisherMeta?.picture),
                                            contentDescription = "Publisher",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(Icons.Default.Person, contentDescription = "Publisher default", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize().padding(8.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = publisherMeta?.name ?: pubkey.take(8) + "...",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = formatTimeAgo(service!!.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                }
                            }
                        }
                    }
                    
                    // Content Section
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        // Title Section with Timestamp
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                service!!.title,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // Description Section
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Description",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    service!!.content.takeIf { !it.isNullOrBlank() } ?: service!!.summary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(2.dp))
                        // Details Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                            
                            // Location
                            val locationTag = service!!.rawTags.find { it.isNotEmpty() && it[0] == "location" }
                            val location = locationTag?.getOrNull(1)
                            if (location != null && location.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = "Location",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        location,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            // Price
                            val priceTag = service!!.rawTags.find { it.size > 1 && it[0] == "price" }
                            val priceValue = priceTag?.getOrNull(1) ?: service!!.price
                            val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"
                    
                    val priceText = when {
                        priceValue.isBlank() || priceValue.equals("N/A", ignoreCase = true) -> "N/A"
                        priceValue == "0" || priceValue.lowercase() == "free" || priceValue.lowercase() == "open" -> "Free"
                        priceValue.lowercase().contains("sat") -> priceValue // Keep original if it already includes "sat" or "sats"
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
                        else -> "$priceValue $priceCurrency"
                    }
                    
                    // Price section end
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CurrencyBitcoin,
                                    contentDescription = "Price",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    priceText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                                    } // End of Row (price)
                        } // End of Column (details content)
                    } // End of Details Card

                    // Tags Section
                    val tagList = service!!.rawTags.filter { it.isNotEmpty() && it[0] == "t" }.mapNotNull { it.getOrNull(1) }
                    if (tagList.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tag,
                                        contentDescription = "Tags",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Categories (${tagList.size})",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    tagList.forEach { tag ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                                            shape = MaterialTheme.shapes.small,
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Contact Button - show pubkey name if available
                    val displayName = publisherMeta?.name ?: pubkey.take(8) + "..."
                    Button(
                        onClick = {
                            navController.navigate(Routes.DM.replace("{pubkey}", pubkey))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Message,
                            contentDescription = "Contact",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Contact $displayName")
                    }

                            Spacer(modifier = Modifier.height(16.dp)) // Bottom padding
                    } // End of Column with padding 16.dp
                } // End of Column with verticalScroll
            } // End of Column with fillMaxSize (service != null case)
            } // End of when block
        } // End of Box with fillMaxSize
    } // End of Scaffold content
} // End of ServiceDetailScreen

// Preview for Compose UI visualization
@Preview(showBackground = true)
@Composable
fun ServiceDetailScreenPreview() {
    MaterialTheme {
        ServiceDetailScreen(
            eventId = "demo",
            pubkey = "demo_pubkey",
            onBack = {},
            navController = rememberNavController()
        )
    }
}
