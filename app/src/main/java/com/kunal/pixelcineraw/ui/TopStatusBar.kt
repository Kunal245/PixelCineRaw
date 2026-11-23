package com.kunal.pixelcineraw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
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
import com.kunal.pixelcineraw.camera.RecordingFormat
import com.kunal.pixelcineraw.camera.ShutterUnit
import com.kunal.pixelcineraw.camera.ThermalStatus
import java.util.Locale

/**
 * Sleek Blackmagic / Arri inspired cinema top status bar displaying real-time
 * camera parameters and live hardware telemetry without number jitter.
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
            .background(Color(0xD90D0F12))
            .border(width = 0.5.dp, color = Color(0x33FFFFFF))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: FPS, Shutter, ISO, WB, Profile (Tap to open tactile rotary scrubbers)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            // Resolution / Format Pill (Blackmagic style)
            CinemaStatusPill(
                label = if (state.recordingFormat == RecordingFormat.RAW_DNG) "RAW" else state.videoCodec.label.substringBefore(" "),
                value = state.videoResolution.aspectLabel,
                subText = "${state.videoResolution.width}p",
                highlight = true,
                onClick = onColorProfileClick
            )
        }

        // Center: High-contrast Recording Badge & Timecode
        if (state.isRecording) {
            val mins = state.recordingTimeSeconds / 60
            val secs = state.recordingTimeSeconds % 60
            val timeString = String.format(Locale.US, "%02d:%02d", mins, secs)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color(0xDDFF0033), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
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

        // Right: Real Live Telemetry (USB-C Mic, Internal Storage, Battery %, Tensor G4 Thermal)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // USB-C External Mic Indicator
            if (state.isExternalMicConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0x2A00E5FF), RoundedCornerShape(4.dp))
                        .border(0.5.dp, Color(0xFF00E5FF), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "USB Mic",
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "USB-C",
                        color = Color(0xFF00E5FF),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Real Live Free Internal Storage
            Text(
                text = "${state.remainingStorageGb.toInt()}GB • ${state.remainingRecordingMinutes}m",
                color = if (state.remainingRecordingMinutes < 15) Color(0xFFFF3333) else Color(0xCCFFFFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            // Real Live Battery Percentage
            Row(verticalAlignment = Alignment.CenterVertically) {
                val batteryIcon = if (state.batteryPercent < 15) Icons.Default.BatteryAlert else Icons.Default.BatteryFull
                val batteryColor = when {
                    state.batteryPercent < 15 -> Color(0xFFFF3333)
                    state.batteryPercent < 30 -> Color(0xFFFFD700)
                    else -> Color(0xFF00FF66)
                }

                Icon(
                    imageVector = batteryIcon,
                    contentDescription = "Battery",
                    tint = batteryColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "${state.batteryPercent}%",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Tensor G4 Real Live Thermal Badge
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
            .background(Color(0xFF181A1D), RoundedCornerShape(5.dp))
            .border(
                0.7.dp,
                if (highlight) Color(0xFFFFCC00).copy(alpha = 0.6f) else Color(0x22FFFFFF),
                RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0x99FFFFFF),
            fontSize = 8.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = if (highlight) Color(0xFFFFCC00) else Color.White,
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
        ThermalStatus.LIGHT, ThermalStatus.MODERATE -> Pair(Color(0xFFFFCC00), "WARM")
        ThermalStatus.SEVERE, ThermalStatus.EMERGENCY -> Pair(Color(0xFFFF2222), "HOT")
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0xFF181A1D), RoundedCornerShape(4.dp))
            .border(0.5.dp, dotColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
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
