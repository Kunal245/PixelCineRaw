package com.kunal.pixelcineraw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
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
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.FrameGuide
import com.kunal.pixelcineraw.camera.StabilizationMode

/**
 * Collapsible left-edge cinema monitoring and pro assistance rail.
 *
 * Houses Clean Viewfinder Eye toggle, Focus Peaking, Exposure Zebras,
 * Frame Guides, Electronic Horizon Leveler, Histogram, LUT preview, and OIS.
 */
@Composable
fun LeftMonitoringRail(
    state: CinemaState,
    onCleanViewfinderToggle: () -> Unit,
    onPeakingToggle: () -> Unit,
    onZebrasToggle: () -> Unit,
    onGuidesCycle: () -> Unit,
    onHorizonToggle: () -> Unit,
    onHistogramToggle: () -> Unit,
    onLutToggle: () -> Unit,
    onStabilizationToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(60.dp)
            .background(Color(0xB30C0E12))
            .border(width = 0.5.dp, color = Color(0x26FFFFFF))
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Clean Viewfinder Eye Toggle
        MonitoringRailButton(
            icon = if (state.isCleanViewfinder) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            label = if (state.isCleanViewfinder) "HUD OFF" else "HUD ON",
            isActive = state.isCleanViewfinder,
            activeColor = Color(0xFFFFCC00),
            onClick = onCleanViewfinderToggle
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 2. Focus Peaking Toggle
        MonitoringRailButton(
            label = "PEAK",
            isActive = state.showFocusPeaking,
            activeColor = Color(0xFF00FF66),
            subDotColor = Color(state.peakingColor.hexColor),
            onClick = onPeakingToggle
        )

        // 3. Exposure Zebras Toggle
        MonitoringRailButton(
            label = "ZEBRA",
            isActive = state.showZebras,
            activeColor = Color(0xFFFFD700),
            onClick = onZebrasToggle
        )

        // 4. Frame Guides
        val guideLabel = when (state.frameGuide) {
            FrameGuide.NONE -> "GUIDE"
            FrameGuide.RATIO_2_39 -> "2.39"
            FrameGuide.RATIO_16_9 -> "16:9"
            FrameGuide.RATIO_4_3 -> "4:3"
            FrameGuide.RATIO_1_1 -> "1:1"
        }
        MonitoringRailButton(
            label = guideLabel,
            isActive = state.frameGuide != FrameGuide.NONE,
            activeColor = Color(0xFF00E5FF),
            onClick = onGuidesCycle
        )

        // 5. Electronic Horizon Leveler Toggle
        MonitoringRailButton(
            icon = Icons.Default.LinearScale,
            label = "LEVEL",
            isActive = state.showHorizonLeveler,
            activeColor = Color(0xFF00FF66),
            onClick = onHorizonToggle
        )

        // 6. Live Histogram Toggle
        MonitoringRailButton(
            label = "HIST",
            isActive = state.showHistogram,
            activeColor = Color(0xFF00E5FF),
            onClick = onHistogramToggle
        )

        // 7. 3D LUT Simulation Toggle
        MonitoringRailButton(
            label = "LUT",
            isActive = state.showLutPreview,
            activeColor = Color(0xFF00E5FF),
            onClick = onLutToggle
        )

        // 8. Optical / Electronic Stabilization Toggle (OFF / OIS / OIS+EIS)
        val stabLabel = when (state.stabilizationMode) {
            StabilizationMode.OFF -> "OFF"
            StabilizationMode.OIS -> "OIS"
            StabilizationMode.OIS_EIS -> "OIS+"
        }
        val stabColor = when (state.stabilizationMode) {
            StabilizationMode.OFF -> Color(0xFF888888)
            StabilizationMode.OIS -> Color(0xFF00E5FF)
            StabilizationMode.OIS_EIS -> Color(0xFF00FF66)
        }
        MonitoringRailButton(
            label = stabLabel,
            isActive = state.stabilizationMode != StabilizationMode.OFF,
            activeColor = stabColor,
            onClick = onStabilizationToggle
        )
    }
}

@Composable
fun MonitoringRailButton(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subDotColor: Color? = null
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.22f) else Color(0x22FFFFFF))
            .border(
                width = 0.8.dp,
                color = if (isActive) activeColor else Color(0x22FFFFFF),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) activeColor else Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                color = if (isActive) activeColor else Color.White,
                fontSize = 9.sp,
                fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subDotColor != null && isActive) {
                Spacer(modifier = Modifier.height(1.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(subDotColor)
                )
            }
        }
    }
}
