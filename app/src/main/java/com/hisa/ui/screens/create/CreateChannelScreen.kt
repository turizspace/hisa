package com.hisa.ui.screens.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChannelScreen(
    onCreateChannel: (
        name: String,
        about: String,
        picture: String,
        relays: List<String>,
        categories: List<String>,
        onSuccess: (String) -> Unit // Pass new channel id
    ) -> Unit,
    onNavigateToChannel: (String) -> Unit,
    onRefreshChannels: () -> Unit
    , navController: NavHostController? = null
) {
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var picture by rememberSaveable { mutableStateOf("") }
    var relays by rememberSaveable { mutableStateOf("") }
    var categories by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create New Channel") },
                navigationIcon = {
                    IconButton(onClick = { onNavigateToChannel("") }) {
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
                        onCreateChannel(
                            name,
                            about,
                            picture,
                            relays.split(',').map { it.trim() }.filter { it.isNotBlank() },
                            categories.split(',').map { it.trim() }.filter { it.isNotBlank() }
                        ) { newChannelId ->
                            onRefreshChannels()
                            onNavigateToChannel(newChannelId)
                        }
                    },
                    enabled = name.isNotBlank() && about.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create Channel")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Create Channel",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier
            .padding(padding)
            .fillMaxSize()) {
            androidx.compose.foundation.rememberScrollState().let { scrollState ->
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxSize()
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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
                                "Channel Details",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = about,
                                onValueChange = { about = it },
                                label = { Text("About") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = picture,
                                    onValueChange = { picture = it },
                                    label = { Text("Picture URL") },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            navController?.currentBackStackEntry?.savedStateHandle?.set("upload_target", "channel_picture")
                                            navController?.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Photo, contentDescription = "Upload channel picture", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = relays,
                                onValueChange = { relays = it },
                                label = { Text("Relays (comma separated)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            // Removed manual pubkey entry
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = categories,
                                onValueChange = { categories = it },
                                label = { Text("Categories (comma separated)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    // Observe uploaded_media_url lifecycle-aware and apply to picture field when appropriate
    val uploadedMediaLiveData = navController?.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("uploaded_media_url")
    // observeAsState returns a State<T?>, but when there's no NavController/backstack the LiveData
    // will be null. Provide a remembered fallback State so the delegate always has a valid target.
    val uploadedMediaState = uploadedMediaLiveData?.observeAsState() ?: remember { mutableStateOf<String?>(null) }
    val uploadedMedia = uploadedMediaState.value

    LaunchedEffect(uploadedMedia) {
        val url = uploadedMedia
        if (!url.isNullOrBlank()) {
            val parts = url.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) {
                val target = navController?.currentBackStackEntry?.savedStateHandle?.get<String>("upload_target") ?: ""
                if (target == "channel_picture") {
                    picture = parts[0]
                    if (parts.size > 1) {
                        // append extras to about so user can see them
                        about = about + "\n" + parts.drop(1).joinToString("\n")
                    }
                }
            }
            navController?.currentBackStackEntry?.savedStateHandle?.remove<String>("uploaded_media_url")
            navController?.currentBackStackEntry?.savedStateHandle?.remove<String>("upload_target")
        }
    }
}

