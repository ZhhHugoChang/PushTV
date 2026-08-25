package com.example.pushtv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF080A0F)
val Surface = Color(0xFF11141B)
val SurfaceElevated = Color(0xFF181C25)
val Primary = Color(0xFF5B8CFF)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFA7ACB8)
val TextDisabled = Color(0xFF686D78)
val Success = Color(0xFF35D07F)
val Warning = Color(0xFFFFB84D)
val Error = Color(0xFFFF5C67)

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
private val TVColorPalette = darkColorScheme(
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceElevated,
    primary = Primary,
    onPrimary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = Error
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
