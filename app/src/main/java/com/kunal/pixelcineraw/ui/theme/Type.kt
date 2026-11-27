package com.kunal.pixelcineraw.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Cinema Typography System v1.5.0 ─────────────────────────────────────────
// All live-updating numeric fields use fontFeatureSettings = "tnum" (tabular figures)
// so digits never jitter horizontally when updating at 60fps.

/** Tabular figure feature tag for all live numeric displays */
const val TABULAR_NUMS = "tnum"

/** TextStyle extension: applies tabular nums for jitter-free HUD readouts */
val TextStyle.tabular get() = this.copy(fontFeatureSettings = TABULAR_NUMS)

val Typography = Typography(
    // Cinema HUD label (7.5–9sp) – used for SHUTTER, ISO, WB labels
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 8.sp,
        lineHeight = 10.sp,
        letterSpacing = 0.08.sp
    ),
    // Cinema HUD value (10–12sp) – live numbers, timecode, zoom
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_NUMS
    ),
    // Cinema pill header / section title
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.06.sp
    ),
    // Body for popovers / menus
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    // Section / popover title
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    )
)
