package com.hisa.ui.screens.messages

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.hisa.data.repository.MetadataRepository
import com.hisa.ui.components.TabLoadingPlaceholder
import com.hisa.ui.components.rememberTabLoadingVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hisa.viewmodel.MessagesViewModel
import com.hisa.ui.navigation.Routes
import com.hisa.data.model.Message
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.ui.components.MessagesSkeletonLoader
import com.hisa.ui.components.EmptyMessagesState

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MessagesTab(
    navController: NavController,
    userPubkey: String,
    privateKey: String,
    messagesViewModel: MessagesViewModel
) {
    // Force recomposition when message list changes.
    val allMessages by messagesViewModel.messages.collectAsState()
    val isLoading by messagesViewModel.isLoading.collectAsState()
    val showLoading = rememberTabLoadingVisibility(isLoading = isLoading)
    LaunchedEffect(Unit) {
        messagesViewModel.ensureSubscribed()
    }
    DisposableEffect(Unit) {
        onDispose {
            messagesViewModel.stopDirectMessagesSubscription()
        }
    }

    val conversations = remember(allMessages) { messagesViewModel.getConversations() }

    if (isLoading) {
        MessagesSkeletonLoader(
            modifier = Modifier.fillMaxSize(),
            itemCount = 6
        )
        return
    }

    if (conversations.isEmpty()) {
        EmptyMessagesState(
            modifier = Modifier.fillMaxSize(),
            onStartConversation = null
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(conversations.entries.toList(), key = { it.key }) { entry ->
            val otherPubkey = entry.key
            val messages = entry.value
            
            var metadata by remember(otherPubkey) { mutableStateOf<com.hisa.data.model.Metadata?>(null) }
            val profileMetaUtil = LocalProfileMetaUtil.current
            
            LaunchedEffect(otherPubkey) {
                profileMetaUtil.fetchProfileMetadata(otherPubkey) { result ->
                    metadata = result
                }
            }
            
            ListItem(
                headlineContent = {
                    val fallback = if (otherPubkey == "unknown") {
                        "Unknown sender"
                    } else if (otherPubkey.length > 12) {
                        "${otherPubkey.take(12)}..."
                    } else {
                        otherPubkey
                    }
                    Text(metadata?.name ?: fallback)
                },
                supportingContent = { 
                    Text(when (val lastMessage = messages.firstOrNull()) {
                        is Message.TextMessage -> lastMessage.content
                        is Message.FileMessage -> "[File] ${lastMessage.fileUrl}"
                        null -> "No messages"
                    })
                },
                leadingContent = {
                    if (metadata?.picture != null && metadata?.picture!!.isNotBlank()) {
                        AsyncImage(
                            model = metadata?.picture,
                            contentDescription = "Profile Picture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            error = rememberVectorPainter(Icons.Default.AccountCircle),
                            placeholder = rememberVectorPainter(Icons.Default.AccountCircle)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Default Profile Picture",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                },
                modifier = Modifier.clickable {
                    if (otherPubkey != "unknown") {
                        navController.navigate(Routes.DM.replace("{pubkey}", otherPubkey))
                    }
                }
            )
            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true)
@Composable
fun MessagesTabPreview() {
    val dummyMessagesViewModel = androidx.lifecycle.viewmodel.compose.viewModel<com.hisa.viewmodel.MessagesViewModel>()
    MessagesTab(
        navController = androidx.navigation.compose.rememberNavController(),
        userPubkey = "demo_pubkey",
        privateKey = "demo_private_key",
        messagesViewModel = dummyMessagesViewModel
    )
}
