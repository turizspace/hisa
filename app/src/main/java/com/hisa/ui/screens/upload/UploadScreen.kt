package com.hisa.ui.screens.upload

import android.content.Context
import android.content.CursorLoader
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.compose.rememberAsyncImagePainter
import com.hisa.viewmodel.UploadViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File
import androidx.core.net.toFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    uploadViewModel: UploadViewModel = hiltViewModel(),
    navController: NavHostController? = null, // no direct popping here
    onUploadComplete: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val state by uploadViewModel.state.collectAsState()

    // AuthViewModel for keys
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

        // Show file list
        if (selectedUris.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(selectedUris) { uri ->
                    Card(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            val name = try {
                                val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
                                val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
                                var fileName = uri.lastPathSegment ?: "file"
                                if (cursor != null && nameIndex != -1) {
                                    cursor.moveToFirst()
                                    fileName = cursor.getString(nameIndex)
                                    cursor.close()
                                }
                                fileName
                            } catch (e: Exception) {
                                uri.lastPathSegment ?: "file"
                            }
                            Text(name)
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

        // Endpoint selector
        Text("Endpoint:")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("upload", "media", "mirror").forEach { ep ->
                Button(
                    onClick = { endpoint = ep },
                    colors = if (endpoint == ep)
                        ButtonDefaults.buttonColors(MaterialTheme.colorScheme.primary)
                    else ButtonDefaults.buttonColors()
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

        // State handling
        when (state) {
            is UploadViewModel.UploadState.Idle -> {
                Button(onClick = {
                    if (selectedUris.isEmpty()) return@Button
                    val privHex = privHexState ?: ""
                    val pubkey = pubkeyState ?: ""
                    val privBytes = if (privHex.isNotBlank()) hexToBytes(privHex) else ByteArray(0)

                    scope.launch {
                        val uploadedUrls = mutableListOf<String>()
                        for (uri in selectedUris) {
                            uploadViewModel.reset()
                            val file = uriToFile(context, uri) ?: continue
                            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

                            val resultDeferred = CompletableDeferred<String?>()
                            val watcher = launch {
                                uploadViewModel.state.collect { st ->
                                    when (st) {
                                        is UploadViewModel.UploadState.Success -> if (!resultDeferred.isCompleted) resultDeferred.complete(st.url)
                                        is UploadViewModel.UploadState.Error -> if (!resultDeferred.isCompleted) resultDeferred.complete(null)
                                        else -> {}
                                    }
                                }
                            }

                            uploadViewModel.uploadFile(file, mime, pubkey, privBytes, endpoint) { url ->
                                if (!resultDeferred.isCompleted) resultDeferred.complete(url)
                            }

                            val uploadedUrl = try { resultDeferred.await() } catch (_: Exception) { null }
                            watcher.cancel()
                            if (!uploadedUrl.isNullOrBlank()) uploadedUrls.add(uploadedUrl)
                        }

                        uploadViewModel.reset()
                        if (uploadedUrls.isNotEmpty()) {
                            val resultString = if (uploadedUrls.size == 1) uploadedUrls[0] else uploadedUrls.joinToString("\n")
                            android.util.Log.i("UploadScreen", "All uploads completed, returning urls=$resultString")
                            try { onUploadComplete(resultString) } catch (_: Exception) {}
                        }
                    }
                }) {
                    Text("Upload Selected (${selectedUris.size})")
                }
            }
            is UploadViewModel.UploadState.Uploading -> {
                val st = state as UploadViewModel.UploadState.Uploading
                LinearProgressIndicator(
                    progress = if (st.totalBytes > 0) st.bytesSent.toFloat() / st.totalBytes.toFloat() else 0f,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Uploading: ${st.bytesSent} / ${st.totalBytes}")
            }
            is UploadViewModel.UploadState.Success -> {
                val s = state as UploadViewModel.UploadState.Success
                Text("Uploaded: ${s.url}", textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                if (!s.url.isNullOrBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(s.url),
                        contentDescription = "Uploaded preview",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // Only return URL via callback — no popping here
                Button(onClick = { try { onUploadComplete(s.url) } catch (_: Exception) {} }) {
                    Text("Insert Link")
                }
            }
            is UploadViewModel.UploadState.Error -> {
                val e = state as UploadViewModel.UploadState.Error
                Text("Error: ${e.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { uploadViewModel.reset() }) { Text("Retry") }
            }
        }
    }
}

fun uriToFile(context: Context, uri: Uri): File? {
    return try {
        if (uri.scheme == "file") return uri.toFile()
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
        var fileName = "upload.tmp"
        if (cursor != null && nameIndex != -1) {
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
            cursor.close()
        }
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val outFile = File(context.cacheDir, fileName)
        input.use { inputStream -> outFile.outputStream().use { output -> inputStream.copyTo(output) } }
        outFile
    } catch (e: Exception) {
        null
    }
}

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim().removePrefix("0x")
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val idx = i * 2
        out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
    }
    return out
}
