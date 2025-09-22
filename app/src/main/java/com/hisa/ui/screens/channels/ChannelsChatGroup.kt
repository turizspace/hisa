package com.hisa.ui.screens.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hisa.data.model.Channel
import com.hisa.ui.navigation.Routes

@Composable
fun ChannelsChatGroup(
    channel: Channel,
    onOpen: (Channel) -> Unit,
    participantCount: Int = 0
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onOpen(channel) },
        tonalElevation = 1.dp
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            // Channel picture
            if (channel.picture.isNotEmpty()) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.padding(end = 8.dp)) {
                    coil.compose.AsyncImage(
                        model = channel.picture,
                        contentDescription = "Channel picture",
                        modifier = Modifier
                            .size(48.dp)
                            .padding(4.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = channel.about,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Show unique participant count (number of distinct pubkeys that have sent messages)
            Text(text = "$participantCount", style = MaterialTheme.typography.labelLarge)
        }
    }
}
