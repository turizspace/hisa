package com.hisa.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer effect for skeleton loaders
 */
@Composable
fun shimmerEffect(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_effect"
    ).value
}

/**
 * Generic skeleton box with shimmer effect
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp)
) {
    val shimmer = shimmerEffect()
    val baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val shimmerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val color = baseColor.copy(alpha = 0.5f + (shimmer * 0.3f))
    
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
    )
}

/**
 * Feed item skeleton loader
 */
@Composable
fun FeedItemSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Header with avatar and title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            SkeletonBox(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            
            // Title and subtitle
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(10.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Content placeholder
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Action buttons
        Row(modifier = Modifier.fillMaxWidth()) {
            SkeletonBox(modifier = Modifier
                .weight(1f)
                .height(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            SkeletonBox(modifier = Modifier
                .weight(1f)
                .height(36.dp)
            )
        }
    }
}

/**
 * Message item skeleton loader
 */
@Composable
fun MessageItemSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        // Conversation header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            SkeletonBox(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            
            // Name and preview
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(10.dp)
                )
            }
            
            // Time
            SkeletonBox(
                modifier = Modifier
                    .width(40.dp)
                    .height(10.dp)
            )
        }
    }
}

/**
 * Stall item skeleton loader
 */
@Composable
fun StallItemSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            SkeletonBox(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            
            // Channel info
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBox(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(10.dp)
                )
            }
        }
    }
}

/**
 * Full feed skeleton loader (multiple items)
 */
@Composable
fun FeedSkeletonLoader(modifier: Modifier = Modifier, itemCount: Int = 5) {
    LazyColumn(modifier = modifier) {
        items(itemCount) {
            FeedItemSkeleton()
        }
    }
}

/**
 * Messages skeleton loader
 */
@Composable
fun MessagesSkeletonLoader(modifier: Modifier = Modifier, itemCount: Int = 6) {
    LazyColumn(modifier = modifier) {
        items(itemCount) {
            MessageItemSkeleton()
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Stalls skeleton loader
 */
@Composable
fun StallsSkeletonLoader(modifier: Modifier = Modifier, itemCount: Int = 8) {
    LazyColumn(modifier = modifier) {
        items(itemCount) {
            StallItemSkeleton()
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
