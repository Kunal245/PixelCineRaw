package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.FocusMode
import com.kunal.pixelcineraw.camera.FrameGuide
import com.kunal.pixelcineraw.camera.LensSelection

/**
 * Bottom cinema control bar containing Lens Selection (0.5x, 1x, 2x),
 * Quick Overlay Toggles, Manual Focus trigger, and the Cinema Record Button.
 */
@Composable
fun BottomControlBar(
    state: CinemaState,
    onLensSelected: (LensSelection) -> Unit,
    onFocusDialClick: () -> Unit,
    onToggleLut: () -> Unit,
    onTogglePeaking: () -> Unit,
    onToggleZebras: () -> Unit,
    onToggleHistogram: () -> Unit,
    onCycleGuide: () -> Unit,
    onToggleStabilization: () -> Unit,
    onRecordClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0D0D0D))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Lens Switcher (0.5x UW, 1x Wide, 2x Lossless In-Sensor Crop)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LensPill(
                label = "0.5x",
                isSelected = state.selectedLens == LensSelection.ULTRAWIDE,
                onClick = { onLensSelected(LensSelection.ULTRAWIDE) }
            )
            LensPill(
                label = "1.0x",
                isSelected = state.selectedLens == LensSelection.WIDE_1X,
                onClick = { onLensSelected(LensSelection.WIDE_1X) }
            )
            LensPill(
                label = "2.0x",
                subText = "48M",
                isSelected = state.selectedLens == LensSelection.CROP_2X,
                onClick = { onLensSelected(LensSelection.CROP_2X) }
            )
        }

        // 2. Monitoring & Tools Toolbar
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolButton(
                label = "FOCUS",
                isActive = state.focusMode == FocusMode.MANUAL,
                onClick = onFocusDialClick
            )
            ToolButton(
                label = "LUT",
                isActive = state.showLutPreview,
                onClick = onToggleLut
            )
            ToolButton(
                label = "PEAK",
                isActive = state.showFocusPeaking,
                onClick = onTogglePeaking
            )
            ToolButton(
                label = "ZEBRA",
                isActive = state.showZebras,
                onClick = onToggleZebras
            )
            ToolButton(
                label = "HIST",
                isActive = state.showHistogram,
                onClick = onToggleHistogram
            )
            ToolButton(
                label = if (state.frameGuide == FrameGuide.NONE) "GUIDE" else state.frameGuide.label.substringBefore(" "),
                isActive = state.frameGuide != FrameGuide.NONE,
                onClick = onCycleGuide
            )
            ToolButton(
                label = if (state.oisEnabed) "OIS" else "STAB OFF",
                isActive = state.oisEnabed,
                onClick = onToggleStabilization
            )
        }

        // 3. Audio Meter + Record Shutter Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stereo VU meters
            AudioVuMeter(peakL = state.audioPeakDbL, peakR = state.audioPeakDbR)

            // Large Record Button
            RecordShutterButton(
                isRecording = state.isRecording,
                onClick = onRecordClick
            )
        }
    }
}

@Composable
fun LensPill(
    label: String,
    subText: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (isSelected) Color(0xFFFFD700) else Color(0xFF222222),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            if (subText != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = subText,
                    color = if (isSelected) Color(0x99000000) else Color(0xFFFFD700),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ToolButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .background(
                if (isActive) Color(0x3300E5FF) else Color(0xFF1E1E1E),
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (isActive) Color(0xFF00E5FF) else Color(0x22FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color(0xFF00E5FF) else Color(0xCCFFFFFF),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun AudioVuMeter(peakL: Float, peakR: Float) {
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VuBar(peakDb = peakL)
        VuBar(peakDb = peakR)
    }
}

@Composable
fun VuBar(peakDb: Float) {
    // peakDb ranges from -60dB (silence) to 0dB (clipping)
    val norm = ((peakDb + 60f) / 60f).coerceIn(0.05f, 1.0f)

    Row(
        modifier = Modifier
            .width(42.dp)
            .height(5.dp)
            .background(Color(0xFF1A1A1A), RoundedCornerShape(2.dp))
    ) {
        val barColor = when {
            peakDb > -6f -> Color(0xFFFF2222) // Clip red
            peakDb > -18f -> Color(0xFFFFD700) // Caution yellow
            else -> Color(0xFF00FF66) // Green nominal
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(norm)
                .background(barColor, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun RecordShutterButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "RecPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RecScale"
    )

    Box(
        modifier = Modifier
            .size(54.dp)
            .border(3.dp, Color.White, CircleShape)
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            // Square stop indicator pulsing
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .scale(pulseScale)
                    .background(Color(0xFFFF0033), RoundedCornerShape(4.dp))
            )
        } else {
            // Circle red record trigger
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFF0033), CircleShape)
            )
        }
    }
}
