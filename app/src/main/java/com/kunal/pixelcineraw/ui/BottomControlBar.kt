package com.kunal.pixelcineraw.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunal.pixelcineraw.camera.AntiBandingMode
import com.kunal.pixelcineraw.camera.CinemaState
import com.kunal.pixelcineraw.camera.ColorMath
import com.kunal.pixelcineraw.camera.FocusMode
import com.kunal.pixelcineraw.camera.LensSelection
import com.kunal.pixelcineraw.camera.LutPreset
import com.kunal.pixelcineraw.camera.ShutterUnit
import com.kunal.pixelcineraw.ui.theme.*
import java.util.Locale

private val TabularBottomStyle = TextStyle(fontFeatureSettings = TABULAR_NUMS)

/**
 * v1.5.0 Bottom Cinema Exposure Deck, LUT/Tools Quick Access, and Optical Zoom Hub.
 *
 * Matte Obsidian (#EB0B0C0E), 0.75dp hairline borders, Warm Tungsten Amber (#F5A623)
 * active indicators, tabular monospace figures (tnum), and edge-to-edge cutout/nav padding.
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
    modifier: Modifier = Modifier,
    activeDial: ActiveDial = ActiveDial.NONE,
    onLutClick: () -> Unit = {},
    onToolsClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CinemaPillBgDark)
            .drawBehind {
                drawLine(
                    color = CinemaBorderInactive,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.75.dp.toPx()
                )
            }
            .navigationBarsPadding()
            .displayCutoutPadding()
            .height(64.dp)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Zero-Jank Lens & Zoom Hub
        CinemaZoomHub(
            state = state,
            onZoomChanged = onZoomChanged,
            onSliderVisibilityChanged = onZoomSliderVisibilityChanged
        )

        // 2. Precision Exposure + Quick LUT/Tools Pills
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shutterVal = if (state.shutterUnit == ShutterUnit.ANGLE) {
                if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_50) {
                    val flickerSpeed = if (state.targetFps <= 30) "1/50" else "1/100"
                    "180°·$flickerSpeed"
                } else if (state.shutterAngle == 180f && state.antiBandingMode == AntiBandingMode.HZ_60) {
                    val flickerSpeed = if (state.targetFps <= 30) "1/60" else "1/120"
                    "180°·$flickerSpeed"
                } else {
                    "${state.shutterAngle.toInt()}°"
                }
            } else {
                ColorMath.formatShutterSpeed(state.shutterSpeedNs)
            }
            ExposurePill(
                label = if (state.shutterUnit == ShutterUnit.ANGLE) "SHUTTER" else "SPEED",
                value = shutterVal,
                isAuto = !state.isManualExposure,
                isSelected = activeDial == ActiveDial.SHUTTER,
                onClick = onShutterClick
            )

            val isoVal = if (state.isAutoIso) "AUTO ${state.iso}" else "${state.iso}"
            ExposurePill(
                label = "ISO",
                value = isoVal,
                isAuto = state.isAutoIso || !state.isManualExposure,
                isSelected = activeDial == ActiveDial.ISO,
                onClick = onIsoClick
            )

            val wbVal = if (state.isManualWb) "${state.wbKelvin}K" else "AWB"
            ExposurePill(
                label = "WB",
                value = wbVal,
                isAuto = !state.isManualWb,
                isSelected = activeDial == ActiveDial.WHITE_BALANCE,
                onClick = onWbClick
            )

            val focusVal = when (state.focusMode) {
                FocusMode.AUTO_CONTINUOUS -> "AF-C"
                FocusMode.LOCKED -> "AF-L"
                FocusMode.MANUAL -> {
                    if (state.focusDistance <= 0.05f) "MF ∞"
                    else String.format(Locale.US, "MF %.1fm", 1.0f / state.focusDistance)
                }
            }
            ExposurePill(
                label = "FOCUS",
                value = focusVal,
                isAuto = state.focusMode != FocusMode.MANUAL,
                isSelected = activeDial == ActiveDial.FOCUS,
                onClick = onFocusClick
            )

            val evVal = if (state.evCompensation > 0f) "+%.1f".format(state.evCompensation)
            else "%.1f".format(state.evCompensation)
            ExposurePill(
                label = "EV",
                value = evVal,
                isAuto = false,
                isSelected = activeDial == ActiveDial.EV,
                highlightColor = if (state.evCompensation != 0f) CinemaAmber else null,
                onClick = onEvClick
            )

            val lutActive = state.showLutPreview && state.lutPreset != LutPreset.OFF
            ExposurePill(
                label = "LUT",
                value = if (lutActive) state.lutPreset.shortLabel else "OFF",
                isAuto = !lutActive,
                showModeDot = false,
                isSelected = activeDial == ActiveDial.LUT,
                highlightColor = if (lutActive) CinemaAmber else null,
                onClick = onLutClick
            )

            val anyToolActive = state.showFocusPeaking || state.showZebras ||
                    state.showFalseColor || state.showHorizonLeveler ||
                    state.showHistogram || state.showWaveform
            ExposurePill(
                label = "TOOLS",
                value = if (anyToolActive) "ON" else "HUD",
                isAuto = false,
                showModeDot = false,
                isSelected = activeDial == ActiveDial.TOOLS,
                highlightColor = if (anyToolActive) CinemaAmber else null,
                onClick = onToolsClick
            )
        }

        // 3. Stereo Audio VU Meter
        AudioVuMeter(peakL = state.audioPeakDbL, peakR = state.audioPeakDbR)
    }
}

@Composable
fun ExposurePill(
    label: String,
    value: String,
    isAuto: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    showModeDot: Boolean = true,
    highlightColor: Color? = null
) {
    val selectedProgress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f),
        label = "expPill"
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .drawBehind {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(color = CinemaElevated, cornerRadius = r)
                if (selectedProgress > 0.01f) {
                    drawRoundRect(color = CinemaAmber.copy(alpha = 0.14f * selectedProgress), cornerRadius = r)
                }
                val borderCol = when {
                    selectedProgress > 0.5f -> CinemaAmber
                    highlightColor != null -> highlightColor.copy(alpha = 0.55f)
                    else -> CinemaBorderInactive
                }
                drawRoundRect(color = borderCol, cornerRadius = r, style = Stroke(0.75.dp.toPx()))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                fontFamily = FontFamily.Monospace
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
                highlightColor != null -> highlightColor
                else -> CinemaWhite
            },
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            style = TabularBottomStyle,
            maxLines = 1,
            softWrap = false
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
            Text("L", color = CinemaGray, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(3.dp))
            VuBar(peakDb = peakL)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", color = CinemaGray, fontSize = 7.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(3.dp))
            VuBar(peakDb = peakR)
        }
    }
}

@Composable
fun VuBar(peakDb: Float) {
    Canvas(
        modifier = Modifier
            .width(42.dp)
            .height(5.dp)
    ) {
        val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        drawRoundRect(
            color = CinemaElevated,
            cornerRadius = cornerRadius
        )
        val norm = ((peakDb + 60f) / 60f).coerceIn(0.04f, 1.0f)
        val barColor = when {
            peakDb > -6f -> CinemaCrimson
            peakDb > -18f -> CinemaAmber
            else -> CinemaAutoGreen
        }
        drawRoundRect(
            color = barColor,
            size = Size(size.width * norm, size.height),
            cornerRadius = cornerRadius
        )
    }
}
