package com.hisa.ui.screens.messages

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import com.hisa.viewmodel.OrderNotificationsViewModel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hisa.viewmodel.MessagesViewModel
import com.hisa.ui.navigation.Routes
import com.hisa.data.model.Message
import com.hisa.ui.util.LocalProfileRepository
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
    val orderNotificationsViewModel: OrderNotificationsViewModel = hiltViewModel()
    val orders by orderNotificationsViewModel.orders.collectAsState()
    val unreadCount by orderNotificationsViewModel.unreadCount.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        messagesViewModel.ensureSubscribed()
    }

    LaunchedEffect(userPubkey) {
        if (userPubkey.isNotBlank()) {
            orderNotificationsViewModel.startListeningForOrders(userPubkey)
        }
    }

    val conversations = remember(allMessages) { messagesViewModel.getConversations() }
    val profileRepository = LocalProfileRepository.current
    val profiles by profileRepository.profiles.collectAsState()

    LaunchedEffect(conversations.keys) {
        profileRepository.ensureProfiles(
            conversations.keys
                .filter { it != "unknown" }
                .toSet()
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
            listOf("Chats", "Orders").forEachIndexed { index, title ->
                val displayTitle = if (index == 1 && unreadCount > 0) {
                    "$title ($unreadCount)"
                } else {
                    title
                }
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(displayTitle) }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                if (isLoading) {
                    MessagesSkeletonLoader(
                        modifier = Modifier.fillMaxSize(),
                        itemCount = 6
                    )
                    return@Column
                }

                if (conversations.isEmpty()) {
                    EmptyMessagesState(
                        modifier = Modifier.fillMaxSize(),
                        onStartConversation = null
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations.entries.toList(), key = { it.key }) { entry ->
                        val otherPubkey = entry.key
                        val messages = entry.value
                        val metadata = profiles[otherPubkey]
                        val fallback = when {
                            otherPubkey == "unknown" -> "Unknown sender"
                            otherPubkey.length > 12 -> "${otherPubkey.take(12)}..."
                            else -> otherPubkey
                        }
                        ListItem(
                            headlineContent = {
                                Text(metadata?.displayName ?: metadata?.name ?: fallback)
                            },
                            supportingContent = {
                                val previewMessage = messages.firstOrNull {
                                    it !is Message.ReactionMessage &&
                                        !(it is Message.TextMessage && it.content == "Unable to decrypt message")
                                } ?: messages.firstOrNull()
                                Text(when (previewMessage) {
                                    is Message.TextMessage -> previewMessage.content
                                    is Message.FileMessage -> "[File] ${previewMessage.fileUrl}"
                                    is Message.ReactionMessage -> "Reaction ${previewMessage.content.ifBlank { "+" }}"
                                    null -> "No messages"
                                })
                            },
                            leadingContent = {
                                if (metadata?.picture != null && metadata.picture.isNotBlank()) {
                                    AsyncImage(
                                        model = metadata.picture,
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
            1 -> {
                if (orders.isEmpty()) {
                    EmptyMessagesState(
                        modifier = Modifier.fillMaxSize(),
                        onStartConversation = null
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(orders, key = { it.orderId }) { order ->
                        val amountLabel = when {
                            order.currency.equals("USD", ignoreCase = true) -> "\$${order.amount}"
                            order.currency.equals("SATS", ignoreCase = true) -> "${order.amount} sats"
                            else -> "${order.amount} ${order.currency.uppercase()}"
                        }
                        val counterpartyPubkey = order.counterpartyPubkey(userPubkey)
                        val counterpartyTitle = order.counterpartyDisplayName(userPubkey)
                        val counterpartyPicture = order.counterpartyPicture(userPubkey)
                        val isBuyer = order.isBuyer(userPubkey)
                        val orderStatusLabel = if (isBuyer) "You ordered from" else "Order received from"
                        ListItem(
                            headlineContent = { Text("$orderStatusLabel $counterpartyTitle") },
                            supportingContent = {
                                Text("${order.subject} • ${order.items.sumOf { it.quantity }} item${if (order.items.sumOf { it.quantity } != 1) "s" else ""} • $amountLabel")
                            },
                            leadingContent = {
                                if (counterpartyPicture.isNotBlank()) {
                                    AsyncImage(
                                        model = counterpartyPicture,
                                        contentDescription = "Counterparty Picture",
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
                                        contentDescription = "Order Partner Icon",
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                navController.navigate(
                                    Routes.DM_ORDER
                                        .replace("{pubkey}", Uri.encode(counterpartyPubkey))
                                        .replace("{orderId}", Uri.encode(order.orderId))
                                )
                                orderNotificationsViewModel.markOrderAsRead(order.orderId)
                            }
                        )
                        HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                    }
                }
            }
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
