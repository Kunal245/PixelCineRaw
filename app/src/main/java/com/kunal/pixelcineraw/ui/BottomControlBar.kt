package com.kunal.pixelcineraw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.AntiBandingMode
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.ColorMath
import com.kunal.pixelcineraw.camera.FocusMode
import com.kunal.pixelcineraw.camera.LensSelection
import com.kunal.pixelcineraw.camera.ShutterUnit
import java.util.Locale

/**
 * Sleek bottom cinema exposure deck and optical lens selector.
 *
 * Houses Lens Pills (0.5x, 1x, 2x with mm equivalent), 5 Precision Exposure Pills
 * (SHUTTER, ISO, WB, FOCUS, EV) with Auto/Manual dots, and Stereo Audio VU meters.
 */
@Composable
fun BottomControlBar(
    state: CinemaState,
    onLensSelected: (LensSelection) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onZoomSliderVisibilityChanged: (Boolean) -> Unit,
    onShutterClick: () -> Unit,
    onIsoClick: () -> Unit,
    onWbClick: () -> Unit,
    onFocusClick: () -> Unit,
    onEvClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(Color(0xB30A0C10))
            .border(width = 0.5.dp, color = Color(0x26FFFFFF))
            .padding(horizontal = 68.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Lens & Zoom Cluster (0.5x, 1x, 2x with 35mm equivalent focal lengths)
        CinemaZoomHub(
            state = state,
            onZoomChanged = onZoomChanged,
            onSliderVisibilityChanged = onZoomSliderVisibilityChanged
        )

        // 2. 5 Precision Exposure Pills (SHUTTER, ISO, WB, FOCUS, EV)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // SHUTTER
            val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
                if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_50) {
                    val flickerSpeed = if (state.targetFps <= 30) "1/50s · 50Hz" else "1/100s · 50Hz"
                    "180° ($flickerSpeed)"
                } else if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_60) {
                    val flickerSpeed = if (state.targetFps <= 30) "1/60s · 60Hz" else "1/120s · 60Hz"
                    "180° ($flickerSpeed)"
                } else {
                    "${state.shutterAngle.toInt()}°"
                }
            } else {
                ColorMath.formatShutterSpeed(state.shutterSpeedNs)
            }
            ExposurePill(
                label = if (state.shutterUnit == ShutterUnit.ANGLE) "SHUTTER ∠" else "SHUTTER SPD",
                value = shutterVal,
                isAuto = !state.isManualExposure,
                onClick = onShutterClick
            )

            // ISO
            val isoVal = if (state.isAutoIso) "AUTO ${state.iso}" else "ISO ${state.iso}"
            ExposurePill(
                label = "SENSITIVITY",
                value = isoVal,
                isAuto = state.isAutoIso || !state.isManualExposure,
                onClick = onIsoClick
            )

            // WHITE BALANCE
            val wbVal = if (state.isManualWb) "${state.wbKelvin}K" else "AWB"
            ExposurePill(
                label = "WHITE BAL",
                value = wbVal,
                isAuto = !state.isManualWb,
                onClick = onWbClick
            )

            // FOCUS
            val focusVal = when (state.focusMode) {
                FocusMode.AUTO_CONTINUOUS -> "AF CONTINUOUS"
                FocusMode.LOCKED -> "AF LOCKED"
                FocusMode.MANUAL -> {
                    if (state.focusDistance <= 0.05f) "MF ∞"
                    else String.format(Locale.US, "MF %.1fm", 1.0f / state.focusDistance)
                }
            }
            ExposurePill(
                label = "FOCUS",
                value = focusVal,
                isAuto = state.focusMode != FocusMode.MANUAL,
                onClick = onFocusClick
            )

            // EV BIAS
            val evVal = if (state.evCompensation > 0f) "+%.1f EV".format(state.evCompensation)
            else "%.1f EV".format(state.evCompensation)
            ExposurePill(
                label = "EXPOSURE",
                value = evVal,
                isAuto = false,
                highlightColor = if (state.evCompensation != 0f) Color(0xFFFFCC00) else null,
                onClick = onEvClick
            )
        }

        // 3. Stereo Audio VU Meter
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AudioVuMeter(peakL = state.audioPeakDbL, peakR = state.audioPeakDbR)
        }
    }
}

@Composable
fun ExposurePill(
    label: String,
    value: String,
    isAuto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlightColor: Color? = null
) {
    Column(
        modifier = modifier
            .background(Color(0xB312141A), RoundedCornerShape(6.dp))
            .border(
                0.8.dp,
                highlightColor?.copy(alpha = 0.6f) ?: Color(0x26FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = Color(0x88FFFFFF),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            // Mode dot (Green for AUTO, Amber for MANUAL)
            Box(
                modifier = Modifier
                    .size(4.5.dp)
                    .clip(CircleShape)
                    .background(if (isAuto) Color(0xFF00FF66) else Color(0xFFFFCC00))
            )
        }
        Text(
            text = value,
            color = highlightColor ?: Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("L", color = Color(0x88FFFFFF), fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(3.dp))
            VuBar(peakDb = peakL)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", color = Color(0x88FFFFFF), fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(3.dp))
            VuBar(peakDb = peakR)
        }
    }
}

@Composable
fun VuBar(peakDb: Float) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .width(44.dp)
            .height(5.dp)
    ) {
        val cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = Color(0xFF16181D),
            cornerRadius = cornerRadius
        )
        val norm = ((peakDb + 60f) / 60f).coerceIn(0.04f, 1.0f)
        val barColor = when {
            peakDb > -6f -> Color(0xFFFF2222)
            peakDb > -18f -> Color(0xFFFFCC00)
            else -> Color(0xFF00FF66)
        }
        drawRoundRect(
            color = barColor,
            size = androidx.compose.ui.geometry.Size(size.width * norm, size.height),
            cornerRadius = cornerRadius
        )
    }
}
