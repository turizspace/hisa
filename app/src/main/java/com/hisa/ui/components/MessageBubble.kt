package com.hisa.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hisa.data.model.Message
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import coil.compose.rememberAsyncImagePainter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: Message,
    isOwnMessage: Boolean,
    profilePicUrl: String? = null,
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
    reactions: List<Message.ReactionMessage>,
    maxBubbleWidth: androidx.compose.ui.unit.Dp
) {
    val bubbleShape = if (isOwnMessage) {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    }
    val bubbleColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOwnMessage) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val metadataColor = textColor.copy(alpha = 0.68f)

    Column(
        modifier = Modifier
            .wrapContentWidth()
            .widthIn(max = maxBubbleWidth)
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape = bubbleShape)
            .background(color = bubbleColor)
            .border(
                width = 1.dp,
                color = if (isOwnMessage) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                },
                shape = bubbleShape
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        when (message) {
            is Message.TextMessage -> Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = textColor
            )
            is Message.FileMessage -> Text(
                text = "📎 ${message.fileUrl}",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = textColor
            )
            is Message.ReactionMessage -> Text(
                text = "Reaction: ${reactionValueForUi(message.content)}",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = textColor
            )
            else -> Text("")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOwnMessage) Arrangement.End else Arrangement.Start
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAt * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = metadataColor
            )
        }

        val replyToId = message.replyTo
        if (replyToId != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Reply to: ${replyToId.take(8)}...",
                style = MaterialTheme.typography.labelSmall,
                color = metadataColor,
                modifier = Modifier.align(if (isOwnMessage) Alignment.End else Alignment.Start)
            )
        }

        if (reactions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            val reactionSummary = reactions
                .groupBy { reactionValueForUi(it.content) }
                .mapValues { (_, values) -> values.size }
                .toList()
                .sortedByDescending { (_, count) -> count }

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
