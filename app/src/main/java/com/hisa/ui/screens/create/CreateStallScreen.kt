package com.hisa.ui.screens.create

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.FilterChipDefaults.filterChipColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hisa.ui.components.HisaFormCard
import com.hisa.ui.components.HisaPrimaryButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun sanitizeListingTag(value: String): String {
    return value.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

data class ShippingZone(
    val id: String,
    var name: String,
    var cost: String,
    var regions: List<String>
)

val stallPredefinedTags = listOf(
    "general",
    "books",
    "electronics",
    "clothing",
    "home-goods",
    "food",
    "services",
    "handmade",
    "collectibles",
    "other"
)


/**
 * NIP-15 Stall Creation Screen (kind 30017)
 * Allows merchants to create and configure marketplace stalls with:
 * - Stall name and description
 * - Currency selection
 * - Shipping zones (optional)
 * - Categories/tags
 */
@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateStallScreen(
    onCreateStall: (title: String, summary: String, description: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit,
    onCreateProduct: ((stallId: String, name: String, description: String, price: String, currency: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit)? = null,
    onNavigateBack: () -> Unit,
    navController: NavHostController? = null
) {
    var stallName by rememberSaveable { mutableStateOf("") }
    var stallDescription by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("SATS") }
    var shippingZones by rememberSaveable { mutableStateOf(listOf<ShippingZone>()) }
    var selectedTagsList by rememberSaveable { mutableStateOf(listOf<String>()) }
    var newZoneName by rememberSaveable { mutableStateOf("") }
    var newZoneCost by rememberSaveable { mutableStateOf("") }
    var showProductComposer by rememberSaveable { mutableStateOf(false) }
    var createdStallId by rememberSaveable { mutableStateOf<String?>(null) }
    var newProductName by rememberSaveable { mutableStateOf("") }
    var newProductDescription by rememberSaveable { mutableStateOf("") }
    var newProductPrice by rememberSaveable { mutableStateOf("") }
    var newProductCurrency by rememberSaveable { mutableStateOf("SATS") }
    var newProductTags by rememberSaveable { mutableStateOf(listOf<String>()) }
    var productCreated by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                HisaPrimaryButton(
                    text = "Create Stall",
                    enabled = stallName.isNotBlank(),
                    modifier = Modifier.padding(16.dp),
                    onClick = {
                        val tags = mutableListOf<List<String>>().apply {
                            selectedTagsList.forEach { tag ->
                                sanitizeListingTag(tag).takeIf { it.isNotBlank() }?.let { add(listOf("t", it)) }
                            }
                        }

                        onCreateStall(
                            stallName,
                            stallDescription,
                            stallDescription,
                            tags
                        ) {
                            createdStallId = UUID.randomUUID().toString()
                            showProductComposer = true
                            productCreated = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
        ) {
            if (showProductComposer && productCreated) {
                HisaFormCard(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = "Add a product to this stall"
                ) {
                    OutlinedTextField(
                        value = newProductName,
                        onValueChange = { newProductName = it },
                        label = { Text("Product name *") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newProductDescription,
                        onValueChange = { newProductDescription = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        minLines = 3
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newProductPrice,
                            onValueChange = { newProductPrice = it },
                            label = { Text("Price") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = newProductCurrency,
                            onValueChange = { newProductCurrency = it },
                            label = { Text("Currency") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        HisaPrimaryButton(
                            text = "Publish Product",
                            enabled = newProductName.isNotBlank(),
                            onClick = {
                                val productTags = mutableListOf<List<String>>().apply {
                                    newProductTags.forEach { tag ->
                                        sanitizeListingTag(tag).takeIf { it.isNotBlank() }?.let { add(listOf("t", it)) }
                                    }
                                }
                                onCreateProduct?.invoke(
                                    createdStallId.orEmpty(),
                                    newProductName,
                                    newProductDescription,
                                    newProductPrice,
                                    newProductCurrency,
                                    productTags
                                ) {
                                    onNavigateBack()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = onNavigateBack) {
                            Text("Skip")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            HisaFormCard(
                modifier = Modifier.padding(16.dp),
                title = "Stall Information"
            ) {
                OutlinedTextField(
                    value = stallName,
                    onValueChange = { stallName = it },
                    label = { Text("Stall Name *") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = stallDescription,
                    onValueChange = { stallDescription = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    minLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            HisaFormCard(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = "Settings"
            ) {
                var currencyExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = currencyExpanded,
                    onExpandedChange = { currencyExpanded = !currencyExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { /* readOnly - selection via menu */ },
                        readOnly = true,
                        label = { Text("Currency") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) }
                    )

                    ExposedDropdownMenu(
                        expanded = currencyExpanded,
                        onDismissRequest = { currencyExpanded = false }
                    ) {
                        listOf("SATS", "USD", "EUR", "GBP", "BTC").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    currency = option
                                    currencyExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HisaFormCard(
                modifier = Modifier.padding(16.dp),
                title = "Shipping Zones (optional)"
            ) {
                shippingZones.forEachIndexed { index, zone ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    zone.name,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "Cost: ${zone.cost}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = {
                                shippingZones = shippingZones.filterIndexed { i, _ -> i != index }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Zone")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newZoneName,
                        onValueChange = { newZoneName = it },
                        label = { Text("Zone name") },
                        modifier = Modifier
                            .weight(0.5f)
                            .height(48.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = newZoneCost,
                        onValueChange = { newZoneCost = it },
                        label = { Text("Cost") },
                        modifier = Modifier
                            .weight(0.5f)
                            .height(48.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (newZoneName.isNotBlank() && newZoneCost.isNotBlank()) {
                            shippingZones = shippingZones + ShippingZone(
                                id = UUID.randomUUID().toString(),
                                name = newZoneName,
                                cost = newZoneCost,
                                regions = listOf()
                            )
                            newZoneName = ""
                            newZoneCost = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Zone")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Zone")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HisaFormCard(
                modifier = Modifier.padding(16.dp),
                title = "Categories"
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stallPredefinedTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selectedTagsList,
                            onClick = {
                                selectedTagsList = if (tag in selectedTagsList) {
                                    selectedTagsList - tag
                                } else {
                                    selectedTagsList + tag
                                }
                            },
                            label = { Text(tag) },
                            colors = filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                var newTag by rememberSaveable { mutableStateOf("") }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("Add category") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val trimmed = sanitizeListingTag(newTag)
                        if (trimmed.isNotBlank() && trimmed !in selectedTagsList) {
                            selectedTagsList = selectedTagsList + trimmed
                            newTag = ""
                        }
                    }) {
                        Text("Add")
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
