package com.kunal.pixelcineraw.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// ─── Cinema Dark Color Scheme v1.5.0 ─────────────────────────────────────────
// Pure cinema dark theme – no dynamic color (would override our precision palette)
private val CinemaDarkColorScheme = darkColorScheme(
    primary = CinemaAmber,
    onPrimary = CinemaObsidian,
    primaryContainer = CinemaElevated,
    secondary = CinemaCyan,
    onSecondary = CinemaObsidian,
    tertiary = CinemaCrimson,
    background = CinemaObsidian,
    surface = CinemaElevated,
    onBackground = CinemaWhite,
    onSurface = CinemaWhite,
    onSurfaceVariant = CinemaGray,
    outline = CinemaBorderInactive,
    outlineVariant = CinemaBorderActive
)

@Composable
fun PixelCineRawTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CinemaDarkColorScheme,
        typography = Typography,
        content = content
    )
}
