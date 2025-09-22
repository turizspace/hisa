package com.hisa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import coil.compose.rememberAsyncImagePainter
import com.hisa.data.model.ChannelMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.TimeUnit

@Composable
fun ChannelMessageBubble(
    message: ChannelMessage,
    isOwnMessage: Boolean,
    profilePicUrl: String? = null,
    displayName: String? = null,
    showProfileImage: Boolean = true,
    onProfileClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (isOwnMessage) {
            Spacer(modifier = Modifier.weight(1f))

            BubbleContent(
                message = message,
                isOwnMessage = isOwnMessage,
                displayName = displayName
            )

            if (showProfileImage) {
                Spacer(modifier = Modifier.width(8.dp))
                ProfileImage(profilePicUrl, onClick = onProfileClick)
            }
        } else {
            if (showProfileImage) {
                ProfileImage(profilePicUrl, onClick = onProfileClick)

                Spacer(modifier = Modifier.width(8.dp))
            }

            BubbleContent(
                message = message,
                isOwnMessage = isOwnMessage,
                displayName = displayName
            )
        }
    }
}

@Composable
fun DateSeparator(dateMillis: Long) {
    val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DATE, -1) }

    val label = when {
        isSameDay(cal, today) -> "Today"
        isSameDay(cal, yesterday) -> "Yesterday"
        withinLastWeek(dateMillis) -> {
            // Return weekday name
            SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(dateMillis))
        }
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(dateMillis))
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean {
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
            && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

private fun withinLastWeek(timeMillis: Long): Boolean {
    val now = System.currentTimeMillis()
    val diff = now - timeMillis
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return days in 1..6
}


@Composable
private fun BubbleContent(message: ChannelMessage, isOwnMessage: Boolean, displayName: String?) {
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .widthIn(max = 320.dp)
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape = MaterialTheme.shapes.medium)
            .background(
                color = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!isOwnMessage) {
            Text(
                text = displayName ?: message.authorPubkey.take(8) + "...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

    // Render message text but replace media links with inline media previews
    MediaText(message.content, isOwnMessage)

        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start)) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = if (isOwnMessage)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
            )
        }

        message.replyTo?.let { replyId ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Reply to: ${replyId.take(8)}...",
                style = MaterialTheme.typography.labelSmall,
                color = if (isOwnMessage)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start)
            )
        }

        if (message.mentions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Mentions: " + message.mentions.joinToString(", ") { it.take(8) + "..." },
                style = MaterialTheme.typography.labelSmall,
                color = if (isOwnMessage)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start)
            )
        }
    }
}




@Composable
private fun ProfileImage(profilePicUrl: String?, onClick: (() -> Unit)? = null) {
    if (!profilePicUrl.isNullOrEmpty()) {
        Image(
            painter = rememberAsyncImagePainter(profilePicUrl),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = onClick != null) { onClick?.invoke() },
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Default Profile Picture",
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(enabled = onClick != null) { onClick?.invoke() }
        )
    }

}

