package com.hisa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import com.hisa.data.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hisa.ui.components.MediaText

@Composable
fun MessageBubble(message: Message, isOwnMessage: Boolean) {
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
                isOwnMessage = isOwnMessage
            )
        } else {
            BubbleContent(
                message = message,
                isOwnMessage = isOwnMessage
            )
        }
    }
}


@Composable
private fun BubbleContent(message: Message, isOwnMessage: Boolean) {
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
                text = message.pubkey.take(8) + "...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        when (message) {
            is Message.TextMessage -> MediaText(message.content, isOwnMessage)
            is Message.FileMessage -> Text(
                text = "[File] ${message.fileUrl}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isOwnMessage)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
            else -> Text("")
        }

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
    }
}