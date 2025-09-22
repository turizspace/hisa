package com.hisa.ui.screens.lists

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.hisa.data.model.Channel
import com.hisa.data.model.ChannelMessage
import com.hisa.data.nostr.NostrClient
import com.hisa.data.nostr.SubscriptionManager
import com.hisa.viewmodel.ChannelDetailViewModel
import com.hisa.viewmodel.ChannelDetailViewModelFactory

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ChannelDetailScreen(
    channelId: String,
    nostrClient: NostrClient,
    subscriptionManager: SubscriptionManager,
    privateKey: ByteArray?,
    userPubkey: String,
    viewModel: ChannelDetailViewModel = viewModel(
        factory = ChannelDetailViewModelFactory(
            nostrClient = nostrClient,
            subscriptionManager = subscriptionManager,
            channelId = channelId,
            privateKey = privateKey,
            pubkey = userPubkey
        )
    )
) {
    var message by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val channel by viewModel.channel.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Channel Header
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                        channel?.picture?.takeIf { it.isNotEmpty() }?.let { pic ->
                            AsyncImage(
                                model = pic,
                                contentDescription = "Channel picture",
                                modifier = Modifier
                                    .size(64.dp)
                                    .padding(4.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        }
                    
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = channel?.name ?: "",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = channel?.about ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                
                // Categories
        channel?.categories?.takeIf { it.isNotEmpty() }?.let { cats ->
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        cats.forEach { category ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(category) }
                            )
                        }
                    }
                }
            }
        }

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(messages) { message ->
                MessageItem(message = message)
            }
        }

        // Message Input
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") }
                )
                
                IconButton(
                    onClick = {
                        if (message.isNotEmpty()) {
                            // use the channelId parameter (already available) instead of referencing channel.id
                            viewModel.sendMessage(channelId, message)
                            message = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message"
                    )
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun MessageItem(message: ChannelMessage) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.authorPubkey.take(8) + "...",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = formatTimestamp(message.createdAt),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun formatTimestamp(timestamp: Long): String {
    // TODO: Implement proper timestamp formatting
    return java.time.Instant.ofEpochSecond(timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
        .toString()
}
