package com.hisa.ui.screens.messages

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hisa.data.model.Message
import com.hisa.ui.navigation.Routes
import com.hisa.ui.theme.AccentPrimary
import com.hisa.ui.theme.AccentSecondary
import com.hisa.ui.theme.GlassAlphaHigh
import com.hisa.ui.util.LocalProfileRepository
import com.hisa.ui.components.EmptyMessagesState
import com.hisa.ui.components.MessagesSkeletonLoader
import com.hisa.viewmodel.MessagesViewModel
import com.hisa.viewmodel.OrderNotificationsViewModel

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun MessagesTab(
    navController: NavController,
    userPubkey: String,
    privateKey: String,
    messagesViewModel: MessagesViewModel
) {
    // Keep the conversation list derived in the ViewModel so the UI doesn't rebuild it on each recomposition.
    val conversations by messagesViewModel.conversations.collectAsState()
    val isLoading by messagesViewModel.isLoading.collectAsState()
    val orderNotificationsViewModel: OrderNotificationsViewModel = hiltViewModel()
    val orders by orderNotificationsViewModel.orders.collectAsState()
    val unreadCount by orderNotificationsViewModel.unreadCount.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(userPubkey) {
        if (userPubkey.isNotBlank()) {
            messagesViewModel.ensureSubscribed()
        }
    }

    LaunchedEffect(userPubkey) {
        if (userPubkey.isNotBlank()) {
            orderNotificationsViewModel.startListeningForOrders(userPubkey)
        }
    }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf("Chats", "Orders").forEachIndexed { index, title ->
                val selected = selectedTab == index
                val statusCount = if (index == 1) unreadCount else 0
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            if (selected) AccentPrimary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { selectedTab = index },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (selected) AccentPrimary else MaterialTheme.colorScheme.onSurface
                        )
                        if (statusCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Badge(
                                containerColor = if (selected) AccentSecondary else MaterialTheme.colorScheme.primaryContainer,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ) {
                                Text(statusCount.toString())
                            }
                        }
                    }
                }
            }
        }

        when (selectedTab) {
            0 -> {
                // Show shimmer only on initial load when cache is empty
                if (isLoading && conversations.isEmpty()) {
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

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(conversations.entries.toList(), key = { it.key }) { entry ->
                        val otherPubkey = entry.key
                        val messages = entry.value
                        val metadata = profiles[otherPubkey]
                        val fallback = when {
                            otherPubkey == "unknown" -> "Unknown sender"
                            otherPubkey.length > 12 -> "${otherPubkey.take(12)}..."
                            else -> otherPubkey
                        }
                        val previewMessage = messages.firstOrNull {
                            it !is Message.ReactionMessage &&
                                !(it is Message.TextMessage && it.content == "Unable to decrypt message")
                        } ?: messages.firstOrNull()
                        val isUnread = false
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUnread) AccentPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        navController.navigate(Routes.DM.replace("{pubkey}", Uri.encode(otherPubkey)))
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (metadata?.picture != null && metadata.picture.isNotBlank()) {
                                        AsyncImage(
                                            model = metadata.picture,
                                            contentDescription = "Profile Picture",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape),
                                            error = rememberVectorPainter(Icons.Default.AccountCircle),
                                            placeholder = rememberVectorPainter(Icons.Default.AccountCircle)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Default Profile Picture",
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            metadata?.displayName ?: metadata?.name ?: fallback,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isUnread) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Badge(
                                                containerColor = AccentSecondary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ) {
                                                Text("New")
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = when (previewMessage) {
                                            is Message.TextMessage -> previewMessage.content
                                            is Message.FileMessage -> "File attached"
                                            is Message.ReactionMessage -> "Reaction ${previewMessage.content.ifBlank { "+" }}"
                                            null -> "No messages yet"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowForwardIos,
                                    contentDescription = "Open conversation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        }
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

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
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
                        val isUnread = !order.isRead && !isBuyer
                        val statusLabel = if (isBuyer) "Awaiting seller" else "Incoming"
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUnread) AccentPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (counterpartyPicture.isNotBlank()) {
                                            AsyncImage(
                                                model = counterpartyPicture,
                                                contentDescription = "Counterparty Picture",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape),
                                                error = rememberVectorPainter(Icons.Default.AccountCircle),
                                                placeholder = rememberVectorPainter(Icons.Default.AccountCircle)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.AccountCircle,
                                                contentDescription = "Order Partner Icon",
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = counterpartyTitle,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "$amountLabel • ${order.items.sumOf { it.quantity }} item${if (order.items.sumOf { it.quantity } != 1) "s" else ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = statusLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isUnread) AccentPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .background(
                                                if (isUnread) AccentPrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.24f),
                                                shape = RoundedCornerShape(999.dp)
                                            )
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = order.subject.ifBlank { "Continue order conversation" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Button(
                                        onClick = { navController.navigate(Routes.DM_ORDER.replace("{pubkey}", Uri.encode(counterpartyPubkey)).replace("{orderId}", Uri.encode(order.orderId))) },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AccentSecondary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        ),
                                        shape = RoundedCornerShape(24.dp)
                                    ) {
                                        Text(text = if (isBuyer) "Track order" else "View order")
                                    }
                                }
                            }
                        }
                        }
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
