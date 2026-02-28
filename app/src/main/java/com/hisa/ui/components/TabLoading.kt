package com.hisa.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun rememberTabLoadingVisibility(
    isLoading: Boolean,
    showDelayMillis: Long = 120L,
    minVisibleMillis: Long = 550L
): Boolean {
    var isVisible by remember { mutableStateOf(false) }
    var shownAtMillis by remember { mutableLongStateOf(0L) }
    val showDelay = showDelayMillis.coerceAtLeast(0L)
    val minVisible = minVisibleMillis.coerceAtLeast(0L)

    LaunchedEffect(isLoading) {
        if (isLoading) {
            if (!isVisible) {
                delay(showDelay)
                if (isLoading && !isVisible) {
                    isVisible = true
                    shownAtMillis = SystemClock.elapsedRealtime()
                }
            }
        } else if (isVisible) {
            val elapsed = (SystemClock.elapsedRealtime() - shownAtMillis).coerceAtLeast(0L)
            val remaining = (minVisible - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            isVisible = false
        }
    }

    return isVisible
}

@Composable
fun TabLoadingPlaceholder(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "tab-loading")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tab-loading-pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(pulse)
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            repeat(3) { idx ->
                val width = when (idx) {
                    0 -> 1f
                    1 -> 0.82f
                    else -> 0.66f
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .height(14.dp)
                        .alpha(pulse)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    }
}
