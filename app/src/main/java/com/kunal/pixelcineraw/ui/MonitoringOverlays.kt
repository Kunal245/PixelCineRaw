package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.ColorProfile
import com.kunal.pixelcineraw.camera.FrameGuide
import com.kunal.pixelcineraw.camera.HistogramData
import kotlin.math.roundToInt

/**
 * Cinema monitoring overlays: Frame Guides (2.39:1, 16:9, 4:3), Rule of Thirds grid,
 * Zebra stripes, Focus Peaking outlines, and a live, movable RGB / Luma Histogram HUD.
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

        // 2b. Electronic Horizon Leveler
        if (state.showHorizonLeveler) {
            HorizonLeveler()
        }

        // 3. Exposure Zebras (Diagonal striped shader simulation for IRE >= threshold, 75% diffuse white in HLG)
        if (state.showZebras) {
            val isHlg = state.colorProfile == ColorProfile.HLG_10BIT
            val threshold = if (isHlg) 75 else state.zebraIreThreshold
            ZebraOverlay(ireThreshold = threshold, isHlg = isHlg)
        }

        // 4. Focus Peaking Overlay
        if (state.showFocusPeaking) {
            FocusPeakingOverlay(
                color = Color(state.peakingColor.hexColor),
                sensitivity = state.peakingSensitivity
            )
        }

        // 5. Real-Time Movable RGB / Luma Histogram (Top Right HUD by default)
        if (state.showHistogram) {
            MovableHistogramHUD(
                data = state.histogramData,
                isHlg = state.colorProfile == ColorProfile.HLG_10BIT,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 16.dp)
            )
        }

        // 5b. Luma Waveform Monitor HUD
        if (state.showWaveform) {
            LumaWaveformHUD(
                data = state.histogramData,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 60.dp, start = 16.dp)
            )
        }

        // 6. 3D LUT Active Indicator
        if (state.showLutPreview && state.lutPreset != com.kunal.pixelcineraw.camera.LutPreset.OFF) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 52.dp, start = 16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .border(0.7.dp, Color(0xFF00E5FF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LUT: ${state.lutPreset.label}${if (state.isLutBakeEnabled) " (BAKED)" else ""}",
                    color = Color(0xFF00E5FF),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 6b. False Color Active Legend
        if (state.showFalseColor) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 80.dp, start = 16.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .border(0.8.dp, Color(0xFFFF5252), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFFFF1744), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "FALSE COLOR (ARRI)",
                        color = Color(0xFFFF5252),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 6c. Anamorphic De-Squeeze Badge
        if (state.anamorphicDeSqueeze != com.kunal.pixelcineraw.camera.AnamorphicDeSqueeze.OFF) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (state.showFalseColor) 108.dp else 80.dp, start = 16.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .border(0.8.dp, Color(0xFFFFD700), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "ANAMORPHIC: ${state.anamorphicDeSqueeze.label}",
                    color = Color(0xFFFFD700),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 7. True 10-Bit HLG HDR Active Indicator Badge
        if (state.colorProfile == ColorProfile.HLG_10BIT) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (state.showFalseColor || state.anamorphicDeSqueeze != com.kunal.pixelcineraw.camera.AnamorphicDeSqueeze.OFF) 136.dp else if (state.showLutPreview) 80.dp else 52.dp, start = 16.dp)
                    .background(Color(0xD9101216), RoundedCornerShape(4.dp))
                    .border(0.8.dp, Color(0xFFFFD700), RoundedCornerShape(4.dp))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFFFFD700), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "HDR · HLG10",
                        color = Color(0xFFFFD700),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
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

        // Center Crosshairs
        val cx = w / 2f
        val cy = h / 2f
        val crossSize = 14.dp.toPx()
        val crossColor = Color.White.copy(alpha = 0.45f)
        drawLine(crossColor, Offset(cx - crossSize, cy), Offset(cx + crossSize, cy), 1.5.dp.toPx())
        drawLine(crossColor, Offset(cx, cy - crossSize), Offset(cx, cy + crossSize), 1.5.dp.toPx())
    }
}

@Composable
fun ZebraOverlay(
    ireThreshold: Int,
    isHlg: Boolean = false
) {
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
        // In HLG mode: 75% IRE indicates diffuse white (203 nits reference); 100% IRE tracks specular headroom (1000 nits)
        val zebraColor = if (isHlg) Color(0x99FFD700) else Color(0x77FFFFFF)
        val w = size.width
        val h = size.height

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

/**
 * Compact, semi-transparent, and freely movable RGB & Luma live histogram.
 */
@Composable
fun MovableHistogramHUD(
    data: HistogramData,
    isHlg: Boolean = false,
    modifier: Modifier = Modifier
) {
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) },
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .size(width = 124.dp, height = 58.dp)
                .background(Color(0xA6111317), RoundedCornerShape(6.dp))
                .border(0.7.dp, if (isHlg) Color(0x88FFD700) else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                    }
                }
                .padding(horizontal = 4.dp, vertical = 3.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val numBins = data.redBins.size

                if (numBins > 1) {
                    val redPath = Path()
                    val greenPath = Path()
                    val bluePath = Path()
                    val lumaPath = Path()

                    val startY = h
                    redPath.moveTo(0f, startY)
                    greenPath.moveTo(0f, startY)
                    bluePath.moveTo(0f, startY)
                    lumaPath.moveTo(0f, startY)

                    for (i in 0 until numBins) {
                        val x = (i.toFloat() / (numBins - 1)) * w
                        val rH = (data.redBins[i] * (h - 6f)).coerceIn(0f, h)
                        val gH = (data.greenBins[i] * (h - 6f)).coerceIn(0f, h)
                        val bH = (data.blueBins[i] * (h - 6f)).coerceIn(0f, h)
                        val lH = (data.lumaBins[i] * (h - 6f)).coerceIn(0f, h)

                        redPath.lineTo(x, h - rH)
                        greenPath.lineTo(x, h - gH)
                        bluePath.lineTo(x, h - bH)
                        lumaPath.lineTo(x, h - lH)
                    }

                    drawPath(redPath, Color(0x99FF3333), style = Stroke(width = 1.2.dp.toPx()))
                    drawPath(greenPath, Color(0x9933FF33), style = Stroke(width = 1.2.dp.toPx()))
                    drawPath(bluePath, Color(0x993399FF), style = Stroke(width = 1.2.dp.toPx()))
                    drawPath(lumaPath, Color(0xEEFFFFFF), style = Stroke(width = 1.0.dp.toPx()))

                    // HLG diffuse white (75% IRE / 203 nits) and specular headroom (100% IRE / 1000 nits) reference line
                    if (isHlg) {
                        val x75 = w * 0.75f
                        drawLine(
                            color = Color(0x99FFD700),
                            start = Offset(x75, 10f),
                            end = Offset(x75, h),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(if (isHlg) Color(0xFFFFD700) else Color(0xFF00FF66), RoundedCornerShape(1.dp))
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (isHlg) "HLG (75% W)" else "RGB/LUMA LIVE",
                    color = if (isHlg) Color(0xFFFFD700) else Color(0x99FFFFFF),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Red HLG badge under live histogram just like mcpro24fps
        if (isHlg) {
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFFCC0000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = "HLG",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun LumaWaveformHUD(
    data: HistogramData,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .width(180.dp)
            .height(80.dp)
            .background(Color(0xD90A0D12), RoundedCornerShape(6.dp))
            .border(0.6.dp, Color(0x3300E5FF), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 0%, 50%, 100% IRE grid lines
            val gridColor = Color(0x22FFFFFF)
            drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), 0.5.dp.toPx()) // 100 IRE
            drawLine(gridColor, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), 0.5.dp.toPx()) // 50 IRE
            drawLine(gridColor, Offset(0f, h), Offset(w, h), 0.5.dp.toPx()) // 0 IRE

            val numBins = data.lumaBins.size
            if (numBins > 1) {
                for (i in 0 until numBins) {
                    val x = (i.toFloat() / (numBins - 1)) * w
                    val lumaWeight = data.lumaBins[i].coerceIn(0f, 1f)
                    val y = h - (lumaWeight * h)
                    drawCircle(
                        color = Color(0xCC00E5FF),
                        radius = 1.8.dp.toPx(),
                        center = Offset(x, y)
                    )
                }
            }
        }

        Text(
            text = "WAVEFORM (IRE)",
            color = Color(0xFF00E5FF),
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}
