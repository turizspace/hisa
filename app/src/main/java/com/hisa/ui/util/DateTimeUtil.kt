package com.hisa.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatTimeAgo(timestamp: Long): String {
    val currentTime = System.currentTimeMillis() / 1000
    val diffInSeconds = currentTime - timestamp

    return when {
        diffInSeconds < 60 -> "${diffInSeconds} seconds ago"
        diffInSeconds < 3600 -> "${diffInSeconds / 60} minutes ago"
        diffInSeconds < 86400 -> "${diffInSeconds / 3600} hours ago"
        diffInSeconds < 518400 -> "${diffInSeconds / 86400} days ago"
        else -> {
            val date = Date(timestamp * 1000)
            val formatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            formatter.format(date)
        }
    }
}