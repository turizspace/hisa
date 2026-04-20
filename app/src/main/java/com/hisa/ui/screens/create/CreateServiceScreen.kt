package com.hisa.ui.screens.create

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.FilterChipDefaults.filterChipColors
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavHostController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hisa.ui.navigation.NAV_RESULT_UPLOADED_MEDIA_URL
import com.hisa.ui.navigation.Routes
import com.hisa.ui.navigation.consumeUploadedMediaUrls
import com.hisa.ui.navigation.prepareUploadResult
import coil.compose.AsyncImage
import java.time.Instant
import java.util.UUID

val predefinedTags = listOf(
    "cleaning",
    "maintenance",
    "gardening",
    "moving",
    "pet-care",
    "senior-care",
    "technology",
    "handyman",
    "organization",
    "painting"
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateServiceScreen(
    onCreateService: (title: String, summary: String, description: String, tags: List<List<String>>, onSuccess: () -> Unit) -> Unit,
    onNavigateBack: () -> Unit,
    navController: NavHostController? = null
) {

    var title by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("USD") }
    var frequency by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTagsList by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedImageUrls by rememberSaveable { mutableStateOf(listOf<String>()) }
    var dTag by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (dTag != null) "Edit Listing" else "Create New Listing") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Button(
                    onClick = {
                        // Create NIP-99 compliant event
                        val dValue = dTag?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                        val tags = mutableListOf<List<String>>().apply {
                            add(listOf("d", dValue))
                            add(listOf("title", title))
                            add(listOf("summary", summary))
                            add(listOf("published_at", Instant.now().epochSecond.toString()))
                            add(listOf("location", location))
                            // Build price tag explicitly as a List<String>
                            val priceTag = ArrayList<String>()
                            priceTag.add("price")
                            priceTag.add(price)
                            priceTag.add(currency)
                            frequency?.let { priceTag.add(it) }
                            add(priceTag)
                            selectedTagsList.forEach { tag ->
                                add(listOf("t", tag))
                            }
                            // Add all uploaded image URLs as individual image tags (no dimensions)
                            if (selectedImageUrls.isNotEmpty()) {
                                selectedImageUrls.forEach { url ->
                                    add(listOf("image", url))
                                }
                            }
                            // Also add an `imeta` tag that contains all image URLs as additional
                            // tag elements (so consumers can read multiple image URLs from one tag).
                            if (selectedImageUrls.isNotEmpty()) {
                                val imetaTag = mutableListOf<String>("imeta")
                                imetaTag.addAll(selectedImageUrls)
                                add(imetaTag)
                            }
                        }

                        onCreateService(
                            title,
                            summary,
                            description,
                            tags
                        ) {
                            // Persist the dValue so future edits reuse the same replaceable key
                            try {
                                val current = navController?.currentBackStackEntry
                                val previous = navController?.previousBackStackEntry
                                listOf(current, previous).forEach { entry ->
                                    try {
                                        entry?.savedStateHandle?.set("edit_service_d", dValue)
                                    } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                            onNavigateBack()
                        }
                    },
                    enabled = title.isNotBlank() && summary.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Send, contentDescription = if (dTag != null) "Update" else "Post")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (dTag != null) "Update Service" else "Post Service",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
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
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        "Service Details",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = summary,
                        onValueChange = { summary = it },
                        label = { Text("Summary") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Pricing and Location Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        "Pricing & Location",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = price,
                            onValueChange = { price = it },
                            label = { Text("Price") },
                            modifier = Modifier.weight(0.65f)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Currency picker: show a dropdown to choose currency
                        var currencyExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = currencyExpanded,
                            onExpandedChange = { currencyExpanded = !currencyExpanded },
                            modifier = Modifier.weight(0.35f)
                        ) {
                            OutlinedTextField(
                                value = currency,
                                onValueChange = { /* readOnly - selection via menu */ },
                                readOnly = true,
                                label = { Text("Currency") },
                                modifier = Modifier.menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) }
                            )

                            ExposedDropdownMenu(
                                expanded = currencyExpanded,
                                onDismissRequest = { currencyExpanded = false }
                            ) {
                                listOf("USD", "EUR", "GBP", "SATS").forEach { option ->
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

                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        label = { Text("Location") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Categories and Image Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        "Categories & Media",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        predefinedTags.forEach { tag ->
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

                    // Free-form tag input so users can add their own categories/hashtags
                    var newTag by rememberSaveable { mutableStateOf("") }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text("Add category / tag") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val trimmed = newTag.trim().lowercase()
                            if (trimmed.isNotBlank() && trimmed !in selectedTagsList) {
                                selectedTagsList = selectedTagsList + trimmed
                                newTag = ""
                            }
                        }) {
                            Text("Add")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Centralized media flow: navigate to Upload screen (consistent with other create flows)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                try {
                                    // Defensive: set the target on both current and previous entries so callers can read it
                                    navController.prepareUploadResult("service_image")
                                    if (navController != null) {
                                        navController.navigate(Routes.UPLOAD)
                                    } else {
                                        // Helpful debug feedback when navController isn't available
                                        android.widget.Toast.makeText(ctx, "Navigation not available", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("CreateServiceScreen", "Failed to open upload: ${e.message}")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Photo, contentDescription = "Add Image")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Image")
                        }
                    }

                    if (selectedImageUrls.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            AsyncImage(
                                model = selectedImageUrls.first(),
                                contentDescription = "Selected Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                            // Close X to allow user to replace the image(s)
                            IconButton(
                                onClick = { selectedImageUrls = emptyList() },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Remove image")
                            }
                        }
                    }
                }
            }
        }
    }

    // Listen for uploaded_media_url result and apply to selectedImageUrls when appropriate
    LaunchedEffect(navController) {
            // Check for edit payload in savedStateHandle (current or previous entry) and prefill form
        try {
            val currentHandle = navController?.currentBackStackEntry?.savedStateHandle
            val prevHandle = navController?.previousBackStackEntry?.savedStateHandle
            val editD = currentHandle?.get<String>("edit_service_d") ?: prevHandle?.get<String>("edit_service_d")
            val etitle = currentHandle?.get<String>("edit_service_title") ?: prevHandle?.get<String>("edit_service_title")
            val esummary = currentHandle?.get<String>("edit_service_summary") ?: prevHandle?.get<String>("edit_service_summary")
            val edesc = currentHandle?.get<String>("edit_service_description") ?: prevHandle?.get<String>("edit_service_description")
            val etags = currentHandle?.get<String>("edit_service_tags") ?: prevHandle?.get<String>("edit_service_tags")
            val eimages = currentHandle?.get<String>("edit_service_image_urls") ?: prevHandle?.get<String>("edit_service_image_urls")
            val eprice = currentHandle?.get<String>("edit_service_price") ?: prevHandle?.get<String>("edit_service_price")
            val ecurrency = currentHandle?.get<String>("edit_service_currency") ?: prevHandle?.get<String>("edit_service_currency")
            val efrequency = currentHandle?.get<String>("edit_service_frequency") ?: prevHandle?.get<String>("edit_service_frequency")
            val elocation = currentHandle?.get<String>("edit_service_location") ?: prevHandle?.get<String>("edit_service_location")
            if (!editD.isNullOrBlank()) {
                dTag = editD
                title = etitle ?: title
                summary = esummary ?: summary
                description = edesc ?: description
                // parse tags JSON if present
                if (!etags.isNullOrBlank()) {
                    try {
                        val arr = org.json.JSONArray(etags)
                        val tlist = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val inner = arr.getJSONArray(i)
                            if (inner.length() > 0) {
                                val key = inner.optString(0, "")
                                if (key == "t") {
                                    val v = inner.optString(1, "")
                                    if (v.isNotBlank()) tlist.add(v)
                                }
                            }
                        }
                        if (tlist.isNotEmpty()) selectedTagsList = tlist
                    } catch (_: Exception) {}
                }
                if (!eimages.isNullOrBlank()) {
                    selectedImageUrls = eimages.split('\n').map { it.trim() }.filter { it.isNotBlank() }
                }
                // Prefill price/currency/frequency/location if present
                try { if (!eprice.isNullOrBlank()) price = eprice } catch (_: Exception) {}
                try { if (!ecurrency.isNullOrBlank()) currency = ecurrency } catch (_: Exception) {}
                try { if (!efrequency.isNullOrBlank()) frequency = efrequency } catch (_: Exception) {}
                try { if (!elocation.isNullOrBlank()) location = elocation } catch (_: Exception) {}
                // Clean saved payload so subsequent opens are fresh
                try {
                    currentHandle?.remove<String>("edit_service_d")
                    currentHandle?.remove<String>("edit_service_title")
                    currentHandle?.remove<String>("edit_service_summary")
                    currentHandle?.remove<String>("edit_service_description")
                    currentHandle?.remove<String>("edit_service_tags")
                    currentHandle?.remove<String>("edit_service_image_urls")
                    prevHandle?.remove<String>("edit_service_d")
                    prevHandle?.remove<String>("edit_service_title")
                    prevHandle?.remove<String>("edit_service_summary")
                    prevHandle?.remove<String>("edit_service_description")
                    prevHandle?.remove<String>("edit_service_tags")
                    prevHandle?.remove<String>("edit_service_image_urls")
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

    }

    val uploadHandle = navController?.currentBackStackEntry?.savedStateHandle
    val uploadedMediaUrlState = uploadHandle
        ?.getStateFlow<String?>(NAV_RESULT_UPLOADED_MEDIA_URL, null)
        ?.collectAsState()
        ?: remember { mutableStateOf<String?>(null) }
    val uploadedMediaUrl = uploadedMediaUrlState.value

    LaunchedEffect(uploadedMediaUrl, uploadHandle) {
        val parts = uploadHandle?.consumeUploadedMediaUrls("service_image").orEmpty()
        if (parts.isNotEmpty()) {
            val merged = selectedImageUrls.toMutableList()
            parts.forEach { part ->
                if (!merged.contains(part)) {
                    merged.add(part)
                }
            }
            selectedImageUrls = merged
        }
    }
}
@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun CreateServiceScreenPreview() {
    CreateServiceScreen(
        onCreateService = { _, _, _, _, onSuccess -> onSuccess() },
        onNavigateBack = {}
    )
}
