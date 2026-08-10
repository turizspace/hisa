package com.hisa.ui.theme

import android.os.Build
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Neon / accent palette
val NeonCyan = Color(0xFF00FFF6)
val NeonPurple = Color(0xFF7C4DFF)
val NeonPink = Color(0xFFFF2D95)

// Neutral surfaces tuned for deep dark mode
val GlassSurface = Color(0xFF0F1113)
val GlassSurfaceVariant = Color(0xFF131416)
val GlassBackground = Color(0xFF070708)

// Accent tokens
val AccentPrimary = NeonPurple
val AccentSecondary = NeonCyan

// Alpha values for glassmorphism layers
const val GlassAlphaHigh = 0.92f
const val GlassAlphaMedium = 0.72f
const val GlassAlphaLow = 0.36f

// Motion timings
object Motion {
    const val Fast = 120
    const val Medium = 240
    const val Slow = 420
}

fun lightColorSchemeFromTokens() = lightColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.White,
    secondary = AccentSecondary,
    onSecondary = Color.Black,
    background = Color(0xFFF6F7FB),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    error = Color(0xFFB00020)
)

fun darkColorSchemeFromTokens() = darkColorScheme(
    primary = AccentPrimary,
    onPrimary = Color.Black,
    secondary = AccentSecondary,
    onSecondary = Color.Black,
    background = GlassBackground,
    onBackground = Color(0xFFECEFF1),
    surface = GlassSurface,
    onSurface = Color(0xFFECEFF1),
    error = Color(0xFFCF6679)
)
