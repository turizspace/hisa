package com.hisa.ui.screens.lists

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.data.model.Product
import com.hisa.data.model.OrderItem
import com.hisa.ui.components.HisaPrimaryButton
import com.hisa.ui.components.OrderComposerDialog
import com.hisa.ui.components.ProductCard
import com.hisa.ui.components.StallCard
import com.hisa.ui.screens.create.CreateServiceViewModel
import com.hisa.util.cleanPubkeyFormat
import com.hisa.util.normalizeNostrPubkey
import com.hisa.viewmodel.AuthViewModel
import com.hisa.viewmodel.OrderCreateViewModel
import com.hisa.viewmodel.OrderCreationState
import com.hisa.viewmodel.StallDetailViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun StallDetailScreen(
    viewModel: StallDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val authViewModel: AuthViewModel = hiltViewModel()
    val orderCreateViewModel: OrderCreateViewModel = hiltViewModel()
    val createViewModel: CreateServiceViewModel = hiltViewModel()

    val stall by viewModel.stall.collectAsState()
    val products by viewModel.products.collectAsState()
    val buyerPubkey by authViewModel.pubKey.collectAsState()
    val privateKeyHex by authViewModel.privateKey.collectAsState()
    val orderState by orderCreateViewModel.state.collectAsState()
    val isCreating by createViewModel.isLoading.collectAsState()
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var showOrderDialog by remember { mutableStateOf(false) }
    var newProductName by rememberSaveable { mutableStateOf("") }
    var newProductDescription by rememberSaveable { mutableStateOf("") }
    var newProductPrice by rememberSaveable { mutableStateOf("") }
    var newProductCurrency by rememberSaveable { mutableStateOf("SATS") }

    val isOwner = remember(stall, buyerPubkey) {
        val normalizedBuyer = normalizeNostrPubkey(buyerPubkey) ?: cleanPubkeyFormat(buyerPubkey.orEmpty()).lowercase()
        val normalizedOwner = normalizeNostrPubkey(stall?.ownerPubkey) ?: cleanPubkeyFormat(stall?.ownerPubkey.orEmpty()).lowercase()
        normalizedBuyer.isNotBlank() && normalizedOwner.isNotBlank() && normalizedBuyer == normalizedOwner
    }

    LaunchedEffect(orderState) {
        if (orderState is OrderCreationState.Success) {
            Toast.makeText(context, "Order sent successfully", Toast.LENGTH_SHORT).show()
            showOrderDialog = false
            selectedProduct = null
            orderCreateViewModel.resetState()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            stall?.let { currentStall ->
                StallCard(
                    stall = currentStall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            } ?: LoadingCard(
                title = "Loading stall...",
                subtitle = "Fetching marketplace data"
            )
        }

        if (isOwner) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Add a product",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Publish a new product for this stall.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        OutlinedTextField(
                            value = newProductName,
                            onValueChange = { newProductName = it },
                            label = { Text("Product name") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                        OutlinedTextField(
                            value = newProductDescription,
                            onValueChange = { newProductDescription = it },
                            label = { Text("Description") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = newProductPrice,
                            onValueChange = { newProductPrice = it },
                            label = { Text("Price") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                        OutlinedTextField(
                            value = newProductCurrency,
                            onValueChange = { newProductCurrency = it },
                            label = { Text("Currency") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )

                        HisaPrimaryButton(
                            text = if (isCreating) "Publishing..." else "Add product",
                            enabled = !isCreating && newProductName.isNotBlank(),
                            modifier = Modifier.padding(top = 12.dp),
                            onClick = {
                                val currentStall = stall ?: return@HisaPrimaryButton
                                createViewModel.createProduct(
                                    stallId = currentStall.id,
                                    name = newProductName.trim(),
                                    description = newProductDescription.trim(),
                                    price = newProductPrice.trim(),
                                    currency = newProductCurrency.trim().ifBlank { "SATS" },
                                    tags = emptyList(),
                                    privateKeyHex = privateKeyHex,
                                    pubKey = buyerPubkey.orEmpty(),
                                    onSuccess = {
                                        newProductName = ""
                                        newProductDescription = ""
                                        newProductPrice = ""
                                        newProductCurrency = "SATS"
                                        Toast.makeText(context, "Product published", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Products",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (products.isNotEmpty()) {
            items(
                items = products,
                key = { it.id }
            ) { product ->
                ProductCard(
                    product = product,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    onOrder = {
                        selectedProduct = product
                        showOrderDialog = true
                    }
                )
            }
        } else if (stall != null) {
            item {
                Text(
                    text = "No products found for this stall.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (selectedProduct != null && showOrderDialog) {
        val product = selectedProduct!!
        OrderComposerDialog(
            open = true,
            sellerDisplayName = stall?.ownerDisplayName ?: product.authorPubkey.take(8),
            sellerPubkey = product.authorPubkey,
            itemReference = "30018:${product.authorPubkey}:${product.id}",
            itemName = product.name,
            unitPriceLabel = "${product.price} ${product.currency}",
            unitPriceSats = if (product.currency.equals("SATS", ignoreCase = true)) product.price.filter { it.isDigit() }.toLongOrNull() else null,
            isSending = orderState is OrderCreationState.Sending,
            errorMessage = (orderState as? OrderCreationState.Error)?.message,
            onCancel = {
                showOrderDialog = false
                selectedProduct = null
                orderCreateViewModel.resetState()
            },
            onSubmit = { quantity, notes, shippingOption, shippingAddress, buyerEmail, buyerPhone ->
                val unitAmount = product.price.filter { it.isDigit() }.toLongOrNull() ?: 0L
                val productCurrency = product.currency.ifBlank { "SATS" }
                val orderAmount = unitAmount * quantity
                val orderProductPrice = if (productCurrency.equals("SATS", ignoreCase = true)) "${product.price} sats" else "${product.price} $productCurrency"
                orderCreateViewModel.submitOrder(
                    buyerPubkey = buyerPubkey.orEmpty(),
                    buyerPrivateKeyHex = privateKeyHex,
                    sellerPubkey = product.authorPubkey,
                    subject = "Order for ${product.name}",
                    items = listOf(
                        OrderItem(
                            productReference = "30018:${product.authorPubkey}:${product.id}",
                            productName = product.name,
                            quantity = quantity,
                            productPrice = orderProductPrice
                        )
                    ),
                    amount = orderAmount,
                    currency = productCurrency,
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

@Composable
private fun LoadingCard(title: String, subtitle: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
