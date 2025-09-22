package com.hisa.ui.components

import android.content.Intent
import androidx.media3.common.MediaItem
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.SimpleExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import java.util.Locale
import java.util.regex.Pattern
import com.hisa.R


private data class LinkPreview(val title: String?, val description: String?, val image: String?)

/**
 * Shared media renderer used by message bubbles. Splits content into text and URL parts and
 * renders images inline (tap to fullscreen), shows a video placeholder for video URLs
 * (tap opens external player), and hides raw non-media URLs behind a "Link" label.
 */
@Composable
fun MediaText(content: String, isOwnMessage: Boolean) {
    val parts = splitTextByUrls(content)
    var fullscreenImageUrl by remember { mutableStateOf<String?>(null) }
    // move video state up so placeholders can set it while rendering
    var videoToPlay by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Column {
        parts.forEach { part ->
            when (part) {
                is ContentPart.TextPart -> {
                    Text(
                        text = part.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOwnMessage)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                is ContentPart.UrlPart -> {
                    val url = part.url
                    when {
                        isImageUrl(url) -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            // show the image and preserve its aspect ratio (no stretching)
                            Image(
                                painter = rememberAsyncImagePainter(url),
                                contentDescription = "image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 360.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .pointerInput(url) {
                                        detectTapGestures(onTap = { fullscreenImageUrl = url })
                                    },
                                // Crop to fill space and avoid letterboxing / black padding
                                contentScale = ContentScale.Crop
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        isVideoUrl(url) -> {
                            Spacer(modifier = Modifier.height(6.dp))
                            // attempt to show a video frame thumbnail using Coil; fall back to a neutral surface
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .pointerInput(url) {
                                        detectTapGestures(onTap = {
                                            // open in-app player
                                            videoToPlay = url
                                        })
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // Try to render a preview frame for the video URL
                                Image(
                                    painter = rememberAsyncImagePainter(url),
                                    contentDescription = "video preview",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp)),
                                    // Crop so the preview fills its container instead of showing black bars
                                    contentScale = ContentScale.Crop
                                )

                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "play",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        else -> {
                            // Enhanced URL preview: fetch page title / og tags asynchronously and show a richer preview
                            val parsed = try { Uri.parse(url) } catch (_: Exception) { null }
                            val host = parsed?.host ?: url
                            val favicon = parsed?.let { "https://www.google.com/s2/favicons?sz=64&domain=${it.host}" }

                            val previewState = remember { mutableStateOf<LinkPreview?>(null) }
                            val client = remember { OkHttpClient() }

                            LaunchedEffect(url) {
                                // fetch metadata off the main thread
                                val meta = try {
                                    withContext(Dispatchers.IO) {
                                        val req = Request.Builder().url(url).header("User-Agent", "Hisa/1.0").build()
                                        val resp = client.newCall(req).execute()
                                        val body = resp.body?.string()
                                        if (body != null) {
                                            // crude extraction: og:title, og:description, og:image, fall back to <title>
                                            val ogTitle = Regex("<meta[^>]*property=[\"']og:title[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(body)?.groups?.get(1)?.value
                                            val ogDesc = Regex("<meta[^>]*property=[\"']og:description[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(body)?.groups?.get(1)?.value
                                            val ogImage = Regex("<meta[^>]*property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(body)?.groups?.get(1)?.value
                                            val titleTag = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(body)?.groups?.get(1)?.value?.trim()
                                            LinkPreview(ogTitle ?: titleTag, ogDesc, ogImage)
                                        } else null
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                                previewState.value = meta
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                                    .pointerInput(url) {
                                        detectTapGestures(onTap = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        })
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // left: preview image or favicon
                                val preview = previewState.value
                                if (preview?.image != null) {
                                    Image(
                                        painter = rememberAsyncImagePainter(preview.image),
                                        contentDescription = "preview image",
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else if (favicon != null) {
                                    Image(
                                        painter = rememberAsyncImagePainter(favicon),
                                        contentDescription = "favicon",
                                        modifier = Modifier.size(40.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preview?.title ?: host,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    preview?.description?.let { desc ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = desc.take(140),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    } ?: run {
                                        Text(
                                            text = url.take(80),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "open",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }

        fullscreenImageUrl?.let { url ->
            Dialog(onDismissRequest = { fullscreenImageUrl = null }) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)) {
                    Image(
                        painter = rememberAsyncImagePainter(url),
                        contentDescription = "full image",
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures(onTap = { fullscreenImageUrl = null }) },
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    videoToPlay?.let { url ->
        VideoPlayerDialog(url = url, onDismiss = { videoToPlay = null }, lifecycleOwner = lifecycleOwner)
    }
    }


@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerDialog(url: String, onDismiss: () -> Unit, lifecycleOwner: LifecycleOwner) {
    val context = LocalContext.current

    // Remember a single ExoPlayer instance for the dialog
    val exoPlayer = remember(context) {
        SimpleExoPlayer.Builder(context).build()
    }

    // Prepare the media and ensure player is released when dialog is dismissed
    DisposableEffect(url, exoPlayer) {
        val mediaItem = MediaItem.fromUri(Uri.parse(url))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)) {
            var videoAspectRatio by remember { mutableStateOf<Float?>(null) }
            // listen for video size changes to compute aspect ratio
            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0 && videoSize.width > 0) {
                            videoAspectRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                        }
                    }
                }
                exoPlayer.addListener(listener)
                onDispose {
                    exoPlayer.removeListener(listener)
                }
            }

            // Choose size: fullscreen fills available space; otherwise respect video's aspect ratio to avoid stretching
            var isFullscreen by remember { mutableStateOf(true) }
            val activity = (LocalContext.current as? Activity)

            val playerModifier = remember(videoAspectRatio) {
                // when not fullscreen, we'll limit height and apply aspect ratio if known
                Modifier
            }

            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    // keep screen awake while playing
                    keepScreenOn = true

                    // preserve aspect ratio (fit into view)
                    try {
                        this.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                    } catch (_: Throwable) { }

                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }, update = { playerView ->
                // ensure player is attached
                if (playerView.player !== exoPlayer) playerView.player = exoPlayer
            }, modifier = if (isFullscreen) Modifier.fillMaxSize() else {
                // default non-fullscreen: use aspect ratio if available, otherwise use a fixed height
                if (videoAspectRatio != null && videoAspectRatio!! > 0f) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspectRatio!!)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                }
            })

            // Overlay controls: fullscreen toggle and Picture-in-Picture
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .align(Alignment.TopEnd)
                    .zIndex(2f)
                    .wrapContentSize(Alignment.TopEnd)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = {
                    // Try to enter Picture-in-Picture when supported
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                        try {
                            val ratio = Rational(16, 9)
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(ratio)
                                .build()
                            activity.enterPictureInPictureMode(params)
                        } catch (_: Exception) { }
                    }
                }) {
                    Icon(imageVector = Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = MaterialTheme.colorScheme.onSurface)
                }

                IconButton(onClick = {
                    // Toggle fullscreen: here we simply toggle a flag that switches layout
                    isFullscreen = !isFullscreen
                }) {
                    Icon(imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = "Toggle Fullscreen", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Observe lifecycle to pause/resume playback
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> exoPlayer.playWhenReady = false
                        Lifecycle.Event.ON_STOP -> exoPlayer.playWhenReady = false
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
        }
    }
}


private sealed class ContentPart {
    data class TextPart(val text: String) : ContentPart()
    data class UrlPart(val url: String) : ContentPart()
}

private fun splitTextByUrls(text: String): List<ContentPart> {
    val urlPattern = Pattern.compile(
        "((https?|ftp)://[^\\s/$.?#].[^\\s]*)",
        Pattern.CASE_INSENSITIVE
    )
    val matcher = urlPattern.matcher(text)
    var lastEnd = 0
    val parts = mutableListOf<ContentPart>()
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        if (start > lastEnd) {
            parts.add(ContentPart.TextPart(text.substring(lastEnd, start)))
        }
        parts.add(ContentPart.UrlPart(text.substring(start, end)))
        lastEnd = end
    }
    if (lastEnd < text.length) {
        parts.add(ContentPart.TextPart(text.substring(lastEnd)))
    }
    return parts
}

private fun isImageUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.getDefault())
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif") || lower.endsWith(".webp")
}

private fun isVideoUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.getDefault())
    return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".m3u8")
}
