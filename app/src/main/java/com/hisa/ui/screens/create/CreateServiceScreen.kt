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
import androidx.navigation.NavHostController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant

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
    // Keep selected tags as a saveable List<String> and derive a Set when needed
    var selectedTagsList by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create New Listing") },
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
                        val tags = mutableListOf<List<String>>().apply {
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
                            selectedImageUrl?.let { url ->
                                // Use uploaded image URL and add image tag
                                add(listOf("image", url))
                            }
                        }

                        onCreateService(
                            title,
                            summary,
                            description,
                            tags
                        ) {
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
                    Icon(Icons.Default.Send, contentDescription = "Post")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Post Service",
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Centralized media flow: navigate to Upload screen (consistent with other create flows)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                try {
                                    // Defensive: set the target on both current and previous entries so callers can read it
                                    val current = navController?.currentBackStackEntry
                                    val previous = navController?.previousBackStackEntry
                                    try { current?.savedStateHandle?.remove<String>("uploaded_media_url") } catch (_: Exception) {}
                                    try { previous?.savedStateHandle?.remove<String>("uploaded_media_url") } catch (_: Exception) {}
                                    current?.savedStateHandle?.set("upload_target", "service_image")
                                    previous?.savedStateHandle?.set("upload_target", "service_image")
                                    android.util.Log.i("CreateServiceScreen", "Opening UPLOAD: set upload_target=service_image current=${current?.destination?.route} previous=${previous?.destination?.route}")
                                    if (navController != null) {
                                        navController.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
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

                    if (!selectedImageUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        AsyncImage(
                            model = selectedImageUrl,
                            contentDescription = "Selected Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                    }
                }
            }
        }
    }

    // Listen for uploaded_media_url result and apply to selectedImageUrl when appropriate
    LaunchedEffect(navController) {
        val saved = navController?.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("uploaded_media_url")
        saved?.observeForever { url ->
            if (!url.isNullOrBlank()) {
                val parts = url.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val target = navController.currentBackStackEntry?.savedStateHandle?.get<String>("upload_target") ?: ""
                    if (target == "service_image") {
                        // If no image yet, set first returned URL as the image; otherwise append new URLs to description
                        if (selectedImageUrl.isNullOrBlank()) {
                            selectedImageUrl = parts[0]
                            if (parts.size > 1) {
                                description = description + "\n" + parts.drop(1).joinToString("\n")
                            }
                        } else {
                            // already have an image; append all returned URLs to description
                            description = description + "\n" + parts.joinToString("\n")
                        }
                    }
                }
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("uploaded_media_url")
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("upload_target")
            }
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