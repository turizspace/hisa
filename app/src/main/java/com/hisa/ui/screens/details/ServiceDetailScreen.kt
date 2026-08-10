package com.hisa.ui.screens.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.hisa.data.model.Metadata
import com.hisa.data.model.OrderItem
import com.hisa.ui.components.OrderComposerDialog
import com.hisa.ui.navigation.Routes
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.ui.util.LocalProfileRepository
import com.hisa.ui.util.formatTimeAgo
import com.hisa.util.JsonFormatter
import com.hisa.viewmodel.AuthViewModel
import com.hisa.viewmodel.OrderCreateViewModel
import com.hisa.viewmodel.OrderCreationState
import com.hisa.viewmodel.ServiceDetailViewModel
import com.hisa.util.formatServicePrice

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ServiceDetailScreen(
    eventId: String,
    pubkey: String,
    onBack: () -> Unit,
    navController: NavController,
    viewModel: ServiceDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val authViewModel: AuthViewModel = hiltViewModel()
    val orderCreateViewModel: OrderCreateViewModel = hiltViewModel()

    val service by viewModel.service.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val buyerPubkey by authViewModel.pubKey.collectAsState()
    val privateKeyHex by authViewModel.privateKey.collectAsState()
    val orderState by orderCreateViewModel.state.collectAsState()
    val rawEvent by viewModel.rawEvent.collectAsState()
    val profileRepository = LocalProfileRepository.current
    val cachedPublisherMetadata = profileRepository.getCachedProfile(pubkey)

    var showOrderDialog by remember { mutableStateOf(false) }
    var orderError by remember { mutableStateOf<String?>(null) }
    var showRawEvent by remember { mutableStateOf(false) }

    LaunchedEffect(orderState) {
        when (val currentOrderState = orderState) {
            is OrderCreationState.Success -> {
                orderError = null
                showOrderDialog = false
                Toast.makeText(context, "Order sent successfully", Toast.LENGTH_SHORT).show()
                orderCreateViewModel.resetState()
            }
            is OrderCreationState.Error -> {
                orderError = currentOrderState.message
            }
            else -> Unit
        }
    }

    LaunchedEffect(eventId) {
        viewModel.loadService(eventId, pubkey)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Service details")
                        
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showRawEvent = true }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "View raw event")
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
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy raw event")
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
                                text = JsonFormatter.prettyPrint(rawEvent) ?: "No raw event data available",
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
                    val serviceData = service!!
                    val profileMetaUtil = LocalProfileMetaUtil.current
                    var publisherMeta by remember(eventId) { mutableStateOf<Metadata?>(cachedPublisherMetadata) }

                    LaunchedEffect(eventId, pubkey) {
                        profileMetaUtil.fetchProfileMetadata(pubkey, eventId = eventId) { result ->
                            if (result != null) {
                                publisherMeta = result
                            }
                        }
                    }

                    val imageUrls = serviceData.rawTags
                        .filter { it.isNotEmpty() && it[0] == "image" }
                        .mapNotNull { it.getOrNull(1) as? String }
                    val locationTag = serviceData.rawTags.find { it.isNotEmpty() && it[0] == "location" }
                    val location = locationTag?.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val priceTag = serviceData.rawTags.find { it.size > 1 && it[0] == "price" }
                    val priceValue = priceTag?.getOrNull(1) ?: serviceData.price
                    val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"
                    val priceText = formatServicePrice(priceValue, priceCurrency)
                    val tagList = serviceData.rawTags.filter { it.isNotEmpty() && it[0] == "t" }.mapNotNull { it.getOrNull(1) }
                    val displayName = publisherMeta?.name ?: pubkey.take(8) + "..."

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                                        .height(220.dp)
                                ) {
                                    itemsIndexed(imageUrls) { index, imageUrl ->
                                        Image(
                                            painter = rememberAsyncImagePainter(imageUrl),
                                            contentDescription = "Service image $index",
                                            modifier = Modifier
                                                .width(screenWidth)
                                                .height(220.dp)
                                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.scrim.copy(alpha = 0.42f))
                                            )
                                        )
                                )

                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(12.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                        tonalElevation = 1.dp
                                    ) {
                                        Text(
                                            text = priceText,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = serviceData.title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color.White,
                                        modifier = Modifier.padding(end = 24.dp)
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                )
                                            )
                                        )
                                        .padding(16.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                        tonalElevation = 1.dp
                                    ) {
                                        Text(
                                            text = priceText,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    }
                                        Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = serviceData.title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = serviceData.summary ?: "A local service ready to help.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(24.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer)
                                    ) {
                                        if (publisherMeta?.picture.isNullOrBlank()) {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(10.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Image(
                                                painter = rememberAsyncImagePainter(publisherMeta?.picture),
                                                contentDescription = "Publisher avatar",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            text = "Posted ${formatTimeAgo(serviceData.createdAt)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "About this service",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = serviceData.content.takeIf { !it.isNullOrBlank() } ?: serviceData.summary ?: "No description provided.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 24.sp
                                    )
                                }
                            }

                            Card(
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!location.isNullOrBlank()) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(location, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CurrencyBitcoin,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(priceText, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                }
                            }

                            if (tagList.isNotEmpty()) {
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Default.Tag,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Tags & categories",
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(10.dp))
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            tagList.forEach { tag ->
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                                                ) {
                                                    Text(
                                                        text = tag,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "Ready to connect?",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Message the seller directly or place an order in one tap.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                Button(
                                    onClick = {
                                        navController.navigate(Routes.DM.replace("{pubkey}", pubkey))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Message $displayName", style = MaterialTheme.typography.labelLarge)
                                }

                                Button(
                                    onClick = { showOrderDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.CurrencyBitcoin, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Order this service", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }

            if (service != null && showOrderDialog) {
                val priceTag = service!!.rawTags.find { it.size > 1 && it[0] == "price" }
                val priceValue = priceTag?.getOrNull(1) ?: service!!.price
                val priceCurrency = priceTag?.getOrNull(2)?.uppercase() ?: "SATS"
                OrderComposerDialog(
                    open = true,
                    sellerDisplayName = pubkey.take(8) + "...",
                    sellerPubkey = pubkey,
                    itemReference = "30402:${pubkey}:${service!!.rawTags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: service!!.eventId}",
                    itemName = service!!.title,
                    unitPriceLabel = if (priceCurrency.equals("SATS", ignoreCase = true)) "$priceValue sats" else "$priceValue $priceCurrency",
                    unitPriceSats = if (priceCurrency.equals("SATS", ignoreCase = true)) priceValue.filter { it.isDigit() }.toLongOrNull() else null,
                    isSending = orderState is OrderCreationState.Sending,
                    errorMessage = (orderState as? OrderCreationState.Error)?.message ?: orderError,
                    onCancel = {
                        showOrderDialog = false
                        orderCreateViewModel.resetState()
                    },
                    onSubmit = { quantity, notes, shippingOption, shippingAddress, buyerEmail, buyerPhone ->
                        val unitAmount = priceValue.filter { it.isDigit() }.toLongOrNull() ?: 0L
                        val orderAmount = unitAmount * quantity
                        val orderProductPrice = if (priceCurrency.equals("SATS", ignoreCase = true)) "$priceValue sats" else "$priceValue $priceCurrency"
                        orderCreateViewModel.submitOrder(
                            buyerPubkey = buyerPubkey.orEmpty(),
                            buyerPrivateKeyHex = privateKeyHex,
                            sellerPubkey = pubkey,
                            subject = "Order for ${service!!.title}",
                            items = listOf(
                                OrderItem(
                                    productReference = "30402:${pubkey}:${service!!.rawTags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: service!!.eventId}",
                                    productName = service!!.title,
                                    quantity = quantity,
                                    productPrice = orderProductPrice
                                )
                            ),
                            amount = orderAmount,
                            currency = priceCurrency,
                            notes = notes,
                            shippingOption = shippingOption,
                            shippingAddress = shippingAddress,
                            buyerEmail = buyerEmail,
                            buyerPhone = buyerPhone
                        )
                    }
                )
            }
        }
    }
}



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
