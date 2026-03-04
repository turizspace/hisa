package com.hisa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Generic empty state with icon, title, and message
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * Empty Feed State
 */
@Composable
fun EmptyFeedState(
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Default.Notes,
        title = "No Services Available",
        message = "Start following creators or check back later for new services to appear in your feed.",
        modifier = modifier,
        actionLabel = if (onRefresh != null) "Refresh Feed" else null,
        onAction = onRefresh
    )
}

/**
 * Empty Messages State
 */
@Composable
fun EmptyMessagesState(
    modifier: Modifier = Modifier,
    onStartConversation: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Default.Mail,
        title = "No Messages",
        message = "Start a new conversation with someone or wait for messages to appear here.",
        modifier = modifier,
        actionLabel = if (onStartConversation != null) "Start Conversation" else null,
        onAction = onStartConversation
    )
}

/**
 * Empty Channels State
 */
@Composable
fun EmptyChannelsState(
    modifier: Modifier = Modifier,
    onCreateChannel: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Default.Group,
        title = "No Channels",
        message = "Create a new channel or join existing ones to connect with communities.",
        modifier = modifier,
        actionLabel = if (onCreateChannel != null) "Create Channel" else null,
        onAction = onCreateChannel
    )
}

/**
 * Empty Shop State
 */
@Composable
fun EmptyShopState(
    modifier: Modifier = Modifier,
    onBrowseServices: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Default.ShoppingCart,
        title = "No Items",
        message = "Start shopping or browse available services to find what you're looking for.",
        modifier = modifier,
        actionLabel = if (onBrowseServices != null) "Browse Services" else null,
        onAction = onBrowseServices
    )
}

/**
 * Generic error state
 */
@Composable
fun ErrorState(
    error: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Default.Notes,
        title = "Something Went Wrong",
        message = error,
        modifier = modifier,
        actionLabel = if (onRetry != null) "Retry" else null,
        onAction = onRetry
    )
}

/**
 * Search Results Empty State
 */
@Composable
fun SearchEmptyState(
    searchQuery: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Notes,
            contentDescription = "No results",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "No Results Found",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = "No items match \"$searchQuery\"\n\nTry searching with different keywords.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
