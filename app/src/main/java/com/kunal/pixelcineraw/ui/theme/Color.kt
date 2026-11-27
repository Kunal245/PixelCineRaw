package com.kunal.pixelcineraw.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Cinema Design System v1.5.0 ─────────────────────────────────────────────
// Palette inspired by Blackmagic Cinema Monitor + Arri Alexa look

/** Canvas/Matte Obsidian – deepest background, 100% opaque */
val CinemaObsidian = Color(0xFF090A0C)

/** Elevated surface – secondary panel background */
val CinemaElevated = Color(0xFF121418)

/** Floating HUD pill background (86% alpha) */
val CinemaPillBg = Color(0xDB0B0C0E)

/** HUD pill background at 92% alpha for recording-critical elements */
val CinemaPillBgDark = Color(0xEB0B0C0E)

/** Hairline border – inactive (12% white) */
val CinemaBorderInactive = Color(0x1FFFFFFF)

/** Hairline border – active/selected (38% white) */
val CinemaBorderActive = Color(0x61FFFFFF)

/** Warm Tungsten Amber – active manual params, LUT/Log indicator */
val CinemaAmber = Color(0xFFF5A623)

/** Arri Tally Crimson – active recording tally & timecode */
val CinemaCrimson = Color(0xFFFF2D3B)

/** Neutral Studio White – primary telemetry text */
val CinemaWhite = Color(0xFFF3F4F6)

/** Muted Studio Gray – secondary/label text */
val CinemaGray = Color(0x99F3F4F6)

/** Deep Gray – tertiary text */
val CinemaGrayDeep = Color(0x55F3F4F6)

/** Auto mode indicator – cool green */
val CinemaAutoGreen = Color(0xFF34D399)

/** Manual mode indicator – amber */
val CinemaManualAmber = CinemaAmber

/** HDR/HLG indicator – warm gold */
val CinemaGold = Color(0xFFFFD700)

/** HDR10+ indicator – hot pink */
val CinemaHdrPink = Color(0xFFFF0080)

/** LOG indicator – cool cyan */
val CinemaCyan = Color(0xFF00C8E0)

/** REC.709 indicator – pure green */
val CinemaGreen = Color(0xFF00E676)

// ─── Cinema Design System v2.0.0 (Leica Lux / Halide Mark II / Kino) ─────────
/** Dynamic-Island & floating deck glass (#0D0F12 at 74% alpha) */
val CinemaCapsuleBg = Color(0xBD0D0F12)

/** Floating popover / quick-sheet glass (#0D0F12 at 86% alpha) */
val CinemaGlassSheetBg = Color(0xDB0D0F12)

/** Recording tally crimson-tinted capsule background */
val CinemaTallyBg = Color(0xD6120B0D)

/** Top 0.5dp specular glass highlight (#FFFFFF at 18% alpha) */
val CinemaSpecularTop = Color(0x2EFFFFFF)

/** Subtle bottom perimeter glass border (#FFFFFF at 8% alpha) */
val CinemaSpecularBottom = Color(0x14FFFFFF)

/** Anodized metallic bezel gradient colors */
val CinemaAnodizedLight = Color(0xFF3E434C)
val CinemaAnodizedDark = Color(0xFF17191E)

/** Deep Crimson Glass shutter tones */
val CinemaCrimsonGlassTop = Color(0xFFFF3D4A)
val CinemaCrimsonGlassCore = Color(0xFFD91826)

// Legacy Material3 colors (kept for Theme.kt compatibility)
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
