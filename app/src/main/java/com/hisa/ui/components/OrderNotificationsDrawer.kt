package com.hisa.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.hisa.data.model.Order
import com.hisa.ui.theme.AccentPrimary
import com.hisa.ui.theme.AccentSecondary
import com.hisa.ui.theme.GlassAlphaHigh
import com.hisa.ui.util.formatTimeAgo
import com.hisa.viewmodel.OrderNotificationsViewModel

/**
 * Bottom drawer showing order notifications
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderNotificationsDrawer(
    notificationsViewModel: OrderNotificationsViewModel,
    onDismiss: () -> Unit,
    onOrderClick: (Order) -> Unit = {}
) {
    val recentOrders = notificationsViewModel.recentUnreadOrders.collectAsState().value
    val unreadCount = notificationsViewModel.unreadCount.collectAsState().value
    val incomingOrders = notificationsViewModel.incomingOrders.collectAsState().value
    val currentUserPubkey = notificationsViewModel.currentUserPubkey.collectAsState().value

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 10.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Order pulse",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$unreadCount unread order${if (unreadCount != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { notificationsViewModel.markAllAsRead() }) {
                        Text("Mark all read")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))

            // Orders list - show only incoming orders (where user is merchant)
            if (incomingOrders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No incoming orders",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(incomingOrders) { order ->
                        OrderNotificationCard(
                            order = order,
                            currentUserPubkey = currentUserPubkey,
                            onCardClick = {
                                onOrderClick(order)
                                if (!order.isRead) {
                                    notificationsViewModel.markOrderAsRead(order.orderId)
                                }
                            },
                            onMarkRead = {
                                notificationsViewModel.markOrderAsRead(order.orderId)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual order notification card
 */
@Composable
fun OrderNotificationCard(
    order: Order,
    currentUserPubkey: String?,
    onCardClick: () -> Unit,
    onMarkRead: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (order.isRead) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            val orderAmountText = formatOrderPrice(order.amount.toString(), order.currency)
            val isBuyerOrder = currentUserPubkey?.equals(order.buyerPubkey, ignoreCase = true) == true
            val counterpartyName = if (isBuyerOrder) order.sellerDisplayName else order.buyerDisplayName
            val counterpartyPicture = if (isBuyerOrder) order.sellerPicture else order.buyerPicture
            val counterpartyLabel = if (isBuyerOrder) "Seller" else "Buyer"

            // Counterparty info row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Counterparty avatar
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (counterpartyPicture.isNotBlank()) {
                        AsyncImage(
                            model = counterpartyPicture,
                            contentDescription = "$counterpartyLabel avatar",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = counterpartyName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = counterpartyName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = orderAmountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Status indicator
                if (!order.isRead) {
                    Surface(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.error
                    ) {}
                }
            }

            // Items summary
            if (order.items.isNotEmpty()) {
                Text(
                    text = buildString {
                        append("Items: ")
                        append(order.items.sumOf { it.quantity })
                        append(" item${if (order.items.sumOf { it.quantity } != 1) "s" else ""}")
                        append(" • Total: ")
                        append(orderAmountText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Show first product name if available
                val firstItem = order.items.firstOrNull()
                if (firstItem != null) {
                    Text(
                        text = buildString {
                            if (firstItem.productName.isNotBlank()) {
                                append(firstItem.productName)
                            }
                            if (!firstItem.productPrice.isNullOrBlank()) {
                                if (firstItem.productName.isNotBlank()) append(" • ")
                                append(firstItem.productPrice)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Timestamp and action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimeAgo(order.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!order.isRead) {
                    Button(
                        onClick = onMarkRead,
                        modifier = Modifier.height(24.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Read", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
