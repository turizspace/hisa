package com.hisa.ui.screens.lists

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.viewmodel.StallsViewModel
import com.hisa.viewmodel.StallsViewModelFactory
import com.hisa.viewmodel.ProductsViewModel
import com.hisa.viewmodel.ProductsViewModelFactory
import com.hisa.data.model.Stall
import com.hisa.ui.components.StallCard
import com.hisa.ui.components.ProductCard

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StallDetailScreen(
    stallId: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    privateKey: ByteArray?,
    userPubkey: String
) {
    val profileMetaUtil = LocalProfileMetaUtil.current
    val stallsViewModel: StallsViewModel = viewModel(
        factory = StallsViewModelFactory(nostrClient = nostrClient, subscriptionManager = subscriptionManager, profileMetaUtil = profileMetaUtil)
    )
    val productsViewModel: ProductsViewModel = viewModel(
        factory = ProductsViewModelFactory(nostrClient = nostrClient, subscriptionManager = subscriptionManager, stallId = stallId),
        key = "products_$stallId"
    )

    val stalls by stallsViewModel.stalls.collectAsState()
    val stall = stalls.find { it.id == stallId }

    val products by productsViewModel.products.collectAsState()
    val rawEvents by productsViewModel.rawEvents.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            stall?.let {
                StallCard(stall = it, modifier = Modifier.fillMaxWidth())
            } ?: Text("Stall not found", modifier = Modifier.padding(16.dp))
        }

        item {
            if (products.isNotEmpty()) {
                Text(
                    text = "Products (${products.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(products) { product ->
            Column(modifier = Modifier.padding(8.dp)) {
                ProductCard(product = product, modifier = Modifier.fillMaxWidth())

                // Show formatted raw event for this product
                var showRawEvent by remember { mutableStateOf(false) }
                if (showRawEvent) {
                    val eventJson = rawEvents[product.id] ?: "{}"
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = eventJson,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState()),
                            maxLines = 50
                        )
                    }
                }

                Button(
                    onClick = { showRawEvent = !showRawEvent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    Text(text = if (showRawEvent) "Hide Event Data" else "Show Event Data")
                }
            }
        }

        if (products.isEmpty()) {
            item {
                Text(
                    text = "No products found for this stall",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
