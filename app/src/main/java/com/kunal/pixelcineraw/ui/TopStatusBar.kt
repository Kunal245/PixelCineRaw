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
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.ThermalStatus

/**
 * Modern dark-glassmorphism top status bar (inspired by Blackmagic Camera & Final Cut Camera).
 *
 * Consolidates Resolution, FPS, Color Profile, Codec, Bitrate, ISP, and System Telemetry
 * into frosted dark translucent pills with tabular monospaced numbers.
 */
@Composable
fun TopStatusBar(
    state: CinemaState,
    onFormatClick: () -> Unit,
    onColorProfileClick: () -> Unit,
    onBitrateClick: () -> Unit,
    onIspClick: () -> Unit,
    onAudioWavClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xB30A0C10))
            .border(width = 0.5.dp, color = Color(0x26FFFFFF))
            .padding(horizontal = 68.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Consolidated Cinema Format & Engine Pills
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Resolution & FPS Pill (e.g. "4K · 24 FPS")
            val resTitle = when {
                state.videoResolution.width >= 3840 -> "4K"
                state.videoResolution.width >= 2560 -> "2.5K"
                state.videoResolution.width >= 1920 -> "1080p"
                else -> "${state.videoResolution.width}p"
            }
            FrostedPill(
                label = "FORMAT",
                value = "$resTitle · ${state.targetFps}p",
                subText = state.videoResolution.aspectLabel,
                highlight = true,
                onClick = onFormatClick
            )

            // 2. Color Profile Pill (HLG10, HDR10+, PIXEL-LOG, REC.709 with color indicator dot)
            val profileDotColor = when (state.colorProfile) {
                ColorProfile.HLG_10BIT -> Color(0xFFFFD700)
                ColorProfile.HDR10_PLUS -> Color(0xFFFF007F)
                ColorProfile.FLAT_LOG -> Color(0xFF00E5FF)
                ColorProfile.REC709 -> Color(0xFF00FF66)
            }
            FrostedPill(
                label = "PROFILE",
                value = state.colorProfile.label,
                dotColor = profileDotColor,
                highlight = true,
                onClick = onColorProfileClick
            )

            // 3. Codec & Bitrate Pill (e.g. "10B ALL-I · 280M")
            val codecLabel = if (state.intraFrameMode == com.kunal.pixelcineraw.camera.IntraFrameMode.ALL_INTRA) "ALL-I" else "GOP"
            FrostedPill(
                label = "CODEC",
                value = "$codecLabel · ${state.targetBitrateMbps}M",
                subText = if (state.videoCodec == com.kunal.pixelcineraw.camera.VideoCodec.HEVC_10BIT) "10-BIT" else "8-BIT",
                highlight = false,
                onClick = onBitrateClick
            )

            // 4. Clean Cinema ISP Pill
            FrostedPill(
                label = "ISP",
                value = state.ispMode.name,
                highlight = state.ispMode == com.kunal.pixelcineraw.camera.IspMode.CLEAN,
                onClick = onIspClick
            )

            // 5. Audio Sidecar Pill
            FrostedPill(
                label = "AUDIO",
                value = if (state.recordWavSidecar) "48k WAV" else "AAC",
                highlight = state.recordWavSidecar,
                onClick = onAudioWavClick
            )
        }

        // Right: Real Live Telemetry Cluster (Storage, Battery %, Tensor G4 Thermal)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            // Internal Storage Remaining (GB and time)
            val storageText = "${state.remainingStorageGb.toInt()}GB · ${state.remainingRecordingMinutes}m"
            Text(
                text = storageText,
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
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
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "${state.batteryPercent}%",
                    color = Color.White,
                    fontSize = 10.sp,
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
fun FrostedPill(
    label: String,
    value: String,
    subText: String? = null,
    dotColor: Color? = null,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(Color(0xB312141A), RoundedCornerShape(6.dp))
            .border(
                0.8.dp,
                if (highlight) Color(0xFFFFCC00).copy(alpha = 0.5f) else Color(0x26FFFFFF),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = Color(0x88FFFFFF),
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(dotColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = value,
                color = if (highlight) Color(0xFFFFCC00) else Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subText != null) {
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = subText,
                    color = Color(0x99FFFFFF),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.SemiBold,
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
            .background(Color(0xB312141A), RoundedCornerShape(4.dp))
            .border(0.5.dp, dotColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = text,
            color = dotColor,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
