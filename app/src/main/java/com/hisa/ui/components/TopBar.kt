package com.hisa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hisa.viewmodel.OrderNotificationsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    title: String,
    onProfileClick: () -> Unit,
    onNotificationBellClick: () -> Unit = {},
    notificationsViewModel: OrderNotificationsViewModel? = hiltViewModel()
) {
    val unreadCount = notificationsViewModel?.unreadCount?.collectAsState()?.value ?: 0

    CenterAlignedTopAppBar(
        title = { Text(title) },
        actions = {
            // Notification bell with badge
            if (notificationsViewModel != null) {
                Box {
                    IconButton(onClick = onNotificationBellClick) {
                        Icon(
                            Icons.Default.NotificationsActive,
                            contentDescription = "Notifications",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Green dot indicator for new orders
                    if (unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = (-6).dp, y = 6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
            
            // Profile button
            IconButton(onClick = onProfileClick) {
                Icon(Icons.Default.Person, contentDescription = "Profile")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
