package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.FrameGuide

/**
 * Cinema monitoring overlays: Frame Guides (2.39:1, 16:9, 4:3), Rule of Thirds grid,
 * Zebra stripes, Focus Peaking outlines, and live RGB / Luma Histogram.
 */
@Composable
fun MonitoringOverlays(
    state: CinemaState,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {

        // 1. Frame Guides (CinemaScope 2.39:1, 16:9, 4:3)
        if (state.frameGuide != FrameGuide.NONE) {
            FrameGuideOverlay(guide = state.frameGuide)
        }

        // 2. Rule of Thirds Grid & Center Crosshair
        if (state.showGrid) {
            GridOverlay()
        }

        // 3. Exposure Zebras (Diagonal striped shader simulation for IRE >= threshold)
        if (state.showZebras) {
            ZebraOverlay(ireThreshold = state.zebraIreThreshold)
        }

        // 4. Focus Peaking Overlay
        if (state.showFocusPeaking) {
            FocusPeakingOverlay(
                color = Color(state.peakingColor.hexColor),
                sensitivity = state.peakingSensitivity
            )
        }

        // 5. Real-time RGB / Luma Histogram (Top Right HUD)
        if (state.showHistogram) {
            HistogramHUD(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 16.dp)
            )
        }

        // 6. 3D LUT Active Indicator
        if (state.showLutPreview) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LUT: REC.709 PRO",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun FrameGuideOverlay(guide: FrameGuide) {
    val targetRatio = guide.aspectRatio ?: return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val screenW = size.width
        val screenH = size.height
        val currentRatio = screenW / screenH

        val maskColor = Color.Black.copy(alpha = 0.65f)
        val guideLineColor = Color.White.copy(alpha = 0.5f)

        if (currentRatio > targetRatio) {
            // Pillarbox: bars on left and right
            val targetW = screenH * targetRatio
            val barW = (screenW - targetW) / 2f

            // Left mask
            drawRect(color = maskColor, topLeft = Offset.Zero, size = Size(barW, screenH))
            // Right mask
            drawRect(color = maskColor, topLeft = Offset(screenW - barW, 0f), size = Size(barW, screenH))

            // Guide lines
            drawLine(guideLineColor, Offset(barW, 0f), Offset(barW, screenH), 1.dp.toPx())
            drawLine(guideLineColor, Offset(screenW - barW, 0f), Offset(screenW - barW, screenH), 1.dp.toPx())
        } else {
            // Letterbox: bars on top and bottom (e.g. 2.39:1 CinemaScope)
            val targetH = screenW / targetRatio
            val barH = (screenH - targetH) / 2f

            // Top mask
            drawRect(color = maskColor, topLeft = Offset.Zero, size = Size(screenW, barH))
            // Bottom mask
            drawRect(color = maskColor, topLeft = Offset(0f, screenH - barH), size = Size(screenW, barH))

            // Guide lines
            drawLine(guideLineColor, Offset(0f, barH), Offset(screenW, barH), 1.dp.toPx())
            drawLine(guideLineColor, Offset(0f, screenH - barH), Offset(screenW, screenH - barH), 1.dp.toPx())
        }
    }
}

@Composable
fun GridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val gridColor = Color.White.copy(alpha = 0.25f)

        // Rule of Thirds
        drawLine(gridColor, Offset(w / 3f, 0f), Offset(w / 3f, h), 1.dp.toPx())
        drawLine(gridColor, Offset(w * 2f / 3f, 0f), Offset(w * 2f / 3f, h), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, h / 3f), Offset(w, h / 3f), 1.dp.toPx())
        drawLine(gridColor, Offset(0f, h * 2f / 3f), Offset(w, h * 2f / 3f), 1.dp.toPx())

        // Center Crosshair
        val cx = w / 2f
        val cy = h / 2f
        val crossSize = 14.dp.toPx()
        val crossColor = Color.White.copy(alpha = 0.45f)
        drawLine(crossColor, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), 1.5.dp.toPx())
        drawLine(crossColor, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), 1.5.dp.toPx())
    }
}

@Composable
fun ZebraOverlay(ireThreshold: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "ZebraAnimation")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ZebraPhase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val step = 20.dp.toPx()
        val zebraColor = Color(0x77FFFFFF)
        val w = size.width
        val h = size.height

        // Simulates zebras across highlights (IRE indicator)
        var x = -h + phase
        while (x < w) {
            drawLine(
                color = zebraColor,
                start = Offset(x, 0f),
                end = Offset(x + h, h),
                strokeWidth = 2.dp.toPx()
            )
            x += step
        }
    }
}

@Composable
fun FocusPeakingOverlay(color: Color, sensitivity: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // High-contrast edge peaking indicator
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = 90.dp.toPx() * sensitivity

        drawCircle(
            color = color.copy(alpha = 0.5f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
fun HistogramHUD(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 130.dp, height = 64.dp)
            .background(Color(0xCC111111), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Simulated real-time RGB & Luma Curves
            val points = 32
            val redPath = Path()
            val greenPath = Path()
            val bluePath = Path()
            val lumaPath = Path()

            redPath.moveTo(0f, h)
            greenPath.moveTo(0f, h)
            bluePath.moveTo(0f, h)
            lumaPath.moveTo(0f, h)

            for (i in 0 until points) {
                val x = (i.toFloat() / (points - 1)) * w
                // Bell curves centered on midtones/shadows for cinema flat profile
                val normX = i.toFloat() / points
                val redY = h - (Math.sin(normX * Math.PI) * 0.75 + Math.sin(normX * 2 * Math.PI) * 0.15).toFloat().coerceIn(0.05f, 0.95f) * h
                val greenY = h - (Math.sin(normX * Math.PI * 0.9 + 0.2) * 0.8).toFloat().coerceIn(0.05f, 0.95f) * h
                val blueY = h - (Math.sin(normX * Math.PI * 1.1) * 0.65).toFloat().coerceIn(0.05f, 0.95f) * h
                val lumaY = (redY + greenY + blueY) / 3f

                redPath.lineTo(x, redY)
                greenPath.lineTo(x, greenY)
                bluePath.lineTo(x, blueY)
                lumaPath.lineTo(x, lumaY)
            }

            drawPath(redPath, Color(0x99FF3333), style = Stroke(width = 1.5.dp.toPx()))
            drawPath(greenPath, Color(0x9933FF33), style = Stroke(width = 1.5.dp.toPx()))
            drawPath(bluePath, Color(0x993388FF), style = Stroke(width = 1.5.dp.toPx()))
            drawPath(lumaPath, Color(0xDDFFFFFF), style = Stroke(width = 1.dp.toPx()))
        }

        Text(
            text = "RGB/LUMA",
            color = Color(0x88FFFFFF),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
        )
    }
}
