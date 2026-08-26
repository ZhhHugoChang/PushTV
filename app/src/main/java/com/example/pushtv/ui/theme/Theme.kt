package com.example.pushtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

object PushTVColors {
    val Background = Color(0xFF080A0F)
    val BackgroundTop = Color(0xFF111827)
    val Surface = Color(0xFF0F172A)
    val Panel = Color(0xFF1E293B)
    val SurfaceFocused = Color(0xFF243247)
    val Primary = Color(0xFF38BDF8)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSoft = Color(0xFFE2E8F0)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val Favorite = Color(0xFFF472B6)
}

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
private val TVColorPalette = darkColorScheme(
    background = PushTVColors.Background,
    surface = PushTVColors.Surface,
    surfaceVariant = PushTVColors.Panel,
    primary = PushTVColors.Primary,
    onPrimary = PushTVColors.TextPrimary,
    onBackground = PushTVColors.TextPrimary,
    onSurface = PushTVColors.TextPrimary,
    onSurfaceVariant = PushTVColors.TextSecondary,
    error = PushTVColors.Error
)

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun PushTVTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TVColorPalette,
        content = content
    )
}
