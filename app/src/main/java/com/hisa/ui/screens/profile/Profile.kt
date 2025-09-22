package com.hisa.ui.screens.profile


import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hisa.viewmodel.ProfileViewModel
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    pubkey: String,
    name: String? = null,
    about: String? = null,
    pictureUrl: String? = null,
    profileViewModel: ProfileViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    navController: NavHostController? = null
) {
    val authViewModel: com.hisa.viewmodel.AuthViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val privateKeyHex = authViewModel.privateKey.collectAsState().value ?: ""
    val currentUserPubkey by authViewModel.pubKey.collectAsState(initial = "")
    val allMetadata by profileViewModel.allMetadata.collectAsState()
    val saveStatus by profileViewModel.saveStatus.collectAsState()
    val latestMeta = allMetadata.lastOrNull()
    LaunchedEffect(allMetadata) {
        android.util.Log.d("ProfileScreen", "allMetadata updated: $allMetadata")
        android.util.Log.d("ProfileScreen", "latestMeta: $latestMeta")
    }
    var showEdit by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editAbout by remember { mutableStateOf("") }
    var editPicture by remember { mutableStateOf("") }
    var editBanner by remember { mutableStateOf("") }
    var editWebsite by remember { mutableStateOf("") }
    var editDisplayName by remember { mutableStateOf("") }
    var editLud16 by remember { mutableStateOf("") }
    // Icons
    val walletIcon = Icons.Default.AccountBalanceWallet
    val nip05Icon = Icons.Default.VerifiedUser
    val websiteIcon = Icons.Default.Language

    // Update edit fields when metadata changes
    LaunchedEffect(latestMeta) {
        latestMeta?.let { meta ->
            editName = meta.name.orEmpty()
            editAbout = meta.about.orEmpty()
            editPicture = meta.picture.orEmpty()
            editBanner = meta.banner.orEmpty()
            editWebsite = meta.website.orEmpty()
            editDisplayName = meta.displayName.orEmpty()
            editLud16 = meta.lud16.orEmpty()
        }
    }

    // Observe uploaded media URL and apply to the correct edit field (picture/banner) based on an
    // "upload_target" entry that we set before navigating to the Upload screen.
    LaunchedEffect(navController) {
        val saved = navController?.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("uploaded_media_url")
        saved?.observeForever { url ->
            if (!url.isNullOrBlank()) {
                val parts = url.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val target = navController.currentBackStackEntry?.savedStateHandle?.get<String>("upload_target") ?: "picture"
                    val first = parts[0]
                    when (target) {
                        "banner" -> editBanner = first
                        "picture" -> editPicture = first
                        "lud16" -> editLud16 = first
                        else -> editPicture = first
                    }
                    // If there are extra URLs, append them to the About field so user can see them and decide what to do
                    if (parts.size > 1) {
                        val extras = parts.drop(1).joinToString("\n")
                        editAbout = if (editAbout.isBlank()) extras else editAbout + "\n" + extras
                    }
                }
                // Ensure the edit dialog is open so user can continue editing after upload
                showEdit = true
                // Clear both keys to avoid re-triggering
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("uploaded_media_url")
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("upload_target")
            }
        }
    }
    // Refresh trigger
    var isRefreshing by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                // Card with profile info
                Card(
                    modifier = Modifier
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Box {
                        // Banner
                        if (!latestMeta?.banner.isNullOrBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(latestMeta?.banner),
                                contentDescription = "Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            )
                        }
                        // Small Edit button top-right when viewing own profile; otherwise show Message button to DM
                        val isOwnProfile = !currentUserPubkey.isNullOrBlank() && currentUserPubkey.equals(pubkey, ignoreCase = true)
                        if (isOwnProfile) {
                            IconButton(
                                onClick = { showEdit = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Profile", modifier = Modifier.size(20.dp))
                            }
                        } else if (!currentUserPubkey.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    // Navigate to DM screen for this pubkey when navController available
                                    navController?.navigate(com.hisa.ui.navigation.Routes.DM.replace("{pubkey}", pubkey))
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.surface, shape = CircleShape)
                            ) {
                                Icon(Icons.Default.Message, contentDescription = "Message User", modifier = Modifier.size(20.dp))
                            }
                        }
                        // Profile picture overlay
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .align(Alignment.BottomCenter)
                                .offset(y = 55.dp)
                                .zIndex(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                shadowElevation = 8.dp,
                                border = BorderStroke(3.dp, MaterialTheme.colorScheme.background),
                                modifier = Modifier.size(110.dp)
                            ) {
                                if (!latestMeta?.picture.isNullOrBlank()) {
                                    Image(
                                        painter = rememberAsyncImagePainter(latestMeta?.picture),
                                        contentDescription = "Profile Picture",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(110.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Default Profile Picture",
                                        modifier = Modifier.size(60.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(60.dp))
                    // Name and About
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = latestMeta?.displayName ?: latestMeta?.name ?: "No Name",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        if (!latestMeta?.about.isNullOrBlank()) {
                            Text(
                                text = latestMeta?.about ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        // Metadata chips with icons
                        Column(
                            modifier = Modifier.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (!latestMeta?.nip05.isNullOrBlank()) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(nip05Icon, contentDescription = "nip05", modifier = Modifier.size(16.dp), tint = Color(0xFF2196F3))
                                            Spacer(Modifier.width(4.dp))
                                            Text("${latestMeta?.nip05}")

                                        }
                                    },
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (!latestMeta?.lud16.isNullOrBlank()) {
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(walletIcon, contentDescription = "Wallet", modifier = Modifier.size(16.dp), tint = Color(0xFF795548))
                                            Spacer(Modifier.width(4.dp))
                                            Text("${latestMeta?.lud16}")
                                        }
                                    },
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            val context = LocalContext.current

                            val websiteUrl = latestMeta?.website

                            if (!websiteUrl.isNullOrBlank()) {
                                AssistChip(
                                    onClick = {
                                        try {
                                            val uri = Uri.parse(websiteUrl.trim())
                                            if (uri.scheme.isNullOrEmpty()) {
                                                // Ensure URI has a proper scheme
                                                val fixedUri = Uri.parse("https://$websiteUrl")
                                                context.startActivity(Intent(Intent.ACTION_VIEW, fixedUri))
                                            } else {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                            // Optionally show a Toast or Snackbar to the user
                                        }
                                    },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                websiteIcon,
                                                contentDescription = "Website",
                                                modifier = Modifier.size(16.dp),
                                                tint = Color(0xFF009688)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Website")
                                        }
                                    },
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }

                        }
                        Text(
                            text = "Pubkey: ${pubkey.take(12)}...${pubkey.takeLast(6)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
            // Stylish Refresh button
            OutlinedButton(
                onClick = {
                    isRefreshing = true
                    profileViewModel.clearSaveStatus()
                    profileViewModel::class.java.getDeclaredMethod("fetchMetadata").apply { isAccessible = true }.invoke(profileViewModel)
                },
                enabled = !isRefreshing,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refresh Profile")
            }
            if (isRefreshing) {
                LaunchedEffect(Unit) {
                    delay(1200)
                    isRefreshing = false
                }
            }
            // Save status
            when (saveStatus) {
                is ProfileViewModel.SaveStatus.Success -> {
                    LaunchedEffect(saveStatus) {
                        delay(1500)
                        profileViewModel.clearSaveStatus()
                    }
                    Text("Profile saved!", color = MaterialTheme.colorScheme.primary)
                }
                is ProfileViewModel.SaveStatus.Error -> {
                    val msg = (saveStatus as ProfileViewModel.SaveStatus.Error).message
                    LaunchedEffect(saveStatus) {
                        delay(2500)
                        profileViewModel.clearSaveStatus()
                    }
                    Text("Error: $msg", color = MaterialTheme.colorScheme.error)
                }
                else -> {}
            }
            // All kind:0 metadata history
                if (allMetadata.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Profile History:", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        for (meta in allMetadata) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!meta.picture.isNullOrBlank()) {
                                    Image(
                                        painter = rememberAsyncImagePainter(meta.picture),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(40.dp).clip(CircleShape)
                                    )
                                }
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(meta.name ?: "No Name", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (!meta.about.isNullOrBlank()) Text(meta.about ?: "", fontSize = 13.sp)
                                    if (!meta.nip05.isNullOrBlank()) Text("nip05: ${meta.nip05}", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        }
        // Edit Profile Dialog
        if (showEdit) {
            AlertDialog(
                onDismissRequest = { showEdit = false },
                title = { Text("Edit Profile") },
                text = {
                    val dialogScroll = rememberScrollState()
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp).verticalScroll(dialogScroll),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = editDisplayName,
                            onValueChange = { editDisplayName = it },
                            label = { Text("Display Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editAbout,
                            onValueChange = { editAbout = it },
                            label = { Text("About") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        // Profile picture field with upload icon and thumbnail preview
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = editPicture,
                                onValueChange = { editPicture = it },
                                label = { Text("Profile Picture URL") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        // mark target so returned URL is applied to picture
                                        navController?.currentBackStackEntry?.savedStateHandle?.set("upload_target", "picture")
                                        navController?.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Photo, contentDescription = "Upload picture", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                            }
                        }
                        // Thumbnail preview for profile picture
                        if (!editPicture.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                painter = rememberAsyncImagePainter(editPicture),
                                contentDescription = "Profile picture preview",
                                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Banner field with upload icon and preview
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = editBanner,
                                onValueChange = { editBanner = it },
                                label = { Text("Banner URL") },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        navController?.currentBackStackEntry?.savedStateHandle?.set("upload_target", "banner")
                                        navController?.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Photo, contentDescription = "Upload banner", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
                            }
                        }
                        // Wide banner preview
                        if (!editBanner.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Image(
                                painter = rememberAsyncImagePainter(editBanner),
                                contentDescription = "Banner preview",
                                modifier = Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        OutlinedTextField(
                            value = editLud16,
                            onValueChange = { editLud16 = it },
                            label = { Text("Lightning Address") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editWebsite,
                            onValueChange = { editWebsite = it },
                            label = { Text("Website") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        // Save and publish
                        showEdit = false
                        val metadata = com.hisa.data.model.Metadata(
                            name = editName,
                            displayName = editDisplayName,
                            about = editAbout,
                            picture = editPicture,
                            banner = editBanner,
                            website = editWebsite,
                            lud16 = editLud16
                        )
                        profileViewModel.updateMetadata(metadata, privateKeyHex, pubkey)
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showEdit = false }) {
                        Text("Cancel")
                    }
                }
            )
}
    }
