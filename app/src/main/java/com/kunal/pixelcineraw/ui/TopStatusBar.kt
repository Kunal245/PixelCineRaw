package com.kunal.pixelcineraw.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.ColorMath
import com.kunal.pixelcineraw.camera.ShutterUnit
import com.kunal.pixelcineraw.camera.ThermalStatus

/**
 * Cinema top status bar displaying real-time camera parameters, battery, storage,
 * and thermal telemetry.
 */
@Composable
fun TopStatusBar(
    state: CinemaState,
    onShutterClick: () -> Unit,
    onIsoClick: () -> Unit,
    onWbClick: () -> Unit,
    onFpsClick: () -> Unit,
    onColorProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCC0D0D0D))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: FPS, Shutter, ISO, WB, Profile (Tap to edit)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // FPS Pill
            CinemaStatusPill(
                label = "FPS",
                value = "${state.targetFps}",
                subText = "${state.measuredFps.toInt()} ACT",
                onClick = onFpsClick
            )

            // Shutter Speed / Angle Pill
            val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
                "${state.shutterAngle.toInt()}°"
            } else {
                ColorMath.formatShutterSpeed(state.shutterSpeedNs)
            }
            CinemaStatusPill(
                label = if (state.shutterUnit == ShutterUnit.ANGLE) "SHUTTER ∠" else "SHUTTER SPD",
                value = shutterVal,
                highlight = state.isManualExposure,
                onClick = onShutterClick
            )

            // ISO Pill
            CinemaStatusPill(
                label = "ISO",
                value = if (state.isAutoIso) "AUTO ${state.iso}" else "${state.iso}",
                highlight = state.isManualExposure,
                onClick = onIsoClick
            )

            // White Balance Pill
            CinemaStatusPill(
                label = "WB",
                value = if (state.isManualWb) "${state.wbKelvin}K" else "AWB",
                subText = if (state.wbTint != 0) "${state.wbTint}T" else null,
                highlight = state.isManualWb,
                onClick = onWbClick
            )

            // Color Profile Pill
            CinemaStatusPill(
                label = "PROFILE",
                value = state.colorProfile.label,
                highlight = true,
                onClick = onColorProfileClick
            )
        }

        // Center / Recording indicator
        if (state.isRecording) {
            val mins = state.recordingTimeSeconds / 60
            val secs = state.recordingTimeSeconds % 60
            val timeString = String.format("%02d:%02d", mins, secs)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xCCFF0033), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.White, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "REC $timeString",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Right: Storage, Battery, USB-C Mic, Thermal status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // External Mic Indicator
            if (state.isExternalMicConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x3300E5FF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "USB Mic",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "USB-C",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Storage Remaining
            Text(
                text = "${state.remainingStorageGb.toInt()}GB • ${state.remainingRecordingMinutes}m",
                color = if (state.remainingRecordingMinutes < 15) Color(0xFFFF4444) else Color(0xCCFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Battery %
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BatteryFull,
                    contentDescription = "Battery",
                    tint = if (state.batteryPercent < 20) Color(0xFFFF4444) else Color(0xCCFFFFFF),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "${state.batteryPercent}%",
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Tensor G4 Thermal Badge
            ThermalIndicator(status = state.thermalStatus)
        }
    }
}

@Composable
fun CinemaStatusPill(
    label: String,
    value: String,
    subText: String? = null,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (highlight) Color(0xFFFFD700).copy(alpha = 0.5f) else Color(0x22FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0x88FFFFFF),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = if (highlight) Color(0xFFFFD700) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subText != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = subText,
                    color = Color(0xAAFFFFFF),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ThermalIndicator(status: ThermalStatus) {
    val (dotColor, text) = when (status) {
        ThermalStatus.NOMINAL -> Pair(Color(0xFF00FF66), "COOL")
        ThermalStatus.LIGHT -> Pair(Color(0xFFFFD700), "WARM")
        ThermalStatus.MODERATE -> Pair(Color(0xFFFF9900), "THROTTLE")
        ThermalStatus.SEVERE, ThermalStatus.EMERGENCY -> Pair(Color(0xFFFF2222), "HOT")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            color = dotColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
