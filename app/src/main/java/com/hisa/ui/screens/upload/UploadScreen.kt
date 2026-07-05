package com.hisa.ui.screens.upload

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.hisa.util.hexToByteArrayOrNull
import com.hisa.viewmodel.UploadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    uploadViewModel: UploadViewModel = hiltViewModel(),
    navController: NavHostController? = null,
    onUploadComplete: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val uploadState by uploadViewModel.uploadState.collectAsState()

    val authVm: com.hisa.viewmodel.AuthViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val privHexState by authVm.privateKey.collectAsState(initial = "")
    val pubkeyState by authVm.pubKey.collectAsState(initial = "")

    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var endpoint by remember { mutableStateOf("upload") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        selectedUris = uris
    }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Upload Media to Blossom", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        OutlinedButton(onClick = { launcher.launch("*/*") }) {
            Text("Pick files (multiple)")
        }
        Spacer(Modifier.height(8.dp))

        Text("Selected: ${selectedUris.size} files")
        Spacer(Modifier.height(8.dp))

        if (selectedUris.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(selectedUris) { uri ->
                    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            val fileName = resolveDisplayName(context, uri)
                            Text(fileName)
                            val mime = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
                            if (mime != null && mime.startsWith("image")) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Image(
                                    painter = rememberAsyncImagePainter(uri.toString()),
                                    contentDescription = "Selected file preview",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Text("Endpoint:")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("upload", "media", "mirror").forEach { ep ->
                Button(
                    onClick = { endpoint = ep },
                    colors = if (endpoint == ep) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(ep)
                }
            }
        }
        Text(
            when (endpoint) {
                "upload" -> "Standard upload endpoint (signed 'upload' verb)"
                "media" -> "Media CDN endpoint (use for public media)"
                "mirror" -> "Mirror endpoint (mirror a blob to another storage)"
                else -> ""
            },
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))

        when (val state = uploadState) {
            is UploadViewModel.UploadState.Idle -> {
                Button(onClick = {
                    if (selectedUris.isEmpty()) return@Button

                    val privHex = privHexState ?: ""
                    val pubkey = pubkeyState ?: ""
                    val privBytes = hexToByteArrayOrNull(privHex, 32)
                    val externalSignerPubkey = authVm.getExternalSignerPubkey()
                    val externalSignerPackage = authVm.getExternalSignerPackage()
                    val hasExternalSigner = !externalSignerPubkey.isNullOrBlank() && !externalSignerPackage.isNullOrBlank()

                    if (pubkey.isBlank() || (privBytes == null && !hasExternalSigner)) {
                        Toast.makeText(context, "No signing key available for upload.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    scope.launch {
                        val uploadedUrls = mutableListOf<String>()
                        for (uri in selectedUris) {
                            uploadViewModel.reset()
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                            val resultDeferred = CompletableDeferred<String?>()
                            val collector = launch {
                                uploadViewModel.uploadState.collect { st ->
                                    when (st) {
                                        is UploadViewModel.UploadState.Success -> {
                                            if (!resultDeferred.isCompleted) resultDeferred.complete(st.url)
                                        }
                                        is UploadViewModel.UploadState.Error -> {
                                            if (!resultDeferred.isCompleted) resultDeferred.complete(null)
                                        }
                                        else -> Unit
                                    }
                                }
                            }

                            uploadViewModel.uploadFileFromUri(
                                uri = uri,
                                contentType = mime,
                                pubkeyHex = pubkey,
                                privKey = privBytes,
                                endpoint = endpoint,
                                externalSignerPubkey = externalSignerPubkey,
                                externalSignerPackage = externalSignerPackage
                            ) { url ->
                                if (!resultDeferred.isCompleted) resultDeferred.complete(url)
                            }

                            val uploadedUrl = try {
                                resultDeferred.await()
                            } catch (_: Exception) {
                                null
                            }
                            collector.cancel()
                            if (!uploadedUrl.isNullOrBlank()) uploadedUrls.add(uploadedUrl)
                        }

                        uploadViewModel.reset()
                        if (uploadedUrls.isNotEmpty()) {
                            val resultString = if (uploadedUrls.size == 1) uploadedUrls[0] else uploadedUrls.joinToString("\n")
                            try { onUploadComplete(resultString) } catch (_: Exception) {}
                        }
                    }
                }) {
                    Text("Upload Selected (${selectedUris.size})")
                }
            }

            is UploadViewModel.UploadState.CalculatingHash -> {
                StatusCard(title = state.progress)
            }

            is UploadViewModel.UploadState.GeneratingEvent -> {
                StatusCard(title = state.progress, subtitle = "Building Blossom descriptor event")
            }

            is UploadViewModel.UploadState.WaitingForSigner -> {
                StatusCard(title = state.progress, subtitle = "Approve the request in Amber")
            }

            is UploadViewModel.UploadState.Uploading -> {
                val progress = if (state.totalBytes > 0) {
                    (state.bytesSent.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Uploading: ${formatBytes(state.bytesSent)} / ${formatBytes(state.totalBytes)}")
                    }
                }
            }

            is UploadViewModel.UploadState.Success -> {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Uploaded successfully", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.url, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        if (state.url.isNotBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(state.url),
                                contentDescription = "Uploaded preview",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { try { onUploadComplete(state.url) } catch (_: Exception) {} }) {
                            Text("Insert Link")
                        }
                    }
                }
            }

            is UploadViewModel.UploadState.Error -> {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Upload failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { uploadViewModel.reset() }) { Text("Dismiss") }
                            if (state.canRetry) {
                                Button(onClick = {
                                    selectedUris.firstOrNull()?.let { uri ->
                                        // Retry the last selected file by reusing current keys
                                    }
                                }) { Text("Retry") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String? = null) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(title, textAlign = TextAlign.Center)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, textAlign = TextAlign.Center, color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String {
    return try {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
        var fileName = uri.lastPathSegment ?: "file"
        if (cursor != null && nameIndex != -1) {
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        cursor?.close()
        fileName
    } catch (_: Exception) {
        uri.lastPathSegment ?: "file"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${String.format("%.1f", bytes / (1024.0 * 1024))} MB"
    }
}
