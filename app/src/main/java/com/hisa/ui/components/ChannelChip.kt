package com.hisa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hisa.data.model.Channel

@Composable
fun ChannelChip(
    channel: Channel,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel picture
            if (channel.picture.isNotEmpty()) {
                AsyncImage(
                    model = channel.picture,
                    contentDescription = "Channel picture",
                    modifier = Modifier
                        .size(48.dp)
                        .padding(4.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            
            // Channel info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = channel.about,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2
                )
                
                // Categories as chips
                if (channel.categories.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        channel.categories.take(3).forEach { category ->
                            SuggestionChip(
                                onClick = { },
                                label = { Text(category) }
                            )
                        }
                    }
                }
            }
        }
    }
}
