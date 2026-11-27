package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.FrameGuide
import com.kunal.pixelcineraw.camera.StabilizationMode
import com.kunal.pixelcineraw.ui.theme.*

private val TabularLeftStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v1.5.0 Left-edge cinema monitoring and pro assistance rail.
 *
 * Matte Obsidian background (#EB0B0C0E), 0.75dp hairline borders, and drawBehind
 * active pill transitions with zero layout invalidations.
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
            .background(CinemaPillBgDark)
            .drawBehind {
                drawLine(
                    color = CinemaBorderInactive,
                    start = Offset(size.width, 0f),
                    end = Offset(size.width, size.height),
                    strokeWidth = 0.75.dp.toPx()
                )
            }
            .displayCutoutPadding()
            .width(58.dp)
            .padding(vertical = 6.dp, horizontal = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // 1. Clean Viewfinder Eye Toggle
        MonitoringRailButton(
            icon = if (state.isCleanViewfinder) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            label = if (state.isCleanViewfinder) "HUD OFF" else "HUD ON",
            isActive = state.isCleanViewfinder,
            activeColor = CinemaAmber,
            onClick = onCleanViewfinderToggle
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 2. Focus Peaking Toggle
        MonitoringRailButton(
            label = "PEAK",
            isActive = state.showFocusPeaking,
            activeColor = CinemaAutoGreen,
            subDotColor = Color(state.peakingColor.hexColor),
            onClick = onPeakingToggle
        )

        // 3. Exposure Zebras Toggle
        MonitoringRailButton(
            label = "ZEBRA",
            isActive = state.showZebras,
            activeColor = CinemaGold,
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
            activeColor = CinemaAmber,
            onClick = onGuidesCycle
        )

        // 5. Electronic Horizon Leveler Toggle
        MonitoringRailButton(
            icon = Icons.Default.LinearScale,
            label = "LEVEL",
            isActive = state.showHorizonLeveler,
            activeColor = CinemaAutoGreen,
            onClick = onHorizonToggle
        )

        // 6. Live Histogram Toggle
        MonitoringRailButton(
            label = "HIST",
            isActive = state.showHistogram,
            activeColor = CinemaAmber,
            onClick = onHistogramToggle
        )

        // 7. 3D LUT Simulation Toggle
        MonitoringRailButton(
            label = "LUT",
            isActive = state.showLutPreview,
            activeColor = CinemaAmber,
            onClick = onLutToggle
        )

        // 8. Optical / Electronic Stabilization Toggle (OFF / OIS / OIS+EIS)
        val stabLabel = when (state.stabilizationMode) {
            StabilizationMode.OFF -> "OFF"
            StabilizationMode.OIS -> "OIS"
            StabilizationMode.OIS_EIS -> "OIS+"
        }
        val stabColor = when (state.stabilizationMode) {
            StabilizationMode.OFF -> CinemaGray
            StabilizationMode.OIS -> CinemaCyan
            StabilizationMode.OIS_EIS -> CinemaAutoGreen
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
    icon: ImageVector? = null,
    subDotColor: Color? = null
) {
    val activeProgress by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "railItem"
    )

    Box(
        modifier = modifier
            .size(width = 48.dp, height = 35.dp)
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(color = CinemaElevated, cornerRadius = r)
                if (activeProgress > 0.01f) {
                    drawRoundRect(color = activeColor.copy(alpha = 0.18f * activeProgress), cornerRadius = r)
                }
                val stroke = if (activeProgress > 0.5f) activeColor.copy(alpha = 0.85f) else CinemaBorderInactive
                drawRoundRect(color = stroke, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .clickable(onClick = onClick),
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
                    tint = if (isActive) activeColor else CinemaWhite,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                color = if (isActive) activeColor else CinemaWhite,
                fontSize = 8.5.sp,
                fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                style = TabularLeftStyle
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
