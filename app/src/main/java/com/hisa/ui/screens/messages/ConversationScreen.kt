package com.hisa.ui.screens.conversation

import com.hisa.util.cleanPubkeyFormat

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.draw.clip
import coil.compose.rememberAsyncImagePainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.hisa.data.repository.ConversationRepository
import com.hisa.ui.util.LocalProfileMetaUtil
import com.hisa.viewmodel.MessagesViewModel
import timber.log.Timber

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ConversationScreen(
    conversationId: String,
    userPubkey: String,
    contactName: String? = null,
    contactProfilePicture: String? = null,
    privateKey: String?,
    messagesViewModel: MessagesViewModel,
    navController: androidx.navigation.NavHostController? = null
)
{
    // Ensure MessagesViewModel is initialized with the correct keys
    LaunchedEffect(userPubkey, privateKey) {
        try {
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize MessagesViewModel")
        }
    }
    val allMessages by messagesViewModel.messages.collectAsState()
    val sendError by messagesViewModel.sendError.collectAsState()


    val normalizedConversationId = cleanPubkeyFormat(conversationId)
    val normalizedUserPubkey = cleanPubkeyFormat(userPubkey)

    val messages = allMessages.filter { message ->
        val msgPub = cleanPubkeyFormat(message.pubkey)
        val recipients = message.recipientPubkeys.map { cleanPubkeyFormat(it) }
        // Debug: log normalized values for every message so we can trace filtering
        Timber.d("Conversation debug: msgId=%s msgPub=%s recipients=%s normalizedConversationId=%s normalizedUser=%s",
            message.id, msgPub, recipients, normalizedConversationId, normalizedUserPubkey)

        val match = (msgPub == normalizedConversationId && recipients.contains(normalizedUserPubkey)) ||
                    (msgPub == normalizedUserPubkey && recipients.contains(normalizedConversationId))
        if (match) {
            Timber.d("Message matched for conversation: pubkey=%s createdAt=%d normalizedPub=%s recipients=%s", message.pubkey, message.createdAt, msgPub, recipients)
        } else {
            Timber.d("Message NOT matched for conversation: pubkey=%s createdAt=%d normalizedPub=%s recipients=%s", message.pubkey, message.createdAt, msgPub, recipients)
        }
        match
    }
    var newMessage by remember { mutableStateOf("") }

    var fetchedMeta by remember { mutableStateOf<com.hisa.data.model.Metadata?>(null) }
    val displayName = contactName ?: fetchedMeta?.name ?: conversationId.take(8) + "..."
    val displayPicture = contactProfilePicture ?: fetchedMeta?.picture

    // Initialize conversation, load messages and fetch metadata
    var isInitialized by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var recipientPubkey by remember { mutableStateOf<String?>(null) }

    // Targeted Nostr subscription for this conversation only
    LaunchedEffect(conversationId, userPubkey) {
        try {
            ConversationRepository.getOrCreateConversation(
                listOf(userPubkey, conversationId)
            )
            // Use the view model's per-conversation subscription
            recipientPubkey = conversationId
            // Subscribe to conversation-specific events (kind 1059 p-tag filters)
            messagesViewModel.subscribeToConversation(conversationId)
            isInitialized = true
            isError = false
            errorMessage = ""
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize conversation")
            isError = true
            errorMessage = "Failed to initialize conversation. Please try again."
        }
    }
    DisposableEffect(conversationId) {
        onDispose {
            // Unsubscribe when leaving the conversation screen
            messagesViewModel.unsubscribeConversation()
        }
    }
    // Observe send errors and surface in the same error UI
    LaunchedEffect(sendError) {
        sendError?.let { err ->
            isError = true
            errorMessage = err
            // Clear after showing so it doesn't persist
            messagesViewModel.clearMessages()
        }
    }
    // Fetch profile metadata if needed
    if ((contactName == null || contactProfilePicture == null) && fetchedMeta == null) {
        val profileMetaUtil = LocalProfileMetaUtil.current
        LaunchedEffect(conversationId) {
            profileMetaUtil.fetchProfileMetadata(conversationId) { meta ->
                fetchedMeta = meta
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (displayPicture != null && displayPicture.isNotBlank()) {
                    Image(
                        painter = rememberAsyncImagePainter(displayPicture),
                        contentDescription = "Profile Picture",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else if (fetchedMeta == null && (contactName == null || contactProfilePicture == null)) {
                    // Show shimmer/loading if fetching

                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default Profile Picture",
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(messages) { message ->
                    val isOwnMessage = message.pubkey == userPubkey
                    com.hisa.ui.components.MessageBubble(message, isOwnMessage)
                    Text(
                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = if (isOwnMessage) 48.dp else 8.dp, end = if (isOwnMessage) 8.dp else 48.dp, bottom = 2.dp)
                    )
                }
            }
            val focusManager = LocalFocusManager.current

            // centralized send logic
            val sendNow: () -> Unit = {
                if (newMessage.isNotBlank() && isInitialized && !isError && recipientPubkey != null) {
                    try {
                        messagesViewModel.sendMessage(recipientPubkey!!, newMessage)
                        newMessage = ""
                        isError = false
                        errorMessage = ""
                        focusManager.clearFocus()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to send message")
                        isError = true
                        errorMessage = "Failed to send message. Please try again."
                    }
                }
            }

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

                // Upload/attach button styled like the message field (white, subtle border, icon)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)), androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            // Save current draft so it can be restored after returning from Upload screen
                            navController?.currentBackStackEntry?.savedStateHandle?.set("compose_draft", newMessage)
                            navController?.navigate(com.hisa.ui.navigation.Routes.UPLOAD)
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
                val sendEnabled = newMessage.isNotBlank() && isInitialized && !isError && recipientPubkey != null
                Button(
                    onClick = sendNow,
                    enabled = sendEnabled,
                    shape = androidx.compose.foundation.shape.CircleShape,
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

    // Listen for uploaded media URL from UploadScreen, restore draft and append URLs to avoid replacing typed text
    LaunchedEffect(navController) {
        val saved = navController?.currentBackStackEntry?.savedStateHandle?.getLiveData<String>("uploaded_media_url")
        saved?.observeForever { url ->
            if (!url.isNullOrBlank()) {
                // restore draft saved before navigating to Upload only if current message is blank
                val draft = navController.currentBackStackEntry?.savedStateHandle?.get<String>("compose_draft") ?: ""

                // support multiple URLs separated by newline
                val parts = url.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isNotEmpty()) {
                    val combined = parts.joinToString(" ")
                    val base = if (newMessage.isBlank()) draft else newMessage
                    newMessage = if (base.isBlank()) combined else base + " " + combined
                }

                // clear saved keys to avoid re-triggering
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("uploaded_media_url")
                navController.currentBackStackEntry?.savedStateHandle?.remove<String>("compose_draft")
            }
        }
    }
}
