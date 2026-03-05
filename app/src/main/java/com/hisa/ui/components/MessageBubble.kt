package com.hisa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hisa.data.model.Message
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import coil.compose.rememberAsyncImagePainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hisa.ui.components.MediaText

@Composable
fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    displayName: String? = null,
    profilePicUrl: String? = null,
    ownDisplayName: String? = null,
    ownProfilePicUrl: String? = null,
    reactions: List<Message.ReactionMessage> = emptyList(),
    showProfileImage: Boolean = true
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val avatarSlot = if (showProfileImage) 42.dp else 0.dp
        val fractionalMax = maxWidth * 0.74f
        val layoutBoundMax = maxWidth - avatarSlot - 24.dp
        val bubbleMaxWidth = minOf(fractionalMax, layoutBoundMax).coerceAtLeast(160.dp)

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
                    displayName = ownDisplayName,
                    reactions = reactions,
                    maxBubbleWidth = bubbleMaxWidth
                )
                if (showProfileImage) {
                    Spacer(modifier = Modifier.width(8.dp))
                    ProfileImage(ownProfilePicUrl)
                }
            } else {
                if (showProfileImage) {
                    ProfileImage(profilePicUrl)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                BubbleContent(
                    message = message,
                    isOwnMessage = isOwnMessage,
                    displayName = displayName,
                    reactions = reactions,
                    maxBubbleWidth = bubbleMaxWidth
                )
            }
        }
    }
}


@Composable
private fun BubbleContent(
    message: Message,
    isOwnMessage: Boolean,
    displayName: String?,
    reactions: List<Message.ReactionMessage>,
    maxBubbleWidth: androidx.compose.ui.unit.Dp
) {
    val bubbleShape = if (isOwnMessage) {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 20.dp,
            bottomEnd = 8.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomStart = 8.dp,
            bottomEnd = 20.dp
        )
    }
    Column(
        modifier = Modifier
            .wrapContentWidth()
            .widthIn(max = maxBubbleWidth)
            .defaultMinSize(minHeight = 32.dp)
            .clip(shape = bubbleShape)
            .background(
                color = if (isOwnMessage) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .border(
                width = 1.dp,
                color = if (isOwnMessage) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
                },
                shape = bubbleShape
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (!isOwnMessage) {
            Text(
                text = displayName ?: message.pubkey.take(8) + "...",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
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
            is Message.ReactionMessage -> Text(
                text = "Reaction: ${reactionValueForUi(message.content)}",
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

        if (reactions.isNotEmpty()) {
            val reactionSummary = reactions
                .groupBy { reactionValueForUi(it.content) }
                .mapValues { (_, values) -> values.size }
                .toList()
                .sortedByDescending { (_, count) -> count }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                reactionSummary.forEach { (label, count) ->
                    ReactionChip(label = label, count = count)
                }
            }
        }
    }
}

@Composable
private fun ReactionChip(label: String, count: Int) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = shape
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (count > 1) "$label $count" else label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun reactionValueForUi(raw: String): String {
    return when (val normalized = raw.trim()) {
        "", "+" -> "\uD83D\uDC4D"
        "-" -> "\uD83D\uDC4E"
        else -> normalized
    }
}

@Composable
private fun ProfileImage(profilePicUrl: String?) {
    if (!profilePicUrl.isNullOrBlank()) {
        Image(
            painter = rememberAsyncImagePainter(profilePicUrl),
            contentDescription = "Profile Picture",
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = "Default Profile Picture",
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
        )
    }
}
