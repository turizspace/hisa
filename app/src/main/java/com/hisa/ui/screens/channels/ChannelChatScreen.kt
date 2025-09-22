package com.hisa.ui.screens.channels

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Photo
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.hisa.viewmodel.ChannelChatViewModel
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.ui.components.ChannelMessageBubble
import com.hisa.ui.components.DateSeparator

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ChannelChatScreen(
    channelId: String,
    channelName: String,
    channelPicture: String,
    userPubkey: String,
    privateKey: ByteArray?,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    navController: androidx.navigation.NavHostController,
    externalSignerPubkey: String? = null,
    externalSignerPackage: String? = null,
    viewModel: ChannelChatViewModel = viewModel(
        key = channelId,
        factory = ChannelChatViewModel.Factory(
            channelId = channelId,
            nostrClient = nostrClient,
            subscriptionManager = subscriptionManager,
            privateKey = privateKey,
            userPubkey = userPubkey,
            externalSignerPubkey = externalSignerPubkey,
            externalSignerPackage = externalSignerPackage
        )
    )
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val profileMetadata by viewModel.profileMetadata.collectAsState()
    var newMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    // Set up cleanup only when the composable is destroyed (popped) — do NOT cleanup on simple compose disposal
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(channelId) {
        android.util.Log.d("ChannelChatScreen", "Entering channel chat screen with id: $channelId, name: $channelName")
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                android.util.Log.d("ChannelChatScreen", "Composable destroyed for channel $channelId — cleaning up subscriptions")
                viewModel.cleanupSubscriptions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            android.util.Log.d("ChannelChatScreen", "DisposableEffect disposed for channel $channelId — removing lifecycle observer")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val focusManager = LocalFocusManager.current

            // centralized send logic
            val sendNow: () -> Unit = {
                if (newMessage.isNotBlank()) {
                    try {
                        viewModel.sendMessage(newMessage)
                        newMessage = ""
                        isError = false
                        errorMessage = ""
                        focusManager.clearFocus()
                    } catch (e: Exception) {
                        android.util.Log.e("ChannelChatScreen", "Failed to send message", e)
                        isError = true
                        errorMessage = "Failed to send message. Please try again."
                    }
                }
            }
            if (isError && errorMessage.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            isError = false
                            errorMessage = ""
                        }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss error",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Channel header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                if (channelPicture.isNotBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(channelPicture),
                        contentDescription = "Channel Picture",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Channel Icon",
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                // Keep the list aware of IME and allow auto-scroll to latest message
                val listState = rememberLazyListState()
                // Build a combined list that includes date separators when the day changes
                val itemsWithSeparators = remember(messages) {
                    val out = mutableListOf<Any>()
                    var lastDayStart: Long? = null
                    messages.forEach { msg ->
                        val dayStart = java.util.Calendar.getInstance().apply {
                            timeInMillis = msg.createdAt * 1000
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (lastDayStart == null || lastDayStart != dayStart) {
                            out.add(dayStart)
                            lastDayStart = dayStart
                        }
                        out.add(msg)
                    }
                    out
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .imePadding(),
                    contentPadding = PaddingValues(bottom = 0.dp)
                ) {
                    items(itemsWithSeparators) { item ->
                        when (item) {
                            is Long -> {
                                // date separator (millis)
                                DateSeparator(item)
                            }
                            is com.hisa.data.model.ChannelMessage -> {
                                val message = item
                                val isOwnMessage = message.pubkey == userPubkey
                                val metadata = profileMetadata[message.authorPubkey]
                                ChannelMessageBubble(
                                    message = message,
                                    isOwnMessage = isOwnMessage,
                                    profilePicUrl = metadata?.picture,
                                    displayName = metadata?.displayName ?: metadata?.name,
                                    showProfileImage = !isOwnMessage,
                                    onProfileClick = {
                                        if (!message.authorPubkey.isNullOrBlank()) {
                                            navController.navigate("profile/${message.authorPubkey}")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Scroll to bottom when messages change
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            }

            // Message input - separate outlined field + rounded send button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .background(Color.Transparent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newMessage,
                    onValueChange = { newMessage = it },
                    placeholder = { Text("Message") },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.extraLarge),
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendNow() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    ),
                    singleLine = false,
                    maxLines = 6
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Attach / upload button styled like the message field
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            // Save current draft so it can be restored after returning from Upload screen
                            navController.currentBackStackEntry?.savedStateHandle?.set("compose_draft", newMessage)
                            navController.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Larger circular send button
                val sendEnabled = newMessage.isNotBlank()
                Button(
                    onClick = sendNow,
                    enabled = sendEnabled,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(22.dp),
                        tint = Color.Unspecified
                    )
                }
            }
        }
    }

    // Listen for uploaded media URL from UploadScreen, restore saved draft and append URLs to avoid replacing typed text
    LaunchedEffect(navController) {
        val saved = navController.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("uploaded_media_url")
        saved?.observeForever { url ->
            if (!url.isNullOrBlank()) {
                // restore draft saved before navigating to Upload only if current message is blank
                val draft = navController.currentBackStackEntry?.savedStateHandle?.get<String>("compose_draft") ?: ""
                val parts = url.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val combined = parts.joinToString(" ")
                    val base = if (newMessage.isBlank()) draft else newMessage
                    newMessage = if (base.isBlank()) combined else base + " " + combined
                }

                // clear to avoid re-triggering
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("uploaded_media_url")
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("compose_draft")
            }
        }
    }
}
