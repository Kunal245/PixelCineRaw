package com.kunal.pixelcineraw.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.*
import com.kunal.pixelcineraw.ui.theme.*

private val TabularBottomStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v2.0.0 Signature 74dp Anodized Bezel + Deep Crimson Glass Shutter Button.
 *
 * Features spring press feedback (`0.92f` scale) and smooth corner-radius morph
 * (`Circle -> 10dp Rounded Square`) strictly in `drawBehind` + `graphicsLayer`
 * (`dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow`).
 */
@Composable
fun CinemaShutterButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outerSize: Dp = 74.dp,
    innerCircleSize: Dp = 54.dp,
    innerSquareSize: Dp = 26.dp
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "shutterPressScale"
    )

    val morphProgress by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "shutterMorph"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "RecCorePulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "RecCoreScale"
    )

    Box(
        modifier = modifier
            .size(outerSize)
            .graphicsLayer {
                val s = pressScale * (if (isRecording) pulseScale else 1.0f)
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                // 1. Anodized Metallic Bezel Outer Ring
                val bezelStroke = 4.dp.toPx()
                val bezelRadius = (size.minDimension - bezelStroke) / 2f
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(CinemaAnodizedLight, CinemaAnodizedDark)
                    ),
                    radius = bezelRadius,
                    style = Stroke(width = bezelStroke)
                )
                // Crisp 0.5dp top specular highlight on the anodized ring
                drawCircle(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x66FFFFFF), Color(0x14FFFFFF))
                    ),
                    radius = (size.minDimension - 0.5.dp.toPx()) / 2f,
                    style = Stroke(width = 0.5.dp.toPx())
                )
                // Recessed obsidian well inside bezel
                drawCircle(
                    color = Color(0xCC07080A),
                    radius = (size.minDimension - bezelStroke * 2f) / 2f
                )

                // 2. Deep Crimson Glass Core (Circle -> 10dp Rounded Square on record)
                val startPx = innerCircleSize.toPx()
                val endPx = innerSquareSize.toPx()
                val currentSize = startPx + (endPx - startPx) * morphProgress
                val startRadius = startPx / 2f
                val endRadius = 10.dp.toPx()
                val corner = startRadius + (endRadius - startRadius) * morphProgress
                val topLeftOffset = Offset(
                    x = (size.width - currentSize) / 2f,
                    y = (size.height - currentSize) / 2f
                )

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(CinemaCrimsonGlassTop, CinemaCrimsonGlassCore)
                    ),
                    topLeft = topLeftOffset,
                    size = Size(currentSize, currentSize),
                    cornerRadius = CornerRadius(corner, corner)
                )
                // Inner glass top-rim specular edge
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0x55FFFFFF), Color.Transparent)
                    ),
                    topLeft = topLeftOffset,
                    size = Size(currentSize, currentSize),
                    cornerRadius = CornerRadius(corner, corner),
                    style = Stroke(width = 0.75.dp.toPx())
                )
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
    )
}

/**
 * v2.0.0 Row 2 — Leica Lux / Kino 5 Crisp Parameter Pills:
 * `ISO`, `SHUTTER`, `WB`, `LUT`, `MONITOR`.
 */
@Composable
fun CinemaParameterPillStrip(
    state: CinemaState,
    activeDial: ActiveDial,
    onIsoClick: () -> Unit,
    onShutterClick: () -> Unit,
    onWbClick: () -> Unit,
    onLutClick: () -> Unit,
    onMonitorClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = CinemaCapsuleBg, cornerRadius = r)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(CinemaSpecularTop, CinemaSpecularBottom)),
                    cornerRadius = r,
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isoVal = if (state.isAutoIso) "A·${state.iso}" else "${state.iso}"
        DeckParameterPill(
            label = "ISO",
            value = isoVal,
            isSelected = activeDial == ActiveDial.ISO,
            isAuto = state.isAutoIso || !state.isManualExposure,
            onClick = onIsoClick
        )

        val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
            "${state.shutterAngle.toInt()}°"
        } else {
            ColorMath.formatShutterSpeed(state.shutterSpeedNs)
        }
        DeckParameterPill(
            label = "SHUTTER",
            value = shutterVal,
            isSelected = activeDial == ActiveDial.SHUTTER,
            isAuto = !state.isManualExposure,
            onClick = onShutterClick
        )

        val wbVal = if (state.isManualWb) "${state.wbKelvin}K" else "AWB"
        DeckParameterPill(
            label = "WB",
            value = wbVal,
            isSelected = activeDial == ActiveDial.WHITE_BALANCE,
            isAuto = !state.isManualWb,
            onClick = onWbClick
        )

        val lutActive = state.showLutPreview && state.lutPreset != LutPreset.OFF
        DeckParameterPill(
            label = "LUT",
            value = if (lutActive) state.lutPreset.shortLabel else "OFF",
            isSelected = activeDial == ActiveDial.LUT,
            isAuto = !lutActive,
            showModeDot = false,
            accentColor = if (lutActive) CinemaAmber else null,
            onClick = onLutClick
        )

        val monitorCount = listOf(
            state.showHistogram,
            state.showZebras,
            state.showFocusPeaking,
            state.showGrid,
            state.showHorizonLeveler,
            state.showWaveform,
            state.showFalseColor
        ).count { it }
        DeckParameterPill(
            label = "MONITOR",
            value = if (monitorCount > 0) "$monitorCount ON" else "OFF",
            isSelected = activeDial == ActiveDial.TOOLS,
            isAuto = false,
            showModeDot = false,
            accentColor = if (monitorCount > 0) CinemaAmber else null,
            onClick = onMonitorClick
        )
    }
}

@Composable
private fun DeckParameterPill(
    label: String,
    value: String,
    isSelected: Boolean,
    isAuto: Boolean,
    onClick: () -> Unit,
    showModeDot: Boolean = true,
    accentColor: Color? = null
) {
    val selProgress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "deckPillSel"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .drawBehind {
                val r = CornerRadius(size.height / 2f, size.height / 2f)
                if (selProgress > 0.01f) {
                    drawRoundRect(
                        color = CinemaAmber.copy(alpha = 0.22f * selProgress),
                        cornerRadius = r
                    )
                    drawRoundRect(
                        color = CinemaAmber.copy(alpha = 0.75f * selProgress),
                        cornerRadius = r,
                        style = Stroke(0.5.dp.toPx())
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = label,
                color = if (isSelected) CinemaAmber else CinemaGray,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.06.sp,
                maxLines = 1,
                softWrap = false
            )
            if (showModeDot) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (isAuto) CinemaAutoGreen else CinemaManualAmber)
                )
            }
        }
        Text(
            text = value,
            color = when {
                isSelected -> CinemaAmber
                accentColor != null -> accentColor
                else -> CinemaWhite
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularBottomStyle,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Last-Clip Gallery Preview Thumbnail (`48dp`, `14dp` radius, `1dp` metallic border).
 */
@Composable
fun LastClipGalleryButton(
    state: CinemaState,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    val context = LocalContext.current
    val clipThumbnail = state.lastRecordedClipThumbnail
    val clipUri = state.lastRecordedClipUri

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(CinemaCapsuleBg)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x22FFFFFF))),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable {
                if (clipUri != null) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(clipUri), "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.w("GalleryPreview", "Failed to launch video viewer", e)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (clipThumbnail != null) {
            Image(
                bitmap = clipThumbnail.asImageBitmap(),
                contentDescription = "Last Recorded Clip",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Gallery",
                tint = CinemaGray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Circular 46dp frosted-obsidian glass button with 0.5dp top specular edge.
 */
@Composable
fun GlassCircleActionButton(
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val activeProgress by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.82f),
        label = "glassBtn"
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind {
                drawCircle(color = CinemaCapsuleBg)
                if (activeProgress > 0.01f) {
                    drawCircle(color = activeColor.copy(alpha = 0.28f * activeProgress))
                }
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(
                            if (activeProgress > 0.5f) activeColor.copy(alpha = 0.85f) else CinemaSpecularTop,
                            CinemaSpecularBottom
                        )
                    ),
                    style = Stroke(width = 0.5.dp.toPx())
                )
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/**
 * Compact Floating Glass Popover for `MONITOR` (`Histogram`, `Zebra`, `Focus Peaking`,
 * `Grid`, `Horizon Level`, plus quick links for Focus MF, Waveform, False Color, Frame Guide,
 * Clean HUD, and Pro Suite).
 */
@Composable
fun MonitorQuickPopover(
    state: CinemaState,
    cameraController: Camera2Controller,
    onOpenFocusDial: () -> Unit,
    onOpenProSuite: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val r = CornerRadius(22.dp.toPx())
                drawRoundRect(color = CinemaGlassSheetBg, cornerRadius = r)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(CinemaSpecularTop, CinemaSpecularBottom)),
                    cornerRadius = r,
                    style = Stroke(0.5.dp.toPx())
                )
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MONITORING OVERLAYS",
                color = CinemaAmber,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.08.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (state.focusMode == FocusMode.MANUAL) "MF" else "AF-C",
                    color = if (state.focusMode == FocusMode.MANUAL) CinemaAmber else CinemaCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable { onOpenFocusDial() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    text = "PRO SUITE",
                    color = CinemaCyan,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clickable {
                            onClose()
                            onOpenProSuite()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Monitor",
                    tint = CinemaWhite,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onClose)
                )
            }
        }

        // Primary 5 Monitoring Toggles requested in v2.0.0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MonitorToggleChip(
                label = "HISTOGRAM",
                isActive = state.showHistogram,
                activeColor = CinemaAmber,
                onClick = { cameraController.toggleHistogram() }
            )
            MonitorToggleChip(
                label = "ZEBRA",
                isActive = state.showZebras,
                activeColor = CinemaGold,
                onClick = { cameraController.toggleZebras() }
            )
            MonitorToggleChip(
                label = "PEAKING",
                isActive = state.showFocusPeaking,
                activeColor = CinemaAutoGreen,
                onClick = { cameraController.toggleFocusPeaking() }
            )
            MonitorToggleChip(
                label = "GRID",
                isActive = state.showGrid,
                activeColor = CinemaAmber,
                onClick = { cameraController.toggleGrid() }
            )
            MonitorToggleChip(
                label = "HORIZON LEVEL",
                isActive = state.showHorizonLeveler,
                activeColor = CinemaAutoGreen,
                onClick = { cameraController.toggleHorizonLeveler() }
            )
        }

        // Secondary Cinema Tools
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MonitorToggleChip(
                label = "WAVEFORM",
                isActive = state.showWaveform,
                activeColor = CinemaCyan,
                onClick = { cameraController.toggleWaveform() }
            )
            MonitorToggleChip(
                label = "FALSE COLOR",
                isActive = state.showFalseColor,
                activeColor = CinemaCrimson,
                onClick = { cameraController.toggleFalseColor() }
            )
            val guideLabel = if (state.frameGuide == FrameGuide.NONE) "GUIDE: OFF" else "GUIDE: ${state.frameGuide.name.replace("RATIO_", "")}"
            MonitorToggleChip(
                label = guideLabel,
                isActive = state.frameGuide != FrameGuide.NONE,
                activeColor = CinemaAmber,
                onClick = {
                    val nextGuide = when (state.frameGuide) {
                        FrameGuide.NONE -> FrameGuide.RATIO_2_39
                        FrameGuide.RATIO_2_39 -> FrameGuide.RATIO_16_9
                        FrameGuide.RATIO_16_9 -> FrameGuide.RATIO_4_3
                        FrameGuide.RATIO_4_3 -> FrameGuide.RATIO_1_1
                        FrameGuide.RATIO_1_1 -> FrameGuide.NONE
                    }
                    cameraController.setFrameGuide(nextGuide)
                }
            )
            MonitorToggleChip(
                label = "TORCH",
                isActive = state.isTorchOn,
                activeColor = CinemaAmber,
                onClick = { if (!state.isFrontCamera) cameraController.toggleTorch() }
            )
            MonitorToggleChip(
                label = if (state.isCleanViewfinder) "HUD: OFF" else "CLEAN HUD",
                isActive = state.isCleanViewfinder,
                activeColor = CinemaAmber,
                onClick = { cameraController.toggleCleanViewfinder() }
            )
        }
    }
}

@Composable
private fun MonitorToggleChip(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .drawBehind {
                val r = CornerRadius(10.dp.toPx())
                drawRoundRect(
                    color = if (isActive) activeColor.copy(alpha = 0.22f) else Color(0x4D000000),
                    cornerRadius = r
                )
                drawRoundRect(
                    color = if (isActive) activeColor.copy(alpha = 0.85f) else CinemaSpecularBottom,
                    cornerRadius = r,
                    style = Stroke(0.5.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (isActive) activeColor else CinemaWhite,
            fontSize = 9.5.sp,
            fontWeight = if (isActive) FontWeight.Black else FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}
